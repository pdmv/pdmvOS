//! Artwork detection, aspect-preserving layout, and corner-preserving transform geometry.

#[inline]
pub fn contrast(a: i32, b: i32) -> i32 {
    let au = a as u32;
    let bu = b as u32;
    let r = (((au >> 16) & 255) as i32 - ((bu >> 16) & 255) as i32).abs();
    let g = (((au >> 8) & 255) as i32 - ((bu >> 8) & 255) as i32).abs();
    let bl = ((au & 255) as i32 - (bu & 255) as i32).abs();
    r + g + bl
}

#[inline]
pub fn fallback_text_color(background_argb: i32) -> i32 {
    let bg = background_argb as u32;
    let luminance = 0.2126f32 * (((bg >> 16) & 255) as f32)
        + 0.7152f32 * (((bg >> 8) & 255) as f32)
        + 0.0722f32 * ((bg & 255) as f32);
    if luminance >= 150.0f32 {
        0xff16_161bu32 as i32
    } else {
        0xfff8_f8fau32 as i32
    }
}

/// Computes `(left, right, top, bottom, step)` for central logo detection.
#[inline]
pub fn sample_bounds(width: i32, height: i32) -> Option<(i32, i32, i32, i32, i32)> {
    if width < 32 || height < 32 {
        return None;
    }
    let left = width / 5;
    let right = width * 4 / 5;
    let top = height / 5;
    let bottom = height * 4 / 5;
    let step = 5.max(width.min(height) / 40);
    Some((left, right, top, bottom, step))
}

pub fn has_detail<F: Fn(i32, i32) -> i32>(width: i32, height: i32, source: F) -> bool {
    let Some((left, right, top, bottom, step)) = sample_bounds(width, height) else {
        return false;
    };
    let mut edges = 0;
    let mut visited = 0;
    let mut y = top;
    while y + step < bottom {
        let mut x = left;
        while x + step < right {
            let here = source(x, y);
            if contrast(here, source(x + step, y)) > 88
                || contrast(here, source(x, y + step)) > 88
            {
                edges += 1;
            }
            visited += 1;
            x += step;
        }
        y += step;
    }
    edges >= 3.max(visited / 100)
}

/// Evaluates flattened `(here, right, down)` triplets collected along the sample grid.
pub fn has_detail_triplets(triplets: &[i32]) -> bool {
    let visited = triplets.len() / 3;
    if visited == 0 {
        return false;
    }
    let mut edges = 0usize;
    for chunk in triplets.chunks_exact(3) {
        let here = chunk[0];
        if contrast(here, chunk[1]) > 88 || contrast(here, chunk[2]) > 88 {
            edges += 1;
        }
    }
    edges >= 3.max(visited / 100)
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Bounds {
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
}

impl Bounds {
    #[inline]
    pub fn width(self) -> f32 {
        self.right - self.left
    }
    #[inline]
    pub fn height(self) -> f32 {
        self.bottom - self.top
    }
}

#[inline]
pub fn center_crop_rect(
    src_w: i32,
    src_h: i32,
    dst_w: f32,
    dst_h: f32,
    out: &mut [i32; 4],
) {
    let w = (src_w.max(1)) as f32;
    let h = (src_h.max(1)) as f32;
    let ratio = (dst_w / w).max(dst_h / h);
    let crop_w = dst_w / ratio;
    let crop_h = dst_h / ratio;
    out[0] = ((w - crop_w) * 0.5) as i32;
    out[1] = ((h - crop_h) * 0.5) as i32;
    out[2] = ((w + crop_w) * 0.5) as i32;
    out[3] = ((h + crop_h) * 0.5) as i32;
}

pub fn fit_layout(
    source_width: i32,
    source_height: i32,
    box_width: f32,
    box_height: f32,
    banner: bool,
) -> Result<Bounds, &'static str> {
    if box_width <= 0.0 || box_height <= 0.0 {
        return Err("invalid canvas size");
    }
    let sw = if source_width <= 0 {
        128.0
    } else {
        source_width as f32
    };
    let sh = if source_height <= 0 {
        128.0
    } else {
        source_height as f32
    };
    let max_width = if banner { box_width } else { box_height * 0.82 };
    let max_height = if banner { box_height } else { box_height * 0.82 };
    let scale = (max_width / sw).min(max_height / sh);
    let w = sw * scale;
    let h = sh * scale;
    let left = (box_width - w) * 0.5;
    let top = (box_height - h) * 0.5;
    Ok(Bounds {
        left,
        top,
        right: left + w,
        bottom: top + h,
    })
}

pub fn transform_scale(destination_size: f32, source_size: f32) -> Result<f32, &'static str> {
    if destination_size <= 0.0 || source_size <= 0.0 {
        return Err("Artwork dimensions must be positive");
    }
    Ok(destination_size / source_size)
}

pub fn transform_source_radius(
    design_radius: f32,
    source_width: f32,
    destination_width: f32,
) -> Result<f32, &'static str> {
    Ok(design_radius / transform_scale(destination_width, source_width)?)
}

/// Computes the average opaque (`alpha > 128`) RGB fill color from sampled pixels,
/// returning `fallback` if no sample exceeds the alpha threshold.
#[inline]
pub fn dominant_fill_color(samples: &[i32], fallback: i32) -> i32 {
    let mut r: u64 = 0;
    let mut g: u64 = 0;
    let mut b: u64 = 0;
    let mut n: u64 = 0;
    for &pixel in samples {
        let c = pixel as u32;
        if (c >> 24) > 128 {
            r += ((c >> 16) & 255) as u64;
            g += ((c >> 8) & 255) as u64;
            b += (c & 255) as u64;
            n += 1;
        }
    }
    if n == 0 {
        fallback
    } else {
        let rr = (r / n) as u32;
        let gg = (g / n) as u32;
        let bb = (b / n) as u32;
        (0xff00_0000u32 | (rr << 16) | (gg << 8) | bb) as i32
    }
}

/// Computes the adaptive header control focus tint from a 5-point sample cross.
#[inline]
pub fn control_focus_tint(samples: &[i32]) -> i32 {
    if samples.is_empty() {
        return 0xff8c_9198u32 as i32;
    }
    let mut red: i32 = 0;
    let mut green: i32 = 0;
    let mut blue: i32 = 0;
    for &pixel in samples {
        let c = pixel as u32;
        red += ((c >> 16) & 255) as i32;
        green += ((c >> 8) & 255) as i32;
        blue += (c & 255) as i32;
    }
    let count = samples.len() as f32;
    let rr = 224.min(((red as f32) / count * 0.48 + 228.0 * 0.52).round() as i32).max(0) as u32;
    let gg = 224.min(((green as f32) / count * 0.48 + 228.0 * 0.52).round() as i32).max(0) as u32;
    let bb = 224.min(((blue as f32) / count * 0.48 + 228.0 * 0.52).round() as i32).max(0) as u32;
    (0xff00_0000u32 | (rr << 16) | (gg << 8) | bb) as i32
}

/// Applies analytical superellipse Signed Distance Field (SDF) anti-aliasing to a masked
/// tile bitmap (`(width + 2 * gutter) x (height + 2 * gutter)`) using the unmasked `source`
/// (`width x height`) RGB pixels so that downscaling from 2x texture space (`504x284`) to
/// idle/focused screen sizes (`228x128` .. `265x149`) produces a uniform, vernier-free
/// ~1.35-screen-pixel anti-aliased corner transition.
pub fn smooth_squircle_corners(
    source: &[i32],
    masked: &mut [i32],
    width: i32,
    height: i32,
    gutter: i32,
    radius: f32,
    n: f32,
) {
    if width <= 0 || height <= 0 || gutter < 0 || radius <= 0.0 {
        return;
    }
    let mw = width + 2 * gutter;
    let mh = height + 2 * gutter;
    if source.len() < (width * height) as usize || masked.len() < (mw * mh) as usize {
        return;
    }
    let w = width as f32;
    let h = height as f32;
    let half_w = w * 0.5;
    let half_h = h * 0.5;
    let r = radius.min(half_w).min(half_h);
    let inner_w = half_w - r;
    let inner_h = half_h - r;
    let deg = n.max(2.0);
    let inv_deg = 1.0 / deg;
    let deg_minus_1 = deg - 1.0;

    let d_in = -1.65f32;
    let d_out = 1.15f32;
    let inv_span = 1.0 / (d_out - d_in);

    for my in 0..mh {
        let y = ((my - gutter) as f32) + 0.5;
        let py = (y - half_h).abs();
        let sy = (my - gutter).max(0).min(height - 1) as usize;
        let row_src = sy * (width as usize);
        let row_dst = (my as usize) * (mw as usize);
        for mx in 0..mw {
            let x = ((mx - gutter) as f32) + 0.5;
            let px = (x - half_w).abs();
            if px <= inner_w && py <= inner_h {
                continue;
            }
            let d = if px <= inner_w || py <= inner_h {
                (px - half_w).max(py - half_h)
            } else {
                let dx = (px - inner_w) / r;
                let dy = (py - inner_h) / r;
                let max_d = dx.max(dy);
                if max_d < 0.65 || max_d > 1.04 {
                    if max_d > 1.04 {
                        masked[row_dst + (mx as usize)] = 0;
                    }
                    continue;
                }
                let dx_n = dx.powf(deg);
                let dy_n = dy.powf(deg);
                let sum = dx_n + dy_n;
                let rho = sum.powf(inv_deg);
                let gx = dx.powf(deg_minus_1);
                let gy = dy.powf(deg_minus_1);
                let grad = (rho / sum.max(1e-12)) * (gx * gx + gy * gy).sqrt();
                r * (rho - 1.0) / grad.max(1e-6)
            };
            if d <= d_in {
                let sx = (mx - gutter).max(0).min(width - 1) as usize;
                masked[row_dst + (mx as usize)] = source[row_src + sx] | (0xff00_0000u32 as i32);
            } else if d >= d_out {
                masked[row_dst + (mx as usize)] = 0;
            } else {
                let t = ((d_out - d) * inv_span).max(0.0).min(1.0);
                let cov = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
                let alpha = (cov * 255.0).round() as u32;
                if alpha == 0 {
                    masked[row_dst + (mx as usize)] = 0;
                } else {
                    let sx = (mx - gutter).max(0).min(width - 1) as usize;
                    let rgb = (source[row_src + sx] as u32) & 0x00ff_ffff;
                    masked[row_dst + (mx as usize)] = ((alpha << 24) | rgb) as i32;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detector_checks() {
        let (w, h) = (576, 324);
        assert!(!has_detail(w, h, |_, _| 0xffe8_e8e8u32 as i32));
        assert!(!has_detail(w, h, |_, _| 0xffd2_1422u32 as i32));
        assert!(!has_detail(w, h, |x, _| {
            let v = (50 + (x * 150 / w)) as u32;
            (0xff00_0000 | (v << 16) | (v << 8) | v) as i32
        }));
        assert!(has_detail(w, h, |x, y| {
            let letter = x > 190 && x < 390 && y > 115 && y < 205;
            if letter {
                0xffff_ffffu32 as i32
            } else {
                0xff17_2536u32 as i32
            }
        }));
        assert!(has_detail(w, h, |x, y| {
            let wordmark = x > 180 && x < 400 && y > 148 && y < 172;
            if wordmark {
                0xff10_1010u32 as i32
            } else {
                0xffee_eeeeu32 as i32
            }
        }));
        assert_eq!(
            fallback_text_color(0xffff_ffffu32 as i32),
            0xff16_161bu32 as i32
        );
        assert_eq!(
            fallback_text_color(0xff10_1010u32 as i32),
            0xfff8_f8fau32 as i32
        );
    }

    #[test]
    fn layout_checks() {
        for (w, h, banner) in [
            (1920, 1080, true),
            (480, 270, true),
            (1200, 200, true),
            (320, 320, false),
            (96, 128, false),
            (300, 800, true),
        ] {
            let b = fit_layout(w, h, 576.0, 324.0, banner).unwrap();
            assert!(
                b.left >= -0.005 && b.top >= -0.005 && b.right <= 576.005 && b.bottom <= 324.005
            );
            let expected = w as f32 / h as f32;
            let actual = b.width() / b.height();
            assert!((actual / expected - 1.0).abs() < 0.0001);
        }
        let wide = fit_layout(1200, 200, 576.0, 324.0, true).unwrap();
        assert!(wide.width() >= 575.9 && wide.height() < 120.0);
        let square = fit_layout(512, 512, 576.0, 324.0, false).unwrap();
        assert!(square.height() < 300.0);
    }

    #[test]
    fn transform_checks() {
        let (source_width, source_height, corner) = (768.0f32, 432.0f32, 59.0f32);
        for [dw, dh] in [[228.0f32, 128.0], [236.0, 133.0], [252.0, 142.0], [246.0, 139.0]] {
            let sx = transform_scale(dw, source_width).unwrap();
            let sy = transform_scale(dh, source_height).unwrap();
            let sr = transform_source_radius(corner, source_width, dw).unwrap();
            assert!((sx * source_width - dw).abs() <= 0.005);
            assert!((sy * source_height - dh).abs() <= 0.005);
            assert!((sr * sx - corner).abs() <= 0.005);
            assert!((sx - sy).abs() <= 0.003);
        }
        let (w, h, g) = (504, 284, 2);
        let src = vec![0xffff_ffffu32 as i32; (w * h) as usize];
        let mut dst = vec![0xffff_ffffu32 as i32; ((w + 2 * g) * (h + 2 * g)) as usize];
        smooth_squircle_corners(&src, &mut dst, w, h, g, 118.0, 3.05);
        assert_eq!(dst[144 * (w + 2 * g) as usize], 0);
        assert_eq!(dst[0], 0);
        assert_eq!(dst[(144 * (w + 2 * g) + 254) as usize], 0xffff_ffffu32 as i32);
    }
}
