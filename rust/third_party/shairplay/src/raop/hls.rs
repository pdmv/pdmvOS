//! HLS (HTTP Live Streaming) handler traits and playback state.
//!
//! The iPhone sends an m3u8 URL via `/play`. The application is responsible
//! for fetching and playing the stream. The library relays playback state
//! (position, duration, rate) back to the iPhone via `/playback-info`.

/// Factory for HLS playback sessions.
pub trait HlsHandler: Send + Sync {
    /// Called when the iPhone sends a `/play` request with an HLS URL.
    /// The application should start playing the stream and return a session
    /// handle for state queries.
    fn on_play(&self, url: &str, start_position: f32) -> Box<dyn HlsSession>;
}

/// A live HLS playback session. The library polls this to respond to
/// `/playback-info` requests from the iPhone.
pub trait HlsSession: Send {
    /// Total duration in seconds (0.0 if unknown/live).
    fn duration(&self) -> f32;
    /// Current playback position in seconds.
    fn position(&self) -> f32;
    /// Playback rate: 0.0 = paused, 1.0 = normal.
    fn rate(&self) -> f32;
    /// Whether the player is ready to play.
    fn ready(&self) -> bool {
        true
    }
    /// Whether playback is waiting for more media data.
    fn buffering(&self) -> bool {
        false
    }
    /// Seek to a position in seconds.
    fn seek(&mut self, position: f32);
    /// Seek to a fraction of the media duration, which may only be known after prepare.
    fn seek_fraction(&mut self, fraction: f32) {
        self.seek(self.duration() * fraction);
    }
    /// Set playback rate (0.0 = pause, 1.0 = play).
    fn set_rate(&mut self, rate: f32);
    /// Stop playback.
    fn stop(&mut self);
}
