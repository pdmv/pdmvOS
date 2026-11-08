//! Read the displayed size from an H.264 sequence parameter set.

struct Bits {
    bytes: Vec<u8>,
    next: usize,
}

impl Bits {
    fn get(&mut self, count: usize) -> Option<u32> {
        let mut result = 0;
        for _ in 0..count {
            let byte = *self.bytes.get(self.next / 8)?;
            result = (result << 1) | u32::from((byte >> (7 - self.next % 8)) & 1);
            self.next += 1;
        }
        Some(result)
    }

    fn ue(&mut self) -> Option<u32> {
        let mut zeros = 0;
        while self.get(1)? == 0 {
            zeros += 1;
            if zeros > 30 {
                return None;
            }
        }
        Some((1 << zeros) - 1 + self.get(zeros)?)
    }

    fn se(&mut self) -> Option<i32> {
        let value = self.ue()? as i32;
        Some(if value & 1 == 0 {
            -(value / 2)
        } else {
            value / 2 + 1
        })
    }
}

pub(super) fn dimensions(nal: &[u8]) -> Option<(i32, i32)> {
    if *nal.first()? & 31 != 7 || nal.len() > 65536 {
        return None;
    }
    let mut bits = Bits {
        bytes: Vec::with_capacity(nal.len()),
        next: 0,
    };
    let mut zeros = 0;
    for &byte in &nal[1..] {
        if zeros == 2 && byte == 3 {
            zeros = 0;
            continue;
        }
        bits.bytes.push(byte);
        zeros = if byte == 0 { zeros + 1 } else { 0 };
    }
    let profile = bits.get(8)?;
    bits.get(16)?;
    bits.ue()?;
    let mut chroma = 1;
    if matches!(
        profile,
        44 | 83 | 86 | 100 | 110 | 118 | 122 | 128 | 134 | 135 | 138 | 139 | 144 | 244
    ) {
        chroma = bits.ue()?;
        let separate = chroma == 3 && bits.get(1)? != 0;
        if chroma > 3 {
            return None;
        }
        bits.ue()?;
        bits.ue()?;
        bits.get(1)?;
        if bits.get(1)? != 0 {
            for list in 0..if chroma == 3 { 12 } else { 8 } {
                if bits.get(1)? == 0 {
                    continue;
                }
                let mut last = 8;
                let mut next = 8;
                for _ in 0..if list < 6 { 16 } else { 64 } {
                    if next != 0 {
                        next = (last + bits.se()?).rem_euclid(256);
                    }
                    if next != 0 {
                        last = next;
                    }
                }
            }
        }
        if separate {
            chroma = 0;
        }
    }
    bits.ue()?;
    match bits.ue()? {
        0 => {
            bits.ue()?;
        }
        1 => {
            bits.get(1)?;
            bits.se()?;
            bits.se()?;
            let offsets = bits.ue()?;
            if offsets > 255 {
                return None;
            }
            for _ in 0..offsets {
                bits.se()?;
            }
        }
        2 => {}
        _ => return None,
    }
    bits.ue()?;
    bits.get(1)?;
    let width = (u64::from(bits.ue()?) + 1) * 16;
    let height = (u64::from(bits.ue()?) + 1) * 16;
    let frame_only = bits.get(1)? != 0;
    if !frame_only {
        bits.get(1)?;
    }
    bits.get(1)?;
    let mut width = width;
    let mut height = height * if frame_only { 1 } else { 2 };
    if bits.get(1)? != 0 {
        let crop_x = if matches!(chroma, 1 | 2) { 2 } else { 1 };
        let crop_y = if chroma == 1 { 2 } else { 1 } * if frame_only { 1 } else { 2 };
        let left = u64::from(bits.ue()?);
        let right = u64::from(bits.ue()?);
        let top = u64::from(bits.ue()?);
        let bottom = u64::from(bits.ue()?);
        width = width.checked_sub((left + right) * crop_x)?;
        height = height.checked_sub((top + bottom) * crop_y)?;
    }
    ((1..=8192).contains(&width) && (1..=8192).contains(&height))
        .then_some((width as i32, height as i32))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_portrait_size_with_cropped_edges() {
        let sps = [
            0x27, 0x64, 0, 0x20, 0xac, 0x13, 0x14, 0x50, 0x26, 2, 0x27, 0xe5, 0x9b, 0x80, 0x80,
            0x80, 0x81,
        ];
        assert_eq!(dimensions(&sps), Some((608, 1080)));
        assert_eq!(dimensions(&sps[..6]), None);
    }
}
