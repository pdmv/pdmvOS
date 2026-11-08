package dev.pdmv.os;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;

/** Android playback and lifecycle bridge for the Rust AirPlay receiver. */
final class AirPlayReceiver {
    private static final String TAG = "AirPlayReceiver";
    private static final AirPlayReceiver INSTANCE = new AirPlayReceiver();

    static final int MODE_ALL = 0;
    static final int MODE_OFF = 3;
    static final int STATE_IDLE = 0;
    static final int STATE_MIRRORING = 1;

    interface Listener {
        void onStateChanged(int state);
        void onVideoDimensionsChanged(int width, int height);
    }

    static AirPlayReceiver getInstance() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object playback = new Object();

    private volatile Thread worker;
    private NsdManager discovery;
    private final ArrayList<Registration> registrations = new ArrayList<>();
    private int currentMode = MODE_OFF;
    private String receiverName;
    private Listener listener;
    private Surface surface;
    private MediaCodec decoder;
    private AudioTrack audio;
    private int codec;
    private int failedCodec;

    private AirPlayReceiver() {}

    void setListener(Listener value) { listener = value; }

    int getCurrentMode() { return currentMode; }

    String getReceiverName() { return receiverName; }

    int getCurrentState() { return NativeCore.airplayGetState(); }

    boolean start(Context context, int mode) {
        if (mode < MODE_ALL || mode > MODE_OFF) return false;
        if (mode == currentMode) return true;
        stop();
        if (mode == MODE_OFF) return true;

        byte[] id = deviceId(context);
        String name = String.format(java.util.Locale.ROOT, "pdmvOS %02X%02X", id[4] & 255, id[5] & 255);
        try {
            byte[] records = NativeCore.airplayStart(mode, name.getBytes(StandardCharsets.UTF_8), id);
            if (records != null) {
                currentMode = mode;
                receiverName = name;
                discovery = context.getSystemService(NsdManager.class);
                if (discovery == null) throw new IllegalStateException("DNS-SD unavailable");
                for (String service : new String(records, StandardCharsets.UTF_8).split("\n\n")) {
                    String[] fields = service.split("\n");
                    NsdServiceInfo info = new NsdServiceInfo();
                    info.setPort(Integer.parseInt(fields[0]));
                    info.setServiceType(fields[1]);
                    info.setServiceName(fields[2]);
                    for (int i = 3; i < fields.length; i++) {
                        int separator = fields[i].indexOf('=');
                        info.setAttribute(fields[i].substring(0, separator), fields[i].substring(separator + 1));
                    }
                    Registration registration = new Registration(info);
                    discovery.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
                    registrations.add(registration);
                }
                Thread next = new Thread(this::run, "AirPlay-Playback");
                worker = next;
                next.start();
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            Log.e(TAG, "Could not start AirPlay", failure);
        }
        stop();
        return false;
    }

    void stop() {
        Thread previous = worker;
        worker = null;
        if (previous != null) previous.interrupt();
        synchronized (playback) {
            releaseDecoder();
            releaseAudio();
        }
        for (Registration registration : registrations) {
            try { discovery.unregisterService(registration); }
            catch (IllegalArgumentException removed) { /* Registration already ended. */ }
        }
        registrations.clear();
        if (currentMode != MODE_OFF) NativeCore.airplayStop();
        currentMode = MODE_OFF;
        receiverName = null;
        if (previous != null && listener != null) listener.onStateChanged(STATE_IDLE);
    }

    private final class Registration implements NsdManager.RegistrationListener {
        private final String type;
        private final int port;

        Registration(NsdServiceInfo info) {
            type = info.getServiceType();
            port = info.getPort();
        }

        @Override public void onServiceRegistered(NsdServiceInfo info) {
            main.post(() -> {
                if (!registrations.contains(this)) return;
                if (type.equals("_airplay._tcp.")) receiverName = info.getServiceName();
                Log.i(TAG, "Registered " + type + " " + info.getServiceName() + " port=" + port);
            });
        }

        @Override public void onRegistrationFailed(NsdServiceInfo info, int code) {
            main.post(() -> {
                if (!registrations.remove(this)) return;
                Log.w(TAG, "Could not register " + type + ": " + code);
                stop();
            });
        }

        @Override public void onServiceUnregistered(NsdServiceInfo info) {}

        @Override public void onUnregistrationFailed(NsdServiceInfo info, int code) {
            Log.w(TAG, "Could not unregister " + type + ": " + code);
        }
    }

    void setSurface(Surface next) {
        synchronized (playback) {
            if (surface == next) return;
            releaseDecoder();
            if (surface != null) surface.release();
            surface = next;
            failedCodec = 0;
        }
    }

    private void run() {
        Thread self = Thread.currentThread();
        int lastState = STATE_IDLE;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (worker == self) {
            int state = getCurrentState();
            if (state != lastState) {
                lastState = state;
                main.post(() -> {
                    if (worker == self && listener != null) listener.onStateChanged(state);
                });
            }
            boolean didWork;
            synchronized (playback) {
                if (worker != self) return;
                if (state != STATE_MIRRORING) {
                    releaseDecoder();
                    failedCodec = 0;
                }
                if (state == STATE_IDLE) releaseAudio();
                didWork = state == STATE_MIRRORING && playVideo(info, self);
                didWork |= state != STATE_IDLE && playAudio();
            }
            if (!didWork) {
                try { Thread.sleep(state == STATE_IDLE ? 40 : 5); }
                catch (InterruptedException stopped) { return; }
            }
        }
    }

    private boolean playVideo(MediaCodec.BufferInfo info, Thread owner) {
        int format = NativeCore.airplayVideoFormat();
        if (format == 0 || format == failedCodec || surface == null || !surface.isValid()) return false;
        try {
            if (decoder == null || codec != format) {
                releaseDecoder();
                String mime = format == 2 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
                MediaFormat config = MediaFormat.createVideoFormat(mime, 1920, 1080);
                config.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
                decoder = MediaCodec.createDecoderByType(mime);
                decoder.configure(config, surface, null, 0);
                decoder.start();
                codec = format;
            }

            // Keep a dequeued input slot until a frame is available.
            if (videoInput < 0) videoInput = decoder.dequeueInputBuffer(0);
            boolean didWork = false;
            if (videoInput >= 0) {
                byte[] frame = NativeCore.airplayPollVideo();
                if (frame != null) {
                    ByteBuffer input = decoder.getInputBuffer(videoInput);
                    if (input != null && frame.length <= input.capacity()) {
                        input.clear();
                        input.put(frame);
                        decoder.queueInputBuffer(videoInput, 0, frame.length, System.nanoTime() / 1000, 0);
                    } else {
                        decoder.queueInputBuffer(videoInput, 0, 0, 0, 0);
                        NativeCore.airplayResetVideo();
                    }
                    videoInput = -1;
                    didWork = true;
                }
            }

            int output = decoder.dequeueOutputBuffer(info, 0);
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat size = decoder.getOutputFormat();
                int width = size.getInteger(MediaFormat.KEY_WIDTH);
                int height = size.getInteger(MediaFormat.KEY_HEIGHT);
                if (size.containsKey("crop-right")) {
                    width = size.getInteger("crop-right") - size.getInteger("crop-left") + 1;
                    height = size.getInteger("crop-bottom") - size.getInteger("crop-top") + 1;
                }
                int w = width, h = height;
                main.post(() -> {
                    if (worker == owner && listener != null) listener.onVideoDimensionsChanged(w, h);
                });
            } else if (output >= 0) {
                decoder.releaseOutputBuffer(output, true);
                didWork = true;
            }
            return didWork;
        } catch (java.io.IOException | RuntimeException failure) {
            Log.w(TAG, "Could not decode AirPlay video", failure);
            releaseDecoder();
            failedCodec = format;
            return false;
        }
    }

    private int videoInput = -1;
    private byte[] pendingAudio;
    private int audioOffset;

    private boolean playAudio() {
        if (pendingAudio == null) {
            pendingAudio = NativeCore.airplayPollAudio();
            audioOffset = 0;
        }
        if (pendingAudio == null) return false;
        try {
            if (audio == null) {
                int channels = AudioFormat.CHANNEL_OUT_STEREO;
                int encoding = AudioFormat.ENCODING_PCM_16BIT;
                int size = AudioTrack.getMinBufferSize(44100, channels, encoding);
                audio = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, channels, encoding,
                        Math.max(size, 8192), AudioTrack.MODE_STREAM);
                audio.play();
            }
            int written = audio.write(pendingAudio, audioOffset, pendingAudio.length - audioOffset,
                    AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) {
                releaseAudio();
                return false;
            }
            audioOffset += written;
            if (audioOffset == pendingAudio.length) pendingAudio = null;
            return written > 0;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not play AirPlay audio", failure);
            releaseAudio();
            return false;
        }
    }

    private void releaseDecoder() {
        videoInput = -1;
        if (decoder != null) {
            decoder.release();
            decoder = null;
        }
    }

    private void releaseAudio() {
        pendingAudio = null;
        if (audio != null) {
            audio.release();
            audio = null;
        }
    }

    private static byte[] deviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE);
        long value = prefs.getLong("id", 0);
        if (value == 0) {
            value = new SecureRandom().nextLong();
            prefs.edit().putLong("id", value).apply();
        }
        byte[] bytes = ByteBuffer.allocate(8).putLong(value).array();
        return new byte[] { 2, bytes[3], bytes[4], bytes[5], bytes[6], bytes[7] };
    }
}
