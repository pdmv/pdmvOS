//! Reference viewport sizes and layout geometry in design pixels (1920x1080).

pub const WIDTH: f32 = 1920.0;
pub const HEIGHT: f32 = 1080.0;
pub const POPUP_PADDING: f32 = 20.0;
pub const POPUP_ROW_HEIGHT: f32 = 60.0;
pub const POPUP_GAP: f32 = POPUP_PADDING;
pub const POPUP_ROW_RADIUS: f32 = POPUP_ROW_HEIGHT / 2.0;
pub const POPUP_TEXT_PADDING: f32 = 20.0;
pub const POPUP_TITLE_INSET: f32 = POPUP_PADDING + 10.0;
pub const POPUP_RADIUS: f32 = POPUP_ROW_RADIUS + POPUP_PADDING;
pub const POPUP_HEADER: f32 = 84.0;
pub const POPUP_PITCH: f32 = POPUP_ROW_HEIGHT + POPUP_GAP;
pub const PREVIEW_PADDING: f32 = 10.0;
pub const PREVIEW_INNER_RADIUS: f32 = 32.0;
pub const PREVIEW_OUTER_RADIUS: f32 = PREVIEW_INNER_RADIUS + PREVIEW_PADDING;

pub const COLUMNS: i32 = 6;
pub const VISIBLE_ROWS: i32 = 3;
pub const PITCH: f32 = 284.0;
pub const TILE_MAX_WIDTH: f32 = 252.0;
pub const TILE_MAX_HEIGHT: f32 = 142.0;
pub const TILE_IDLE_WIDTH: f32 = 228.0;
pub const TILE_IDLE_HEIGHT: f32 = 128.0;
pub const TILE_RADIUS: f32 = 59.0;
pub const FOCUS_GAIN: f32 = 0.05;
pub const DOCK_PADDING: f32 = 24.0;
pub const DOCK_RADIUS: f32 = TILE_RADIUS + DOCK_PADDING;
pub const DOCK_HEIGHT: f32 = TILE_MAX_HEIGHT + 2.0 * DOCK_PADDING;
pub const DOCK_WIDTH: f32 = TILE_MAX_WIDTH + ((COLUMNS - 1) as f32) * PITCH + 2.0 * DOCK_PADDING;
pub const DOCK_LEFT: f32 = (WIDTH - DOCK_WIDTH) / 2.0;
pub const ROW_GAP: f32 =
    (HEIGHT - ((VISIBLE_ROWS + 1) as f32) * TILE_IDLE_HEIGHT) / ((VISIBLE_ROWS + 2) as f32);
pub const DOCK_APPS_TOP: f32 =
    ROW_GAP - (TILE_MAX_HEIGHT - TILE_IDLE_HEIGHT) / 2.0 - DOCK_PADDING;
pub const ROW_PITCH: f32 = TILE_IDLE_HEIGHT + ROW_GAP;
pub const TILE_NODE_HEIGHT: i32 = 212;
pub const LABEL_BASELINE: f32 = 179.0;
pub const APP_LABEL_SIZE: f32 = 25.0;
pub const HEADER_TOP: f32 = 53.0;
pub const HEADER_HEIGHT: f32 = 70.0;
pub const MENU_WIDTH: f32 = 440.0;
pub const DIALOG_WIDTH: f32 = 480.0;
pub const DIALOG_LINE_HEIGHT: f32 = 31.0;
pub const DIALOG_BODY_TOP: f32 = 84.0;
pub const DIALOG_PROGRESS_HEIGHT: f32 = 36.0;
pub const DOCK_HOME_TOP: f32 = HEIGHT - HEADER_TOP - DOCK_HEIGHT;
pub const GRID_TOP: f32 = DOCK_APPS_TOP + DOCK_PADDING + ROW_PITCH;
pub const GRID_CLIP_TOP: f32 = GRID_TOP - 20.0;
pub const GRID_CLIP_BOTTOM: f32 = HEIGHT;

#[inline]
pub fn clamp(value: f32, low: f32, high: f32) -> f32 {
    value.max(low).min(high)
}

#[inline]
pub fn popup_item_scale(focus: f32, press: f32) -> f32 {
    let selected = clamp(focus, 0.0, 1.0);
    1.0 - (0.12 * (1.0 - selected) + 0.07 * clamp(press, 0.0, 1.0) * selected) / 1.06
}

#[inline]
pub fn popup_height(count: i32) -> f32 {
    POPUP_HEADER + ((1.max(count) - 1) as f32) * POPUP_PITCH + POPUP_ROW_HEIGHT + POPUP_PADDING
}

#[inline]
pub fn below_dock(column: i32, first_visible_row: i32, item_count: i32) -> i32 {
    if item_count <= 0 {
        return -1;
    }
    let row = 0.max(first_visible_row.min((item_count - 1) / COLUMNS));
    (item_count - 1).min(row * COLUMNS + 0.max((COLUMNS - 1).min(column)))
}

#[inline]
pub fn center_x(col: i32) -> Result<f32, &'static str> {
    if !(0..COLUMNS).contains(&col) {
        return Err("col out of bounds");
    }
    Ok(DOCK_LEFT + DOCK_PADDING + TILE_MAX_WIDTH / 2.0 + (col as f32) * PITCH)
}

#[inline]
pub fn row_top(row: i32, scroll_rows: f32) -> f32 {
    GRID_TOP + ((row as f32) - scroll_rows) * ROW_PITCH
}

#[inline]
pub fn grid_transition_offset(drawer_progress: f32) -> f32 {
    (HEIGHT - GRID_TOP) * (1.0 - drawer_progress)
}

#[inline]
pub fn drawer_grid_clip_top(drawer_progress: f32) -> f32 {
    dock_top(drawer_progress) + DOCK_PADDING + TILE_MAX_HEIGHT + 12.0
}

#[inline]
pub fn grid_clip_bottom(_drawer_progress: f32) -> f32 {
    HEIGHT
}

#[inline]
pub fn dock_top(drawer_progress: f32) -> f32 {
    DOCK_HOME_TOP + (DOCK_APPS_TOP - DOCK_HOME_TOP) * drawer_progress
}

#[inline]
pub fn menu_rect(
    item_count: i32,
    source_header: i32,
    near_tile: bool,
    anchor_x: f32,
    anchor_y: f32,
    out: &mut [f32; 4],
) {
    let h = popup_height(item_count);
    if source_header >= 0 {
        let right = DOCK_LEFT + DOCK_WIDTH;
        let left = clamp(right - MENU_WIDTH, 32.0, WIDTH - 32.0 - MENU_WIDTH);
        let top = HEADER_TOP + HEADER_HEIGHT + 14.0;
        out[0] = left;
        out[1] = top;
        out[2] = left + MENU_WIDTH;
        out[3] = top + h;
        return;
    }
    if !near_tile {
        out[0] = (WIDTH - MENU_WIDTH) * 0.5;
        out[1] = (HEIGHT - h) * 0.5;
        out[2] = (WIDTH + MENU_WIDTH) * 0.5;
        out[3] = (HEIGHT + h) * 0.5;
        return;
    }
    let left = clamp(anchor_x - MENU_WIDTH * 0.5, 32.0, WIDTH - 32.0 - MENU_WIDTH);
    let above = anchor_y - TILE_MAX_HEIGHT * 0.5 - 14.0 - h;
    let below = anchor_y + TILE_MAX_HEIGHT * 0.5 + 14.0;
    let top = if above >= 48.0 {
        above
    } else if below + h <= 1032.0 {
        below
    } else {
        clamp(anchor_y - h * 0.5, 48.0, 1032.0 - h)
    };
    out[0] = left;
    out[1] = top;
    out[2] = left + MENU_WIDTH;
    out[3] = top + h;
}

#[inline]
pub fn dialog_rect(line_count: i32, has_progress: bool, out: &mut [f32; 4]) {
    let h = DIALOG_BODY_TOP
        + (line_count.max(1) as f32) * DIALOG_LINE_HEIGHT
        + POPUP_GAP
        + POPUP_ROW_HEIGHT
        + POPUP_PADDING
        + if has_progress { DIALOG_PROGRESS_HEIGHT } else { 0.0 };
    out[0] = 960.0 - DIALOG_WIDTH * 0.5;
    out[1] = 540.0 - h * 0.5;
    out[2] = 960.0 + DIALOG_WIDTH * 0.5;
    out[3] = 540.0 + h * 0.5;
}

#[inline]
pub fn dialog_button_rect(
    panel_left: f32,
    panel_right: f32,
    panel_bottom: f32,
    two_buttons: bool,
    index: i32,
    out: &mut [f32; 4],
) {
    let gap = POPUP_GAP;
    let mut width = (panel_right - panel_left) - POPUP_PADDING * 2.0;
    if two_buttons {
        width = (width - gap) * 0.5;
    }
    let x = panel_left + POPUP_PADDING + (index as f32) * (width + gap);
    let bottom = panel_bottom - POPUP_PADDING;
    out[0] = x;
    out[1] = bottom - POPUP_ROW_HEIGHT;
    out[2] = x + width;
    out[3] = bottom;
}

pub fn check_invariants() -> Result<(), &'static str> {
    if DOCK_RADIUS != TILE_RADIUS + DOCK_PADDING {
        return Err("radii");
    }
    if POPUP_RADIUS != POPUP_ROW_RADIUS + POPUP_PADDING {
        return Err("popup radius");
    }
    if PREVIEW_OUTER_RADIUS != PREVIEW_INNER_RADIUS + PREVIEW_PADDING {
        return Err("preview radius");
    }
    if DOCK_LEFT < 0.0 || DOCK_LEFT + DOCK_WIDTH > WIDTH {
        return Err("dock offscreen");
    }
    if TILE_MAX_WIDTH * (1.0 + FOCUS_GAIN) >= PITCH {
        return Err("focus overlaps neighboring cell");
    }
    if TILE_RADIUS <= 0.0 || TILE_RADIUS >= TILE_IDLE_HEIGHT / 2.0 {
        return Err("tile corner radius exceeds half idle height");
    }
    if DOCK_RADIUS >= DOCK_HEIGHT / 2.0 {
        return Err("dock should remain a softened rectangle, not a full capsule");
    }
    if DOCK_APPS_TOP < 32.0 || DOCK_HOME_TOP + DOCK_HEIGHT > HEIGHT - 48.0 {
        return Err("vertical safe zone");
    }
    if row_top(VISIBLE_ROWS - 1, 0.0) + TILE_MAX_HEIGHT + 33.0 > GRID_CLIP_BOTTOM {
        return Err("grid offscreen");
    }
    for i in 0..COLUMNS {
        let x = center_x(i)?;
        if x - TILE_MAX_WIDTH / 2.0 < DOCK_LEFT + DOCK_PADDING - 0.1 {
            return Err("tile left");
        }
        if x + TILE_MAX_WIDTH / 2.0 > DOCK_LEFT + DOCK_WIDTH - DOCK_PADDING + 0.1 {
            return Err("tile right");
        }
    }
    Ok(())
}

/// Computes the 133 `(x, y)` vertex pairs (`266` floats) of a 32-step-per-quadrant
/// continuous-corner superellipse bounded by `(left, top, right, bottom)` with
/// corner radius `radius` and superellipse degree `n` (`exponent = 2.0 / n`).
pub fn squircle_vertices(
    left: f32,
    top: f32,
    right: f32,
    bottom: f32,
    radius: f32,
    n: f32,
    out: &mut [f32; 266],
) {
    let deg = n.max(0.1) as f64;
    let exponent = 2.0f64 / deg;
    let warp = (deg * 0.5).max(1.0);
    let mut sin_pow = [0.0f32; 33];
    let mut cos_pow = [0.0f32; 33];
    for i in 1..=32 {
        let t = (i as f64) / 32.0;
        let a = t.powf(warp);
        let b = (1.0 - t).powf(warp);
        let angle = core::f64::consts::FRAC_PI_2 * (a / (a + b));
        let (s, c) = angle.sin_cos();
        sin_pow[i] = s.powf(exponent) as f32;
        cos_pow[i] = c.powf(exponent) as f32;
    }

    let mut idx = 0usize;
    let mut push = |x: f32, y: f32| {
        out[idx] = x;
        out[idx + 1] = y;
        idx += 2;
    };

    push(left + radius, top);
    push(right - radius, top);
    for i in 1..=32 {
        push(
            right - radius + radius * sin_pow[i],
            top + radius - radius * cos_pow[i],
        );
    }
    push(right, bottom - radius);
    for i in 1..=32 {
        push(
            right - radius + radius * cos_pow[i],
            bottom - radius + radius * sin_pow[i],
        );
    }
    push(left + radius, bottom);
    for i in 1..=32 {
        push(
            left + radius - radius * sin_pow[i],
            bottom - radius + radius * cos_pow[i],
        );
    }
    push(left, top + radius);
    for i in 1..=32 {
        push(
            left + radius - radius * cos_pow[i],
            top + radius - radius * sin_pow[i],
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn geometry_invariants() {
        check_invariants().unwrap();
        assert_eq!(below_dock(0, 0, 0), -1);
        assert_eq!(popup_item_scale(1.0, 0.0), 1.0);
        assert!(popup_item_scale(0.0, 0.0) < popup_item_scale(1.0, 1.0));
    }

    #[test]
    fn squircle_vertex_generation() {
        let mut verts = [0.0f32; 266];
        squircle_vertices(10.0, 20.0, 262.0, 162.0, 59.0, 3.05, &mut verts);
        assert!((verts[0] - 69.0).abs() < 1e-4 && (verts[1] - 20.0).abs() < 1e-4);
        assert!((verts[2] - 203.0).abs() < 1e-4 && (verts[3] - 20.0).abs() < 1e-4);
        for pair in verts.chunks_exact(2) {
            assert!(pair[0] >= 9.999 && pair[0] <= 262.001);
            assert!(pair[1] >= 19.999 && pair[1] <= 162.001);
        }
    }
}
