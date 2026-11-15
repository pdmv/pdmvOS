//! Android's platform codecs and audio sink, driven by the Rust receiver thread.

use std::collections::VecDeque;
use std::ffi::{c_char, c_void, CStr};
use std::ptr::null_mut;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use super::clock::VideoClock;
use super::video::VideoConfig;
use super::{log_message, Streams};

type Handle = *mut c_void;

#[repr(C)]
struct BufferInfo {
    offset: i32,
    size: i32,
    timestamp: i64,
    flags: u32,
}

#[link(name = "android")]
extern "C" {
    fn ANativeWindow_fromSurface(env: crate::jni::JNIEnv, surface: crate::jni::jobject) -> Handle;
    fn ANativeWindow_release(window: Handle);
}

#[link(name = "mediandk")]
extern "C" {
    fn AMediaCodec_createDecoderByType(mime: *const c_char) -> Handle;
    fn AMediaCodec_delete(codec: Handle) -> i32;
    fn AMediaCodec_configure(
        codec: Handle,
        format: Handle,
        window: Handle,
        crypto: Handle,
        flags: u32,
    ) -> i32;
    fn AMediaCodec_start(codec: Handle) -> i32;
    fn AMediaCodec_dequeueInputBuffer(codec: Handle, timeout_us: i64) -> isize;
    fn AMediaCodec_getInputBuffer(codec: Handle, index: usize, size: *mut usize) -> *mut u8;
    fn AMediaCodec_queueInputBuffer(
        codec: Handle,
        index: usize,
        offset: isize,
        size: usize,
        time: u64,
        flags: u32,
    ) -> i32;
    fn AMediaCodec_dequeueOutputBuffer(
        codec: Handle,
        info: *mut BufferInfo,
        timeout_us: i64,
    ) -> isize;
    fn AMediaCodec_releaseOutputBuffer(codec: Handle, index: usize, render: bool) -> i32;
    fn AMediaCodec_getOutputFormat(codec: Handle) -> Handle;
    fn AMediaFormat_new() -> Handle;
    fn AMediaFormat_delete(format: Handle) -> i32;
    fn AMediaFormat_setString(format: Handle, name: *const c_char, value: *const c_char);
    fn AMediaFormat_setInt32(format: Handle, name: *const c_char, value: i32);
    fn AMediaFormat_setBuffer(
        format: Handle,
        name: *const c_char,
        data: *const c_void,
        size: usize,
    );
    fn AMediaFormat_getInt32(format: Handle, name: *const c_char, value: *mut i32) -> bool;
}

#[link(name = "aaudio")]
extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut Handle) -> i32;
    fn AAudioStreamBuilder_delete(builder: Handle) -> i32;
    fn AAudioStreamBuilder_setSampleRate(builder: Handle, rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: Handle, channels: i32);
    fn AAudioStreamBuilder_setFormat(builder: Handle, format: i32);
    fn AAudioStreamBuilder_openStream(builder: Handle, stream: *mut Handle) -> i32;
    fn AAudioStream_requestStart(stream: Handle) -> i32;
    fn AAudioStream_write(stream: Handle, data: *const c_void, frames: i32, timeout_ns: i64)
        -> i32;
    fn AAudioStream_close(stream: Handle) -> i32;
}

struct Window(Handle);
// ANativeWindow's reference is owned by Window and shared with the decoder thread.
unsafe impl Send for Window {}
unsafe impl Sync for Window {}
impl Drop for Window {
    fn drop(&mut self) {
        unsafe {
            ANativeWindow_release(self.0);
        }
    }
}

static SURFACE: Mutex<Option<Arc<Window>>> = Mutex::new(None);

pub unsafe fn set_surface(env: crate::jni::JNIEnv, surface: crate::jni::jobject) {
    let window = if surface.is_null() {
        null_mut()
    } else {
        ANativeWindow_fromSurface(env, surface)
    };
    *SURFACE.lock().unwrap() = (!window.is_null()).then(|| Arc::new(Window(window)));
    super::with_streams(|streams| streams.wake_video());
}

struct Format(Handle);
impl Drop for Format {
    fn drop(&mut self) {
        unsafe {
            AMediaFormat_delete(self.0);
        }
    }
}
impl Format {
    fn get(&self, key: &CStr) -> Option<i32> {
        let mut value = 0;
        unsafe { AMediaFormat_getInt32(self.0, key.as_ptr(), &mut value) }.then_some(value)
    }

    fn dimensions(&self) -> Option<(i32, i32)> {
        if let (Some(left), Some(right), Some(top), Some(bottom)) = (
            self.get(c"crop-left"),
            self.get(c"crop-right"),
            self.get(c"crop-top"),
            self.get(c"crop-bottom"),
        ) {
            return Some((right - left + 1, bottom - top + 1));
        }
        Some((self.get(c"width")?, self.get(c"height")?))
    }
}

#[derive(Default)]
struct VideoTiming {
    inflight: VecDeque<(u64, Instant)>,
    queue_us: u64,
    decode_us: u64,
    queued: u64,
    decoded: u64,
}

impl VideoTiming {
    fn submitted(&mut self, deadline: u64, received: Instant, queued: Instant) {
        self.queue_us += queued.saturating_duration_since(received).as_micros() as u64;
        self.queued += 1;
        // Bound measurement state when a codec discards outputs internally.
        if self.inflight.len() == 64 {
            self.inflight.pop_front();
        }
        self.inflight.push_back((deadline, queued));
    }

    fn output(&mut self, deadline: u64) {
        if let Some(index) = self.inflight.iter().position(|(time, _)| *time == deadline) {
            if let Some((_, queued)) = self.inflight.remove(index) {
                self.decode_us += queued.elapsed().as_micros() as u64;
                self.decoded += 1;
            }
        }
    }
}

struct Codec {
    handle: Handle,
    window: Arc<Window>,
    revision: u32,
    input: Option<usize>,
    dimensions: (i32, i32),
    dimensions_from_stream: bool,
    reported_dimensions: Option<(i32, i32)>,
    timing: VideoTiming,
    clock: Instant,
    playout: VideoClock,
    output: Option<(usize, u64)>,
    presented: u64,
    skipped: u64,
}
impl Drop for Codec {
    fn drop(&mut self) {
        if self.presented != 0 || self.skipped != 0 {
            log_message(&format!(
                "AirPlay playout: {} presented, {} skipped late",
                self.presented, self.skipped
            ));
        }
        if self.timing.queued != 0 && self.timing.decoded != 0 {
            log_message(&format!(
                "AirPlay timing: queue avg {} ms ({}), codec output avg {} ms ({})",
                self.timing.queue_us / self.timing.queued / 1000,
                self.timing.queued,
                self.timing.decode_us / self.timing.decoded / 1000,
                self.timing.decoded
            ));
        }
        unsafe {
            AMediaCodec_delete(self.handle);
        }
    }
}
impl Codec {
    fn open(window: Arc<Window>, config: &VideoConfig) -> Result<Self, i32> {
        let mime = if config.hevc {
            c"video/hevc"
        } else {
            c"video/avc"
        };
        unsafe {
            let handle = AMediaCodec_createDecoderByType(mime.as_ptr());
            if handle.is_null() {
                return Err(-1);
            }
            let codec = Self {
                handle,
                window,
                revision: config.revision,
                input: None,
                dimensions: config.dimensions.unwrap_or((1920, 1080)),
                dimensions_from_stream: config.dimensions.is_some(),
                reported_dimensions: None,
                timing: VideoTiming::default(),
                clock: Instant::now(),
                playout: VideoClock::default(),
                output: None,
                presented: 0,
                skipped: 0,
            };
            let format = AMediaFormat_new();
            if format.is_null() {
                return Err(-1);
            }
            let format = Format(format);
            AMediaFormat_setString(format.0, c"mime".as_ptr(), mime.as_ptr());
            AMediaFormat_setInt32(format.0, c"width".as_ptr(), codec.dimensions.0);
            AMediaFormat_setInt32(format.0, c"height".as_ptr(), codec.dimensions.1);
            AMediaFormat_setInt32(format.0, c"max-input-size".as_ptr(), 4 * 1024 * 1024);
            for (key, data) in [c"csd-0", c"csd-1"].into_iter().zip(&config.csd) {
                AMediaFormat_setBuffer(format.0, key.as_ptr(), data.as_ptr().cast(), data.len());
            }
            check(AMediaCodec_configure(
                handle,
                format.0,
                codec.window.0,
                null_mut(),
                0,
            ))?;
            check(AMediaCodec_start(handle))?;
            Ok(codec)
        }
    }

    fn tick(&mut self, streams: &Mutex<Streams>) -> Result<bool, i32> {
        let mut worked = false;
        unsafe {
            if self.input.is_none() {
                let index = AMediaCodec_dequeueInputBuffer(self.handle, 0);
                if index < -1 {
                    return Err(index as i32);
                }
                self.input = (index >= 0).then_some(index as usize);
            }
            if let Some(index) = self.input {
                let frame = {
                    let mut streams = streams.lock().unwrap();
                    streams.video.pop(self.revision)
                };
                if let Some(frame) = frame {
                    let mut capacity = 0;
                    let dest = AMediaCodec_getInputBuffer(self.handle, index, &mut capacity);
                    if dest.is_null() || capacity < frame.data.len() {
                        return Err(-2);
                    }
                    std::ptr::copy_nonoverlapping(frame.data.as_ptr(), dest, frame.data.len());
                    let time = self.playout.deadline(
                        frame.timestamp,
                        frame
                            .received
                            .saturating_duration_since(self.clock)
                            .as_micros() as u64,
                        self.clock.elapsed().as_micros() as u64,
                    );
                    let queued = Instant::now();
                    check(AMediaCodec_queueInputBuffer(
                        self.handle,
                        index,
                        0,
                        frame.data.len(),
                        time,
                        0,
                    ))?;
                    self.timing.submitted(time, frame.received, queued);
                    self.input = None;
                    worked = true;
                }
            }
            if self.output.is_none() {
                self.output = self.dequeue_picture()?;
            }
            let Some((mut index, mut time)) = self.output else {
                return Ok(worked);
            };
            let now = self.clock.elapsed().as_micros() as u64;
            if now < time {
                return Ok(worked);
            }
            self.output = None;
            if self.presented != 0 {
                // Only trade a stale picture for a newer decoded picture that
                // can be displayed now. Submitted input is not a replacement.
                for _ in 0..3 {
                    if now - time < super::clock::LATE_US {
                        break;
                    }
                    let Some((next_index, next_time)) = self.dequeue_picture()? else {
                        break;
                    };
                    if !super::clock::can_replace_picture(now, time, next_time) {
                        self.output = Some((next_index, next_time));
                        break;
                    }
                    check(AMediaCodec_releaseOutputBuffer(self.handle, index, false))?;
                    self.skipped += 1;
                    (index, time) = (next_index, next_time);
                }
            }
            check(AMediaCodec_releaseOutputBuffer(self.handle, index, true))?;
            self.presented += 1;
            if self.reported_dimensions != Some(self.dimensions) {
                self.reported_dimensions = Some(self.dimensions);
                if streams.lock().unwrap().video.mark_rendered(self.revision, self.dimensions) {
                    log_message(&format!(
                        "First AirPlay frame {}x{}",
                        self.dimensions.0, self.dimensions.1
                    ));
                }
            }
        }
        Ok(true)
    }

    fn dequeue_picture(&mut self) -> Result<Option<(usize, u64)>, i32> {
        let mut info = BufferInfo {
            offset: 0,
            size: 0,
            timestamp: 0,
            flags: 0,
        };
        unsafe {
            match AMediaCodec_dequeueOutputBuffer(self.handle, &mut info, 0) {
                -2 => {
                    let handle = AMediaCodec_getOutputFormat(self.handle);
                    if !handle.is_null() {
                        let format = Format(handle);
                        if !self.dimensions_from_stream {
                            if let Some(size) = format.dimensions() {
                                self.dimensions = size;
                            }
                        }
                    }
                    Ok(None)
                }
                -1 | -3 => Ok(None),
                index if index >= 0 => {
                    let deadline = info.timestamp.max(0) as u64;
                    self.timing.output(deadline);
                    Ok(Some((index as usize, deadline)))
                }
                error => Err(error as i32),
            }
        }
    }
}

fn check(status: i32) -> Result<(), i32> {
    if status == 0 {
        Ok(())
    } else {
        Err(status)
    }
}

struct Audio(Handle);
impl Drop for Audio {
    fn drop(&mut self) {
        unsafe {
            AAudioStream_close(self.0);
        }
    }
}
impl Audio {
    fn open() -> Result<Self, i32> {
        unsafe {
            let mut builder = null_mut();
            check(AAudio_createStreamBuilder(&mut builder))?;
            AAudioStreamBuilder_setSampleRate(builder, 44100);
            AAudioStreamBuilder_setChannelCount(builder, 2);
            AAudioStreamBuilder_setFormat(builder, 1); // AAUDIO_FORMAT_PCM_I16
            let mut stream = null_mut();
            let status = AAudioStreamBuilder_openStream(builder, &mut stream);
            AAudioStreamBuilder_delete(builder);
            check(status)?;
            let audio = Self(stream);
            check(AAudioStream_requestStart(stream))?;
            Ok(audio)
        }
    }

    fn write(&self, data: &[u8]) -> Result<usize, i32> {
        let frames = unsafe {
            AAudioStream_write(
                self.0,
                data.as_ptr().cast(),
                (data.len() / 4) as i32,
                8_000_000,
            )
        };
        if frames < 0 {
            Err(frames)
        } else {
            Ok(frames as usize * 4)
        }
    }
}

#[derive(Default)]
pub(super) struct VideoPlayback {
    codec: Option<Codec>,
}

impl VideoPlayback {
    pub(super) fn tick(&mut self, streams: &Mutex<Streams>) -> Option<Duration> {
        if self.video(streams) {
            return Some(Duration::ZERO);
        }
        let has_surface = SURFACE.lock().unwrap().is_some();
        let active = has_surface && streams.lock().unwrap().video.can_render();
        if !active {
            return self.codec.as_ref().map(|_| Duration::from_millis(2));
        }
        let delay = self.codec.as_ref().map_or(2, |codec| {
            if codec.input.is_some() && codec.output.is_none() && codec.timing.inflight.is_empty() {
                12
            } else {
                2
            }
        });
        Some(Duration::from_millis(delay))
    }

    fn video(&mut self, streams: &Mutex<Streams>) -> bool {
        let window = SURFACE.lock().unwrap().clone();
        let (revision, active) = {
            let streams = streams.lock().unwrap();
            (streams.video.revision(), streams.video.can_render())
        };
        if let Some(codec) = &self.codec {
            if active
                && codec.revision == revision
                && window
                    .as_ref()
                    .is_none_or(|next| !Arc::ptr_eq(next, &codec.window))
            {
                // A fresh decoder has no reference pictures from the old surface.
                streams.lock().unwrap().video.wait_for_sync(revision);
            }
        }
        let Some(window) = window.filter(|_| active) else {
            self.codec = None;
            return false;
        };

        if self
            .codec
            .as_ref()
            .is_none_or(|c| c.revision != revision || !Arc::ptr_eq(&window, &c.window))
        {
            self.codec = None;
            let config = streams.lock().unwrap().video.config_for(revision);
            let Some(config) = config else { return false };
            match Codec::open(window, &config) {
                Ok(codec) => self.codec = Some(codec),
                Err(error) => {
                    log_message(&format!("AirPlay decoder open failed: {error}"));
                    streams.lock().unwrap().video.fail(revision);
                    return false;
                }
            }
        }
        match self.codec.as_mut().unwrap().tick(streams) {
            Ok(worked) => worked,
            Err(error) => {
                log_message(&format!("AirPlay decoder failed: {error}"));
                let surface_replaced =
                    SURFACE.lock().unwrap().as_ref().is_none_or(|next| {
                        !Arc::ptr_eq(next, &self.codec.as_ref().unwrap().window)
                    });
                self.codec = None;
                let mut streams = streams.lock().unwrap();
                if surface_replaced {
                    streams.video.wait_for_sync(revision);
                } else {
                    streams.video.fail(revision);
                }
                false
            }
        }
    }
}

#[derive(Default)]
pub(super) struct AudioPlayback {
    audio: Option<Audio>,
    audio_generation: u64,
    audio_retry: Option<Instant>,
    pending: Vec<u8>,
    offset: usize,
}

impl AudioPlayback {
    pub(super) fn tick(&mut self, streams: &Mutex<Streams>) -> Option<Duration> {
        if self.audio(streams) {
            return Some(Duration::ZERO);
        }
        let active = streams.lock().unwrap().rtp_audio_active();
        (active || self.audio.is_some()).then_some(Duration::from_millis(3))
    }

    fn audio(&mut self, streams: &Mutex<Streams>) -> bool {
        // Roughly 35 ms of stereo PCM at 44.1 kHz.
        const PREBUFFER_BYTES: usize = 6_144;
        let mut previous = None;
        {
            let mut streams = streams.lock().unwrap();
            if self.audio_generation != streams.audio_generation || !streams.rtp_audio_active() {
                previous = self.audio.take();
                self.pending.clear();
                self.offset = 0;
                self.audio_retry = None;
                self.audio_generation = streams.audio_generation;
            }
            if let Some(retry) = self.audio_retry {
                if retry > Instant::now() {
                    return false;
                }
                // Rejoin the live stream after an output failure. Playing all
                // the queued PCM would leave sound trailing the video.
                let stale = streams.audio.len().saturating_sub(PREBUFFER_BYTES);
                streams.audio.drain(..stale);
                self.audio_retry = None;
            }
            if self.offset == self.pending.len() {
                // Start with roughly 35 ms of PCM so a small RTP gap does not underrun the sink.
                if self.audio.is_none() && streams.audio.len() < PREBUFFER_BYTES {
                    return false;
                }
                let count = streams.audio.len().min(4096) & !3;
                self.pending.clear();
                self.pending.extend(streams.audio.drain(..count));
                self.offset = 0;
            }
        }
        drop(previous);
        if self.pending.is_empty() {
            return false;
        }
        let result = (|| {
            if self.audio.is_none() {
                self.audio = Some(Audio::open()?);
            }
            self.audio
                .as_ref()
                .unwrap()
                .write(&self.pending[self.offset..])
        })();
        match result {
            Ok(bytes) => {
                self.offset += bytes;
                bytes > 0
            }
            Err(error) => {
                log_message(&format!("AirPlay audio sink failed: {error}"));
                self.audio = None;
                self.pending.clear();
                self.offset = 0;
                self.audio_retry = Some(Instant::now() + Duration::from_secs(1));
                false
            }
        }
    }
}

extern "C" {
    fn pdmvos_eld_open(config: *const u8, size: usize, rate: i32) -> Handle;
    fn pdmvos_eld_decode(
        decoder: Handle,
        input: *const u8,
        size: usize,
        output: *mut f32,
        capacity: usize,
    ) -> i32;
    fn pdmvos_eld_free(decoder: Handle);
}

pub(super) struct EldDecoder {
    handle: Handle,
    reported: bool,
    probes: u8,
}

// The RTP session owns the decoder exclusively, even if its task moves threads.
unsafe impl Send for EldDecoder {}

impl Drop for EldDecoder {
    fn drop(&mut self) {
        unsafe {
            pdmvos_eld_free(self.handle);
        }
    }
}

impl EldDecoder {
    pub(super) fn open(rate: u32, samples: u32) -> Option<Self> {
        let frequency = match rate {
            44100 => 4,
            48000 => 3,
            _ => return None,
        };
        let frame_length = match samples {
            480 => 0x10,
            512 => 0,
            _ => return None,
        };
        // MPEG-4 AudioSpecificConfig: object type 39, stereo, no LD-SBR.
        let config = [0xf8, 0xe0 | (frequency << 1), 0x40 | frame_length, 0];
        let handle = unsafe { pdmvos_eld_open(config.as_ptr(), config.len(), rate as i32) };
        if handle.is_null() {
            log_message("AirPlay AAC-ELD decoder rejected the negotiated format");
            return None;
        }
        log_message(&format!(
            "AirPlay AAC-ELD decoder ready: {rate} Hz, {samples} samples"
        ));
        Some(Self {
            handle,
            reported: false,
            probes: 0,
        })
    }
}

impl shairplay::raop::AacEldDecoder for EldDecoder {
    fn decode(&mut self, packet: &[u8], samples: &mut [f32]) -> usize {
        if packet.len() <= 4 {
            return 0;
        }
        let count = unsafe {
            pdmvos_eld_decode(
                self.handle,
                packet.as_ptr(),
                packet.len(),
                samples.as_mut_ptr(),
                samples.len(),
            )
        };
        if count <= 0 && self.probes < 4 {
            log_message(&format!(
                "AirPlay AAC-ELD frame: length={}, first={:02x}, decoder={count}",
                packet.len(),
                packet[0]
            ));
            self.probes += 1;
        }
        if count > 0 && !self.reported {
            log_message("AirPlay decoded the first AAC-ELD audio frame");
            self.reported = true;
        }
        count.max(0) as usize
    }
}
