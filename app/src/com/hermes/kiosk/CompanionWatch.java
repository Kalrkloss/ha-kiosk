package com.hermes.kiosk;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Keeps the optional companion service alive.
 *
 * On a low-memory device the low-memory killer reaps background processes even when they run a
 * foreground service - measured on a 1.8 GB tablet where the dashboard WebView alone is ~200 MB.
 * This watchdog checks every minute whether the companion's process still exists and restarts its
 * exported service if it is gone, so a music player survives a kill instead of staying dead until
 * the next reboot.
 *
 * Deliberately conservative: two consecutive misses (>= 1 min apart) and at most one restart
 * attempt every five minutes, so a player that is merely slow to appear is never restarted while
 * it is actually playing.
 */
final class CompanionWatch {
    private static final String TAG = "KIOSK";
    private static final long CHECK_INTERVAL_MS = 60000;
    private static final long MIN_RESTART_INTERVAL_MS = 300000;
    private static final int MISSES_BEFORE_RESTART = 2;

    private long lastCheck;
    private long lastRestart;
    private int misses;

    private CompanionWatch() { }

    static final CompanionWatch INSTANCE = new CompanionWatch();

    /** Called about once per second from the motion service tick. */
    void tick(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) return;
        lastCheck = now;
        Check checker = new Check(context, this);
        checker.start();
    }

    /** d8-safe named thread. */
    static class Check extends Thread {
        private final Context context;
        private final CompanionWatch watch;

        Check(Context context, CompanionWatch watch) {
            this.context = context.getApplicationContext();
            this.watch = watch;
        }

        @Override public void run() {
            try {
                String spec = watch.spec(context);
                if (spec.length() == 0) return;
                ComponentName component = ComponentName.unflattenFromString(spec);
                if (component == null) return;
                String pkg = component.getPackageName();
                boolean alive = watch.processRunning(pkg);
                if (alive) {
                    watch.misses = 0;
                    return;
                }
                watch.misses++;
                ControlReceiver.log(context, "companion " + pkg + " missing (" + watch.misses + ")");
                if (watch.misses < MISSES_BEFORE_RESTART) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - watch.lastRestart < MIN_RESTART_INTERVAL_MS) return;
                watch.lastRestart = now;
                watch.misses = 0;
                BootReceiver.startCompanionService(context);
                ControlReceiver.log(context, "companion restart requested: " + spec);
            } catch (Exception e) {
                Log.e(TAG, "companion check failed " + e);
            }
        }
    }

    String spec(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String spec = prefs.getString(MainActivity.PREF_BOOT_SERVICE, "");
        return spec == null ? "" : spec;
    }

    /** True if the package's process shows up in the process list. */
    boolean processRunning(String pkg) {
        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "/system/bin/ps" });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(pkg)) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "ps failed " + e);
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception e) { /* ignore */ }
            try { if (process != null) process.destroy(); } catch (Exception e) { /* ignore */ }
        }
        return false;
    }
}
