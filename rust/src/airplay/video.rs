use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use shairplay::{PacketKind, VideoHandler, VideoPacket, VideoSession};

use super::{Output, Streams};

use std::time::{Duration, Instant};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct VideoConfig {
    pub(super) revision: u32,
    pub(super) hevc: bool,
    pub(super) dimensions: Option<(i32, i32)>,
    pub(super) csd: Vec<Vec<u8>>,
    length_size: usize,
}

pub(super) struct Frame {
    pub(super) data: Vec<u8>,
    #[cfg(target_os = "android")]
    pub(super) timestamp: u64,
    #[cfg(target_os = "android")]
    pub(super) received: Instant,
}

#[derive(Default)]
pub(super) struct Video {
    frames: VecDeque<Frame>,
    bytes: usize,
    session: u32,
    revision: u32,
    config: Option<VideoConfig>,
    needs_keyframe: bool,
    lost_frames: bool,
    phase: i32,
    dimensions: (i32, i32),
    waiting_since: Option<Instant>,
}

impl Video {
    pub(super) fn status(&mut self) -> [i32; 4] {
        if self
            .waiting_since
            .is_some_and(|time| time.elapsed() >= Duration::from_secs(7))
        {
            super::log_message("AirPlay timed out waiting for a decoded frame");
            self.fail(self.revision);
        }
        [
            self.phase,
            self.dimensions.0,
            self.dimensions.1,
            self.session as i32,
        ]
    }

    #[cfg(target_os = "android")]
    pub(super) fn revision(&self) -> u32 {
        self.revision
    }

    #[cfg(target_os = "android")]
    pub(super) fn can_render(&self) -> bool {
        matches!(self.phase, 1 | 2) && self.config.is_some()
    }

    #[cfg(target_os = "android")]
    pub(super) fn config_for(&self, revision: u32) -> Option<VideoConfig> {
        self.config
            .as_ref()
            .filter(|cfg| cfg.revision == revision)
            .cloned()
    }

    #[cfg(any(target_os = "android", test))]
    pub(super) fn pop(&mut self, revision: u32) -> Option<Frame> {
        if revision != self.revision {
            return None;
        }
        let frame = self.frames.pop_front()?;
        self.bytes -= frame.data.len();
        Some(frame)
    }

    #[cfg(target_os = "android")]
    pub(super) fn mark_rendered(&mut self, revision: u32, size: (i32, i32)) -> bool {
        if revision != self.revision || !self.can_render() {
            return false;
        }
        let first = self.phase != 2;
        self.phase = 2;
        self.waiting_since = None;
        self.dimensions = size;
        first
    }

    pub(super) fn fail(&mut self, revision: u32) {
        if revision == self.revision && matches!(self.phase, 1 | 2) {
            self.phase = 3;
            self.waiting_since = None;
            self.frames.clear();
            self.bytes = 0;
        }
    }

    #[cfg(any(target_os = "android", test))]
    pub(super) fn wait_for_sync(&mut self, revision: u32) {
        if revision != self.revision || !matches!(self.phase, 1 | 2) {
            return;
        }
        self.frames.clear();
        self.bytes = 0;
        self.needs_keyframe = true;
        self.lost_frames = false;
        self.waiting_since = Some(Instant::now());
    }

    fn configure(&mut self, mut config: VideoConfig) {
        config.revision = self.revision;
        if self.config.as_ref() == Some(&config) {
            return;
        }
        self.revision = self.revision.wrapping_add(1);
        config.revision = self.revision;
        super::log_message(&format!(
            "AirPlay video config: {}, NAL length {}",
            if config.hevc { "HEVC" } else { "AVC" },
            config.length_size
        ));
        self.config = Some(config);
        self.frames.clear();
        self.bytes = 0;
        self.needs_keyframe = true;
        self.lost_frames = false;
        self.waiting_since = Some(Instant::now());
    }

    fn queue(&mut self, frame: Frame, keyframe: bool) {
        if self.needs_keyframe && !keyframe {
            return;
        }
        let full = self.frames.len() == 72 || self.bytes + frame.data.len() > 16 * 1024 * 1024;
        if self.lost_frames || full {
            // Keep the startup GOP until it can be decoded. After overflow, only
            // resume at a sync frame so dependent frames never cross the gap.
            if !keyframe {
                self.lost_frames = true;
                return;
            }
            self.frames.clear();
            self.bytes = 0;
            self.revision = self.revision.wrapping_add(1);
            if let Some(config) = &mut self.config {
                config.revision = self.revision;
            }
            self.lost_frames = false;
        }
        self.needs_keyframe = false;
        self.bytes += frame.data.len();
        self.frames.push_back(frame);
    }
}

impl VideoHandler for Output {
    fn video_init(&self) -> Box<dyn VideoSession> {
        let mut streams = self.streams.lock().unwrap();
        let session = streams.video.session.wrapping_add(1);
        let revision = streams.video.revision.wrapping_add(1);
        streams.video = Video {
            session,
            revision,
            phase: 1,
            waiting_since: Some(Instant::now()),
            ..Video::default()
        };
        Box::new(Mirror {
            streams: self.streams.clone(),
            session,
            received_frames: 0,
            first_stamp: 0,
            last_stamp: 0,
        })
    }
}

struct Mirror {
    streams: Arc<Mutex<Streams>>,
    session: u32,
    received_frames: u64,
    first_stamp: u64,
    last_stamp: u64,
}

impl VideoSession for Mirror {
    fn on_video(&mut self, packet: VideoPacket) {
        const MAX_ACCESS_UNIT: usize = 4 * 1024 * 1024;
        if packet.payload.len() > MAX_ACCESS_UNIT {
            return;
        }

        match packet.kind {
            PacketKind::AvcC | PacketKind::HvcC => {
                let hevc = packet.kind == PacketKind::HvcC;
                if let Some(config) = decoder_config(&packet.payload, hevc) {
                    let mut streams = self.streams.lock().unwrap();
                    let video = &mut streams.video;
                    if video.session == self.session && video.phase != 3 {
                        video.configure(config);
                    }
                } else {
                    super::log_message(&format!(
                        "Invalid AirPlay codec configuration ({} bytes)",
                        packet.payload.len()
                    ));
                }
            }
            PacketKind::Payload => {
                if self.received_frames == 0 {
                    self.first_stamp = packet.timestamp;
                }
                self.received_frames += 1;
                self.last_stamp = packet.timestamp;
                #[cfg(target_os = "android")]
                let received = Instant::now();
                let (revision, length_size, hevc) = {
                    let streams = self.streams.lock().unwrap();
                    let video = &streams.video;
                    if video.session != self.session || video.phase == 3 {
                        return;
                    }
                    let Some(config) = &video.config else { return };
                    (video.revision, config.length_size, config.hevc)
                };
                let Some((data, keyframe)) = access_unit(&packet.payload, length_size, hevc) else {
                    return;
                };
                let mut streams = self.streams.lock().unwrap();
                let video = &mut streams.video;
                if video.session == self.session && video.revision == revision && video.phase != 3 {
                    video.queue(
                        Frame {
                            data,
                            #[cfg(target_os = "android")]
                            timestamp: packet.timestamp,
                            #[cfg(target_os = "android")]
                            received,
                        },
                        keyframe,
                    );
                }
            }
            _ => {}
        }
    }

    fn on_video_end(&mut self) {
        let mut streams = self.streams.lock().unwrap();
        if streams.video.session == self.session && streams.video.phase != 3 {
            streams.video = Video {
                session: self.session,
                revision: streams.video.revision,
                ..Video::default()
            };
        }
    }
}

impl Drop for Mirror {
    fn drop(&mut self) {
        self.on_video_end();
        let elapsed = self.last_stamp.saturating_sub(self.first_stamp);
        if elapsed != 0 {
            let fps = self.received_frames.saturating_sub(1) as f64 * (1u64 << 32) as f64
                / elapsed as f64;
            super::log_message(&format!(
                "AirPlay source: {} frames, {:.1} fps",
                self.received_frames, fps
            ));
        }
    }
}

fn read_byte(data: &mut &[u8]) -> Option<u8> {
    let (value, rest) = data.split_first()?;
    *data = rest;
    Some(*value)
}

fn append_nal(out: &mut Vec<u8>, data: &mut &[u8], length_size: usize) -> Option<u8> {
    let (length, rest) = data.split_at_checked(length_size)?;
    let length = length
        .iter()
        .fold(0usize, |acc, &b| (acc << 8) | usize::from(b));
    let (nal, tail) = rest.split_at_checked(length)?;
    let header = *nal.first()?;
    out.extend_from_slice(&[0, 0, 0, 1]);
    out.extend_from_slice(nal);
    *data = tail;
    Some(header)
}

fn append_group(out: &mut Vec<u8>, data: &mut &[u8], count: usize) -> Option<()> {
    for _ in 0..count {
        append_nal(out, data, 2)?;
    }
    Some(())
}

fn hevc_config(data: &[u8]) -> Option<&[u8]> {
    // AirPlay sends a VisualSampleEntry with the HEVC config in its hvcC child.
    let mut boxes = data.get(86..)?;
    while !boxes.is_empty() {
        let size = u32::from_be_bytes(boxes.get(..4)?.try_into().ok()?) as usize;
        if size < 8 {
            return None;
        }
        let (item, rest) = boxes.split_at_checked(size)?;
        if &item[4..8] == b"hvcC" {
            return item.get(8..);
        }
        boxes = rest;
    }
    None
}

fn decoder_config(data: &[u8], hevc: bool) -> Option<VideoConfig> {
    let mut first = Vec::new();
    let length_size;
    let mut dimensions = None;
    let csd = if hevc {
        let record = if data.get(4..8) == Some(b"hvc1") {
            let width = i32::from(u16::from_be_bytes(data.get(32..34)?.try_into().ok()?));
            let height = i32::from(u16::from_be_bytes(data.get(34..36)?.try_into().ok()?));
            if width > 0 && height > 0 {
                dimensions = Some((width, height));
            }
            hevc_config(data)?
        } else {
            data
        };
        length_size = usize::from(*record.get(21)? & 3) + 1;
        let arrays = *record.get(22)?;
        let mut rest = record.get(23..)?;
        for _ in 0..arrays {
            read_byte(&mut rest)?;
            let count = u16::from_be_bytes([read_byte(&mut rest)?, read_byte(&mut rest)?]);
            append_group(&mut first, &mut rest, usize::from(count))?;
        }
        vec![first]
    } else {
        if data.first() != Some(&1) {
            return None;
        }
        length_size = usize::from(*data.get(4)? & 3) + 1;
        let sps = *data.get(5)? & 31;
        let mut rest = data.get(6..)?;
        let first_sps_len = usize::from(u16::from_be_bytes(rest.get(..2)?.try_into().ok()?));
        dimensions = super::avc::dimensions(rest.get(2..2 + first_sps_len)?);
        append_group(&mut first, &mut rest, usize::from(sps))?;
        let pps = read_byte(&mut rest)?;
        let mut second = Vec::new();
        append_group(&mut second, &mut rest, usize::from(pps))?;
        vec![first, second]
    };
    csd.iter()
        .all(|part| !part.is_empty())
        .then_some(VideoConfig {
            revision: 0,
            hevc,
            dimensions,
            csd,
            length_size,
        })
}

fn access_unit(mut data: &[u8], length_size: usize, hevc: bool) -> Option<(Vec<u8>, bool)> {
    let mut out = Vec::with_capacity(data.len());
    let mut keyframe = false;
    while !data.is_empty() {
        let header = append_nal(&mut out, &mut data, length_size)?;
        keyframe |= if hevc {
            (16..=23).contains(&((header >> 1) & 63))
        } else {
            header & 31 == 5
        };
    }
    (!out.is_empty()).then_some((out, keyframe))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_avc_config_and_keyframe() {
        let config = [1, 100, 0, 40, 255, 225, 0, 2, 0x67, 1, 1, 0, 2, 0x68, 2];
        let config = decoder_config(&config, false).unwrap();
        assert_eq!(config.length_size, 4);
        assert_eq!(
            config.csd,
            vec![vec![0, 0, 0, 1, 0x67, 1], vec![0, 0, 0, 1, 0x68, 2]]
        );
        assert_eq!(
            access_unit(&[0, 0, 0, 2, 0x65, 3], 4, false),
            Some((vec![0, 0, 0, 1, 0x65, 3], true))
        );
        assert!(access_unit(&[0, 0, 0, 2, 0x65], 4, false).is_none());
    }

    #[test]
    fn startup_queue_keeps_initial_sync_frame() {
        let mut video = Video {
            needs_keyframe: true,
            ..Video::default()
        };
        let frame = |value| Frame {
            data: vec![value],
            #[cfg(target_os = "android")]
            timestamp: 0,
            #[cfg(target_os = "android")]
            received: Instant::now(),
        };
        video.queue(frame(0), false);
        video.queue(frame(1), true);
        for _ in 0..90 {
            video.queue(frame(2), false);
        }
        assert_eq!(video.pop(0).map(|frame| frame.data), Some(vec![1]));
        assert_eq!(video.pop(0).map(|frame| frame.data), Some(vec![2]));
        video.queue(frame(3), true);
        assert_eq!(video.pop(0).map(|frame| frame.data), None);
        assert_eq!(video.pop(1).map(|frame| frame.data), Some(vec![3]));
    }

    #[test]
    fn replaced_surface_waits_for_a_fresh_sync_frame() {
        let mut video = Video {
            phase: 2,
            ..Video::default()
        };
        let frame = |value| Frame {
            data: vec![value],
            #[cfg(target_os = "android")]
            timestamp: 0,
            #[cfg(target_os = "android")]
            received: Instant::now(),
        };
        video.queue(frame(1), false);
        video.wait_for_sync(0);
        video.queue(frame(2), false);
        assert!(video.pop(0).is_none());
        assert!(video.waiting_since.is_some());
        video.queue(frame(3), true);
        assert_eq!(video.pop(0).map(|frame| frame.data), Some(vec![3]));
    }
}
