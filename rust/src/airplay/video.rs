use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use shairplay::{PacketKind, VideoHandler, VideoPacket, VideoSession};

use super::{Output, Streams};

#[derive(Default)]
pub(super) struct Video {
    pub(super) active: bool,
    pub(super) format: i32,
    pub(super) frames: VecDeque<Vec<u8>>,
    generation: u64,
    config: Vec<u8>,
    length_size: usize,
    needs_keyframe: bool,
}

impl Video {
    pub(super) fn reset(&mut self) {
        self.frames.clear();
        self.needs_keyframe = true;
    }
}

impl VideoHandler for Output {
    fn video_init(&self) -> Box<dyn VideoSession> {
        let mut streams = self.streams.lock().unwrap();
        let generation = streams.video.generation.wrapping_add(1);
        streams.video = Video {
            generation,
            ..Video::default()
        };
        Box::new(Mirror {
            streams: self.streams.clone(),
            generation,
        })
    }
}

struct Mirror {
    streams: Arc<Mutex<Streams>>,
    generation: u64,
}

impl VideoSession for Mirror {
    fn on_video(&mut self, packet: VideoPacket) {
        const MAX_ACCESS_UNIT: usize = 4 * 1024 * 1024;
        if packet.payload.len() > MAX_ACCESS_UNIT {
            return;
        }

        let mut streams = self.streams.lock().unwrap();
        let video = &mut streams.video;
        if video.generation != self.generation {
            return;
        }
        match packet.kind {
            PacketKind::AvcC | PacketKind::HvcC => {
                let hevc = packet.kind == PacketKind::HvcC;
                if let Some((length_size, config)) = decoder_config(&packet.payload, hevc) {
                    video.format = if hevc { 2 } else { 1 };
                    video.length_size = length_size;
                    video.config = config;
                    video.reset();
                    video.active = true;
                }
            }
            PacketKind::Payload if video.active => {
                let Some((mut data, keyframe)) =
                    access_unit(&packet.payload, video.length_size, video.format == 2)
                else {
                    return;
                };
                if video.frames.len() >= 8 {
                    video.reset();
                }
                if keyframe {
                    let mut prefixed = video.config.clone();
                    prefixed.append(&mut data);
                    data = prefixed;
                    video.needs_keyframe = false;
                }
                if !video.needs_keyframe {
                    video.frames.push_back(data);
                }
            }
            _ => {}
        }
    }

    fn on_video_end(&mut self) {
        let mut streams = self.streams.lock().unwrap();
        if streams.video.generation == self.generation {
            streams.video = Video {
                generation: self.generation,
                ..Video::default()
            };
        }
    }
}

impl Drop for Mirror {
    fn drop(&mut self) {
        self.on_video_end();
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

fn decoder_config(data: &[u8], hevc: bool) -> Option<(usize, Vec<u8>)> {
    let mut out = Vec::new();
    let length_size;
    if hevc {
        let data = hevc_config(data)?;
        length_size = usize::from(*data.get(21)? & 3) + 1;
        let arrays = *data.get(22)?;
        let mut rest = data.get(23..)?;
        for _ in 0..arrays {
            read_byte(&mut rest)?;
            let count = u16::from_be_bytes([read_byte(&mut rest)?, read_byte(&mut rest)?]);
            append_group(&mut out, &mut rest, usize::from(count))?;
        }
    } else {
        length_size = usize::from(*data.get(4)? & 3) + 1;
        let sps = *data.get(5)? & 31;
        let mut rest = data.get(6..)?;
        append_group(&mut out, &mut rest, usize::from(sps))?;
        let pps = read_byte(&mut rest)?;
        append_group(&mut out, &mut rest, usize::from(pps))?;
    }
    (!out.is_empty()).then_some((length_size, out))
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
        assert_eq!(
            decoder_config(&config, false),
            Some((4, vec![0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2]))
        );
        assert_eq!(
            access_unit(&[0, 0, 0, 2, 0x65, 3], 4, false),
            Some((vec![0, 0, 0, 1, 0x65, 3], true))
        );
        assert!(access_unit(&[0, 0, 0, 2, 0x65], 4, false).is_none());
    }
}
