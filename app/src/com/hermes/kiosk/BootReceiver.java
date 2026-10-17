package com.hermes.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the dashboard after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    static final String TAG = "KIOSK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "null" : intent.getAction();
        Log.i(TAG, "boot broadcast " + action);
        Intent start = new Intent(context, MainActivity.class);
        start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(start);
        } catch (Exception e) {
            Log.e(TAG, "boot start failed " + e);
        }
    }
}
