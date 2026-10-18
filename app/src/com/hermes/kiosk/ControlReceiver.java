package com.hermes.kiosk;

import android.content.BroadcastReceiver;
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
 */
public class ControlReceiver extends BroadcastReceiver {
    static final String TAG = "KIOSK";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        MotionService svc = MotionService.instance;
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
}
