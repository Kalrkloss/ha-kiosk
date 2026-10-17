# HA Kiosk

A fullscreen Home Assistant dashboard kiosk for **old Android tablets** — built for and verified
on Android 6.0 (API 23), unrooted. It turns a device the vendor abandoned into a wall panel that
boots itself back into the dashboard, cannot be swiped out of, turns its panel off when nobody is
around and wakes up when somebody steps in front of the camera.

Why this exists: current kiosk apps (and the current Flutter toolchain in general) require
Android 7 or newer, so a 2015-era tablet cannot run them. This app is deliberately written against
API 23 and uses nothing that needs root.

## Features

| Feature | How |
|---|---|
| Fullscreen dashboard | Immersive system-UI flags; status and navigation bar hidden, re-applied on focus |
| Locked to the dashboard | Device owner + lock task mode: Home, Recents and Back do not leave the app |
| No lock screen | Device owner disables keyguard and status bar |
| Survives reboot | `BOOT_COMPLETED` receiver starts the dashboard |
| Survives a dead dashboard | Failed loads and the login redirect trigger a reload |
| Screen off when idle | The *system* screen timeout is used (default guidance: 60 s); the app sets no keep-awake flag |
| Wake on motion | A foreground service watches the front camera and turns the screen back on when the picture changes |
| Configurable at runtime | Dashboard URL, motion sensitivity and status can be set/read over adb (see below) |
| Reversible | An adb-triggered escape hatch clears device-owner status and lock task |

The panel showing an empty main area (sidebar only) right after the login redirect, and the trap of
`LOAD_CACHE_ELSE_NETWORK`, are both handled — see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

- Android 6.0 / API 23 or newer, `arm64-v8a` or `armeabi-v7a` (there is no native code, any ABI works)
- An up-to-date **Android System WebView** — the dashboard is a WebView app. On API 23 the last
  WebView/Chrome release is 106.0.5249.126; verify with the app's console log or a probe page.
- A reachable Home Assistant instance and a user to log in once (the session cookie then persists)
- For the kiosk features: no accounts on the device (required by `dpm set-device-owner`)
- For the motion wake: a front camera

## Build

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk   # needs build-tools + one platform
keytool -genkeypair -keystore kiosk.keystore -alias kiosk -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass changeit -keypass changeit -dname "CN=Kiosk"
./build.sh
```

`DEBUG=1 ./build.sh` adds `android:debuggable="true"`, which enables `adb shell run-as` so the
service log file can be read from the host. Never publish a debug build.

No Gradle is involved: the script drives `aapt2`, `javac`, `d8`, `zipalign` and `apksigner`
directly, and injects `classes.dex` with a small python3 snippet (so no `zip` binary is needed).

## Install and provision

```bash
adb install -r ha-kiosk.apk

# point the kiosk at your dashboard (or pass -d <url> on the am start below)
adb shell am broadcast -a com.hermes.kiosk.SET_URL --es url http://your-host:8123/lovelace

# camera permission for the motion wake (targetSdk 23 => runtime permission)
adb shell pm grant com.hermes.kiosk android.permission.CAMERA

# screen off after 60 s of idle, and do not keep the screen on just because it is charging
adb shell settings put system screen_off_timeout 60000
adb shell svc power stayon false

# real kiosk mode: device owner (fails if the device has accounts configured)
adb shell dpm set-device-owner com.hermes.kiosk/.KioskAdmin
adb shell am start -n com.hermes.kiosk/.MainActivity
```

Then log in to Home Assistant on the device once, with *keep me logged in* enabled — the session is
stored in the app's WebView data and survives reboots.

## Operations

Everything below is adb-only and needs no root.

```bash
# move the dashboard elsewhere at runtime
adb shell am broadcast -a com.hermes.kiosk.SET_URL --es url http://host:8123/lovelace

# motion wake: read state, tune sensitivity, simulate a detection
adb shell am broadcast -a com.hermes.kiosk.STATUS
adb shell am broadcast -a com.hermes.kiosk.SET_THRESHOLD --ei v 5    # default 7, lower = more sensitive
adb shell am broadcast -a com.hermes.kiosk.TEST_WAKE

# service log (debug builds only)
adb shell run-as com.hermes.kiosk cat files/motion.log

# release the kiosk lock-down - note --ez (boolean); a plain -e string extra reads as false
adb shell am start -n com.hermes.kiosk/.MainActivity --ez kiosk_clear_device_owner true

# re-provision after that
adb shell dpm set-device-owner com.hermes.kiosk/.KioskAdmin
```

Detailed recipes and what each command proves: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Limitations

- **Android 6/7 era only.** `targetSdkVersion` is pinned to 23 on purpose: no runtime-permission
  prompts, and background activity starts still work. Raising it changes that behaviour.
- **Camera motion needs power.** Unplugged, Android 6 doze (idle + stationary) suspends the wake
  locks and the preview loop, so the wake stops working. A permanently powered kiosk is unaffected.
  Keeping a camera open is also a constant drain, which is the price of camera-based detection.
- **Verification is from the device.** Startup, lock task, fullscreen, reboot autostart and the
  wake chain were each checked on real hardware over adb; see docs/OPERATIONS.md for the commands.
- `dpm set-device-owner` fails if the device has accounts (Google, mail, …). Don't set one up.

## License

MIT — see [LICENSE](LICENSE).
