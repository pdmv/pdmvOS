//! Shared playback controls for the Android media player.

use super::Streams;
use shairplay::raop::hls::{HlsHandler, HlsSession};
use std::sync::{Arc, Mutex};

#[derive(Default)]
pub(super) struct Playback {
    id: i32,
    url: Option<String>,
    seek_revision: i32,
    seek_ms: i32,
    play: bool,
    position_ms: i32,
    duration_ms: i32,
    ready: bool,
    buffering: bool,
    playing: bool,
}

impl Playback {
    pub(super) fn owns_audio(&self) -> bool {
        self.url.is_some() && self.ready
    }

    pub(super) fn controls(&self, gain: f32) -> [i32; 5] {
        if self.url.is_none() {
            return [0; 5];
        }
        [
            self.id,
            self.seek_revision,
            self.seek_ms,
            i32::from(self.play),
            gain.to_bits() as i32,
        ]
    }

    pub(super) fn url(&self, session: i32) -> Option<Vec<u8>> {
        (self.id == session)
            .then_some(self.url.as_ref()?)
            .map(|url| url.as_bytes().to_vec())
    }

    pub(super) fn report(
        &mut self,
        id: i32,
        position_ms: i32,
        duration_ms: i32,
        ready: i32,
        playing: bool,
    ) {
        if id == self.id && self.url.is_some() {
            self.position_ms = position_ms.max(0);
            self.duration_ms = duration_ms.max(0);
            self.ready = ready > 0;
            self.buffering = ready == 2;
            self.playing = playing;
            if ready < 0 {
                self.url = None;
            }
        }
    }
}

pub(super) struct Handler(pub(super) Arc<Mutex<Streams>>);

impl HlsHandler for Handler {
    fn on_play(&self, url: &str, start_position: f32) -> Box<dyn HlsSession> {
        let mut streams = self.0.lock().unwrap();
        let id = streams.hls.id.wrapping_add(1).max(1);
        streams.hls = Playback {
            id,
            url: Some(url.into()),
            seek_ms: milliseconds(start_position),
            play: true,
            ..Playback::default()
        };
        Box::new(Session {
            streams: self.0.clone(),
            id,
        })
    }
}

struct Session {
    streams: Arc<Mutex<Streams>>,
    id: i32,
}

impl Session {
    fn state<T: Default>(&self, f: impl FnOnce(&mut Playback) -> T) -> T {
        let mut streams = self.streams.lock().unwrap();
        if streams.hls.id == self.id && streams.hls.url.is_some() {
            f(&mut streams.hls)
        } else {
            T::default()
        }
    }
}

fn milliseconds(seconds: f32) -> i32 {
    (seconds.max(0.0) * 1000.0) as i32
}

impl HlsSession for Session {
    fn duration(&self) -> f32 {
        self.state(|p| p.duration_ms as f32 / 1000.0)
    }
    fn position(&self) -> f32 {
        self.state(|p| p.position_ms as f32 / 1000.0)
    }
    fn rate(&self) -> f32 {
        self.state(|p| if p.playing { 1.0 } else { 0.0 })
    }
    fn ready(&self) -> bool {
        self.state(|p| p.ready)
    }
    fn buffering(&self) -> bool {
        self.state(|p| p.buffering)
    }
    fn seek(&mut self, position: f32) {
        self.state(|p| {
            p.seek_ms = milliseconds(position);
            p.seek_revision = p.seek_revision.wrapping_add(1);
        });
    }
    fn seek_fraction(&mut self, fraction: f32) {
        self.state(|p| {
            // Negative positions encode millionths of the duration until Android knows it.
            p.seek_ms = -1 - (fraction.clamp(0.0, 1.0) * 1_000_000.0).round() as i32;
            p.seek_revision = p.seek_revision.wrapping_add(1);
        });
    }
    fn set_rate(&mut self, rate: f32) {
        self.state(|p| p.play = rate > 0.0);
    }
    fn stop(&mut self) {
        self.state(|p| p.url = None);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hls_snapshot_separates_url_and_rejects_stale_sessions() {
        let streams = Arc::new(Mutex::new(Streams::default()));
        let _session = Handler(streams.clone()).on_play("https://example.test/stream.m3u8", 0.0);
        let mut streams = streams.lock().unwrap();
        let playback = &mut streams.hls;
        let id = playback.id;
        assert!(!playback.owns_audio());
        assert_eq!(
            playback.controls(0.5),
            [id, 0, 0, 1, 0.5_f32.to_bits() as i32]
        );
        assert_eq!(
            playback.url(id).as_deref(),
            Some(b"https://example.test/stream.m3u8".as_slice())
        );
        assert!(playback.url(id + 1).is_none());
        playback.report(id, 100, 200, 2, true);
        assert!(playback.owns_audio());
        assert!(playback.buffering);
        playback.report(id, 0, 0, -1, false);
        assert!(!playback.owns_audio());
        assert_eq!(playback.controls(1.0), [0; 5]);
        assert!(playback.url(id).is_none());
    }
}
