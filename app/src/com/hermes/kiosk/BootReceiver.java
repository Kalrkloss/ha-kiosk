package com.hermes.kiosk;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the dashboard after a reboot, plus an optional companion service. */
public class BootReceiver extends BroadcastReceiver {
    static final String TAG = "KIOSK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        Log.i(TAG, "boot broadcast " + action);
        startKiosk(context);
        startCompanionService(context);
    }

    private void startKiosk(Context context) {
        Intent start = new Intent(context, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(start);
        } catch (Exception e) {
            Log.e(TAG, "boot start failed " + e);
        }
    }

    /**
     * Starts the companion service configured with
     *   adb shell am broadcast -a com.hermes.kiosk.SET_BOOT_SERVICE --es component org.example.app/.PlayerService
     * The component has to be exported by its own app. Lock task mode does not block service
     * starts, so a media player keeps running behind the locked dashboard. Empty = feature off.
     */
    static void startCompanionService(Context context) {
        String spec = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.PREF_BOOT_SERVICE, "");
        if (spec == null || spec.length() == 0) return;
        ComponentName component = ComponentName.unflattenFromString(spec);
        if (component == null) {
            Log.e(TAG, "unusable boot service spec: " + spec);
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(component);
        try {
            ComponentName started = context.startService(intent);
            Log.i(TAG, "companion service start -> " + started);
        } catch (Exception e) {
            Log.e(TAG, "companion service start failed " + e);
        }
    }
}
