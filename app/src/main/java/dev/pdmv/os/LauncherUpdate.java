package dev.pdmv.os;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/** GitHub release lookup and verified staging, with state transitions on the main thread. */
final class LauncherUpdate {
    private static final String REPO = "pdmv/pdmvOS";
    private static final String RELEASES = "https://github.com/" + REPO + "/releases/";
    private static final int MAX_APK = 64 * 1024 * 1024;
    private static final long CHECK_INTERVAL = TimeUnit.DAYS.toMillis(1);
    private static final String TAG = "pdmvOS-update";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> new Thread(task, TAG));
    private static final long RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(15);

    enum State { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLING, CHECK_FAILED, DOWNLOAD_FAILED }

    private record Release(String version, long code, int bytes, String hash, String signer, String apkUrl) {}
    private interface Work { void run() throws Exception; }

    private final Context context;
    private final SharedPreferences preferences;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private long lastAttempt;
    private String failure = "";
    private Release release;
    private State state = State.IDLE;
    private int progress;

    LauncherUpdate(Context context, SharedPreferences preferences, Runnable changed) {
        this.context = context.getApplicationContext();
        this.preferences = preferences;
        this.changed = changed;
    }

    State state() { return state; }
    String version() { return release == null ? "" : release.version(); }
    int progress() { return progress; }
    String failure() { return failure; }

    boolean shouldAnnounce() {
        if (state != State.AVAILABLE || preferences.getLong("update_announced", 0) >= release.code()) return false;
        preferences.edit().putLong("update_announced", release.code()).apply();
        return true;
    }

    void checkIfDue() {
        long elapsed = System.currentTimeMillis() - preferences.getLong("update_checked_at", 0);
        if ((elapsed < 0 || elapsed >= CHECK_INTERVAL)
                && (lastAttempt == 0 || android.os.SystemClock.elapsedRealtime() - lastAttempt >= RETRY_INTERVAL)) check();
    }

    void check() {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.VERIFYING
                || state == State.READY || state == State.INSTALLING) return;
        lastAttempt = android.os.SystemClock.elapsedRealtime();
        release = null;
        setState(State.CHECKING);
        run(State.CHECK_FAILED, () -> {
            PackageManager packages = context.getPackageManager();
            PackageInfo installed = packages.getPackageInfo(context.getPackageName(), signingFlags());
            cleanCache(null, false);
            Release latest = findReleaseWithRetry(installed);
            ensureOpen();
            boolean staged = latest != null && recoverStagedApk(latest);
            cleanCache(latest, true);
            post(() -> {
                preferences.edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
                release = latest;
                setState(staged ? State.READY : latest == null ? State.CURRENT : State.AVAILABLE);
            });
        });
    }

    void download() {
        if (state != State.AVAILABLE && state != State.DOWNLOAD_FAILED) return;
        Release candidate = release;
        progress = 0;
        setState(State.DOWNLOADING);
        run(State.DOWNLOAD_FAILED, () -> {
            File directory = UpdateApkProvider.directory(context);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
            if (directory.getUsableSpace() < candidate.bytes()) {
                throw new UpdateFailure("TV không đủ dung lượng để tải bản cập nhật.");
            }
            File apk = UpdateApkProvider.apk(context, candidate.hash());
            File staging = File.createTempFile("download-", ".part.apk", directory);
            try {
                downloadApk(candidate, staging, percent -> post(() -> {
                    progress = percent;
                    changed.run();
                }));
                verifyPackage(staging, candidate);
                ensureOpen();
                if (!staging.renameTo(apk)) throw new IOException("Cannot stage verified APK");
                post(() -> setState(State.READY));
            } finally {
                staging.delete();
            }
        });
    }

    void prepareInstall(Consumer<Uri> ready) {
        if (state != State.READY) return;
        Release candidate = release;
        setState(State.VERIFYING);
        run(State.DOWNLOAD_FAILED, () -> {
            if (!recoverStagedApk(candidate)) {
                throw new UpdateFailure("Tệp cập nhật đã mất hoặc không còn hợp lệ. Hãy tải lại.");
            }
            post(() -> {
                setState(State.READY);
                ready.accept(UpdateApkProvider.uri(context, candidate.hash()));
            });
        });
    }

    void installerOpened() { setState(State.INSTALLING); }

    void installerClosed() {
        if (state == State.INSTALLING) setState(State.READY);
    }

    private void ensureOpen() throws InterruptedIOException {
        if (closed) throw new InterruptedIOException("Launcher closed");
    }

    private boolean recoverStagedApk(Release candidate) throws Exception {
        File apk = UpdateApkProvider.apk(context, candidate.hash());
        if (!apk.isFile()) return false;
        try {
            if (!matchesDigest(apk, candidate)) throw new IOException("Staged APK digest differs");
            verifyPackage(apk, candidate);
            return true;
        } catch (Exception error) {
            ensureOpen();
            apk.delete();
            return false;
        }
    }

    private boolean matchesDigest(File apk, Release candidate) throws Exception {
        if (apk.length() != candidate.bytes()) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int total = 0;
        try (InputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                ensureOpen();
                total += count;
                if (total > candidate.bytes()) return false;
                digest.update(buffer, 0, count);
            }
        }
        return total == candidate.bytes() && candidate.hash().equals(hex(digest.digest()));
    }

    private void cleanCache(Release keep, boolean removeOldApks) {
        File[] entries = UpdateApkProvider.directory(context).listFiles();
        new File(context.getCacheDir(), "pdmvOS-update.apk").delete();
        if (entries == null) return;
        String keepName = keep == null ? "" : keep.hash() + ".apk";
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith("download-") && name.endsWith(".part.apk")
                    || removeOldApks && name.matches("[a-f0-9]{64}\\.apk") && !name.equals(keepName)) {
                entry.delete();
            }
        }
    }

    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
    }

    private void setState(State next) {
        state = next;
        Log.i(TAG, "state=" + next);
        changed.run();
    }

    private void run(State errorState, Work work) {
        IO.execute(() -> {
            if (closed) return;
            try {
                work.run();
            } catch (Exception error) {
                if (closed) return;
                String cause = error instanceof HttpFailure http ? "HTTP " + http.status : error.getClass().getSimpleName();
                Log.w(TAG, "Update failed: " + cause);
                post(() -> {
                    failure = error instanceof UpdateFailure ? error.getMessage()
                        : isTransient(error) ? "Mất kết nối với GitHub. Hãy thử lại."
                        : errorState == State.CHECK_FAILED ? "Không đọc được bản phát hành. Hãy thử lại."
                        : "Không tải hoặc xác minh được tệp cập nhật. Hãy thử lại.";
                    setState(errorState);
                });
            }
        });
    }

    private void post(Runnable action) {
        main.post(() -> { if (!closed) action.run(); });
    }

    private static final class UpdateFailure extends IOException {
        UpdateFailure(String message) { super(message); }
    }

    private static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { this.status = status; }
    }

    private static boolean isTransient(Exception error) {
        return error instanceof SocketException || error instanceof SocketTimeoutException
            || error instanceof UnknownHostException || error instanceof HttpFailure http && http.status >= 500;
    }

    private Release findReleaseWithRetry(PackageInfo installed) throws Exception {
        try {
            return findRelease(installed);
        } catch (IOException error) {
            if (!isTransient(error)) throw error;
            Thread.sleep(1000);
            ensureOpen();
            return findRelease(installed);
        }
    }

    private Release findRelease(PackageInfo installed) throws Exception {
        String tag = latestTag();
        String version = tag.substring(1);
        String base = RELEASES + "download/" + tag + "/pdmvOS-" + version;
        JSONObject info = readJson(base + ".release.json", 65536);
        long code = info.getLong("versionCode");
        int bytes = info.getInt("bytes");
        String hash = info.getString("sha256");
        String signer = info.getString("signerSha256");
        if (!version.equals(info.getString("version")) || code <= 0
                || bytes <= 0 || bytes > MAX_APK || !hash.matches("[a-f0-9]{64}")
                || !signer.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid release metadata");
        }
        if (code <= versionCode(installed)) return null;
        if (info.getInt("minSdk") > Build.VERSION.SDK_INT) throw new UpdateFailure("Bản cập nhật yêu cầu Android mới hơn.");
        if (!signer.equals(certificate(installed))) throw new UpdateFailure("Chữ ký bản phát hành không khớp với pdmvOS đang cài.");
        return new Release(version, code, bytes, hash, signer, base + ".apk");
    }

    private void verifyPackage(File apk, Release release) throws Exception {
        PackageManager packages = context.getPackageManager();
        // Android 9 collects archive certificates only when GET_SIGNATURES is also present.
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getPath(),
            signingFlags() | PackageManager.GET_SIGNATURES);
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), signingFlags());
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)
                || versionCode(candidate) != release.code() || versionCode(candidate) <= versionCode(installed)
                || !release.version().equals(candidate.versionName)
                || !release.signer().equals(certificate(candidate))
                || !release.signer().equals(certificate(installed))) {
            throw new IOException("APK identity or version verification failed");
        }
    }

    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
            ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signatures == null || signatures.length != 1) throw new IOException("Expected one APK signer");
        return hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }

    private void downloadApk(Release release, File staging, IntConsumer progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection connection = connect(release.apkUrl());
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(staging)) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0, lastPercent = 0;
            long started = android.os.SystemClock.elapsedRealtime();
            for (int count; (count = input.read(buffer)) != -1;) {
                ensureOpen();
                total += count;
                if (total > release.bytes() || android.os.SystemClock.elapsedRealtime() - started > 300_000) {
                    throw new IOException("APK transfer exceeded its size or time limit");
                }
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                int percent = (int) (99L * total / release.bytes());
                if (percent >= lastPercent + 3) {
                    progress.accept(percent);
                    lastPercent = percent;
                }
            }
            if (total != release.bytes() || !release.hash().equals(hex(digest.digest()))) {
                throw new UpdateFailure("Tệp tải về không khớp bản phát hành. Hãy tải lại.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(String url, int limit) throws Exception {
        HttpURLConnection connection = connect(url);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long started = android.os.SystemClock.elapsedRealtime();
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > limit || android.os.SystemClock.elapsedRealtime() - started > 45_000) {
                    throw new IOException("Release metadata exceeded its size or time limit");
                }
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static String latestTag() throws IOException {
        URL latest = new URL(RELEASES + "latest");
        HttpURLConnection connection = openConnection(latest);
        try {
            int status = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (status < 300 || status >= 400 || location == null) throw new HttpFailure(status);
            String target = new URL(latest, location).toExternalForm();
            String prefix = RELEASES + "tag/";
            if (target.startsWith(prefix)) {
                String tag = target.substring(prefix.length());
                if (tag.matches("v[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")) return tag;
            }
            throw new IOException("Unexpected GitHub release redirect");
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        if (!"https".equals(url.getProtocol())) throw new IOException("HTTPS required");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "pdmvOS-launcher");
        return connection;
    }

    private static HttpURLConnection connect(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = openConnection(url);
            boolean connected = false;
            try {
                int status = connection.getResponseCode();
                if (status == 200) {
                    connected = true;
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || location == null) throw new HttpFailure(status);
                url = new URL(url, location);
            } finally {
                if (!connected) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] value = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            value[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            value[i * 2 + 1] = digits[bytes[i] & 0xf];
        }
        return new String(value);
    }
}
