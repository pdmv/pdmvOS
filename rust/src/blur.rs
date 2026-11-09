//! Three-pass separable box blur with exact integer division shared across pixels.

pub fn apply(pixels: &mut [i32], scratch: &mut [i32], width: usize, height: usize, radius: usize) {
    if width == 0 || height == 0 {
        return;
    }
    let total = width.saturating_mul(height);
    if pixels.len() < total || scratch.len() < total {
        return;
    }
    let diameter = radius * 2 + 1;
    let mut divided = vec![0i32; 256 * diameter];
    for (i, slot) in divided.iter_mut().enumerate() {
        *slot = (i / diameter) as i32;
    }

    let w_i32 = width as isize;
    let h_i32 = height as isize;
    let r_i32 = radius as isize;

    let mut columns = vec![[0_usize; 3]; width];
    for _pass in 0..3 {
        for y in 0..height {
            let mut red: usize = 0;
            let mut green: usize = 0;
            let mut blue: usize = 0;
            let row = y * width;
            for i in -r_i32..=r_i32 {
                let clamped = i.max(0).min(w_i32 - 1) as usize;
                let color = pixels[row + clamped] as u32;
                red += ((color >> 16) & 255) as usize;
                green += ((color >> 8) & 255) as usize;
                blue += (color & 255) as usize;
            }
            for x in 0..width {
                scratch[row + x] = (0xff00_0000u32 as i32)
                    | (divided[red] << 16)
                    | (divided[green] << 8)
                    | divided[blue];
                let before_x = if x > radius { x - radius } else { 0 };
                let next_x = if x + radius + 1 < width {
                    x + radius + 1
                } else {
                    width - 1
                };
                let before = pixels[row + before_x] as u32;
                let next = pixels[row + next_x] as u32;
                red = (red as isize + ((next >> 16) & 255) as isize
                    - ((before >> 16) & 255) as isize) as usize;
                green = (green as isize + ((next >> 8) & 255) as isize
                    - ((before >> 8) & 255) as isize) as usize;
                blue = (blue as isize + (next & 255) as isize - (before & 255) as isize) as usize;
            }
        }
        // Walk complete rows so both source rows and the output stay contiguous.
        columns.fill([0; 3]);
        for i in -r_i32..=r_i32 {
            let row = i.max(0).min(h_i32 - 1) as usize * width;
            for (sum, &color) in columns.iter_mut().zip(&scratch[row..row + width]) {
                sum[0] += ((color >> 16) & 255) as usize;
                sum[1] += ((color >> 8) & 255) as usize;
                sum[2] += (color & 255) as usize;
            }
        }
        for y in 0..height {
            let row = y * width;
            let before = y.saturating_sub(radius) * width;
            let next = (y + radius + 1).min(height - 1) * width;
            let entering = &scratch[next..next + width];
            let leaving = &scratch[before..before + width];
            for (((pixel, sum), &add), &remove) in pixels[row..row + width]
                .iter_mut()
                .zip(&mut columns)
                .zip(entering)
                .zip(leaving)
            {
                *pixel = (0xff00_0000u32 as i32)
                    | (divided[sum[0]] << 16)
                    | (divided[sum[1]] << 8)
                    | divided[sum[2]];
                sum[0] = sum[0] + ((add >> 16) & 255) as usize - ((remove >> 16) & 255) as usize;
                sum[1] = sum[1] + ((add >> 8) & 255) as usize - ((remove >> 8) & 255) as usize;
                sum[2] = sum[2] + (add & 255) as usize - (remove & 255) as usize;
            }
        }
    }
}

/// Linearly interpolates between two opaque `ARGB_8888` pixel buffers (`low` and `high`)
/// using `alpha` in `0..=255` (`0` = `low`, `255` = `high`), writing opaque `ARGB_8888` pixels to `out`.
pub fn blend_opaque(low: &[i32], high: &[i32], out: &mut [i32], alpha: i32) {
    let n = low.len().min(high.len()).min(out.len());
    if n == 0 {
        return;
    }
    let a = alpha.clamp(0, 255) as u32;
    if a == 0 {
        out[..n].copy_from_slice(&low[..n]);
        return;
    }
    if a == 255 {
        out[..n].copy_from_slice(&high[..n]);
        return;
    }
    let inv = 255 - a;
    for i in 0..n {
        let l = low[i] as u32;
        let h = high[i] as u32;
        let r = (((l >> 16) & 255) * inv + ((h >> 16) & 255) * a + 127) / 255;
        let g = (((l >> 8) & 255) * inv + ((h >> 8) & 255) * a + 127) / 255;
        let b = ((l & 255) * inv + (h & 255) * a + 127) / 255;
        out[i] = (0xff00_0000u32 | (r << 16) | (g << 8) | b) as i32;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reference(source: &[i32], w: usize, h: usize, r: usize) -> Vec<i32> {
        let mut a = source.to_vec();
        let mut b = vec![0i32; a.len()];
        let d = (2 * r + 1) as i32;
        let r_i = r as isize;
        let w_i = w as isize;
        let h_i = h as isize;
        for _pass in 0..3 {
            for y in 0..h {
                for x in 0..w {
                    let (mut red, mut green, mut blue) = (0i32, 0i32, 0i32);
                    for i in -r_i..=r_i {
                        let cx = (x as isize + i).max(0).min(w_i - 1) as usize;
                        let c = a[y * w + cx] as u32;
                        red += ((c >> 16) & 255) as i32;
                        green += ((c >> 8) & 255) as i32;
                        blue += (c & 255) as i32;
                    }
                    b[y * w + x] = (0xff00_0000u32 as i32)
                        | ((red / d) << 16)
                        | ((green / d) << 8)
                        | (blue / d);
                }
            }
            for y in 0..h {
                for x in 0..w {
                    let (mut red, mut green, mut blue) = (0i32, 0i32, 0i32);
                    for i in -r_i..=r_i {
                        let cy = (y as isize + i).max(0).min(h_i - 1) as usize;
                        let c = b[cy * w + x] as u32;
                        red += ((c >> 16) & 255) as i32;
                        green += ((c >> 8) & 255) as i32;
                        blue += (c & 255) as i32;
                    }
                    a[y * w + x] = (0xff00_0000u32 as i32)
                        | ((red / d) << 16)
                        | ((green / d) << 8)
                        | (blue / d);
                }
            }
        }
        a
    }

    #[test]
    fn exact_blur_pixels() {
        let mut seed: u64 = 64;
        for w in [1, 2, 7, 32, 65] {
            for h in [1, 3, 19] {
                for r in [0, 1, 3, 6, 12] {
                    let mut a = vec![0i32; w * h];
                    for slot in a.iter_mut() {
                        seed = seed.wrapping_mul(25214903917).wrapping_add(11) & ((1u64 << 48) - 1);
                        *slot = (seed >> 16) as i32;
                    }
                    let expected = reference(&a, w, h, r);
                    let mut scratch = vec![0i32; a.len()];
                    apply(&mut a, &mut scratch, w, h, r);
                    assert_eq!(a, expected, "blur mismatch {}x{} r={}", w, h, r);
                }
            }
        }
    }
}
