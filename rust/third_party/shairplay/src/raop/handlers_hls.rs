//! AirPlay video control over HTTP and FCUP playlist delivery over PTTH.

use std::collections::{HashMap, VecDeque};
use std::net::IpAddr;
use std::time::{Duration, Instant};

use plist::{Dictionary, Value};
use tokio::sync::mpsc;

use super::handlers_ap1::RaopConnection;
use super::hls::HlsSession;
use crate::proto::http::{HttpRequest, HttpResponse};

const PLAYLIST_TIMEOUT: Duration = Duration::from_secs(12);
const MAX_PLAYLISTS: usize = 64;
const MAX_PLAYLIST_BYTES: usize = 4 * 1024 * 1024;
const MAX_SESSION_PLAYLIST_BYTES: usize = 32 * 1024 * 1024;

#[derive(Default)]
pub(crate) struct Receiver {
    reverse: HashMap<IpAddr, mpsc::Sender<Vec<u8>>>,
    active: Option<Playback>,
    request_id: u64,
}

struct Playback {
    peer: IpAddr,
    session_id: String,
    uuid: Option<String>,
    prefer_high_quality: bool,
    start: f32,
    start_fraction: Option<f32>,
    rate: f32,
    path: String,
    port: u16,
    pending: VecDeque<String>,
    requested: Option<(u64, String, Instant)>,
    resources: Vec<String>,
    playlists: HashMap<usize, String>,
    player: Option<Box<dyn HlsSession>>,
}

impl Playback {
    fn start_player(&mut self, handler: &dyn super::hls::HlsHandler, url: &str) {
        let mut player = handler.on_play(url, self.start);
        if let Some(fraction) = self.start_fraction {
            player.seek_fraction(fraction);
        }
        player.set_rate(self.rate);
        self.player = Some(player);
    }
}

impl Drop for Playback {
    fn drop(&mut self) {
        if let Some(player) = self.player.as_mut() {
            player.stop();
        }
    }
}

pub(crate) fn dispatch(
    conn: &mut RaopConnection,
    request: &HttpRequest,
    method: &str,
    url: &str,
) -> Option<HttpResponse> {
    let path = url.split('?').next()?;
    let peer = conn.remote_socket.ip();
    if !matches!(
        (method, path),
        (
            "GET",
            "/server-info" | "/playback-info" | "/scrub" | "/getProperty"
        ) | (
            "POST",
            "/reverse" | "/play" | "/action" | "/stop" | "/rate" | "/scrub" | "/fp-setup2"
        ) | ("PUT", "/setProperty")
    ) && !(method == "GET" && path.starts_with("/pdmvos-hls/"))
    {
        return None;
    }

    let mut state = conn.shared.hls.lock().ok()?;
    let handler = conn.shared.hls_handler.as_ref()?;
    let session_id = request.header("X-Apple-Session-ID").unwrap_or("");
    if session_id.len() > 128 || session_id.contains(|ch: char| ch.is_control()) {
        return Some(error(400, "Bad Request"));
    }
    let mut response = HttpResponse::new("HTTP/1.1", 200, "OK");
    response.add_header("Server", "AirTunes/220.68");

    let body = match (method, path) {
        ("GET", "/server-info") => server_info(conn, &mut response),
        ("POST", "/reverse") => {
            if !request
                .header("Upgrade")
                .is_some_and(|s| s.eq_ignore_ascii_case("PTTH/1.0"))
            {
                return Some(error(400, "Bad Request"));
            }
            state.reverse.retain(|_, sender| !sender.is_closed());
            let (send, receive) = mpsc::channel(4);
            state.reverse.insert(peer, send);
            conn.hls_reverse = Some(receive);
            let mut upgrade = HttpResponse::new("HTTP/1.1", 101, "Switching Protocols");
            upgrade.add_header("Connection", "Upgrade");
            upgrade.add_header("Upgrade", "PTTH/1.0");
            upgrade.finish(None);
            return Some(upgrade);
        }
        ("POST", "/play") => {
            let Some(dict) = request.data().and_then(play_parameters) else {
                return Some(error(400, "Bad Request"));
            };
            let Some(location) = string(&dict, "Content-Location").filter(|s| valid_location(s))
            else {
                return Some(error(400, "Bad Request"));
            };
            let (start, start_fraction) = start_position(&dict);
            let direct = location.starts_with("https://");
            let reverse = state.reverse.get(&peer).cloned();
            if !direct
                && (!location.contains("/master.m3u8")
                    || session_id.is_empty()
                    || reverse.is_none())
            {
                return Some(error(415, "Unsupported Media Type"));
            }
            state.active = None;
            let mut play = Playback {
                peer,
                session_id: session_id.into(),
                uuid: string(&dict, "uuid").map(str::to_owned),
                prefer_high_quality: string(&dict, "clientProcName") == Some("YouTube"),
                start,
                start_fraction,
                rate: 1.0,
                path: format!("/pdmvos-hls/{}/", conn.nonce),
                port: conn.hls_port,
                pending: VecDeque::new(),
                requested: None,
                resources: Vec::new(),
                playlists: HashMap::new(),
                player: None,
            };
            if direct {
                play.start_player(handler.as_ref(), location);
            } else {
                play.resources.push(location.into());
                play.pending.push_back(location.into());
                if !request_playlist(&mut state.request_id, &mut play, reverse.as_ref()) {
                    return Some(error(503, "Service Unavailable"));
                }
            }
            tracing::info!(direct, "AirPlay video play");
            state.active = Some(play);
            None
        }
        ("GET", path) if path.starts_with("/pdmvos-hls/") => {
            if !peer.is_loopback() {
                return Some(error(404, "Not Found"));
            }
            let playlist = state.active.as_ref().and_then(|play| {
                let index = path
                    .strip_prefix(&play.path)?
                    .strip_suffix(".m3u8")?
                    .parse()
                    .ok()?;
                play.playlists.get(&index)
            });
            let Some(playlist) = playlist else {
                return Some(error(404, "Not Found"));
            };
            response.add_header("Content-Type", "application/vnd.apple.mpegurl");
            response.add_header("Connection", "close");
            response.set_disconnect(true);
            Some(expand_condensed_playlist(playlist)?.into_bytes())
        }
        ("POST", "/fp-setup2") => return Some(error(421, "Misdirected Request")),
        (_, "/getProperty" | "/setProperty") => None,
        _ => {
            let reverse = state.reverse.get(&peer).cloned();
            let Receiver {
                active, request_id, ..
            } = &mut *state;
            let Some(play) = active.as_mut().filter(|p| {
                p.peer == peer && (session_id.is_empty() || session_id == p.session_id)
            }) else {
                if path == "/playback-info" {
                    return Some(error(404, "Not Found"));
                }
                return Some(error(400, "Bad Request"));
            };
            if play
                .requested
                .as_ref()
                .is_some_and(|(_, _, sent)| sent.elapsed() >= PLAYLIST_TIMEOUT)
            {
                *active = None;
                tracing::warn!("AirPlay FCUP playlist request timed out");
                return Some(error(503, "Service Unavailable"));
            }
            match (method, path) {
                ("POST", "/action") => {
                    let Some(Value::Dictionary(action)) =
                        request.data().and_then(|b| plist::from_bytes(b).ok())
                    else {
                        return Some(error(400, "Bad Request"));
                    };
                    let params = action.get("params").and_then(Value::as_dictionary);
                    match (string(&action, "type"), params) {
                        (Some("unhandledURLResponse"), Some(params)) => {
                            if !receive_playlist(play, params)
                                || !request_playlist(request_id, play, reverse.as_ref())
                            {
                                return Some(error(400, "Bad Request"));
                            }
                            if play.requested.is_none() && play.player.is_none() {
                                let local =
                                    format!("http://localhost:{}{}0.m3u8", play.port, play.path);
                                play.start_player(handler.as_ref(), &local);
                                tracing::info!("AirPlay FCUP playlists ready");
                            }
                        }
                        (Some("playlistRemove"), Some(params)) => {
                            let uuid = params
                                .get("item")
                                .and_then(Value::as_dictionary)
                                .and_then(|item| string(item, "uuid"));
                            if uuid.is_some() && uuid == play.uuid.as_deref() {
                                *active = None;
                            }
                        }
                        _ => {}
                    }
                    None
                }
                ("POST", "/stop") => {
                    *active = None;
                    None
                }
                ("POST", "/scrub") => {
                    if let Some(position) = query(url, "position").filter(|p| *p >= 0.0) {
                        play.start = position;
                        play.start_fraction = None;
                        if let Some(player) = play.player.as_mut() {
                            player.seek(position);
                        }
                    }
                    None
                }
                ("POST", "/rate") => {
                    if let Some(rate) = query(url, "value") {
                        play.rate = if rate > 0.0 { 1.0 } else { 0.0 };
                        if let Some(player) = play.player.as_mut() {
                            player.set_rate(play.rate);
                        }
                    }
                    None
                }
                ("GET", "/scrub") => {
                    let (duration, position, _, _) = player_status(play);
                    response.add_header("Content-Type", "text/parameters");
                    Some(format!("duration: {duration}\r\nposition: {position}\r\n").into_bytes())
                }
                ("GET", "/playback-info") => playback_info(play, &mut response),
                _ => unreachable!(),
            }
        }
    };
    if body.is_none() {
        response.add_header("Content-Length", "0");
    }
    response.finish(body.as_deref());
    Some(response)
}

fn error(code: u16, reason: &str) -> HttpResponse {
    let mut response = HttpResponse::new("HTTP/1.1", code, reason);
    response.add_header("Content-Length", "0");
    response.finish(None);
    response
}

fn server_info(conn: &RaopConnection, response: &mut HttpResponse) -> Option<Vec<u8>> {
    let dict = Dictionary::from_iter([
        ("features", Value::Integer(0x27f_u64.into())),
        ("macAddress", Value::String(conn.shared.device_id.clone())),
        ("deviceid", Value::String(conn.shared.device_id.clone())),
        (
            "model",
            Value::String(crate::raop::config::GLOBAL_MODEL.into()),
        ),
        (
            "srcvers",
            Value::String(crate::raop::config::AP2_SRCVERS.into()),
        ),
        ("protovers", Value::String("1.0".into())),
        ("osBuildVersion", Value::String("12B435".into())),
        ("vv", Value::Integer(2_u64.into())),
    ]);
    xml(dict, response)
}

fn player_status(play: &Playback) -> (f64, f64, f64, bool) {
    play.player.as_ref().map_or((0.0, 0.0, 0.0, false), |p| {
        (
            p.duration().into(),
            p.position().into(),
            p.rate().into(),
            p.ready(),
        )
    })
}

fn playback_info(play: &Playback, response: &mut HttpResponse) -> Option<Vec<u8>> {
    let (duration, position, rate, ready) = player_status(play);
    let buffering = !ready
        || play
            .player
            .as_ref()
            .is_some_and(|player| player.buffering());
    let range = Value::Dictionary(Dictionary::from_iter([
        ("start", Value::Real(0.0)),
        ("duration", Value::Real(duration)),
    ]));
    let ranges = if ready && duration > 0.0 {
        vec![range]
    } else {
        vec![]
    };
    xml(
        Dictionary::from_iter([
            ("duration", Value::Real(duration)),
            ("position", Value::Real(position)),
            ("rate", Value::Real(rate)),
            ("readyToPlay", Value::Boolean(ready)),
            ("playbackBufferEmpty", Value::Boolean(buffering)),
            ("playbackBufferFull", Value::Boolean(!buffering)),
            ("playbackLikelyToKeepUp", Value::Boolean(!buffering)),
            ("loadedTimeRanges", Value::Array(ranges.clone())),
            ("seekableTimeRanges", Value::Array(ranges)),
        ]),
        response,
    )
}

fn xml(dict: Dictionary, response: &mut HttpResponse) -> Option<Vec<u8>> {
    let mut body = Vec::new();
    plist::to_writer_xml(&mut body, &dict).ok()?;
    response.add_header("Content-Type", "text/x-apple-plist+xml");
    Some(body)
}

fn play_parameters(body: &[u8]) -> Option<Dictionary> {
    if let Ok(Value::Dictionary(dict)) = plist::from_bytes(body) {
        return Some(dict);
    }
    let mut dict = Dictionary::new();
    for line in std::str::from_utf8(body)
        .ok()?
        .lines()
        .filter(|line| !line.trim().is_empty())
    {
        let (name, value) = line.split_once(':')?;
        let value = value.trim();
        if name.eq_ignore_ascii_case("Content-Location") {
            dict.insert("Content-Location".into(), Value::String(value.into()));
        } else if name.eq_ignore_ascii_case("Start-Position") {
            dict.insert("Start-Position".into(), Value::Real(value.parse().ok()?));
        }
    }
    Some(dict)
}

fn start_position(dict: &Dictionary) -> (f32, Option<f32>) {
    if let Some(seconds) = number(dict, "Start-Position-Seconds") {
        return (seconds.max(0.0), None);
    }
    match number(dict, "Start-Position") {
        Some(fraction) if (0.0..=1.0).contains(&fraction) => (0.0, Some(fraction)),
        Some(seconds) => (seconds.max(0.0), None),
        None => (0.0, None),
    }
}

fn string<'a>(dict: &'a Dictionary, key: &str) -> Option<&'a str> {
    dict.get(key)?.as_string()
}

fn number(dict: &Dictionary, key: &str) -> Option<f32> {
    let value = dict.get(key)?;
    let number = value
        .as_real()
        .or_else(|| value.as_signed_integer().map(|n| n as f64))? as f32;
    number.is_finite().then_some(number)
}

fn query(url: &str, key: &str) -> Option<f32> {
    let value = url
        .split_once('?')?
        .1
        .split('&')
        .filter_map(|pair| pair.split_once('='))
        .find(|(name, _)| *name == key)?
        .1
        .parse::<f32>()
        .ok()?;
    value.is_finite().then_some(value)
}

fn valid_location(url: &str) -> bool {
    !url.is_empty()
        && url.len() <= 8192
        && !url.contains(|ch: char| ch.is_control() || ch.is_whitespace())
}

fn request_playlist(
    id: &mut u64,
    play: &mut Playback,
    reverse: Option<&mpsc::Sender<Vec<u8>>>,
) -> bool {
    let Some(url) = play.pending.front().cloned() else {
        return true;
    };
    let Some(reverse) = reverse else {
        return false;
    };
    *id = id.wrapping_add(1);
    let headers = Dictionary::from_iter([
        (
            "X-Playback-Session-Id",
            Value::String(play.session_id.clone()),
        ),
        (
            "User-Agent",
            Value::String("AppleCoreMedia/1.0.0.11B554a (Apple TV)".into()),
        ),
    ]);
    let request = Dictionary::from_iter([
        ("sessionID", Value::Integer(1_u64.into())),
        ("FCUP_Response_ClientInfo", Value::Integer(1_u64.into())),
        (
            "FCUP_Response_ClientRef",
            Value::Integer(40030004_u64.into()),
        ),
        ("FCUP_Response_RequestID", Value::Integer((*id).into())),
        ("FCUP_Response_URL", Value::String(url.clone())),
        ("FCUP_Response_Headers", Value::Dictionary(headers)),
    ]);
    let dict = Dictionary::from_iter([
        ("sessionID", Value::Integer(1_u64.into())),
        ("type", Value::String("unhandledURLRequest".into())),
        ("request", Value::Dictionary(request)),
    ]);
    let mut body = Vec::new();
    if plist::to_writer_xml(&mut body, &dict).is_err() {
        return false;
    }
    let mut event = format!(
        "POST /event HTTP/1.1\r\nX-Apple-Session-ID: {}\r\nContent-Type: text/x-apple-plist+xml\r\nContent-Length: {}\r\n\r\n",
        play.session_id, body.len()
    ).into_bytes();
    event.extend(body);
    if reverse.try_send(event).is_err() {
        return false;
    }
    play.pending.pop_front();
    play.requested = Some((*id, url, Instant::now()));
    true
}

fn receive_playlist(play: &mut Playback, params: &Dictionary) -> bool {
    let Some((id, url, _)) = play.requested.as_ref() else {
        return false;
    };
    if string(params, "FCUP_Response_URL") != Some(url)
        || params
            .get("FCUP_Response_RequestID")
            .and_then(Value::as_unsigned_integer)
            .is_some_and(|n| n != *id)
    {
        return false;
    }
    let Some(data) = params.get("FCUP_Response_Data").and_then(Value::as_data) else {
        return false;
    };
    if data.len() > MAX_PLAYLIST_BYTES {
        tracing::warn!("AirPlay FCUP playlist exceeds input size limit");
        return false;
    }
    let Ok(text) = std::str::from_utf8(data) else {
        return false;
    };
    if text.lines().next() != Some("#EXTM3U") {
        return false;
    }
    let url = url.clone();
    let Some(index) = play.resources.iter().position(|item| *item == url) else {
        return false;
    };
    let resources = play.resources.len();
    let pending = play.pending.len();
    let stored: usize = play.playlists.values().map(String::len).sum();
    let Some(text) = rewrite_playlist(play, &url, text)
        .filter(|text| stored + text.len() <= MAX_SESSION_PLAYLIST_BYTES)
    else {
        play.resources.truncate(resources);
        play.pending.truncate(pending);
        tracing::warn!("AirPlay FCUP playlist rejected during rewrite or storage");
        return false;
    };
    play.playlists.insert(index, text);
    play.requested = None;
    true
}

fn rewrite_playlist(play: &mut Playback, base: &str, text: &str) -> Option<String> {
    let master = text
        .lines()
        .any(|line| line.starts_with("#EXT-X-STREAM-INF:"));
    if !master {
        // Keep the compact media playlists and expand only what the player requests.
        return Some(text.to_owned());
    }
    let mut output = String::new();
    let variants = play
        .prefer_high_quality
        .then(|| best_variant_first(text))
        .flatten();
    let mut lines = text.lines();
    while let Some(line) = lines.next() {
        if variants.is_some() && line.starts_with("#EXT-X-STREAM-INF:") {
            // Re-emit the variants with the highest quality first.
            lines.next()?;
            continue;
        }
        let line = if !line.is_empty() && !line.starts_with('#') {
            media_reference(play, base, line)?
        } else if let Some((prefix, rest)) = line.split_once("URI=\"") {
            let (uri, suffix) = rest.split_once('"')?;
            format!(
                "{prefix}URI=\"{}\"{suffix}",
                media_reference(play, base, uri)?
            )
        } else {
            line.to_owned()
        };
        output.push_str(&line);
        output.push('\n');
        if output.len() > 4 * MAX_PLAYLIST_BYTES {
            return None;
        }
    }
    if let Some(variants) = variants {
        for (tag, uri) in variants {
            output.push_str(tag);
            output.push('\n');
            output.push_str(&media_reference(play, base, uri)?);
            output.push('\n');
        }
    }
    Some(output)
}

fn expand_condensed_playlist(text: &str) -> Option<String> {
    let Some(tag) = text
        .lines()
        .find(|line| line.starts_with("#YT-EXT-CONDENSED-URL:"))
    else {
        return Some(text.to_owned());
    };
    let prefix = attr(tag, "PREFIX")?;
    let origin = attr(tag, "BASE-URI")?.trim_end_matches('/');
    if !origin.starts_with("https://") {
        return None;
    }
    let params: Vec<_> = attr(tag, "PARAMS")?.split(',').collect();
    let mut output = String::new();
    for line in text.lines().filter(|line| *line != tag) {
        if let Some(segment) = line.strip_prefix(prefix).filter(|_| !line.starts_with('#')) {
            let values: Vec<_> = segment.splitn(params.len(), '/').collect();
            if values.len() != params.len() {
                return None;
            }
            output.push_str(origin);
            for (param, value) in params.iter().zip(values) {
                use std::fmt::Write;
                write!(output, "/{param}/{value}").ok()?;
            }
        } else {
            output.push_str(line);
        }
        output.push('\n');
        if output.len() > 4 * MAX_PLAYLIST_BYTES {
            return None;
        }
    }
    Some(output)
}

/// Android HLS starts at the first advertised rendition. Put the highest
/// resolution first, leaving the remaining renditions available for adaptation.
fn best_variant_first(text: &str) -> Option<Vec<(&str, &str)>> {
    let mut variants = Vec::new();
    let mut lines = text.lines();
    while let Some(tag) = lines.next() {
        if !tag.starts_with("#EXT-X-STREAM-INF:") {
            continue;
        }
        let uri = lines.next()?;
        if uri.is_empty() || uri.starts_with('#') {
            return None;
        }
        variants.push((tag, uri));
    }
    // Keep adaptation within the same decoder family on Android TV. Some
    // YouTube ladders mix VP9 with AVC renditions in the same master.
    let is_avc = |tag: &str| {
        attr(tag, "CODECS").is_some_and(|codecs| {
            codecs
                .split(',')
                .any(|codec| codec.trim().starts_with("avc1."))
        })
    };
    if variants.iter().any(|(tag, _)| is_avc(tag)) {
        variants.retain(|(tag, _)| is_avc(tag));
    }
    let best = variants
        .iter()
        .enumerate()
        .max_by_key(|(_, (tag, _))| {
            let number = |name: &str| {
                tag.split_once(name)
                    .and_then(|(_, value)| value.split(',').next())
                    .unwrap_or("")
            };
            let height = number("RESOLUTION=")
                .split_once('x')
                .and_then(|(_, height)| height.parse::<u32>().ok())
                .unwrap_or(0);
            let fps = (number("FRAME-RATE=").parse::<f32>().unwrap_or(0.0) * 100.0) as u32;
            let bitrate = number("BANDWIDTH=").parse::<u32>().unwrap_or(0);
            (height, fps, bitrate)
        })?
        .0;
    let preferred = variants.remove(best);
    variants.insert(0, preferred);
    Some(variants)
}

fn attr<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let (_, value) = line.split_once(&format!("{key}=\""))?;
    Some(value.split_once('"')?.0)
}

fn media_reference(play: &mut Playback, base: &str, uri: &str) -> Option<String> {
    if !valid_location(uri) {
        return None;
    }
    if uri.starts_with("https://") {
        return Some(uri.into());
    }
    if uri.starts_with("//") {
        return Some(format!("https:{uri}"));
    }
    let uri = if uri.contains("://") {
        uri.to_owned()
    } else {
        if uri.starts_with('/') {
            let (scheme, rest) = base.split_once("://")?;
            let host = rest.split('/').next()?;
            format!("{scheme}://{host}{uri}")
        } else {
            let (directory, _) = base.split('?').next()?.rsplit_once('/')?;
            format!("{directory}/{uri}")
        }
    };
    if !valid_location(&uri) {
        return None;
    }
    let index = match play.resources.iter().position(|item| *item == uri) {
        Some(index) => index,
        None if play.resources.len() < MAX_PLAYLISTS => {
            let index = play.resources.len();
            play.resources.push(uri.clone());
            play.pending.push_back(uri);
            index
        }
        None => return None,
    };
    Some(format!("{index}.m3u8"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_play_parameters_use_fraction_and_modern_seconds_take_priority() {
        let mut dict = play_parameters(
            b"Content-Location: https://media.example/movie.m3u8\r\nStart-Position: 0.25\r\n",
        )
        .unwrap();
        assert_eq!(
            string(&dict, "Content-Location"),
            Some("https://media.example/movie.m3u8")
        );
        assert_eq!(start_position(&dict), (0.0, Some(0.25)));
        dict.insert("Start-Position-Seconds".into(), Value::Real(0.25));
        assert_eq!(start_position(&dict), (0.25, None));
    }

    #[test]
    fn youtube_starts_at_highest_resolution_and_keeps_fallbacks() {
        let variants = best_variant_first(concat!(
            "#EXT-X-STREAM-INF:CODECS=\"avc1.4D401E\",RESOLUTION=854x480\n480.m3u8\n",
            "#EXT-X-STREAM-INF:CODECS=\"vp09.00.31.08\",RESOLUTION=1280x720\nvp9.m3u8\n",
            "#EXT-X-STREAM-INF:CODECS=\"avc1.640028\",RESOLUTION=1920x1080\n1080.m3u8\n",
            "#EXT-X-STREAM-INF:CODECS=\"avc1.64001F\",RESOLUTION=1280x720\n720.m3u8\n",
        ))
        .unwrap();
        assert_eq!(
            variants.iter().map(|(_, uri)| *uri).collect::<Vec<_>>(),
            ["1080.m3u8", "480.m3u8", "720.m3u8"]
        );
    }

    #[test]
    fn fcup_rewrites_video_audio_and_condensed_segment_urls() {
        let master = "mlhls://sender/dir/master.m3u8";
        let mut play = Playback {
            peer: IpAddr::from([127, 0, 0, 1]),
            session_id: "test".into(),
            uuid: None,
            prefer_high_quality: false,
            start: 0.0,
            start_fraction: None,
            rate: 1.0,
            path: "/pdmvos-hls/test/".into(),
            port: 1234,
            pending: VecDeque::new(),
            requested: None,
            resources: vec![master.into()],
            playlists: HashMap::new(),
            player: None,
        };
        let rewritten = rewrite_playlist(
            &mut play,
            master,
            concat!(
                "#EXTM3U\n",
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"/audio.m3u8\"\n",
                "#EXT-X-STREAM-INF:BANDWIDTH=123,AUDIO=\"a\"\n",
                "video.m3u8\n",
            ),
        )
        .unwrap();
        assert!(rewritten.contains("URI=\"1.m3u8\""));
        assert!(rewritten.ends_with("\n2.m3u8\n"));
        assert_eq!(
            play.pending,
            ["mlhls://sender/audio.m3u8", "mlhls://sender/dir/video.m3u8"]
        );

        let media = expand_condensed_playlist(concat!(
            "#EXTM3U\n",
            "#YT-EXT-CONDENSED-URL:BASE-URI=\"https://media.example/seg\",PARAMS=\"seq,ts\",PREFIX=\"s/\"\n",
            "#EXTINF:6,\ns/24/test.ts\n",
        )).unwrap();
        assert_eq!(
            media,
            "#EXTM3U\n#EXTINF:6,\nhttps://media.example/seg/seq/24/ts/test.ts\n"
        );
    }
}
