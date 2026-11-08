//! Analytical damped harmonic oscillator: frame-rate independent and interruptible.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Spring {
    pub value: f32,
    pub target: f32,
    pub velocity: f32,
    pub stiffness: f32,
    pub damping: f32,
}

impl Spring {
    #[inline]
    pub const fn new(start: f32, stiffness: f32, damping: f32) -> Self {
        Self {
            value: start,
            target: start,
            velocity: 0.0,
            stiffness,
            damping,
        }
    }

    #[inline]
    pub fn set_dynamics(&mut self, next_stiffness: f32, next_damping: f32) {
        self.stiffness = next_stiffness;
        self.damping = next_damping;
    }

    #[inline]
    pub fn aim(&mut self, next: f32) {
        self.target = next;
    }

    #[inline]
    pub fn aim_with(&mut self, next: f32, next_stiffness: f32, next_damping: f32) {
        self.stiffness = next_stiffness;
        self.damping = next_damping;
        self.target = next;
    }

    #[inline]
    pub fn busy(&self) -> bool {
        self.value != self.target || self.velocity != 0.0
    }

    pub fn update(&mut self, dt: f32) -> bool {
        if self.value == self.target && self.velocity == 0.0 {
            return false;
        }
        if dt <= 0.0 {
            return self.busy();
        }
        let (next_val, next_vel) = step_spring(
            self.value,
            self.target,
            self.velocity,
            self.stiffness,
            self.damping,
            dt,
        );
        self.value = next_val;
        self.velocity = next_vel;
        self.busy()
    }
}

/// Pure function advancing `(value, velocity)` for one elapsed interval `dt`.
#[inline]
pub fn step_spring(
    value: f32,
    target: f32,
    velocity: f32,
    stiffness: f32,
    damping: f32,
    dt: f32,
) -> (f32, f32) {
    if value == target && velocity == 0.0 {
        return (value, 0.0);
    }
    if dt <= 0.0 {
        return (value, velocity);
    }
    let t = 0.25f64.min(dt as f64);
    let x = (value - target) as f64;
    let v = velocity as f64;
    let gamma = (damping as f64) * 0.5;
    let w0 = (stiffness as f64).sqrt();
    let (nx, nv) = if gamma < w0 - 1e-5 {
        let w = (w0 * w0 - gamma * gamma).sqrt();
        let e = (-gamma * t).exp();
        let a = x;
        let b = (v + gamma * x) / w;
        let cs = (w * t).cos();
        let sn = (w * t).sin();
        (
            e * (a * cs + b * sn),
            e * ((b * w - gamma * a) * cs + (-a * w - gamma * b) * sn),
        )
    } else if gamma > w0 + 1e-5 {
        let s = (gamma * gamma - w0 * w0).sqrt();
        let r1 = -gamma + s;
        let r2 = -gamma - s;
        let c1 = (v - r2 * x) / (r1 - r2);
        let c2 = x - c1;
        let e1 = (r1 * t).exp();
        let e2 = (r2 * t).exp();
        (c1 * e1 + c2 * e2, r1 * c1 * e1 + r2 * c2 * e2)
    } else {
        let e = (-gamma * t).exp();
        let b = v + gamma * x;
        (e * (x + b * t), e * (v - gamma * b * t))
    };
    let next_value = target + (nx as f32);
    let next_velocity = nv as f32;
    if (next_value - target).abs() < 0.0005 && next_velocity.abs() < 0.012 {
        (target, 0.0)
    } else {
        (next_value, next_velocity)
    }
}

#[inline]
pub fn pack_f32_pair(a: f32, b: f32) -> i64 {
    (((a.to_bits() as u64) << 32) | (b.to_bits() as u64)) as i64
}
