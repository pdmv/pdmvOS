package dev.pdmv.os;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Plays the current AirPlay HTTP media session on the launcher's surface. */
final class AirPlayHlsPlayer {
    private static final long START_TIMEOUT_MS = 20_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable startupTimeout = () -> fail("startup timeout");
    private MediaPlayer player;
    private Surface surface;
    private int session;
    private int seekRevision;
    private int seekPosition;
    private boolean shouldPlay;
    private boolean playingRequested;
    private boolean prepared;
    private boolean buffering;
    private long nextReport;
    private float gain;
    private String url;
    private int state;
    private int width;
    private int height;

    boolean update(int[] status, boolean muted) {
        int id = status[4];
        if (id == 0) {
            if (session != 0) clear();
            return false;
        }
        int revision = status[5];
        int position = status[6];
        boolean play = status[7] != 0;
        float volume = muted ? 0f : Float.intBitsToFloat(status[8]);
        if (id != session) {
            byte[] location = NativeCore.airplayHlsUrl(id);
            if (location == null) return false;
            release();
            session = id;
            gain = volume;
            seekRevision = revision;
            seekPosition = position;
            shouldPlay = play;
            url = new String(location, StandardCharsets.UTF_8);
            width = height = 0;
            state = AirPlayReceiver.CONNECTING;
            startIfReady();
        } else {
            if (volume != gain) {
                gain = volume;
                if (player != null) player.setVolume(gain, gain);
            }
            shouldPlay = play;
            if (revision != seekRevision) {
                seekRevision = revision;
                seekPosition = position;
                if (prepared) seek();
            }
            applyRate();
        }
        long now = SystemClock.uptimeMillis();
        if (prepared && now >= nextReport) {
            NativeCore.airplayHlsReport(session, player.getCurrentPosition(), player.getDuration(),
                buffering ? 2 : 1, player.isPlaying() ? 1 : 0);
            nextReport = now + 250;
        }
        // Release the route after delivering the terminal status once.
        if (state == AirPlayReceiver.FAILED || state == AirPlayReceiver.IDLE) {
            NativeCore.airplayHlsReport(session, 0, 0, -1, 0);
        }
        status[0] = state;
        status[1] = width;
        status[2] = height;
        // Screen mirroring has its own sequence numbers.
        status[3] = -session;
        return true;
    }

    boolean active() { return session != 0; }

    void setSurface(Surface next) {
        surface = next;
        if (player != null) player.setSurface(next);
        startIfReady();
    }

    void clear() {
        release();
        session = 0;
        state = AirPlayReceiver.IDLE;
        url = null;
    }

    private void startIfReady() {
        if (state != AirPlayReceiver.CONNECTING || player != null
            || surface == null || !surface.isValid()) return;
        MediaPlayer next = new MediaPlayer();
        player = next;
        try {
            next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
            next.setSurface(surface);
            next.setVolume(gain, gain);
            next.setOnVideoSizeChangedListener((mp, w, h) -> {
                if (player == mp) {
                    width = w;
                    height = h;
                }
            });
            next.setOnPreparedListener(mp -> {
                if (player != mp) return;
                prepared = true;
                width = mp.getVideoWidth();
                height = mp.getVideoHeight();
                if (seekPosition != 0) seek();
                main.removeCallbacks(startupTimeout);
                applyRate();
                Log.i("AirPlayReceiver", "HLS prepared " + width + "x" + height);
            });
            next.setOnInfoListener((mp, what, extra) -> {
                if (player == mp) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        main.removeCallbacks(startupTimeout);
                        state = AirPlayReceiver.PLAYING;
                        Log.i("AirPlayReceiver", "HLS first video frame rendered");
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                        || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START;
                        nextReport = 0;
                        Log.i("AirPlayReceiver", "HLS buffering " + (buffering ? "started" : "ended"));
                    }
                }
                return false;
            });
            next.setOnCompletionListener(mp -> {
                if (player != mp) return;
                int duration = mp.getDuration();
                NativeCore.airplayHlsReport(session, duration, duration, 0, 0);
                release();
                state = AirPlayReceiver.IDLE;
            });
            next.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) fail(what + "/" + extra);
                return true;
            });
            next.setDataSource(url);
            next.prepareAsync();
            main.postDelayed(startupTimeout, START_TIMEOUT_MS);
        } catch (IOException | RuntimeException error) {
            fail(error.getClass().getSimpleName());
        }
    }

    private void seek() {
        int position = seekPosition;
        if (position < 0) {
            int duration = player.getDuration();
            if (duration <= 0) return;
            position = (int) Math.round(duration * ((-1L - position) / 1_000_000.0));
        }
        player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
    }

    private void applyRate() {
        if (!prepared || shouldPlay == playingRequested) return;
        if (shouldPlay) {
            player.start();
            if (state == AirPlayReceiver.CONNECTING) main.postDelayed(startupTimeout, START_TIMEOUT_MS);
        } else {
            player.pause();
            main.removeCallbacks(startupTimeout);
        }
        playingRequested = shouldPlay;
        nextReport = 0;
    }

    private void fail(String reason) {
        Log.w("AirPlayReceiver", "HLS playback failed " + reason);
        release();
        state = AirPlayReceiver.FAILED;
    }

    private void release() {
        main.removeCallbacks(startupTimeout);
        prepared = false;
        buffering = false;
        nextReport = 0;
        playingRequested = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
