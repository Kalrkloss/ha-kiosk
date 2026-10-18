package com.hermes.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Fullscreen dashboard kiosk for old Android tablets (API 23, no root).
 *
 * - immersive fullscreen, status and navigation bar hidden
 * - dashboard URL from the intent, a stored preference, or a generic default
 * - retries the dashboard whenever a load fails, and again after the login redirect
 * - boot autostart (see BootReceiver)
 * - when installed as device owner: lock task mode, status bar and keyguard disabled
 * - the panel uses the system screen timeout; MotionService wakes it on camera motion
 *
 * Deliberately no anonymous inner classes and no generic type arguments on implemented
 * interfaces: the d8 shipping with build-tools 34 crashes on those class files.
 */
public class MainActivity extends Activity {
    static final String TAG = "KIOSK";
    static final String PREFS = "kiosk";
    static final String PREF_URL = "url";
    /** Optional exported service of another app to start after boot (see BootReceiver). */
    static final String PREF_BOOT_SERVICE = "boot_service";
    /** Generic default; the real dashboard URL is normally set once (see docs/OPERATIONS.md). */
    static final String FALLBACK_URL = "http://homeassistant.local:8123/lovelace";
    static final int RETRY_MS = 10000;

    /** Only used so the control receiver can push a new URL into a running kiosk. */
    static volatile MainActivity instance;

    WebView web;
    String url;
    final Handler handler = new Handler();
    Reloader reloader;
    boolean loadFailed = false;
    boolean released = false;

    /** d8-safe named Runnable used for the load retries. */
    static class Reloader implements Runnable {
        private final MainActivity owner;
        Reloader(MainActivity owner) { this.owner = owner; }

        @Override public void run() {
            if (owner.web == null) return;
            Log.i(TAG, "reload " + owner.url);
            owner.web.loadUrl(owner.url);
        }
    }

    static class KioskClient extends WebViewClient {
        private final MainActivity owner;
        KioskClient(MainActivity owner) { this.owner = owner; }

        @Override public void onPageFinished(WebView view, String u) {
            Log.i(TAG, "pageFinished " + u);
            owner.loadFailed = false;
            // The Home Assistant frontend can finish loading its shell with an empty main view
            // right after the login redirect; one reload clears that.
            if (u != null && (u.contains("/auth/authorize") || u.contains("auth_callback"))) {
                Log.i(TAG, "login redirect -> reload dashboard");
                owner.handler.postDelayed(owner.reloader, 3000);
            }
        }

        @Override public void onReceivedError(WebView view, int code, String desc, String u) {
            Log.e(TAG, "loadError " + code + " " + desc + " url=" + u);
            owner.loadFailed = true;
            owner.scheduleRetry();
        }
    }

    static class KioskChrome extends WebChromeClient {
        @Override public boolean onConsoleMessage(ConsoleMessage cm) {
            Log.i(TAG, "console " + cm.message());
            return true;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        // Deliberately no FLAG_KEEP_SCREEN_ON: the system screen timeout turns the panel off
        // after the configured idle time, and MotionService wakes it again.

        Intent intent0 = getIntent();
        if (intent0 != null && intent0.getBooleanExtra("kiosk_clear_device_owner", false)) {
            releaseDeviceOwner();
            finish();
            return;
        }

        url = resolveUrl(intent0);
        Log.i(TAG, "start url=" + url + " sdk=" + Build.VERSION.SDK_INT);
        reloader = new Reloader(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // LOAD_DEFAULT: LOAD_CACHE_ELSE_NETWORK happily serves an expired index.html whose
        // referenced assets were rotated away, which renders a half-empty single page app.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 17) {
            s.setMediaPlaybackRequiresUserGesture(false);
        }
        web.setWebViewClient(new KioskClient(this));
        web.setWebChromeClient(new KioskChrome());
        web.setBackgroundColor(Color.BLACK);

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.loadUrl(url);

        applyDeviceOwnerPolicies();
        startMotionService();
        goFullscreen();
    }

    /** Intent data wins, then the stored preference, then the generic default. */
    String resolveUrl(Intent intent) {
        if (intent != null && intent.getDataString() != null
                && intent.getDataString().startsWith("http")) {
            return intent.getDataString();
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getString(PREF_URL, FALLBACK_URL);
    }

    /** Called by ControlReceiver for the SET_URL broadcast. */
    void applyUrl(String newUrl) {
        if (newUrl == null || !newUrl.startsWith("http")) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_URL, newUrl).apply();
        url = newUrl;
        Log.i(TAG, "url changed -> " + newUrl);
        if (web != null) web.loadUrl(newUrl);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra("kiosk_clear_device_owner", false)) {
            releaseDeviceOwner();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (released) return;
        goFullscreen();
        if (loadFailed) scheduleRetry();
        enterLockTaskIfPossible();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    void scheduleRetry() {
        handler.removeCallbacks(reloader);
        handler.postDelayed(reloader, RETRY_MS);
    }

    private void startMotionService() {
        try {
            startService(new Intent(this, MotionService.class));
            Log.i(TAG, "motion service start requested");
        } catch (Exception e) {
            Log.e(TAG, "startService failed " + e);
        }
    }

    private void goFullscreen() {
        View decor = getWindow().getDecorView();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decor.setSystemUiVisibility(flags);
    }

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager d = dpm();
        return d != null && d.isDeviceOwnerApp(getPackageName());
    }

    private void applyDeviceOwnerPolicies() {
        DevicePolicyManager d = dpm();
        boolean owner = isDeviceOwner();
        Log.i(TAG, "deviceOwner=" + owner);
        if (!owner) return;
        ComponentName admin = new ComponentName(this, KioskAdmin.class);
        try {
            d.setLockTaskPackages(admin, new String[] { getPackageName() });
            ControlReceiver.log(this, "lock task packages set: "
                    + java.util.Arrays.toString(d.getLockTaskPackages(admin)));
        } catch (Exception e) {
            ControlReceiver.log(this, "setLockTaskPackages FAILED: " + e);
        }
        try {
            // API 23: status bar and keyguard off, so no swipe-lock can gate the dashboard
            d.setStatusBarDisabled(admin, true);
            d.setKeyguardDisabled(admin, true);
        } catch (Exception e) {
            Log.e(TAG, "status bar / keyguard policy failed " + e);
        }
    }

    /**
     * Maintenance escape hatch, needs adb access:
     *   adb shell am start -n com.hermes.kiosk/.MainActivity --ez kiosk_clear_device_owner true
     * Note --ez (boolean); a plain -e string extra reads as false.
     */
    private void releaseDeviceOwner() {
        released = true;
        Log.i(TAG, "releaseDeviceOwner requested");
        DevicePolicyManager d = dpm();
        try {
            if (d != null) {
                try { stopLockTask(); } catch (Exception e) { Log.e(TAG, "stopLockTask " + e); }
                d.clearDeviceOwnerApp(getPackageName());
                Log.i(TAG, "device owner cleared");
            }
        } catch (Exception e) {
            Log.e(TAG, "clearDeviceOwnerApp failed " + e);
        }
        Toast.makeText(this, "Kiosk ownership released", Toast.LENGTH_LONG).show();
    }

    private void enterLockTaskIfPossible() {
        if (!isDeviceOwner()) return;
        try {
            startLockTask();
        } catch (Exception e) {
            Log.e(TAG, "startLockTask failed " + e);
        }
    }

    @Override
    public void onBackPressed() {
        // back must not leave the kiosk: reload the dashboard instead
        if (web != null) web.loadUrl(url);
        goFullscreen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(reloader);
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
