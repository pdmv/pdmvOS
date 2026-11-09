package dev.pdmv.os;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
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

/** Registers the Rust receiver with Android and delivers status to the launcher. */
final class AirPlayReceiver {
    private static final String TAG = "AirPlayReceiver";
    private static final AirPlayReceiver INSTANCE = new AirPlayReceiver();

    static final int MODE_ALL = 0;
    static final int MODE_OFF = 3;
    static final int IDLE = 0;
    static final int CONNECTING = 1;
    static final int PLAYING = 2;
    static final int FAILED = 3;

    interface Listener {
        void onVideoState(int state, int width, int height, int session);
    }

    static AirPlayReceiver getInstance() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final int[] status = new int[9];
    private final int[] lastStatus = new int[4];
    private final AirPlayHlsPlayer hls = new AirPlayHlsPlayer();
    private final Runnable poll = this::pollStatus;
    private NsdManager discovery;
    private final ArrayList<Registration> registrations = new ArrayList<>();
    private int currentMode = MODE_OFF;
    private String receiverName;
    private Boolean hevcMirroring;
    private Listener listener;

    private AirPlayReceiver() {}

    void setListener(Listener value) { listener = value; }

    int getCurrentMode() { return currentMode; }

    String getReceiverName() { return receiverName; }

    private void pollStatus() {
        if (currentMode == MODE_OFF) return;
        boolean wasHls = hls.active();
        NativeCore.airplayStatus(status);
        if (hls.update(status, currentMode == 1) && !wasHls) {
            NativeCore.airplaySetSurface(null);
        }
        main.postDelayed(poll, status[0] == IDLE ? 150 : 32);
        if (status[0] != lastStatus[0] || status[1] != lastStatus[1]
            || status[2] != lastStatus[2] || status[3] != lastStatus[3]) {
            System.arraycopy(status, 0, lastStatus, 0, lastStatus.length);
            if (listener != null) listener.onVideoState(status[0], status[1], status[2], status[3]);
        }
    }

    void disconnect(Context context) {
        int mode = currentMode;
        stop();
        start(context, mode);
    }

    boolean start(Context context, int mode) {
        if (mode < MODE_ALL || mode > MODE_OFF) return false;
        if (mode == currentMode) return true;
        stop();
        if (mode == MODE_OFF) return true;

        byte[] id = deviceId(context);
        String name = String.format(java.util.Locale.ROOT, "pdmvOS %02X%02X", id[4] & 255, id[5] & 255);
        try {
            byte[] records = NativeCore.airplayStart(mode, name.getBytes(StandardCharsets.UTF_8), id, mode != 2 && supportsHevcMirroring() ? 1 : 0);
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
                main.post(poll);
                return true;
            }
        } catch (RuntimeException | LinkageError failure) {
            Log.e(TAG, "Could not start AirPlay", failure);
        }
        stop();
        return false;
    }

    void stop() {
        main.removeCallbacks(poll);
        hls.clear();
        hls.setSurface(null);
        for (Registration registration : registrations) {
            try { discovery.unregisterService(registration); }
            catch (IllegalArgumentException removed) { /* Registration already ended. */ }
        }
        registrations.clear();
        if (currentMode != MODE_OFF) NativeCore.airplayStop();
        currentMode = MODE_OFF;
        receiverName = null;
        if (lastStatus[0] != IDLE && listener != null) listener.onVideoState(IDLE, 0, 0, 0);
        java.util.Arrays.fill(lastStatus, 0);
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

    void setSurface(Surface surface) {
        hls.setSurface(surface);
        NativeCore.airplaySetSurface(hls.active() ? null : surface);
    }

    private boolean supportsHevcMirroring() {
        if (hevcMirroring == null) {
            try {
                hevcMirroring = findHevcDecoder();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not query HEVC decoders", error);
                hevcMirroring = false;
            }
        }
        return hevcMirroring;
    }

    private static boolean findHevcDecoder() {
        for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder() || codec.getName().startsWith("OMX.google.")
                || codec.getName().startsWith("c2.android.")) continue;
            if (android.os.Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated()) continue;
            for (String type : codec.getSupportedTypes()) {
                if (!type.equalsIgnoreCase("video/hevc")) continue;
                MediaCodecInfo.VideoCapabilities caps = codec.getCapabilitiesForType(type).getVideoCapabilities();
                if (caps != null && caps.areSizeAndRateSupported(1920, 1080, 60)) return true;
            }
        }
        return false;
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
