package com.hermes.kiosk;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Host-side control/inspection of the motion wake feature (adb broadcasts):
 *
 *   adb shell am broadcast -a com.hermes.kiosk.STATUS
 *   adb shell am broadcast -a com.hermes.kiosk.TEST_WAKE          # same code path as camera motion
 *   adb shell am broadcast -a com.hermes.kiosk.SET_THRESHOLD --ei v 10
 *   adb shell am broadcast -a com.hermes.kiosk.SET_URL --es url http://host:8123/lovelace
 *   adb shell am broadcast -a com.hermes.kiosk.SET_BOOT_SERVICE --es component org.example.app/.PlayerService
 *   adb shell am broadcast -a com.hermes.kiosk.RUN_BOOT_SERVICE
 *   adb shell am broadcast -a com.hermes.kiosk.HIDE_APP --es pkgs com.a,com.b      # device owner only
 *   adb shell am broadcast -a com.hermes.kiosk.UNHIDE_APP --es pkgs com.a
 */
public class ControlReceiver extends BroadcastReceiver {
    static final String TAG = "KIOSK";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        MotionService svc = MotionService.instance;
        if ("com.hermes.kiosk.HIDE_APP".equals(action) || "com.hermes.kiosk.UNHIDE_APP".equals(action)) {
            hidePackages(context, action, intent);
            return;
        }
        if ("com.hermes.kiosk.SET_BOOT_SERVICE".equals(action)) {
            String component = intent.getStringExtra("component");
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(MainActivity.PREF_BOOT_SERVICE, component == null ? "" : component)
                    .apply();
            Log.i(TAG, "SET_BOOT_SERVICE " + component);
            return;
        }
        if ("com.hermes.kiosk.RUN_BOOT_SERVICE".equals(action)) {
            Log.i(TAG, "RUN_BOOT_SERVICE");
            BootReceiver.startCompanionService(context);
            return;
        }
        if ("com.hermes.kiosk.SET_THRESHOLD".equals(action)) {
            int v = intent.getIntExtra("v", MotionService.threshold);
            MotionService.threshold = v;
            Log.i(TAG, "threshold=" + v);
            if (svc != null) svc.note("threshold=" + v);
            return;
        }
        if ("com.hermes.kiosk.SET_UA".equals(action)) {
            String ua = intent.getStringExtra("ua");
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(MainActivity.PREF_USER_AGENT, ua == null ? "" : ua).apply();
            Log.i(TAG, "SET_UA " + ua);
            log(context, "SET_UA " + (ua == null ? "(stock)" : ua));
            return;
        }
        if ("com.hermes.kiosk.EVAL".equals(action)) {
            String js = intent.getStringExtra("js");
            // Base64 form: adb re-parses a shell command line, so anything with quotes, brackets
            // or pipes gets mangled on the way in; encoded JS survives untouched.
            String b64 = intent.getStringExtra("b64");
            if (b64 != null) {
                try {
                    js = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
                } catch (Exception e) {
                    log(context, "EVAL b64 decode failed " + e);
                    return;
                }
            }
            Log.i(TAG, "EVAL");
            MainActivity act = MainActivity.instance;
            if (act != null && js != null) {
                act.evalAndLog(js);
            } else {
                log(context, "EVAL ohne laufende Activity");
            }
            return;
        }
        if ("com.hermes.kiosk.SET_URL".equals(action)) {
            String url = intent.getStringExtra("url");
            Log.i(TAG, "SET_URL " + url);
            if (MainActivity.instance != null) {
                MainActivity.instance.applyUrl(url);
            } else {
                context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                        .edit().putString(MainActivity.PREF_URL, url).apply();
            }
            return;
        }
        if ("com.hermes.kiosk.TEST_WAKE".equals(action)) {
            Log.i(TAG, "TEST_WAKE broadcast");
            if (svc != null) {
                svc.wakeScreen("broadcast");
            } else {
                Log.e(TAG, "TEST_WAKE: service not running");
            }
            return;
        }
        Log.i(TAG, "STATUS " + MotionService.lastStatus
                + " threshold=" + MotionService.threshold
                + " wakes=" + MotionService.wakes
                + " service=" + (svc != null));
        if (svc != null) svc.note("status " + MotionService.lastStatus);
    }

    /**
     * Hides (or shows) packages as the device owner. Hiding is the only way to get rid of
     * preinstalled apps without root: the shell cannot change component state on many vendor ROMs
     * ("Permission Denial ... uid=2000"), and pm hide needs MANAGE_USERS.
     */
    private void hidePackages(Context context, String action, Intent intent) {
        boolean hide = "com.hermes.kiosk.HIDE_APP".equals(action);
        String list = intent.getStringExtra("pkgs");
        if (list == null) list = intent.getStringExtra("pkg");
        if (list == null || list.length() == 0) {
            log(context, "no packages given");
            return;
        }
        android.app.admin.DevicePolicyManager dpm =
                (android.app.admin.DevicePolicyManager)
                        context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, KioskAdmin.class);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            log(context, "not device owner, cannot hide packages");
            return;
        }
        String[] packages = list.split(",");
        for (int i = 0; i < packages.length; i++) {
            String pkg = packages[i].trim();
            if (pkg.length() == 0) continue;
            try {
                dpm.setApplicationHidden(admin, pkg, hide);
                log(context, (hide ? "hidden " : "shown ") + pkg
                        + " -> hidden=" + dpm.isApplicationHidden(admin, pkg));
            } catch (Exception e) {
                log(context, (hide ? "hide " : "show ") + pkg + " FAILED: " + e);
            }
        }
    }

    /** Logcat on these devices evicts app lines within seconds, so results also go to a file. */
    static void log(Context context, String msg) {
        Log.i(TAG, msg);
        try {
            java.io.FileOutputStream fos = context.openFileOutput("control.log",
                    Context.MODE_APPEND);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "log write failed " + e);
        }
    }
}
