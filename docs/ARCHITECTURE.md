# Architecture

Six small classes, no dependencies, no Gradle. It is written for API 23 and for a device where
root is not available, so every mechanism is either a public API or a documented device-owner API.

```
MainActivity        the WebView, fullscreen flags, device-owner policies, lock task, escape hatch
KioskAdmin          DeviceAdminReceiver - registering this as device owner unlocks lock task mode
BootReceiver        starts the dashboard after a reboot
MotionService       foreground service: camera preview analysis, screen wake
ControlReceiver     adb-triggered commands (status, wake, threshold, url)
```

## Dashboard (MainActivity)

- **URL resolution** — intent data first (`am start -d <url>`), then a stored preference
  (`SET_URL` broadcast), then a generic default. No deployment-specific host is compiled in.
- **WebView settings** — JavaScript, DOM storage and database enabled (Home Assistant needs all
  three), media playback without a user gesture, and `LOAD_DEFAULT` cache mode.
  `LOAD_CACHE_ELSE_NETWORK` is a trap here: it serves expired entries, and a stale `index.html`
  pointing at rotated asset hashes renders a single-page app with its shell but an empty view.
- **Login redirect** — after `/auth/authorize` or `auth_callback` the frontend can finish loading
  with an empty main area; the app reloads the dashboard once, three seconds later.
- **Failure retry** — `onReceivedError` schedules a reload every 10 s until the page loads.
- **Fullscreen** — the immersive system-UI flags are re-applied on resume and on every focus change,
  because the keyboard and system dialogs reset them.

## Lock-down (device owner + lock task)

`dpm set-device-owner` makes the app a device owner, which is what turns "I hope nobody presses
Home" into "Home does nothing":

- `setLockTaskPackages(admin, {ownPackage})` plus `startLockTask()` in `onResume` — lock task mode.
  Home, Recents and Back are swallowed. Without device owner the same call would only pin the app
  behind a confirmation dialog, which a user can cancel.
- `setStatusBarDisabled(admin, true)` and `setKeyguardDisabled(admin, true)` (both API 23) — no
  status bar and no swipe lock in front of the dashboard.
- `launchMode="singleTask"` + `excludeFromRecents` keeps exactly one kiosk task alive.
- `onBackPressed` reloads the dashboard instead of finishing the activity.

**Reversibility.** `dpm remove-active-admin` refuses a non-test device owner, so the app carries its
own exit: the `kiosk_clear_device_owner` boolean extra makes it call
`clearDeviceOwnerApp(getPackageName())` and finish. It is handled in `onCreate` *and* `onNewIntent`,
because with `singleTask` a second `am start` is delivered to the running instance. Use `--ez` on the
command line; `-e` sends a string and `getBooleanExtra` then returns false.

## Startup

`BOOT_COMPLETED` (plus the vendor `QUICKBOOT_POWERON`) starts `MainActivity` with
`FLAG_ACTIVITY_NEW_TASK`. On API 23 a background broadcast may still start an activity, which is one
more reason `targetSdkVersion` stays at 23.

## Screen off and motion wake

The split of responsibility matters: **the app never holds the screen awake**, and the panel is
switched off by the normal power policy after `screen_off_timeout` (60 s in the reference setup).
Two settings make that work on a device that is permanently charging:

```
settings put system screen_off_timeout 60000
svc power stayon false          # a charging device would otherwise never sleep
```

`MotionService` is a foreground service that:

1. opens the **front camera** through the Camera1 API at the smallest preview size of at least
   320x240 and throttles analysis to about one frame per second;
2. uses a dummy `SurfaceTexture` as the preview target — there is no window surface to lose when
   the panel turns off, which is exactly what would break a SurfaceView-based preview;
3. holds a `PARTIAL_WAKE_LOCK` so frames keep arriving while the screen is dark, and an ongoing
   `PRIORITY_MIN` notification (no channel needed on API 23) so the process is not killed;
4. computes the **mean absolute difference of the luma plane** against the previous analysed frame,
   sampled on an 8-pixel grid. Two *consecutive* samples above the threshold (default 7 of 255)
   count as motion;
5. turns the screen back on with `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` (deprecated, but
   the only public way; a device owner cannot switch a screen *on*). Release after 20 s.

Three guards keep this from waking the screen by itself, and all three are needed:

1. **Settle suppression** — whenever the panel changes state (on -> off or off -> on) the camera's
   exposure and the room's illumination take a few seconds to settle, which a frame comparison
   reads as movement. Everything is ignored for 15 s after such a change.
2. **Wake rate limit** — no more than one wake per 20 s.
3. **Two consecutive samples** above the threshold.

Measured on the reference device: with only the consecutive-sample rule the kiosk woke itself
~14 times in 15 minutes (panel off -> exposure shift -> wake -> panel on -> ...). With the
suppression window and the rate limit, an idle room produces no wakes at all. Verify it that way
rather than by eye: clear the log, leave the device alone, then count `wake` lines.

## Companion service

A locked kiosk cannot launch another app's activity (the system refuses it with lock task mode
active), but **service starts are not restricted**. So the boot receiver also starts an optional
companion service whose component is stored in a preference:

```
adb shell am broadcast -a com.hermes.kiosk.SET_BOOT_SERVICE --es component org.example.app/.PlayerService
```

The component must be `exported` by its own app and must not require a permission. A media player
started this way calls `startForeground()` itself and keeps streaming behind the locked dashboard -
verified with the Lyrion Squeezelite Android player: it reconnects and plays after a reboot without
anyone touching the device. `RUN_BOOT_SERVICE` triggers the same code path on demand.

## Host-side control and evidence

`ControlReceiver` exposes `STATUS`, `TEST_WAKE`, `SET_THRESHOLD` and `SET_URL` as broadcast actions,
and `MotionService` appends events to `files/motion.log`. Together they make the feature inspectable
without a screen and without a debugger — logcat on these devices is a small ring buffer that system
spam evicts within seconds, so the file is the reliable channel (a debug build is needed for
`adb shell run-as` to read it).

Deliberate API-23-only choices: `Notification.Builder` without a channel, Camera1 instead of
Camera2, background activity start from a receiver, deprecated screen wake locks.
