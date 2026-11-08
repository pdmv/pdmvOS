//! Display mode selection, Wi-Fi transition deduplication, return sound gate, and durable slot ordering.

pub fn select_display_mode(
    widths: &[i32],
    heights: &[i32],
    rates: &[f32],
    current: i32,
) -> Result<usize, &'static str> {
    if widths.len() != heights.len()
        || widths.len() != rates.len()
        || current < 0
        || (current as usize) >= widths.len()
    {
        return Err("modes");
    }
    let cur = current as usize;
    let mut best = cur;
    let mut fastest = rates[cur];
    let current_aspect = (widths[cur] as f64) / (heights[cur] as f64);
    for i in 0..rates.len() {
        if widths[i] > 0
            && heights[i] > 0
            && (((widths[i] as f64) / (heights[i] as f64)) - current_aspect).abs() <= 0.02
        {
            fastest = fastest.max(rates[i]);
        }
    }
    let floor = 60.0f32.min(fastest) - 0.6;
    let aspect = current_aspect;
    for i in 0..widths.len() {
        if widths[i] <= 0
            || heights[i] <= 0
            || rates[i] < floor
            || (((widths[i] as f64) / (heights[i] as f64)) - aspect).abs() > 0.02
        {
            continue;
        }
        let pixels = (widths[i] as i64) * (heights[i] as i64);
        let previous = (widths[best] as i64) * (heights[best] as i64);
        if rates[best] < floor
            || pixels > previous
            || (pixels == previous && rates[i] > rates[best] + 0.6)
        {
            best = i;
        }
    }
    Ok(best)
}

pub mod wifi {
    pub const NONE: i32 = -1;
    pub const OFF: i32 = 0;
    pub const ON: i32 = 1;
    pub const CONNECTED: i32 = 2;
    pub const LOST: i32 = 3;

    #[derive(Clone, Copy, Debug)]
    pub struct WifiFeedback {
        pub announced: i32,
        pub pending_loss: i64,
    }

    impl WifiFeedback {
        #[inline]
        pub const fn new(initial: i32) -> Self {
            Self {
                announced: initial,
                pending_loss: -1,
            }
        }

        pub fn observe(&mut self, current: i32, visible: bool, now: i64) -> i32 {
            if !(0..=2).contains(&current) {
                return NONE;
            }
            if !visible {
                self.pending_loss = -1;
                return NONE;
            }
            if current == self.announced {
                self.pending_loss = -1;
                return NONE;
            }
            if self.announced == 2 && current == 1 {
                if self.pending_loss < 0 {
                    self.pending_loss = now;
                    return NONE;
                }
                if now - self.pending_loss < 600 {
                    return NONE;
                }
            }
            let event = if current == 2 {
                CONNECTED
            } else if current == 0 {
                OFF
            } else if self.announced == 2 {
                LOST
            } else {
                ON
            };
            self.announced = current;
            self.pending_loss = -1;
            event
        }
    }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct ReturnSoundGate {
    pub pending: bool,
    pub active: bool,
    pub retries: i32,
}

impl ReturnSoundGate {
    #[inline]
    pub fn request(&mut self) {
        if !self.pending {
            self.retries = 0;
        }
        self.pending = true;
    }

    #[inline]
    pub fn set_active(&mut self, value: bool) {
        self.active = value;
    }

    #[inline]
    pub fn pending(&self) -> bool {
        self.pending
    }

    #[inline]
    pub fn can_play(&self, loaded: bool, enabled: bool) -> bool {
        self.pending && self.active && loaded && enabled
    }

    #[inline]
    pub fn retry(&mut self) -> bool {
        if self.pending {
            self.retries += 1;
            self.retries <= 3
        } else {
            false
        }
    }

    #[inline]
    pub fn consume(&mut self) {
        self.pending = false;
        self.retries = 0;
    }

    #[inline]
    pub fn to_bits(self) -> i32 {
        (if self.pending { 1 } else { 0 })
            | (if self.active { 2 } else { 0 })
            | ((self.retries & 0x1fff_ffff) << 2)
    }

    #[inline]
    pub fn from_bits(bits: i32) -> Self {
        Self {
            pending: (bits & 1) != 0,
            active: (bits & 2) != 0,
            retries: bits >> 2,
        }
    }
}

pub const DOCK_CAPACITY: usize = 6;

#[derive(Clone, Debug, PartialEq)]
pub struct SlotLayout<T: PartialEq + Clone> {
    pub dock: Vec<Option<T>>,
    pub grid: Vec<T>,
}

impl<T: PartialEq + Clone> SlotLayout<T> {
    pub fn new(pinned: &[Option<T>], applications: &[Option<T>]) -> Self {
        let mut dock: Vec<Option<T>> = Vec::with_capacity(DOCK_CAPACITY);
        let mut grid: Vec<T> = Vec::new();
        for item in pinned.iter().flatten() {
            if !dock.iter().any(|x| x.as_ref() == Some(item)) && dock.len() < DOCK_CAPACITY {
                dock.push(Some(item.clone()));
            }
        }
        for item in applications.iter().flatten() {
            if !dock.iter().any(|x| x.as_ref() == Some(item)) && !grid.contains(item) {
                grid.push(item.clone());
            }
        }
        while dock.len() < DOCK_CAPACITY {
            dock.push(None);
        }
        Self { dock, grid }
    }

    fn pad(&mut self) {
        while self.dock.len() < DOCK_CAPACITY {
            self.dock.push(None);
        }
    }

    pub fn first_empty(&self) -> isize {
        self.dock
            .iter()
            .position(|x| x.is_none())
            .map(|i| i as isize)
            .unwrap_or(-1)
    }

    pub fn occupied(&self) -> usize {
        let i = self.first_empty();
        if i < 0 {
            DOCK_CAPACITY
        } else {
            i as usize
        }
    }

    pub fn unpin(&mut self, slot: isize) -> bool {
        self.to_grid(slot, 0)
    }

    pub fn to_grid(&mut self, slot: isize, destination: isize) -> bool {
        if slot < 0 || (slot as usize) >= DOCK_CAPACITY {
            return false;
        }
        let s = slot as usize;
        if self.dock[s].is_none() {
            return false;
        }
        let item = self.dock.remove(s).unwrap();
        self.pad();
        let dest = destination.max(0).min(self.grid.len() as isize) as usize;
        self.grid.insert(dest, item);
        true
    }

    pub fn to_dock(&mut self, source: isize, destination: isize) -> bool {
        if source < 0
            || (source as usize) >= self.grid.len()
            || destination < 0
            || (destination as usize) >= DOCK_CAPACITY
        {
            return false;
        }
        let src = source as usize;
        let dst = destination as usize;
        let item = self.grid.remove(src);
        let empty = self.first_empty();
        if empty >= 0 && dst >= (empty as usize) {
            self.dock[empty as usize] = Some(item);
        } else {
            let displaced = self.dock[dst].replace(item);
            if let Some(prev) = displaced {
                let insert_at = src.min(self.grid.len());
                self.grid.insert(insert_at, prev);
            }
        }
        true
    }

    pub fn move_dock(&mut self, from: isize, to: isize) -> bool {
        let occ = self.occupied() as isize;
        if from < 0 || from >= occ || to < 0 || (to as usize) >= DOCK_CAPACITY {
            return false;
        }
        let target = to.min(occ - 1);
        if from == target {
            return false;
        }
        let item = self.dock.remove(from as usize);
        self.dock.insert(target as usize, item);
        true
    }

    pub fn move_grid(&mut self, from: isize, to: isize) -> bool {
        let len = self.grid.len() as isize;
        if from < 0 || from >= len || to < 0 || to >= len || from == to {
            return false;
        }
        let item = self.grid.remove(from as usize);
        self.grid.insert(to as usize, item);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_policy_checks() {
        assert_eq!(
            select_display_mode(&[1920, 3840, 3840], &[1080, 2160, 2160], &[60.0, 59.94, 30.0], 0)
                .unwrap(),
            1
        );
        assert_eq!(
            select_display_mode(&[1920, 3840], &[1080, 2160], &[60.0, 30.0], 0).unwrap(),
            0
        );
        assert_eq!(
            select_display_mode(&[1920, 3840], &[1080, 2160], &[50.0, 50.0], 0).unwrap(),
            1
        );
        assert_eq!(
            select_display_mode(&[1920, 4096], &[1080, 2160], &[60.0, 60.0], 0).unwrap(),
            0
        );
        assert_eq!(
            select_display_mode(&[3840, 1920], &[2160, 1080], &[30.0, 60.0], 0).unwrap(),
            1
        );
        assert_eq!(
            select_display_mode(&[3840], &[2160], &[60.0], 0).unwrap(),
            0
        );
    }

    #[test]
    fn wifi_feedback_checks() {
        let mut f = wifi::WifiFeedback::new(0);
        assert_eq!(f.observe(0, true, 0), -1);
        assert_eq!(f.observe(1, true, 10), 1);
        assert_eq!(f.observe(1, true, 20), -1);
        assert_eq!(f.observe(2, true, 30), 2);
        assert_eq!(f.observe(2, true, 40), -1);
        assert_eq!(f.observe(1, true, 100), -1);
        assert_eq!(f.observe(1, true, 699), -1);
        assert_eq!(f.observe(1, true, 700), 3);
        assert_eq!(f.observe(1, true, 900), -1);
        assert_eq!(f.observe(2, true, 1000), 2);
        assert_eq!(f.observe(1, true, 1100), -1);
        assert_eq!(f.observe(2, true, 1200), -1);
        assert_eq!(f.observe(1, true, 1300), -1);
        assert_eq!(f.observe(0, true, 1400), 0);
        assert_eq!(f.observe(0, true, 2000), -1);
        assert_eq!(f.observe(2, true, 2100), 2);
        assert_eq!(f.observe(0, false, 2200), -1);
        assert_eq!(f.observe(1, false, 2300), -1);
        assert_eq!(f.observe(2, true, 2400), -1);
        assert_eq!(f.observe(0, false, 2500), -1);
        assert_eq!(f.observe(0, true, 2600), 0);
        assert_eq!(f.observe(0, true, 2700), -1);
        assert_eq!(f.observe(-1, true, 2800), -1);
        assert_eq!(f.observe(4, true, 2900), -1);
    }

    #[test]
    fn return_sound_gate_checks() {
        for load_first in [true, false] {
            let mut gate = ReturnSoundGate::default();
            gate.request();
            gate.request();
            if load_first {
                assert!(!gate.can_play(true, true));
                gate.set_active(true);
            } else {
                gate.set_active(true);
                assert!(!gate.can_play(false, true));
            }
            assert!(gate.can_play(true, true));
            gate.consume();
            gate.set_active(false);
            gate.set_active(true);
            assert!(!gate.can_play(true, true));
            gate.request();
            assert!(!gate.can_play(true, false));
            gate.consume();
            assert!(!gate.can_play(true, true));
            gate.request();
            gate.set_active(false);
            assert!(!gate.can_play(true, true));
            gate.set_active(true);
            assert!(gate.can_play(true, true));
            assert!(gate.retry() && gate.retry() && gate.retry() && !gate.retry());
            gate.consume();
            assert!(!gate.pending());
        }
    }

    #[test]
    fn slot_layout_checks() {
        let dock: Vec<Option<&str>> = ["A", "B", "C", "D", "E", "F"]
            .into_iter()
            .map(Some)
            .collect();
        let grid: Vec<Option<&str>> = ["G", "H", "I", "J", "K", "L", "M"]
            .into_iter()
            .map(Some)
            .collect();
        let mut s = SlotLayout::new(&dock, &grid);
        assert!(s.unpin(2));
        assert_eq!(
            s.dock,
            vec![
                Some("A"),
                Some("B"),
                Some("D"),
                Some("E"),
                Some("F"),
                None
            ]
        );
        assert_eq!(s.grid[0], "C");
        let empty = s.first_empty();
        assert!(s.to_dock(0, empty));
        assert_eq!(s.dock[5], Some("C"));
        assert!(s.to_dock(2, 1));
        assert_eq!(s.dock[1], Some("I"));
        assert_eq!(s.grid[2], "B");
        assert!(s.to_grid(1, 6));
    }
}
