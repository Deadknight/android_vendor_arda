# ARDA AAOS/AAP Handover — 2026-07-25

## 1. Executive state

The current Raspberry Pi 4 AAOS device is operational and stable with the latest ARDA runtime fixes.

Validated on the device after the final recovery:

- Android boot ID remained unchanged for 60 seconds.
- `com.android.car.carlauncher` PID remained unchanged for 60 seconds.
- The `com.android.systemui` PID set remained unchanged for 60 seconds.
- No `FATAL EXCEPTION` was present for CarLauncher or CarSystemUI.
- No ConstraintSet child-ID crash was present.
- No `ARDA_VM_CAR_SERVICE_RESTART_PASS` occurred during boot replay.
- The left-bottom Home button works in the current runtime.
- Repressing Home on the Launcher toggles Launcher cards rather than sending the embedded Maps task to the back.

The current device must not be redeployed merely to reproduce this state. The purpose of the associated commit script is source/document synchronization only.

## 2. Environment

- Build host: `edo@192.168.1.55`
- AOSP root: `/home/edo/aosp-rpi-aaos16`
- Lunch target: `aosp_rpi4_car-bp4a-userdebug`
- Product: `aosp_rpi4_car`
- Platform: Android 16 / AAOS
- Build ID observed: `BP4A.251205.006`
- Primary USB ADB serial: `1000000028c0b59c`
- Network ADB: `192.168.1.185:5555`
- Network fallback: `192.168.1.15:5555`

Project rule: **never run `repo sync`**. Work only in the existing local source tree with targeted changes.

## 3. Vehicle profile boot replay fix — V19.5

### Root cause

Persisted vehicle-profile replay was applying VHAL values and then unconditionally restarting:

- `com.android.car`
- Google Maps

This happened during early boot while CarSystemUI and CarSettings were still establishing CarService connections. Killing CarService caused a secondary CarSystemUI null dereference in `CarSystemUIProxyImpl.onConnected()`.

### Implemented behavior

File:

`vendor/arda/vehicle/manager/src/com/arda/vehicle/manager/VehicleInfoController.java`

Boot replay now:

1. Reapplies the persisted VHAL vehicle profile.
2. Updates the manager's last-applied state.
3. Does **not** restart CarService.
4. Does **not** restart Maps.

Manual profile Apply keeps the existing consumer-refresh behavior after the system is already running.

Expected boot markers:

```text
ARDA_VM_BOOT_REPLAY_NO_CONSUMER_RESTART user=10
ARDA_VM_VEHICLE_INFO_APPLY_PASS ... consumers_refreshed=false maps_restarted=false
```

Validated result:

```text
BOOT_REPLAY_CAR_SERVICE_RESTART_COUNT=0
CAR_AND_SYSTEMUI_STABLE_60_SECONDS=PASS
```

## 4. Launcher Home-card toggle and clock fix — V19.4E

### Intended Home behavior

From another application:

- First Home press performs normal Home navigation and returns to CarLauncher.

While CarLauncher and its embedded Maps task are already visible:

- First Home repress hides only the Launcher media and weather cards.
- The embedded Maps surface remains visible and expands into the released card area.
- Second Home repress restores the media and weather cards.

The top status bar and the persistent automotive navigation controls remain visible. This is not immersive fullscreen.

### Root cause of the white Maps area

The earlier Home routing used the Home button selected state as the main decision:

```java
homeButton.getSelected()
```

When the embedded Maps task had focus, the Home button could report `selected=false` even though the Launcher surface was visible. The normal Home action then moved the embedded Maps task to the back, leaving the Launcher map container visible as a white area.

### Implemented behavior

The routing now checks whether the Launcher surface is visible and whether the foreground/embedded task belongs to the Launcher/Maps home surface. In that state it sends the protected Launcher card-toggle broadcast instead of invoking normal Home navigation.

Important markers:

```text
ARDA_HOME_SURFACE_ACTIVE
ARDA_LAUNCHER_MAP_FULLSCREEN
ARDA_LAUNCHER_HOME_CARDS_HIDDEN
```

Relevant source areas:

- `packages/apps/Car/Launcher/app/src/com/android/car/carlauncher/CarLauncher.java`
- `packages/apps/Car/Launcher/app/res/layout/car_launcher.xml`
- `packages/apps/Car/Launcher/app/res/layout-land/car_launcher.xml`
- `packages/apps/Car/SystemUI/src/com/android/systemui/car/systembar/HomeButtonController.java`
- `packages/apps/Car/SystemUI/res/values/dimens.xml`
- `packages/apps/Car/SystemUI/res/values/styles.xml`
- `packages/apps/Car/SystemUI/res/layout/car_top_system_bar.xml`

Clock runtime resource:

```text
status_bar_clock_size = 22sp
```

A direct `FocusParkingView` in the Launcher root has an ID so ConstraintSet cloning does not crash.

## 5. Apparent boot loop after V19.4E deployment

V19.4E CarLauncher and CarSystemUI APKs were successfully built and written to their system paths.

The first deployment appeared to cause a boot/UI loop. An attempted rollback restored older source files but Ninja printed:

```text
ninja: no work to do
```

The rollback used timestamp-preserving copies, so the old sources did not trigger a rebuild. The same V19.4E APKs were written again.

That recovery path also removed stale preoptimized artifacts:

- `/system/priv-app/CarLauncher/oat`
- `/system_ext/priv-app/CarSystemUI/oat`

After reboot, the existing V19.4E runtime became stable and Home behavior worked. The strongest current explanation is a stale APK/preopt mismatch, not a persistent V19.4E Java crash. This is an evidence-based inference, not a proven low-level root cause.

Future direct APK replacements for these privileged apps should remove their stale `oat` directories before reboot.

## 6. Source/runtime synchronization

The working V19.4E sources were preserved before the unsuccessful source rollback at:

```text
/home/edo/arda-source-backups/20260725_164539-v19-4e-failed-source-rescue
```

The source-only restore copies those exact rescued files back into the Ubuntu AOSP tree without building or deploying.

This handover/commit operation verifies the V19.4E markers before committing. If the markers are absent, it restores the rescue files first.

## 7. ArdaPlatform status

The current Platform APK is not rolled back.

The 0.7.1 Platform UI source fixes the Apply workflow so map layout options are held as draft UI state and are persisted only by Apply. Reload restores persisted state.

The Platform source package is maintained separately from these Ubuntu AOSP commits:

```text
ArdaPlatform_0.7.1_LAUNCHER_LAYOUT_APPLY_FIX_20260725.zip
```

Do not recreate or reapply older Platform UI patches over that source snapshot.

## 8. USB Handler note

`android.car.usb.handler` was disabled for users 0 and 10 during diagnosis. It was ultimately not the cause of the white Maps area.

Current package policy should be reviewed separately. Re-enabling it may restore USB host-management UI behavior; leaving it disabled avoids that UI. Do not conflate this package with the Launcher embedded-task issue.

## 9. Graphics/AAP state retained

Previously validated ARDA graphics/AAP behavior remains conceptually separate from these UI changes:

- Late mapper binding is used.
- No SurfaceFlinger, allocator, or Codec2 restart is used for mapper activation.
- AAP prewarm occurs after mapper readiness.
- The selected encoder remains `c2.v4l2.avc.encoder`.
- `max_frames=0`.
- 720p screen recording previously passed.

Do not broaden UI fixes into AOSP-owned hardware encoder repositories without an explicit requirement.

## 10. Commit scope produced by the companion script

The script commits only these logical changes:

1. Vehicle manager boot replay fix.
2. CarLauncher Home-card/embedded-Maps behavior.
3. CarSystemUI Home routing and clock resources.
4. This handover document.

It does not:

- run `repo sync`
- build
- deploy an APK
- use ADB
- reboot the device
- push to a remote
- commit unrelated dirty or staged files

Generated commit references:

- Vehicle manager: `5c95fe744b1e3766984c4307dae60f7d2eef89cd`
- CarLauncher: `08e47c725a8634281e5086d8b08c5cdc32a5c8d0`
- CarSystemUI: `b4ea8902ed7baa4a949c6df461e2efabee9aeb7f`
- Handover document: committed separately after code commits
- Commit operation timestamp: `20260725_172844`

## 11. Recommended next validation

Before any future deployment:

1. Confirm all targeted repositories have the expected source commits.
2. Build only the affected modules.
3. Replace the privileged APKs atomically.
4. Remove stale module-specific `oat` directories.
5. Reboot once.
6. Check boot ID and Launcher/SystemUI PIDs for at least 60 seconds.
7. Test Home from another app and Home repress twice on the Launcher.
8. Check logcat for:
   - `FATAL EXCEPTION`
   - CarLauncher/SystemUI process declarations
   - ConstraintSet child-ID errors
   - unexpected `ARDA_VM_CAR_SERVICE_RESTART_PASS`

## 12. Non-negotiable workflow constraints

- Never run `repo sync`.
- Do not claim a push unless a push command actually succeeds.
- Do not claim a device deployment when the operation is source-only.
- Do not restart CarService during persisted vehicle-profile boot replay.
- Do not replace the stable Platform APK merely to align it with an older source snapshot.
- Keep changes targeted and preserve unrelated local work.
