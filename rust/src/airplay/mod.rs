//! AirPlay transport runs in Rust. Android supplies DNS-SD registration and media output.
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
        })
    }

    fn on_volume(&self, volume: f32) {
        if volume.is_finite() {
            self.streams.lock().unwrap().audio_volume_db = volume.clamp(-144.0, 0.0);
        }
    }

    fn on_error(&self, error: &ShairplayError) {
        log_error(error);
    }
}

struct Pcm {
    streams: Arc<Mutex<Streams>>,
    channels: usize,
    generation: Option<u64>,
}

impl AudioSession for Pcm {
    fn audio_process(&mut self, samples: &[f32]) {
        let Some(generation) = self.generation else {
            return;
        };
        // 250 ms of stereo PCM. Bound latency if Android pauses its audio output.
        const CAPACITY: usize = 44100;
        let mut streams = self.streams.lock().unwrap();
        if streams.audio_generation != generation {
            return;
        }
        let frames = samples.chunks_exact(self.channels);
        let keep = frames.len().min(CAPACITY / 4);
        let discard = (streams.audio.len() + keep * 4).saturating_sub(CAPACITY);
        streams.audio.drain(..discard);
        let gain = 32768.0 * 10.0_f32.powf(streams.audio_volume_db / 20.0);
        for frame in frames.skip(samples.len() / self.channels - keep) {
            let pcm = |sample: f32| (sample * gain).clamp(-32768.0, 32767.0) as i16;
            streams.audio.extend(pcm(frame[0]).to_le_bytes());
            streams
                .audio
                .extend(pcm(frame[self.channels - 1]).to_le_bytes());
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
}

static SERVICE: Mutex<Option<Service>> = Mutex::new(None);

fn stop(service: Option<Service>) {
    if let Some(service) = service {
        let _ = service.shutdown.send(());
        let _ = service.worker.join();
    }
}

pub fn global_start(mode: i32, name: String, mac: [u8; 6]) -> Option<Vec<u8>> {
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
                        log_error(&error);
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
                    Err(error) => log_error(&error),
                }
                server.stop().await;
            });
            runtime.shutdown_timeout(Duration::from_secs(1));
        });
    let worker = worker.ok()?;
    if let Ok(records) = ready_rx.recv() {
        *service = Some(Service {
            streams,
            shutdown,
            worker,
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

pub fn global_get_state() -> i32 {
    with_streams(|streams| {
        if streams.video.active {
            1
        } else if streams.audio_active {
            2
        } else {
            0
        }
    })
    .unwrap_or_default()
}

pub fn global_poll_video() -> Option<Vec<u8>> {
    with_streams(|streams| streams.video.frames.pop_front()).flatten()
}

pub fn global_reset_video() {
    with_streams(|streams| streams.video.reset());
}

pub fn global_video_format() -> i32 {
    with_streams(|streams| streams.video.format).unwrap_or_default()
}

pub fn global_poll_audio(max_bytes: usize) -> Option<Vec<u8>> {
    with_streams(|streams| {
        let len = max_bytes.min(streams.audio.len()) & !3;
        (len > 0).then(|| streams.audio.drain(..len).collect())
    })
    .flatten()
}

#[cfg(not(target_os = "android"))]
fn log_error(error: &ShairplayError) {
    eprintln!("pdmvOS AirPlay: {error}");
}

#[cfg(target_os = "android")]
fn log_error(error: &ShairplayError) {
    use std::ffi::{c_char, c_int, CString};

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    if let Ok(message) = CString::new(error.to_string()) {
        unsafe {
            __android_log_write(5, c"AirPlayReceiver".as_ptr(), message.as_ptr());
        }
    }
}
