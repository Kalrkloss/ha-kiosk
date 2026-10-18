# Operations

Every command here is adb-only, no root. `<pkg>` is `com.hermes.kiosk`.

## Have a look at the device first

```bash
adb shell getprop ro.build.version.sdk        # 23 = Android 6.0
adb shell wm size                             # tap coordinates depend on this
adb shell dumpsys activity activities | grep mFocusedActivity     # what is in front
adb shell dumpsys activity | grep mLockTaskModeState              # LOCKED / NONE
adb shell dumpsys device_policy | head -5                         # device owner present?
adb shell dumpsys window | grep mShowingLockscreen                # keyguard off?
adb shell dumpsys power | grep mWakefulness                       # Awake / Asleep
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png    # look at it
```

## Runtime commands

```bash
# dashboard URL (persisted; intent data -d <url> overrides it for one launch)
adb shell am broadcast -a com.hermes.kiosk.SET_URL --es url http://host:8123/lovelace

# companion service: another app's exported service, started after every boot
adb shell am broadcast -a com.hermes.kiosk.SET_BOOT_SERVICE --es component org.example.app/.PlayerService
adb shell am broadcast -a com.hermes.kiosk.SET_BOOT_SERVICE --es component ""   # turn it off
adb shell am broadcast -a com.hermes.kiosk.RUN_BOOT_SERVICE                      # start it now

# motion wake
adb shell am broadcast -a com.hermes.kiosk.STATUS                     # metric, threshold, wakes, screen
adb shell am broadcast -a com.hermes.kiosk.SET_THRESHOLD --ei v 5     # default 7, lower = more sensitive
adb shell am broadcast -a com.hermes.kiosk.TEST_WAKE                  # same code path as real motion

# service log (debug build)
adb shell run-as <pkg> cat files/motion.log
```

`STATUS` output example after a successful wake:

```
status metric=4 threshold=7 hits=0 screen=true wakes=3
```

## Removing preinstalled apps

```bash
adb shell am broadcast -a com.hermes.kiosk.HIDE_APP --es pkgs com.google.android.youtube,com.android.chrome
adb shell am broadcast -a com.hermes.kiosk.UNHIDE_APP --es pkgs com.google.android.youtube
adb shell run-as <pkg> cat files/control.log        # what actually happened
adb shell dumpsys package <pkg> | grep "User 0:"    # hidden=true ?
```

## Provisioning and un-provisioning

```bash
adb shell dumpsys account | grep -c "Account {"     # must be 0 for device owner
adb shell dpm set-device-owner <pkg>/.KioskAdmin

# release the lock-down (test it once after installing - then re-provision)
adb shell am start -n <pkg>/.MainActivity --ez kiosk_clear_device_owner true
adb shell dpm set-device-owner <pkg>/.KioskAdmin
```

A plain `adb install -r` of a new build keeps the device-owner status, so iterative development
needs no re-provisioning.

## Verifying that it really does what it claims

These are the checks that were run on real hardware; run them again after any change.

```bash
# 1. it is in front, in lock task, without system bars
adb shell dumpsys activity activities | grep mFocusedActivity        # -> <pkg>/.MainActivity
adb shell dumpsys activity | grep mLockTaskModeState                 # -> mLockTaskModeState=LOCKED
adb shell screencap -p /sdcard/a.png && adb pull /sdcard/a.png       # no status/nav bar in the image

adb shell input keyevent 3    # Home
adb shell input keyevent 187  # Recents
adb shell input keyevent 4    # Back
adb shell dumpsys activity activities | grep mFocusedActivity        # still the kiosk

# 2. screen off after the configured idle time, and no self-inflicted wake
adb shell settings get system screen_off_timeout                     # 60000
sleep 90
adb shell dumpsys power | grep mWakefulness                          # -> Asleep
adb shell run-as <pkg> cat files/motion.log | tail -3                # metric low, screen=false, no wake

# 3. the camera -> metric -> wake chain, without a human in front of the lens
adb shell input keyevent 26                                          # screen off
adb shell am broadcast -a com.hermes.kiosk.SET_THRESHOLD --ei v 0    # every sample counts as motion
adb shell dumpsys power | grep mWakefulness                          # -> Awake
adb shell run-as <pkg> cat files/motion.log                          # "wake camera motion (metric 0)"
adb shell am broadcast -a com.hermes.kiosk.SET_THRESHOLD --ei v 7    # restore sensitivity

# 4. no phantom wakes: empty the log, leave the device alone for 10+ minutes
adb shell run-as <pkg> rm -f files/motion.log
# ... wait ...
adb shell run-as <pkg> cat files/motion.log | grep -c wake       # expect 0

# 5. survives a reboot: unplug/re-plug or
adb reboot
adb shell dumpsys activity activities | grep mFocusedActivity        # kiosk back within ~30 s
```

The one thing that cannot be automated: whether real movement in front of the lens is detected.
Step in front of the device and move; if nothing happens, lower `SET_THRESHOLD` and watch the metric
reported by `STATUS` while moving.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Sidebar renders, main area stays empty | Frontend shell loaded before the view; the app reloads after the login redirect. Check that the cache mode is `LOAD_DEFAULT`, not `LOAD_CACHE_ELSE_NETWORK`. |
| Login page instead of the dashboard | Home Assistant has no trusted-network rule and the WebView profile is empty — log in on the device once with "keep me logged in". |
| `Failure [INSTALL_FAILED_OLDER_SDK]` | The APK's `minSdkVersion` is above the device's API level. This app is built for API 23, so a *newer* device is fine, an older one is not. |
| `dpm set-device-owner` refuses | The device has accounts configured, or another device owner is already set (`dumpsys device_policy`). Remove the accounts / the old owner. |
| Home button works despite lock task | Not provisioned as device owner — `startLockTask()` without it only pins. |
| Screen never turns off | `svc power stayon true` (stay awake while charging) is still set, or something holds a screen wake lock. |
| Motion never wakes the screen | Threshold too high (`STATUS` shows the live metric while you move), camera permission missing, or the device is unplugged and dozing. |
| Wakes on its own every couple of minutes | The settle suppression after a panel state change is missing or too short, or the threshold is below the sensor noise floor. A panel going dark shifts exposure and is not motion. |
| Wakes immediately after going dark | Same cause: the exposure settle window is too short. |
| An app comes back after hiding it | It was hidden while running, or the ROM refuses to hide it (`hidden=false` in the log, e.g. GMS on some ROMs) - force-stop it as well. |
| Player/media app disappears after a while | Low-memory kill. Check `adb shell 'cat /proc/meminfo | head -2'`, force-stop unused preinstalled apps (or hide them) and confirm the watchdog restarts the companion within a minute (`files/control.log`). |
| Companion app never starts | Its service is not `exported`, or it requires a permission, or the component string is wrong (`pkg/.Service`). Check `adb shell dumpsys package <pkg>` and the kiosk log tag `KIOSK`. |
| Logcat lines missing | Expected on these devices — the ring buffer is tiny. Use `files/motion.log` via `run-as` (debug build). |
