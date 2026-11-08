//! AirPlay transport and playback. Android supplies service registration and the display surface.
#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "android")]
pub use android::set_surface;
mod avc;
#[cfg(any(target_os = "android", test))]
mod clock;
mod video;

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use shairplay::{AirPlayMode, AudioFormat, AudioHandler, AudioSession, RaopServer, ShairplayError};
use tokio::sync::oneshot;
use video::Video;

#[derive(Default)]
struct Streams {
    video: Video,
    audio: VecDeque<u8>,
    audio_active: bool,
    audio_generation: u64,
    audio_volume_db: f32,
}

struct Output {
    streams: Arc<Mutex<Streams>>,
    audio_enabled: bool,
}

impl AudioHandler for Output {
    fn audio_init(&self, format: AudioFormat) -> Box<dyn AudioSession> {
        let enabled =
            self.audio_enabled && format.sample_rate == 44100 && matches!(format.channels, 1 | 2);
        let generation = enabled.then(|| {
            let mut streams = self.streams.lock().unwrap();
            streams.audio.clear();
            streams.audio_active = true;
            streams.audio_generation = streams.audio_generation.wrapping_add(1);
            streams.audio_generation
        });
        Box::new(Pcm {
            streams: self.streams.clone(),
            channels: usize::from(format.channels),
            generation,
            converted: Vec::new(),
        })
    }

    #[cfg(target_os = "android")]
    fn aac_eld_decoder(
        &self,
        rate: u32,
        samples: u32,
    ) -> Option<Box<dyn shairplay::raop::AacEldDecoder>> {
        android::EldDecoder::open(rate, samples).map(|decoder| Box::new(decoder) as _)
    }

    fn on_volume(&self, volume: f32) {
        if volume.is_finite() {
            self.streams.lock().unwrap().audio_volume_db = volume.clamp(-144.0, 0.0);
        }
    }

    fn on_error(&self, error: &ShairplayError) {
        log_message(&error.to_string());
    }
}

struct Pcm {
    streams: Arc<Mutex<Streams>>,
    channels: usize,
    generation: Option<u64>,
    converted: Vec<u8>,
}

impl AudioSession for Pcm {
    fn audio_process(&mut self, samples: &[f32]) {
        let Some(generation) = self.generation else {
            return;
        };
        // 250 ms of stereo PCM. Bound latency if Android pauses its audio output.
        const CAPACITY: usize = 44100;
        let volume_db = {
            let streams = self.streams.lock().unwrap();
            if streams.audio_generation != generation {
                return;
            }
            streams.audio_volume_db
        };
        let frames = samples.chunks_exact(self.channels);
        let keep = frames.len().min(CAPACITY / 4);
        let gain = 32768.0 * 10.0_f32.powf(volume_db / 20.0);
        let pcm = |sample: f32| (sample * gain).clamp(-32768.0, 32767.0) as i16;
        self.converted.clear();
        for frame in frames.skip(samples.len() / self.channels - keep) {
            self.converted.extend(pcm(frame[0]).to_le_bytes());
            self.converted
                .extend(pcm(frame[self.channels - 1]).to_le_bytes());
        }
        let mut streams = self.streams.lock().unwrap();
        if streams.audio_generation == generation {
            let discard = (streams.audio.len() + self.converted.len()).saturating_sub(CAPACITY);
            streams.audio.drain(..discard);
            streams.audio.extend(&self.converted);
        }
    }

    fn audio_flush(&mut self) {
        let Some(generation) = self.generation else {
            return;
        };
        let mut streams = self.streams.lock().unwrap();
        if streams.audio_generation == generation {
            streams.audio.clear();
        }
    }
}

impl Drop for Pcm {
    fn drop(&mut self) {
        let Some(generation) = self.generation else {
            return;
        };
        let mut streams = self.streams.lock().unwrap();
        if streams.audio_generation == generation {
            streams.audio_active = false;
            streams.audio.clear();
        }
    }
}

struct Service {
    streams: Arc<Mutex<Streams>>,
    shutdown: oneshot::Sender<()>,
    worker: JoinHandle<()>,
    #[cfg(target_os = "android")]
    playback: (JoinHandle<()>, JoinHandle<()>),
}

static SERVICE: Mutex<Option<Service>> = Mutex::new(None);

fn stop(service: Option<Service>) {
    if let Some(service) = service {
        let _ = service.shutdown.send(());
        let _ = service.worker.join();
        #[cfg(target_os = "android")]
        {
            let _ = service.playback.0.join();
            let _ = service.playback.1.join();
        }
    }
}

pub fn global_start(mode: i32, name: String, mac: [u8; 6]) -> Option<Vec<u8>> {
    #[cfg(target_os = "android")]
    install_diagnostics();
    let mut service = SERVICE.lock().unwrap();
    stop(service.take());
    let protocol = match mode {
        0 | 1 => AirPlayMode::AirPlay2,
        2 => AirPlayMode::AirPlay1,
        _ => return None,
    };

    let streams = Arc::new(Mutex::new(Streams::default()));
    let output = Arc::new(Output {
        streams: streams.clone(),
        audio_enabled: mode != 1,
    });
    #[cfg(target_os = "android")]
    let playing = Arc::new(std::sync::atomic::AtomicBool::new(true));
    #[cfg(target_os = "android")]
    let playing_for_server = playing.clone();
    let (shutdown, shutdown_rx) = oneshot::channel();
    let (ready, ready_rx) = std::sync::mpsc::sync_channel(1);
    let worker = thread::Builder::new()
        .name("pdmvos-airplay".into())
        .spawn(move || {
            let Ok(runtime) = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            else {
                return;
            };
            runtime.block_on(async {
                let mut builder = RaopServer::builder()
                    .name(name)
                    .hwaddr(mac)
                    .port(0)
                    .max_clients(4)
                    .mode(protocol)
                    .output_sample_rate(44100)
                    .output_max_channels(2);
                // Android registers the LAN records below via its DNS-SD service.
                // Restrict shairplay's built-in announcer to loopback to avoid duplicates.
                if cfg!(target_os = "android") {
                    builder = builder.mdns_scope(shairplay::MdnsScope::Addrs(vec![
                        std::net::Ipv4Addr::LOCALHOST.into(),
                    ]));
                }
                if mode != 2 {
                    builder = builder.video_handler(output.clone());
                }
                let mut server = match builder.build(output) {
                    Ok(server) => server,
                    Err(error) => {
                        log_message(&error.to_string());
                        return;
                    }
                };
                match server.start().await {
                    Ok(()) => {
                        let info = server.service_info();
                        let mut records = String::new();
                        let mut append = |name: &str, service: &str, txt: &[(String, String)]| {
                            use std::fmt::Write;
                            if !records.is_empty() {
                                records.push('\n');
                            }
                            let _ = writeln!(records, "{}\n{service}\n{name}", info.port);
                            for (key, value) in txt {
                                let _ = writeln!(records, "{key}={value}");
                            }
                        };
                        if mode != 1 {
                            append(&info.raop_name, "_raop._tcp.", &info.raop_txt);
                        }
                        if mode != 2 {
                            append(&info.airplay_name, "_airplay._tcp.", &info.airplay_txt);
                        }
                        if ready.send(records.into_bytes()).is_ok() {
                            let _ = shutdown_rx.await;
                        }
                    }
                    Err(error) => log_message(&error.to_string()),
                }
                server.stop().await;
            });
            #[cfg(target_os = "android")]
            playing_for_server.store(false, std::sync::atomic::Ordering::Release);
            runtime.shutdown_timeout(Duration::from_secs(1));
        });
    let worker = worker.ok()?;
    if let Ok(records) = ready_rx.recv() {
        #[cfg(target_os = "android")]
        let playback = {
            let video_streams = streams.clone();
            let video_running = playing.clone();
            let render = thread::Builder::new()
                .name("pdmvos-airplay-video".into())
                .spawn(move || {
                    let mut player = android::VideoPlayback::default();
                    while video_running.load(std::sync::atomic::Ordering::Acquire) {
                        let delay = player.tick(&video_streams);
                        if !delay.is_zero() {
                            thread::sleep(delay);
                        }
                    }
                });
            let render = match render {
                Ok(worker) => worker,
                Err(_) => {
                    let _ = shutdown.send(());
                    let _ = worker.join();
                    return None;
                }
            };
            let audio_streams = streams.clone();
            let audio = thread::Builder::new()
                .name("pdmvos-airplay-audio".into())
                .spawn(move || {
                    let mut player = android::AudioPlayback::default();
                    while playing.load(std::sync::atomic::Ordering::Acquire) {
                        let delay = player.tick(&audio_streams);
                        if !delay.is_zero() {
                            thread::sleep(delay);
                        }
                    }
                });
            match audio {
                Ok(audio) => (render, audio),
                Err(_) => {
                    let _ = shutdown.send(());
                    let _ = worker.join();
                    let _ = render.join();
                    return None;
                }
            }
        };
        *service = Some(Service {
            streams,
            shutdown,
            worker,
            #[cfg(target_os = "android")]
            playback,
        });
        Some(records)
    } else {
        let _ = worker.join();
        None
    }
}

pub fn global_stop() {
    let mut service = SERVICE.lock().unwrap();
    stop(service.take());
}

fn with_streams<T>(read: impl FnOnce(&mut Streams) -> T) -> Option<T> {
    let service = SERVICE.lock().unwrap();
    let streams = &service.as_ref()?.streams;
    let mut streams = streams.lock().unwrap();
    Some(read(&mut streams))
}

pub fn global_status() -> [i32; 4] {
    with_streams(|streams| streams.video.status()).unwrap_or_default()
}

#[cfg(not(target_os = "android"))]
fn log_message(message: &str) {
    eprintln!("pdmvOS AirPlay: {message}");
}

#[cfg(target_os = "android")]
fn log_message(message: &str) {
    use std::ffi::{c_char, c_int, CString};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    if let Ok(message) = CString::new(message) {
        unsafe {
            __android_log_write(5, c"AirPlayReceiver".as_ptr(), message.as_ptr());
        }
    }
}

#[cfg(target_os = "android")]
fn install_diagnostics() {
    use tracing::{span, Event, Metadata, Subscriber};
    struct Diagnostics;
    impl Subscriber for Diagnostics {
        fn enabled(&self, metadata: &Metadata<'_>) -> bool {
            metadata.target().starts_with("shairplay::raop")
                && metadata.level() <= &tracing::Level::INFO
        }
        fn new_span(&self, _: &span::Attributes<'_>) -> span::Id {
            span::Id::from_u64(1)
        }
        fn record(&self, _: &span::Id, _: &span::Record<'_>) {}
        fn record_follows_from(&self, _: &span::Id, _: &span::Id) {}
        fn event(&self, event: &Event<'_>) {
            struct Message(String);
            impl tracing::field::Visit for Message {
                fn record_debug(
                    &mut self,
                    field: &tracing::field::Field,
                    value: &dyn std::fmt::Debug,
                ) {
                    if matches!(
                        field.name(),
                        "message"
                            | "stream_type"
                            | "audio_format"
                            | "sample_rate"
                            | "samples_per_frame"
                    ) {
                        use std::fmt::Write;
                        let _ = write!(self.0, " {}={value:?}", field.name());
                    }
                }
            }
            if self.enabled(event.metadata()) {
                let mut message = Message(event.metadata().target().into());
                event.record(&mut message);
                log_message(&message.0);
            }
        }
        fn enter(&self, _: &span::Id) {}
        fn exit(&self, _: &span::Id) {}
    }
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        let _ = tracing::subscriber::set_global_default(Diagnostics);
    });
}
