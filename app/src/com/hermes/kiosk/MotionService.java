package com.hermes.kiosk;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.FileOutputStream;
import java.util.List;

/**
 * Watches the front camera and wakes the screen when something moves in front of it.
 *
 * The screen itself is switched off by the normal power policy (screen_off_timeout = 60 s);
 * this service only has to hold the CPU (partial wake lock) so preview frames keep arriving
 * while the panel is dark, and to turn the screen back on when the picture changes.
 *
 * API 23 only: no notification channels, Camera1 API with a dummy SurfaceTexture as the
 * preview target (no real surface, so nothing dies when the screen goes off).
 * No anonymous inner classes / generic-interface implementations: d8 8.2 crashes on those.
 */
public class MotionService extends Service {
    static final String TAG = "KIOSK";

    public static volatile MotionService instance;
    public static volatile String lastStatus = "not started";
    public static volatile int threshold = 7;
    public static volatile long wakes = 0;

    Camera camera;
    Camera.Parameters params;
    PreviewSink sink;
    SurfaceTexture dummy;
    int width = 320, height = 240;
    byte[] previous;
    long lastAnalysis;
    int consecutive;
    boolean screenWasOn = true;
    long suppressUntil;
    long lastWake;
    /** After the panel changes state the camera's exposure settles for a few seconds and looks
     *  like motion; ignore everything in that window. Also bounds the wake rate. */
    static final long SETTLE_MS = 15000;
    static final long MIN_WAKE_INTERVAL_MS = 20000;
    PowerManager.WakeLock partial;
    PowerManager.WakeLock screenWaker;
    android.net.wifi.WifiManager.WifiLock wifiLock;

    /** Named static callback (d8-safe). */
    static class PreviewSink implements Camera.PreviewCallback {
        private final MotionService svc;
        PreviewSink(MotionService svc) { this.svc = svc; }

        @Override public void onPreviewFrame(byte[] data, Camera cam) {
            svc.onFrame(data, cam);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        Foreground foreground = new Foreground(this);
        startForeground(1, foreground.build());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        partial = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kiosk:cpu");
        partial.setReferenceCounted(false);
        screenWaker = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "kiosk:wake");
        screenWaker.setReferenceCounted(false);

        // Keep the radio associated while the panel is dark. Measured on the reference device:
        // with the screen off the ROM powers the WiFi down (despite wifi_sleep_policy=2), which
        // silently kills the dashboard and any companion player on the network.
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kiosk:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                note("wifi lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "wifi lock failed " + e);
        }

        openCamera();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    /** Minimal ongoing notification (no channels on API 23). */
    static class Foreground {
        private final MotionService svc;
        Foreground(MotionService svc) { this.svc = svc; }
        Notification build() {
            Notification.Builder b = new Notification.Builder(svc);
            b.setContentTitle("HA Kiosk");
            b.setContentText("Motion wake active");
            b.setSmallIcon(android.R.drawable.ic_menu_view);
            b.setOngoing(true);
            if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_MIN);
            return b.build();
        }
    }

    void openCamera() {
        try {
            int id = frontCameraId();
            Log.i(TAG, "camera id=" + id);
            camera = Camera.open(id);
            params = camera.getParameters();
            Camera.Size size = pickSize(params.getSupportedPreviewSizes());
            if (size != null) {
                width = size.width;
                height = size.height;
                params.setPreviewSize(width, height);
            }
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            try {
                List<int[]> ranges = params.getSupportedPreviewFpsRange();
                if (ranges != null && !ranges.isEmpty()) {
                    int[] best = ranges.get(0);
                    for (int i = 0; i < ranges.size(); i++) {
                        if (ranges.get(i)[0] >= 5000 && ranges.get(i)[0] < best[0]) best = ranges.get(i);
                    }
                    params.setPreviewFpsRange(best[0], best[1]);
                }
            } catch (Exception e) {
                Log.i(TAG, "fps range not settable " + e);
            }
            camera.setParameters(params);
            dummy = new SurfaceTexture(1);
            camera.setPreviewTexture(dummy);
            sink = new PreviewSink(this);
            camera.setPreviewCallbackWithBuffer(sink);
            for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[width * height * 3 / 2]);
            camera.startPreview();
            partial.acquire();
            lastStatus = "camera " + width + "x" + height + " running";
            Log.i(TAG, "preview started " + width + "x" + height);
            note("camera started " + width + "x" + height);
        } catch (Exception e) {
            lastStatus = "camera failed: " + e;
            Log.e(TAG, "openCamera failed " + e);
            note("camera failed " + e);
            scheduleReopen();
        }
    }

    int frontCameraId() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i;
        }
        return 0;
    }

    Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null) return null;
        Camera.Size best = null;
        for (int i = 0; i < sizes.size(); i++) {
            Camera.Size s = sizes.get(i);
            if (s.width * s.height < 300 * 200) continue;
            if (best == null || s.width * s.height < best.width * best.height) best = s;
        }
        return best == null ? sizes.get(0) : best;
    }

    void scheduleReopen() {
        new Retry(this).start();
    }

    /** Retries opening the camera after a failure. */
    static class Retry extends Thread {
        private final MotionService svc;
        Retry(MotionService svc) { this.svc = svc; }
        @Override public void run() {
            try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
            svc.openCamera();
        }
    }

    void onFrame(byte[] data, Camera cam) {
        try {
            long now = SystemClock.uptimeMillis();
            if (now - lastAnalysis >= 1000 && data != null && data.length >= width * height) {
                lastAnalysis = now;
                boolean screenOn = isScreenOn();
                if (screenWasOn != screenOn) {
                    // panel just changed state: exposure settles, do not trust the next seconds
                    suppressUntil = now + SETTLE_MS;
                    screenWasOn = screenOn;
                    note("screen " + (screenOn ? "on" : "off") + " -> suppress " + (SETTLE_MS / 1000) + "s");
                }
                int metric = difference(data);
                if (now < suppressUntil) {
                    consecutive = 0;
                } else if (metric >= threshold) {
                    consecutive++;
                } else {
                    consecutive = 0;
                }
                lastStatus = "metric=" + metric + " threshold=" + threshold
                        + " hits=" + consecutive + " screen=" + screenOn + " wakes=" + wakes
                        + " suppress=" + Math.max(0, suppressUntil - now) + "ms";
                if (consecutive >= 2 && now - lastWake >= MIN_WAKE_INTERVAL_MS) {
                    consecutive = 0;
                    lastWake = now;
                    note("motion metric=" + metric);
                    wakeScreen("camera motion (metric " + metric + ")");
                }
                if (previous == null || previous.length != data.length) {
                    previous = new byte[data.length];
                }
                System.arraycopy(data, 0, previous, 0, Math.min(data.length, previous.length));
                CompanionWatch.INSTANCE.tick(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "onFrame " + e);
        } finally {
            if (cam != null) {
                try { cam.addCallbackBuffer(data); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    /** Mean absolute difference of the Y plane, sampled on a grid. */
    int difference(byte[] data) {
        if (previous == null || previous.length != data.length) return 0;
        int step = 8;
        long sum = 0;
        int n = 0;
        for (int y = 0; y < height; y += step) {
            int row = y * width;
            for (int x = 0; x < width; x += step) {
                int i = row + x;
                sum += Math.abs((data[i] & 0xff) - (previous[i] & 0xff));
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isScreenOn();
    }

    void wakeScreen(String why) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        // The panel normally stays powered (dark), so motion only has to un-dim the UI.
        MainActivity activity = MainActivity.instance;
        if (activity != null) {
            activity.undimPanel("motion");
        }
        if (pm.isScreenOn()) {
            Log.i(TAG, "motion, panel already powered (" + why + ")");
            return;
        }
        wakes++;
        suppressUntil = SystemClock.uptimeMillis() + SETTLE_MS;
        lastStatus = "wake: " + why;
        try {
            screenWaker.acquire(20000);
            Log.i(TAG, "MOTION -> screen wake (" + why + ")");
            note("wake " + why);
        } catch (Exception e) {
            Log.e(TAG, "wake failed " + e);
        }
    }

    /** Append one line to files/motion.log (readable via adb run-as once the app is debuggable). */
    void note(String msg) {
        try {
            FileOutputStream fos = openFileOutput("motion.log", MODE_APPEND);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "note " + e);
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        instance = null;
        try { if (camera != null) { camera.setPreviewCallbackWithBuffer(null); camera.stopPreview(); camera.release(); } }
        catch (Exception e) { Log.e(TAG, "camera release " + e); }
        try { if (partial != null && partial.isHeld()) partial.release(); } catch (Exception e) { /* ignore */ }
        try { if (screenWaker != null && screenWaker.isHeld()) screenWaker.release(); } catch (Exception e) { /* ignore */ }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception e) { /* ignore */ }
    }
}
