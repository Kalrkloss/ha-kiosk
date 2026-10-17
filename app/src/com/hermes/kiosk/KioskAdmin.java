package com.hermes.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/** Device-admin receiver; registering this as device owner enables lock task mode. */
public class KioskAdmin extends DeviceAdminReceiver {
    static final String TAG = "KIOSK";

    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(TAG, "device admin enabled");
        Toast.makeText(context, "Kiosk admin enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.i(TAG, "device admin disabled");
    }
}
