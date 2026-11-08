//! Motion profiles, coupled spring chains, rubber boundaries, focus coordination, and surface morphing.

use crate::spring::Spring;

pub mod profile {
    pub const FOCUS_IN_K: f32 = 420.0;
    pub const FOCUS_IN_D: f32 = 30.0;
    pub const FOCUS_OUT_K: f32 = 480.0;
    pub const FOCUS_OUT_D: f32 = 36.0;

    pub const LABEL_IN_K: f32 = 440.0;
    pub const LABEL_IN_D: f32 = 40.0;
    pub const LABEL_OUT_K: f32 = 520.0;
    pub const LABEL_OUT_D: f32 = 44.0;

    pub const REORDER_K: f32 = 320.0;
    pub const REORDER_D: f32 = 29.0;

    pub const DRAWER_OPEN_K: f32 = 360.0;
    pub const DRAWER_OPEN_D: f32 = 38.0;
    pub const DRAWER_CLOSE_K: f32 = 440.0;
    pub const DRAWER_CLOSE_D: f32 = 42.0;
    pub const SCROLL_K: f32 = 400.0;
    pub const SCROLL_D: f32 = 40.0;
    pub const SETTINGS_OPEN_K: f32 = 340.0;
    pub const SETTINGS_OPEN_D: f32 = 36.0;
    pub const SETTINGS_CLOSE_K: f32 = 420.0;
    pub const SETTINGS_CLOSE_D: f32 = 40.0;

    pub const PRESS_IN_K: f32 = 900.0;
    pub const PRESS_IN_D: f32 = 52.0;
    pub const PRESS_OUT_K: f32 = 520.0;
    pub const PRESS_OUT_D: f32 = 30.0;

    pub const HEADER_IN_K: f32 = 420.0;
    pub const HEADER_IN_D: f32 = 33.0;
    pub const HEADER_OUT_K: f32 = 480.0;
    pub const HEADER_OUT_D: f32 = 37.0;

    pub const MENU_OPEN_K: f32 = 600.0;
    pub const MENU_OPEN_D: f32 = 46.0;
    pub const MENU_CLOSE_K: f32 = 760.0;
    pub const MENU_CLOSE_D: f32 = 54.0;
    pub const MENU_SELECTION_K: f32 = 700.0;
    pub const MENU_SELECTION_D: f32 = 40.0;
    pub const LAUNCH_K: f32 = 480.0;
    pub const LAUNCH_D: f32 = 40.0;
    pub const RETURN_K: f32 = 240.0;
    pub const RETURN_D: f32 = 25.0;

    pub const SELECTION_K: f32 = 620.0;
    pub const SELECTION_D: f32 = 38.0;

    pub const FOCUS_ENTER_VELOCITY: f32 = 1.4;
    pub const FOCUS_LEAVE_VELOCITY: f32 = 1.7;
    pub const LABEL_ENTER_VELOCITY: f32 = 0.6;
    pub const LABEL_LEAVE_VELOCITY: f32 = 0.8;
    pub const FOCUS_LIFT: f32 = 3.4;
    pub const FOCUS_GROWTH: f32 = 0.115;
    pub const PRESS_DEPTH: f32 = 0.030;
    pub const MENU_START_SCALE: f32 = 0.966;
    pub const MENU_START_Y: f32 = 10.0;
    pub const LABEL_START_Y: f32 = -14.0;

    #[inline]
    pub fn arrange_angle(seconds: f64, identity: i32, selected: bool) -> f32 {
        let phase = ((identity & 1023) as f64) * 0.013;
        let amp = if selected { 0.8f32 } else { 0.65f32 };
        amp * ((seconds * 21.36 + phase).sin() as f32)
    }

    #[inline]
    pub fn reorder_tilt(vx: f32, vy: f32, selected: bool) -> f32 {
        if vx == 0.0 && vy == 0.0 {
            return 0.0;
        }
        let scale_x = if selected { 3.0f32 } else { 1.6f32 };
        -scale_x * (((vx / 1700.0) as f64).tanh() as f32)
            + 0.45f32 * (((vy / 1300.0) as f64).tanh() as f32)
    }

    #[inline]
    pub fn strain(velocity: f32, reference_speed: f32, maximum: f32) -> f32 {
        if velocity == 0.0 {
            return 0.0;
        }
        let ratio = velocity / reference_speed;
        maximum * (((ratio * ratio) as f64).tanh() as f32)
    }

    #[inline]
    pub fn step_exponential(value: f32, target: f32, dt: f32, tau: f32, epsilon: f32) -> f32 {
        let diff = target - value;
        if diff.abs() <= epsilon {
            return target;
        }
        let next = value + diff * (1.0 - ((-dt / tau) as f64).exp() as f32);
        if (target - next).abs() <= epsilon {
            target
        } else {
            next
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn resolve_tile_transform(
        x: f32,
        y: f32,
        scale_val: f32,
        pushed: f32,
        presence_val: f32,
        retired: bool,
        exiting: bool,
        selected: bool,
        traveling: bool,
        dock: bool,
        edge_stretched: bool,
        canvas_left: f32,
        canvas_top: f32,
        canvas_scale: f32,
        edge_x: f32,
        edge_y: f32,
        edge_anchor_x: f32,
        edge_anchor_y: f32,
        vx: f32,
        vy: f32,
        scroll_strain: f32,
        offset_x: f32,
        offset_y: f32,
        drawer_val: f32,
        hidden_for_morph: bool,
        out: &mut [f32; 8],
    ) {
        use crate::geometry::{
            clamp, FOCUS_GAIN, TILE_IDLE_HEIGHT, TILE_IDLE_WIDTH, TILE_MAX_HEIGHT, TILE_MAX_WIDTH,
        };
        let z = clamp(scale_val, 0.0, 1.025);
        let visible = clamp(presence_val, 0.0, 1.0);
        let compression = (1.0 - PRESS_DEPTH * clamp(pushed, -0.12, 1.0))
            * if retired {
                visible
            } else {
                0.94 + 0.06 * visible
            };
        let sx =
            (TILE_IDLE_WIDTH + (TILE_MAX_WIDTH - TILE_IDLE_WIDTH) * z) / 252.0 * compression;
        let sy =
            (TILE_IDLE_HEIGHT + (TILE_MAX_HEIGHT - TILE_IDLE_HEIGHT) * z) / 142.0 * compression;
        let mut tx = canvas_left + x * canvas_scale - 138.0;
        let mut ty = canvas_top + y * canvas_scale - 83.0;
        let mut scale_x = sx * canvas_scale * (1.0 + FOCUS_GAIN * z);
        let mut scale_y = sy * canvas_scale * (1.0 + FOCUS_GAIN * z);
        if edge_stretched {
            tx += edge_x * 3.0 * canvas_scale;
            ty += edge_y * 3.0 * canvas_scale;
            scale_x *= 1.0 + 0.022 * edge_x * edge_anchor_x;
            scale_y *= 1.0 + 0.022 * edge_y * edge_anchor_y;
        }
        let travel_x = strain(vx, 1600.0, 0.018);
        let travel_y = strain(vy, 1100.0, 0.018);
        let scroll_y = if !dock { scroll_strain } else { 0.0 };
        scale_x *= 1.0 + travel_x - 0.38 * (travel_y + scroll_y);
        scale_y *= 1.0 + travel_y + scroll_y - 0.38 * travel_x;
        let departure = clamp(
            (offset_x.abs() + offset_y.abs()) / TILE_MAX_HEIGHT,
            0.0,
            1.0,
        );
        let home_exit = if !dock && traveling {
            departure + (1.0 - departure) * clamp(drawer_val, 0.0, 1.0)
        } else {
            1.0
        };
        let alpha = visible * home_exit * if hidden_for_morph { 0.0 } else { 1.0 };
        let translation_z = if exiting {
            3.0
        } else if selected {
            4.0
        } else if traveling {
            2.0
        } else {
            0.0
        };
        let tint = (clamp(z, 0.0, 1.0) * 64.0).round();
        out[0] = tx;
        out[1] = ty;
        out[2] = scale_x;
        out[3] = scale_y;
        out[4] = sy;
        out[5] = alpha;
        out[6] = translation_z;
        out[7] = tint;
    }

    #[inline]
    pub fn clock_char_transition(
        progress: f32,
        slot: i32,
        total_slots: i32,
        travel_y: f32,
        out: &mut [f32; 7],
    ) {
        use crate::geometry::clamp;
        let p = clamp(progress, 0.0, 1.0);
        let pos_t = p * p * (3.0 - 2.0 * p);
        let count = total_slots.max(1);
        let idx = slot.max(0).min(count - 1);
        let local_p = if count > 1 {
            let step = (0.24f32 / ((count - 1) as f32)).min(0.075);
            let span = (1.0 - step * ((count - 1) as f32)).max(0.2);
            clamp((p - step * (idx as f32)) / span, 0.0, 1.0)
        } else {
            p
        };
        let out_t = clamp(local_p / 0.78, 0.0, 1.0);
        let out_ease = out_t * out_t * (3.0 - 2.0 * out_t);
        let dy_out = -travel_y * out_ease;
        let scale_out = 1.0 - 0.22 * out_ease;
        let alpha_out = (1.0 - out_ease) * (1.0 - 0.35 * out_t);

        let in_t = clamp((local_p - 0.12) / 0.88, 0.0, 1.0);
        let u = 1.0 - in_t;
        let in_ease = 1.0 - u * u * u;
        let dy_in = travel_y * (1.0 - in_ease);
        let scale_in = 0.78 + 0.22 * in_ease;
        let alpha_in = clamp(in_t * 1.35, 0.0, 1.0) * in_ease;

        out[0] = pos_t;
        out[1] = dy_out;
        out[2] = scale_out;
        out[3] = alpha_out;
        out[4] = dy_in;
        out[5] = scale_in;
        out[6] = alpha_in;
    }
}

#[derive(Clone, Copy, Debug)]
pub struct DrawerFlow {
    pub dock: Spring,
    pub rows: [Spring; 3],
}

impl Default for DrawerFlow {
    fn default() -> Self {
        Self::new()
    }
}

impl DrawerFlow {
    pub const fn new() -> Self {
        Self {
            dock: Spring::new(0.0, 360.0, 30.0),
            rows: [
                Spring::new(0.0, 650.0, 43.0),
                Spring::new(0.0, 650.0, 43.0),
                Spring::new(0.0, 650.0, 43.0),
            ],
        }
    }

    pub fn update(&mut self, target: f32, elapsed: f32) -> bool {
        let clamped_elapsed = 0.25f32.min(elapsed);
        let steps = 1.max((clamped_elapsed * 120.0).ceil() as i32);
        let dt = clamped_elapsed / (steps as f32);
        for _ in 0..steps {
            if target > 0.0 {
                self.dock.aim(target);
                self.dock.update(dt);
                let mut lead = self.dock.value;
                for row in &mut self.rows {
                    row.aim(lead);
                    row.update(dt);
                    lead = row.value;
                }
            } else {
                let mut lead = target;
                for row in self.rows.iter_mut().rev() {
                    row.aim(lead);
                    row.update(dt);
                    lead = row.value;
                }
                self.dock.aim(lead);
                self.dock.update(dt);
            }
        }
        self.busy()
    }

    #[inline]
    pub fn busy(&self) -> bool {
        if self.dock.busy() {
            return true;
        }
        self.rows.iter().any(|r| r.busy())
    }

    #[inline]
    pub fn row_progress(&self, relative_row: f32) -> f32 {
        row_progress_from(
            self.rows[0].value,
            self.rows[1].value,
            self.rows[2].value,
            relative_row,
        )
    }
}

#[inline]
pub fn row_progress_from(r0: f32, r1: f32, r2: f32, relative_row: f32) -> f32 {
    let rows = [r0, r1, r2];
    let position = relative_row.max(0.0).min(2.0);
    let i = position as usize;
    if i >= 2 {
        rows[2]
    } else {
        rows[i] + (rows[i + 1] - rows[i]) * (position - (i as f32))
    }
}

pub mod elastic {
    #[derive(Clone, Copy, Debug, Default)]
    pub struct Approach {
        pub direction: i32,
        pub last_move: i64,
        pub speed: f32,
    }

    impl Approach {
        pub fn moved(&mut self, next: i32, time: i64) {
            let interval = time - self.last_move;
            if next == self.direction && interval > 0 && interval < 500 {
                let measured = 16.0f32.min(1000.0f32 / (40.max(interval) as f32));
                self.speed = if self.speed == 0.0 {
                    measured
                } else {
                    self.speed * 0.35 + measured * 0.65
                };
            } else {
                self.speed = 0.0;
            }
            self.direction = next;
            self.last_move = time;
        }

        pub fn speed(&self, next: i32, time: i64) -> f32 {
            let age = time - self.last_move;
            if next != self.direction || age < 0 || age > 500 {
                return 0.0;
            }
            self.speed * ((-((age as f32) / 180.0) as f64).exp() as f32)
        }
    }

    #[derive(Clone, Copy, Debug, Default)]
    pub struct Axis {
        pub value: f32,
        pub velocity: f32,
        pub direction: f32,
    }

    impl Axis {
        #[inline]
        pub fn aim(&mut self, next: f32) {
            let next = next.max(-1.0).min(1.0);
            if next != 0.0 && self.direction == 0.0 && self.velocity.abs() < 0.1 {
                self.velocity += next * 2.4;
            }
            self.direction = next;
        }

        #[inline]
        pub fn press(&mut self, next: f32, approach_speed: f32) {
            let next = next.max(-1.0).min(1.0);
            if next != 0.0 && self.direction == 0.0 {
                let incoming = if approach_speed.is_finite() {
                    approach_speed.max(0.0).min(16.0)
                } else {
                    0.0
                };
                self.velocity = (self.velocity
                    + next * (2.4 + incoming * 0.45) / (1.0 + 2.0 * self.value.abs()))
                .max(-14.0)
                .min(14.0);
            }
            self.direction = next;
        }

        #[inline]
        fn acceleration(&self, position: f32, speed: f32) -> f32 {
            let drag = if self.direction == 0.0 { 15.0 } else { 19.0 };
            140.0 * self.direction
                - 110.0 * position
                - 90.0 * position * position * position
                - drag * speed
        }

        pub fn update(&mut self, dt: f32) -> bool {
            if self.direction == 0.0 && self.value == 0.0 && self.velocity == 0.0 {
                return false;
            }
            if dt <= 0.0 {
                return self.busy();
            }
            let elapsed = 0.25f32.min(dt);
            let steps = 1.max((elapsed * 240.0).ceil() as i32);
            let h = elapsed / (steps as f32);
            for _ in 0..steps {
                let a = self.acceleration(self.value, self.velocity);
                let mid_v = self.velocity + a * h * 0.5;
                let mid_x = self.value + self.velocity * h * 0.5;
                self.value += mid_v * h;
                self.velocity += self.acceleration(mid_x, mid_v) * h;
            }
            if !self.busy() && self.direction == 0.0 {
                self.value = 0.0;
                self.velocity = 0.0;
            }
            self.busy()
        }

        #[inline]
        pub fn busy(&self) -> bool {
            self.velocity.abs() > 0.008 || self.acceleration(self.value, 0.0).abs() > 0.035
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct FocusDepth {
    pub horizontal: Spring,
    pub vertical: Spring,
}

impl Default for FocusDepth {
    fn default() -> Self {
        Self::new()
    }
}

impl FocusDepth {
    pub const fn new() -> Self {
        Self {
            horizontal: Spring::new(0.0, 180.0, 23.0),
            vertical: Spring::new(0.0, 180.0, 23.0),
        }
    }

    pub fn navigate(&mut self, dx: i32, dy: i32) {
        if dx != 0 {
            let (v, vel) = kick_horizontal(self.horizontal.value, self.horizontal.velocity, dx);
            self.horizontal.value = v;
            self.horizontal.velocity = vel;
            self.horizontal.aim(0.0);
        }
        if dy != 0 {
            let (v, vel) = kick_vertical(self.vertical.value, self.vertical.velocity, dy);
            self.vertical.value = v;
            self.vertical.velocity = vel;
            self.vertical.aim(0.0);
        }
    }

    pub fn update(&mut self, dt: f32) -> bool {
        let a = self.horizontal.update(dt);
        let b = self.vertical.update(dt);
        a || b
    }

    #[inline]
    pub fn x(&self) -> f32 {
        self.horizontal.value
    }

    #[inline]
    pub fn y(&self) -> f32 {
        self.vertical.value
    }

    #[inline]
    pub fn active(&self) -> bool {
        self.horizontal.busy() || self.vertical.busy()
    }
}

#[inline]
pub fn kick_horizontal(value: f32, velocity: f32, dx: i32) -> (f32, f32) {
    if dx == 0 {
        return (value, velocity);
    }
    let d = dx as f32;
    (
        (value - d * 0.13).max(-0.3).min(0.3),
        (velocity - d * 0.75).max(-2.4).min(2.4),
    )
}

#[inline]
pub fn kick_vertical(value: f32, velocity: f32, dy: i32) -> (f32, f32) {
    if dy == 0 {
        return (value, velocity);
    }
    let d = dy as f32;
    (
        (value - d * 0.12).max(-0.3).min(0.3),
        (velocity - d * 0.7).max(-2.4).min(2.4),
    )
}

pub mod menu {
    use super::profile;

    pub const OPEN_K: f32 = 420.0;
    pub const OPEN_D: f32 = 30.0;
    pub const CLOSE_K: f32 = 500.0;
    pub const CLOSE_D: f32 = 36.0;

    #[derive(Clone, Copy, Debug, Default, PartialEq)]
    pub struct Frame {
        pub x: f32,
        pub y: f32,
        pub scale_x: f32,
        pub scale_y: f32,
        pub alpha: f32,
        pub clarity: f32,
    }

    #[inline]
    pub fn resolve(
        progress: f32,
        velocity: f32,
        source_x: f32,
        source_y: f32,
        target_x: f32,
        target_y: f32,
        height: f32,
    ) -> Frame {
        let start = 0.32f32.min(126.0f32 / 1.0f32.max(height));
        resolve_from_scale(
            progress, velocity, source_x, source_y, target_x, target_y, start,
        )
    }

    #[inline]
    pub fn resolve_from_scale(
        progress: f32,
        velocity: f32,
        source_x: f32,
        source_y: f32,
        target_x: f32,
        target_y: f32,
        start: f32,
    ) -> Frame {
        let p = progress.max(0.0).min(1.05);
        let visible = 1.0f32.min(p);
        let scale = start + (1.0 - start) * p;
        let fall = 0.0f32.max((((-velocity / 5.0) as f64).tanh()) as f32);
        let strain = profile::strain(velocity, 8.0, 0.035);
        Frame {
            x: source_x + (target_x - source_x) * p,
            y: source_y + (target_y - source_y) * p + 64.0 * visible * (1.0 - visible) * fall,
            scale_x: scale * (1.0 - 0.35 * strain),
            scale_y: scale * (1.0 + strain),
            alpha: visible,
            clarity: ((visible - 0.18) / 0.82).max(0.0).min(1.0),
        }
    }
}

pub mod home_return {
    pub const MAX_SCALE: f32 = 3.25;
    pub const DEST_X: f32 = 960.0;
    pub const DEST_Y: f32 = 540.0;

    #[inline]
    fn clamp(p: f32) -> f32 {
        p.max(0.0).min(1.0)
    }

    #[inline]
    pub fn position(source: f32, destination: f32, p: f32) -> f32 {
        let t = clamp(p);
        source + (destination - source) * t
    }

    #[inline]
    pub fn scale(p: f32) -> f32 {
        1.0 + (MAX_SCALE - 1.0) * clamp(p)
    }

    #[inline]
    pub fn backdrop_opacity(p: f32) -> f32 {
        let t = clamp(p);
        t * (1.15 - 0.15 * t)
    }
}

pub mod surface_morph {
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
    pub fn artwork_progress(progress: f32) -> f32 {
        1.0f32.min(progress)
    }

    #[inline]
    pub fn ease(progress: f32) -> f32 {
        let p = progress.max(0.0).min(1.0);
        p * p * (3.0 - 2.0 * p)
    }

    #[inline]
    pub fn artwork_corner_exponent(progress: f32) -> f32 {
        2.0 + 1.05 * (1.0 - ease(artwork_progress(progress) / 0.18))
    }

    #[inline]
    pub fn between(
        l0: f32,
        t0: f32,
        r0: f32,
        b0: f32,
        l1: f32,
        t1: f32,
        r1: f32,
        b1: f32,
        progress: f32,
    ) -> Bounds {
        let p = progress.max(0.0).min(1.0);
        Bounds {
            left: l0 + (l1 - l0) * p,
            top: t0 + (t1 - t0) * p,
            right: r0 + (r1 - r0) * p,
            bottom: b0 + (b1 - b0) * p,
        }
    }

    #[inline]
    pub fn resolve_render_frame(
        origin_l: f32,
        origin_t: f32,
        origin_r: f32,
        origin_b: f32,
        progress: f32,
        opening: bool,
        morph_fill: i32,
        image_w: f32,
        image_h: f32,
        out: &mut [f32; 12],
    ) {
        use crate::geometry::{clamp, TILE_RADIUS};
        let p = artwork_progress(progress);
        let left = origin_l * (1.0 - p);
        let top = origin_t * (1.0 - p);
        let right = origin_r + (1920.0 - origin_r) * p;
        let bottom = origin_b + (1080.0 - origin_b) * p;
        let origin_w = origin_r - origin_l;
        let radius = TILE_RADIUS * origin_w / 252.0 * (1.0 - 0.0f32.max(p));
        let dark = if opening {
            ease(clamp((p - 0.4) / 0.6, 0.0, 1.0))
        } else {
            0.0
        };
        let fill_u = morph_fill as u32;
        let r = ((((fill_u >> 16) & 255) as f32) * (1.0 - dark)) as u32;
        let g = ((((fill_u >> 8) & 255) as f32) * (1.0 - dark)) as u32;
        let b = (((fill_u & 255) as f32) * (1.0 - dark)) as u32;
        let fill_rgb = 0xff00_0000u32 | ((r & 255) << 16) | ((g & 255) << 8) | (b & 255);
        let crop = 26.0 * 0.0f32.max(p);
        let cx = crop * 504.0 / 284.0;
        out[0] = left;
        out[1] = top;
        out[2] = right;
        out[3] = bottom;
        out[4] = p;
        out[5] = radius;
        out[6] = dark;
        out[7] = f32::from_bits(fill_rgb);
        out[8] = 2.0 + cx;
        out[9] = 2.0 + crop;
        out[10] = image_w - 2.0 - cx;
        out[11] = image_h - 2.0 - crop;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drawer_flow_checks() {
        let mut f = DrawerFlow::new();
        for _ in 0..9 {
            f.update(1.0, 1.0 / 60.0);
        }
        assert!(
            f.dock.value > f.rows[0].value
                && f.rows[0].value > f.rows[1].value
                && f.rows[1].value > f.rows[2].value
        );
        let velocity = f.dock.velocity;
        f.update(0.0, 1.0 / 120.0);
        assert!(velocity > 0.0 && f.dock.velocity > 0.0);
        for _ in 0..360 {
            f.update(0.0, 1.0 / 60.0);
        }
        assert!(!f.busy() && f.dock.value == 0.0 && f.rows[2].value == 0.0);

        let mut closing = DrawerFlow::new();
        for _ in 0..240 {
            closing.update(1.0, 1.0 / 60.0);
        }
        for _ in 0..12 {
            closing.update(0.0, 1.0 / 60.0);
            assert!(
                closing.dock.value >= closing.rows[0].value
                    && closing.rows[0].value >= closing.rows[1].value
                    && closing.rows[1].value >= closing.rows[2].value
            );
        }
        for _ in 0..300 {
            closing.update(0.0, 1.0 / 60.0);
        }
        assert!(!closing.busy());

        let (mut a, mut b) = (DrawerFlow::new(), DrawerFlow::new());
        for _ in 0..30 {
            a.update(1.0, 1.0 / 30.0);
        }
        for _ in 0..60 {
            b.update(1.0, 1.0 / 60.0);
        }
        assert!((a.rows[2].value - b.rows[2].value).abs() < 0.003);
        for i in 0..360 {
            a.update(if i % 40 < 20 { 1.0 } else { 0.0 }, 1.0 / 60.0);
        }
        assert!(a.dock.value > -0.04 && a.dock.value < 1.04);
    }

    #[test]
    fn drawer_motion_checks() {
        let mut open = Spring::new(0.0, profile::DRAWER_OPEN_K, profile::DRAWER_OPEN_D);
        open.aim(1.0);
        let mut previous = 0.0f32;
        for _ in 0..120 {
            open.update(1.0 / 60.0);
            assert!(open.value >= previous - 0.00005);
            assert!(open.value <= 1.00005);
            previous = open.value;
        }
        assert!((open.value - 1.0).abs() < 0.001);
        open.aim_with(0.0, profile::DRAWER_CLOSE_K, profile::DRAWER_CLOSE_D);
        previous = open.value;
        for _ in 0..120 {
            open.update(1.0 / 60.0);
            assert!(open.value <= previous + 0.00005);
            assert!(open.value >= -0.00005);
            previous = open.value;
        }
        assert!(open.value.abs() < 0.001);
    }

    #[test]
    fn elastic_boundary_checks() {
        let mut approach = elastic::Approach::default();
        approach.moved(22, 1000);
        approach.moved(22, 1080);
        let fast = approach.speed(22, 1100);
        assert!(fast > 8.0);
        assert_eq!(approach.speed(21, 1100), 0.0);
        assert_eq!(approach.speed(22, 1700), 0.0);
        approach.moved(22, 1800);
        approach.moved(22, 2200);
        assert!(approach.speed(22, 2220) < fast);

        fn collision_peak(speed: f32, hz: i32) -> f32 {
            let mut a = elastic::Axis::default();
            a.press(1.0, speed);
            a.aim(0.0);
            let mut peak = 0.0f32;
            for _ in 0..(hz * 3) {
                a.update(1.0 / (hz as f32));
                peak = peak.max(a.value);
            }
            assert!(!a.busy() && a.value == 0.0);
            peak
        }

        for hz in [30, 60, 120] {
            let slow = collision_peak(0.0, hz);
            let quick = collision_peak(12.0, hz);
            assert!(quick > slow * 2.0 && quick < 0.5);
            assert!((quick - collision_peak(12.0, 240)).abs() < 0.015);
            let mut a = elastic::Axis::default();
            a.press(1.0, 12.0);
            let vel = a.velocity;
            for _ in 0..100 {
                a.press(1.0, 12.0);
            }
            assert_eq!(a.velocity, vel);
            a.update(0.1);
            a.aim(0.0);
            let val = a.value;
            let vel = a.velocity;
            a.press(1.0, 0.0);
            assert!(a.value == val && a.velocity > vel);
            for _ in 0..1000 {
                a.aim(0.0);
                a.press(1.0, 16.0);
            }
            assert!(a.velocity.is_finite() && a.velocity.abs() <= 14.0);
        }
    }

    #[test]
    fn focus_depth_checks() {
        let (mut a, mut b) = (FocusDepth::new(), FocusDepth::new());
        a.navigate(1, 0);
        b.navigate(1, 0);
        assert!(a.x() < 0.0 && a.y() == 0.0);
        for _ in 0..60 {
            a.update(1.0 / 60.0);
        }
        for _ in 0..30 {
            b.update(1.0 / 30.0);
        }
        assert!((a.x() - b.x()).abs() < 0.008);
        assert!(a.x().abs() < 0.01);
        b.navigate(-1, 1);
        b.navigate(1, -1);
        for _ in 0..70 {
            b.update(1.0 / 60.0);
        }
        assert!(!b.active());
    }

    #[test]
    fn menu_motion_checks() {
        let closed = menu::resolve(0.0, 0.0, 250.0, 930.0, 300.0, 580.0, 400.0);
        let open = menu::resolve(1.0, 0.0, 250.0, 930.0, 300.0, 580.0, 400.0);
        assert!(closed.x == 250.0 && closed.y == 930.0 && closed.scale_x < 0.33 && closed.alpha == 0.0);
        assert!(open.x == 300.0 && open.y == 580.0 && open.scale_x == 1.0 && open.scale_y == 1.0 && open.alpha == 1.0 && open.clarity == 1.0);
    }

    #[test]
    fn surface_morph_checks() {
        assert!((surface_morph::artwork_corner_exponent(0.0) - 3.05).abs() < 0.0005);
        assert!((surface_morph::artwork_corner_exponent(0.18) - 2.0).abs() < 0.0005);
        for hz in [30, 60, 120] {
            let mut s = Spring::new(1.0, profile::RETURN_K, profile::RETURN_D);
            s.aim(0.0);
            let mut min_p = 1.0f32;
            let (mut crossed, mut rebounded) = (false, false);
            for _ in 0..(hz * 3) {
                if !s.busy() {
                    break;
                }
                s.update(1.0 / (hz as f32));
                let p = surface_morph::artwork_progress(s.value);
                min_p = min_p.min(p);
                if p < 0.0 {
                    crossed = true;
                }
                if crossed && s.velocity > 0.0 {
                    rebounded = true;
                }
            }
            assert!(crossed && rebounded && !s.busy() && min_p <= -0.005 && min_p >= -0.03);
        }
    }

    #[test]
    fn clock_char_transition_checks() {
        let mut out = [0.0f32; 7];
        profile::clock_char_transition(0.0, 0, 3, 20.0, &mut out);
        assert!((out[0] - 0.0).abs() < 1e-6);
        assert!((out[1] - 0.0).abs() < 1e-6);
        assert!((out[2] - 1.0).abs() < 1e-6);
        assert!((out[3] - 1.0).abs() < 1e-6);
        assert!((out[6] - 0.0).abs() < 1e-6);

        profile::clock_char_transition(1.0, 2, 3, 20.0, &mut out);
        assert!((out[0] - 1.0).abs() < 1e-6);
        assert!((out[3] - 0.0).abs() < 1e-6);
        assert!((out[4] - 0.0).abs() < 1e-6);
        assert!((out[5] - 1.0).abs() < 1e-6);
        assert!((out[6] - 1.0).abs() < 1e-6);

        let mut first = [0.0f32; 7];
        let mut second = [0.0f32; 7];
        profile::clock_char_transition(0.4, 0, 3, 20.0, &mut first);
        profile::clock_char_transition(0.4, 2, 3, 20.0, &mut second);
        assert!(first[6] > second[6] && first[3] < second[3]);
    }
}
