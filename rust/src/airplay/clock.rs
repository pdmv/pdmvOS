//! Maps the sender's 32.32 timestamps onto a local playout timeline.

pub(super) const BUFFER_US: u64 = 55_000;
const REBASE_US: u64 = 160_000;
const MAX_JITTER_US: u64 = 80_000;
const DECODE_HEADROOM_US: u64 = 12_000;

#[derive(Default)]
pub(super) struct VideoClock {
    anchor: Option<(u64, u64)>,
    last: Option<(u64, u64, u64)>,
    jitter_us: u64,
}

impl VideoClock {
    pub(super) fn deadline(&mut self, stamp: u64, received_us: u64, now_us: u64) -> u64 {
        let (origin, base) = *self
            .anchor
            .get_or_insert((stamp, now_us.saturating_add(BUFFER_US)));
        let offset = ((i128::from(stamp.wrapping_sub(origin) as i64) * 1_000_000) >> 32) as i64;
        let target = base.saturating_add_signed(offset);
        let arrival_target = received_us.saturating_add(BUFFER_US);
        let deadline = if let Some((last_stamp, last_receive, last_deadline)) = self.last {
            if stamp == last_stamp {
                // Some senders omit timestamps. Preserve arrival spacing without
                // throwing away already buffered time.
                last_deadline
                    .saturating_add(received_us.saturating_sub(last_receive))
                    .max(arrival_target)
            } else if target.saturating_add(REBASE_US) < arrival_target
                || target > arrival_target.saturating_add(REBASE_US)
            {
                let base = now_us.max(received_us).saturating_add(BUFFER_US);
                self.anchor = Some((stamp, base));
                self.jitter_us = 0;
                base
            } else {
                // Grow only when late arrivals consume the decoder's margin.
                // Ease back toward the normal delay after the burst has passed.
                let needed = received_us
                    .saturating_add(DECODE_HEADROOM_US)
                    .saturating_sub(target)
                    .min(MAX_JITTER_US);
                self.jitter_us = needed.clamp(
                    self.jitter_us.saturating_sub(100),
                    self.jitter_us.saturating_add(8_000),
                );
                target.saturating_add(self.jitter_us)
            }
        } else {
            target
        };
        let deadline = if self.last.is_some_and(|(_, _, last)| deadline == last) {
            deadline.saturating_add(1)
        } else {
            deadline
        };
        self.last = Some((stamp, received_us, deadline));
        deadline
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn stamp(ms: u64) -> u64 {
        (ms << 32) / 1000
    }

    #[test]
    fn burst_keeps_source_spacing() {
        let mut clock = VideoClock::default();
        assert_eq!(clock.deadline(stamp(1000), 0, 0), BUFFER_US);
        assert_eq!(clock.deadline(stamp(1033), 45_000, 45_000), 87_999);
        assert_eq!(clock.deadline(stamp(1066), 45_000, 46_000), 120_999);
    }

    #[test]
    fn resets_after_long_gap_and_handles_missing_clock() {
        let mut clock = VideoClock::default();
        assert_eq!(clock.deadline(0, 0, 0), BUFFER_US);
        assert_eq!(clock.deadline(0, 33_000, 34_000), 88_000);
        assert_eq!(clock.deadline(stamp(33), 600_000, 602_000), 657_000);
    }

    #[test]
    fn untimed_frames_do_not_accumulate_decoder_delay() {
        let mut clock = VideoClock::default();
        assert_eq!(clock.deadline(0, 0, 0), BUFFER_US);
        assert_eq!(clock.deadline(0, 33_000, 140_000), 88_000);
        assert_eq!(clock.deadline(0, 66_000, 180_000), 121_000);
    }

    #[test]
    fn late_arrivals_raise_delay_then_healthy_arrivals_release_it() {
        let mut clock = VideoClock::default();
        clock.deadline(stamp(1000), 0, 0);
        assert_eq!(clock.deadline(stamp(1017), 70_000, 70_000), 79_999);
        assert_eq!(clock.deadline(stamp(1034), 87_000, 87_000), 99_000);
        assert_eq!(clock.deadline(stamp(1051), 88_000, 88_000), 115_900);
    }
}
