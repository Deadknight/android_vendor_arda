# ARDA AAOS/AAP — Authoritative Handover

**Date:** 2026-07-25  
**Project:** `arda`  
**Status:** Core AAP/DHU/input/GNSS/VHAL, dynamic userspace adapter, persistent AAP prewarm, vehicle-profile routing, and the current CarLauncher/CarSystemUI runtime are operational under SELinux Enforcing. The 2026-07-25 device is stable with V19.4E Home routing and V19.5 non-disruptive vehicle-profile boot replay. Source-only targeted commits and this authoritative handover update are the next action; no device deployment is required.

---

## 1. Executive summary

The Raspberry Pi 4 AAOS stack now has these proven end-to-end paths:

```text
Phone / Android Auto
  -> USB accessory / AAP / TLS / SDR
  -> arda-senderd
  -> native H.264 video
  -> DHU
```

```text
AAP input
  -> arda-senderd
  -> abstract AF_UNIX InputBridge
  -> Android input stack
```

```text
Vehicle adapter
  -> ArdaVehicleManager
  -> vehicle router
  -> arda-vhal-proxyd
  -> IVehicle/default
  -> CarService
```

A normal third-party APK installed under `/data/app` was dynamically discovered as a vehicle adapter, shown as pending in ArdaPlatform, approved with exact publish/command grants, connected under `untrusted_app`, received a command, returned an ACK, and published confirmed state. No Manager/router/VHAL core change and no SD-card flash were required for the new adapter.

The current architecture therefore satisfies the plug-in requirement: a new adapter supplies its descriptor and implementation, while Manager/router remain adapter-independent.

A dedicated Google Maps EV Vehicle Energy Model handover also exists. The energy model must not be collapsed into a guessed VHAL mapping. The leading design is raw AAP field-23 inner-protobuf pass-through to the Maps OEM energy ContentProvider contract.

A separate performance track is active for Raspberry Pi 4 hardware H.264 encoding. The device exposes `/dev/video11`, registers `c2.v4l2.avc.encoder`, and runs the V4L2 Codec2 service. The unresolved blocker is the SurfaceFlinger/Codec2/minigbm input-buffer contract, not AAP framing or service registration. The selected implementation policy is vendor-only under `vendor/arda`; `external/v4l2_codec2` and `external/minigbm` must remain clean.

---

## 2. Canonical environment

### Ubuntu AOSP host

```text
host:       edo@192.168.1.55
AOSP root:  /home/edo/aosp-rpi-aaos16
lunch:      aosp_rpi4_car-bp4a-userdebug
Android:    16
Build ID:   BP4A.251205.006
```

Hard rule:

```text
repo sync must never be run
```

Relevant committed checkpoints:

```text
vendor/arda/ardaplatform:
  e8662e2 Update AAP runtime and automatic key management

vendor/arda:
  e87c9af Add AOAP USB accessory sink

external/v4l2_codec2:
  2bae40c ARDA: tolerate unsupported V4L2 framerate ioctl

external/minigbm:
  3824dd9 gbm_mesa: Add constraints for hardware video decoder & encoder

system/sepolicy:
  502093f026cce590144a1d8603c119c5ae4c98e6
```

### Mac projects

```text
aaos-aap:
/Users/edo/Documents/Code/aaos-aap

ArdaPlatform:
/Users/edo/Documents/Code/ArdaPlatform

Userspace command-test adapter:
/Users/edo/Documents/Code/ArdaVehicleUserspaceCommandAdapter
```

### Pi / ADB

```text
USB serial:       1000000028c0b59c
current network:  192.168.1.185:5555
runtime user:     10
```

The Pi address has changed during development. Do not reuse stale addresses such as `192.168.1.10` or `192.168.1.21` without checking `adb devices`.

During AOAP/accessory mode, USB ADB may disappear. Prefer network ADB for live AAP tests.

Do not run `adb root` or `adb unroot` while DHU/live AA is active.

---

## 3. Closed runtime gates

### 3.1 AAP / DHU

```text
USB accessory transition       PASS
AAP version negotiation 1.7    PASS
TLS handshake                  PASS
AUTH_COMPLETE                  PASS
Service discovery              PASS
Video channel setup/start      PASS
Sensor channel                 PASS
Native H.264 SPS/PPS/IDR       PASS
First video frame + ACK        PASS
DHU image                      PASS
```

The final video blocker was Android Codec2 SELinux labeling, not AAP, USB, DHU, senderd, or screen geometry.

### 3.2 InputBridge

```text
abstract socket name: arda_input_bridge
Java namespace:       ABSTRACT
Rust connection:      abstract AF_UNIX
SELinux domain:       arda_input_bridge_app
SO_PEERCRED root gate PASS
non-root rejection    PASS evidence
real tap              PASS
real swipe            PASS
real key              PASS
```

InputBridge is functionally closed.

Do not add broad `app_data_file` permissions.

### 3.3 Location / GNSS

Authoritative path:

```text
AAP SensorBatch.location_data
  -> ARDA location/GNSS bridge
  -> Android GPS_PROVIDER / IGnss path
  -> Android location stack
  -> fused consumers
```

Location must never become:

```text
VehicleProperty
router Wasm input
VHAL property
```

### 3.4 Sensor -> VHAL publication

Previously closed authoritative mappings include:

```text
SPEED          -> PERF_VEHICLE_SPEED
ODOMETER       -> PERF_ODOMETER
FUEL warning   -> FUEL_LEVEL_LOW
PARKING_BRAKE  -> PARKING_BRAKE_ON
GEAR           -> GEAR_SELECTION
NIGHT_MODE     -> NIGHT_MODE
TOLL_CARD      -> ELECTRONIC_TOLL_COLLECTION_CARD_STATUS
```

Fabricated values remain forbidden:

```text
PERF_VEHICLE_SPEED_DISPLAY
CURRENT_GEAR
invented FUEL_LEVEL
invented RANGE_REMAINING
```

---

## 4. Codec2 Enforcing fix

### Root cause

These Codec2 services were missing from platform `service_contexts`:

```text
android.hardware.media.c2.IComponentStore/ffmpeg
android.hardware.media.c2.IComponentStore/v4l2
```

These properties were also falling back to `default_prop`:

```text
ro.com.android.media.swcodec.ion.heapmask
ro.com.android.media.swcodec.ion.flags
ro.com.android.media.swcodec.ion.align
```

Under Enforcing, the stores could not register/read configuration and `screenrecord` stalled during encoder configuration.

Under Permissive, all stores registered and H.264 output succeeded. This A/B test proved the SELinux root cause.

### Changed AOSP repository

```text
/home/edo/aosp-rpi-aaos16/system/sepolicy
```

Changed files:

```text
private/service_contexts
private/property_contexts
build/soong/service_fuzzer_bindings.go
```

`private/hal_codec2.te` already contained the required narrow rule:

```te
get_prop(hal_codec2_server, codec2_config_prop)
```

No broad allow was added.

### Runtime result

```text
SELinux                         Enforcing
media.swcodec                   running
vendor.media.c2.ffmpeg          running
vendor.media.c2.v4l2            running
IComponentStore/software        registered
IComponentStore/ffmpeg          registered
IComponentStore/v4l2            registered
screenrecord under Enforcing    PASS
DHU video under Enforcing       PASS
```

### Git checkpoint

The functional build/flash/runtime gate passed and the authoritative `system/sepolicy` checkpoint is:

```text
502093f026cce590144a1d8603c119c5ae4c98e6
```

---

## 5. Vehicle Sources and dynamic userspace adapters

### 5.1 Core architecture

```text
Adapter APK/service
  -> ArdaVehicleManager
  -> router
  -> arda-vhal-proxyd
  -> CarService
```

ArdaPlatform is only:

```text
approval
configuration
status
revoke / disable / enable UI
```

It is not the high-rate data path.

Security model:

```text
default deny
exact publish-property grants
exact command-property grants
source identity binding
revoke/disable stops ownership
```

### 5.2 Userspace adapter proof

Test package:

```text
com.arda.vehicle.userspacecommand
```

Service:

```text
com.arda.vehicle.userspacecommand/.UserspaceCommandAdapterService
```

Install location:

```text
/data/app/...
```

Runtime SELinux domain:

```text
untrusted_app
```

Verified behavior:

```text
third-party /data APK               PASS
dynamic service discovery           PASS
pending approval state               PASS
exact publish grant                  PASS
exact command grant                  PASS
active Manager connection            PASS
command delivery                     PASS
command ACK                          PASS
confirmed-state publication          PASS
SELinux Enforcing                    PASS
Manager core change required         NO
router core change required          NO
VHAL proxy change required           NO
system image rebuild required        NO
SD-card flash required               NO
```

This closes the architectural question: a new adapter does not need to be hardcoded into Manager/router. The adapter itself must still implement its descriptor, command handler, ACK/reject behavior, and confirmed-state publication.

The userspace adapter may remain installed as a regression fixture.

Phase status:

```text
PHASE_3F0_USERSPACE_ADAPTER_ONBOARDING=PASS
PHASE_3F1_COMMAND_ROUND_TRIP=PASS
PHASE_3F_USERSPACE_ADAPTER_GATE=CLOSED
```

---

## 6. ArdaPlatform state and persistence

Mac source:

```text
/Users/edo/Documents/Code/ArdaPlatform
HEAD observed: c6f1ea7
versionName: 0.4.0-vehicle-sources-debug
label: ARDA Platform
```

Runtime APK:

```text
/system/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk
```

Persistent replacement result:

```text
provider authority present    PASS
user 0 enabled                PASS
user 10 enabled               PASS
current UI manually launched  PASS
```

Correct Activity component:

```text
com.github.deadknight.ardaplatform.debug/
com.github.deadknight.ardaplatform.MainActivity
```

Do not use this incorrect shorthand:

```text
com.github.deadknight.ardaplatform.debug/.MainActivity
```

The debug application ID contains `.debug`, but the Java/Kotlin Activity package does not.

### Canonical AOSP prebuilt and persistent runtime

Repository:

```text
/home/edo/aosp-rpi-aaos16/vendor/arda/ardaplatform
```

Canonical prebuilt:

```text
vendor/arda/ardaplatform/ArdaPlatform.apk
```

The persistent AAP runtime, automatic key-management integration, and current product configuration are committed and pushed:

```text
commit: e8662e2
message: Update AAP runtime and automatic key management
branch: main
working tree: clean
targeted build: PASS
```

The AOAP accessory sink is also committed and pushed in `vendor/arda`:

```text
commit: e87c9af
message: Add AOAP USB accessory sink
branch: arda-aaos16
working tree before HW-encoder work: clean
```

The previous `build/envsetup.sh: TOP: unbound variable` failure was resolved by not keeping `set -u` active while sourcing `envsetup.sh`. Re-running the targeted product build returned `ninja: no work to do` and completed successfully.

---

## 7. Google Maps Vehicle Energy Model track

A dedicated deep-dive handover exists:

```text
ARDA_MAPS_EV_HANDOVER.md
```

That document is the authoritative continuation guide for the Maps EV work. The main conclusions are summarized here so the general handover does not lose the track.

### 7.1 What is already known

AAP transports the Vehicle Energy Model in:

```text
SensorBatch field / sensor kind 23
SENSOR_VEHICLE_ENERGY_MODEL_DATA
```

The supplied `ford_ev_model.bin` is an outer serialized `SensorBatch` containing the nested field-23 energy-model protobuf. Maps should receive the inner model bytes, not the whole outer wrapper.

The actual Maps Car build on the Pi uses an OEM ContentProvider contract:

```text
Maps reads:   content://google_maps_energy/model
Maps writes:  content://google_maps_energy/forecast
```

Leading architecture:

```text
AAP SensorBatch field 23
  -> arda-senderd extracts exact nested bytes
  -> platform bridge
  -> provider authority google_maps_energy
  -> /model returns raw energy-model protobuf
  -> Google Maps Car
```

Forecast direction:

```text
Google Maps
  -> content://google_maps_energy/forecast
  -> ARDA captures opaque forecast protobuf initially
```

### 7.2 Critical architectural rule

Do not map the Vehicle Energy Model into:

```text
a guessed VehicleProperty
router Wasm
normal VHAL signal publication
```

This model is a rich vehicle-physics protobuf, including battery fields and large road-load/temperature/energy curves. It is not merely `EV_BATTERY_LEVEL` or `RANGE_REMAINING`.

### 7.3 Ford fixture

Known approximate values:

```text
battery level:     68378 Wh
battery capacity:  92403 Wh
expected SoC:      about 74%
```

The first compatibility test should preserve the raw nested bytes exactly and expose them at `/model`.

Do not re-encode, normalize, or edit road-load tables before raw pass-through is tested.

### 7.4 Maps gates to verify

Maps analysis indicates likely requirements:

```text
Maps runs as non-system Android user
CarInfo fuel types resolve to pure [ELECTRIC]
provider resolves for Maps
CAR_ENERGY permission boundary is correct
cross-user/provider visibility works
route is active for obvious BatteryOnArrival/Return UI
```

### 7.5 Next Vehicle Energy Model task

Implement the smallest provider PoC that:

```text
1. exposes content://google_maps_energy/model
2. returns the raw extracted Ford field-23 inner bytes
3. notifies Maps when the model changes
4. logs caller/URI/byte length without dumping the model
5. captures /forecast writes as opaque bytes
6. keeps SELinux and permissions narrow
```

Expected first UI/model proof:

```text
provider resolved
model opened
protobuf parsed
battery validation accepted
about 74% initial SoC
EV route UI / BatteryOnArrival path activated
```

Only after this proof should live AAP field-23 traffic be connected.

---

## 8. Raspberry Pi 4 hardware H.264 encoder track

### 8.1 Objective

The current AAP capture path uses:

```text
c2.android.avc.encoder
```

That is the software AVC encoder and is too slow for higher-resolution head units. The target is:

```text
c2.v4l2.avc.encoder
```

with hardware-first selection and a controlled software fallback.

Required performance profiles must be tested empirically:

```text
1280x720 at approximately 60 fps
1920x1080 at approximately 30 fps
1920x1080 at approximately 60 fps
```

Do not claim 1080p60 support until sustained runtime measurement passes. A raw H.264 stream has no reliable timestamps by itself, so FPS must be measured using continuous animation and frame counting, not a static screen or MPV's guessed 25 fps.

### 8.2 Proven device-side prerequisites

```text
/dev/video11                                      present
c2.v4l2.avc.encoder codec XML                     present
IComponentStore/v4l2                              registered
vendor.media.c2.v4l2                              running
android.hardware.media.c2-service-v4l2            running
allocator mapper                                  minigbm_gbm_mesa
allocator APEX                                    com.android.hardware.graphics.allocator.minigbm_gbm_mesa
```

The hardware component can be created and reaches V4L2 queue setup. The blocker is the graphic input-buffer path.

### 8.3 Failure matrix

#### Baseline / no input-usage override

SurfaceFlinger can allocate the encoder input surface, but V4L2 Codec2 later tries to CPU-map the incoming `RGBA_8888` block for conversion and minigbm rejects the lock:

```text
Attempted to lock() a buffer that was not allocated with a BufferUsage::CPU_* usage
C2AllocatorGralloc: failed transaction: lock(RGBA_8888)
V4L2EncodeComponent: Failed to convert input block
Encoder failed (err=-38)
```

#### `VIDEO_ENCODER | CPU_READ_OFTEN`

The CPU lock guard is avoided, but the combined usage cannot be allocated for the virtual display path. SurfaceFlinger repeatedly reports:

```text
ANativeWindow::dequeueBuffer failed
```

No frame reaches the encoder and the output remains zero bytes.

#### `VIDEO_ENCODER` only

Surface allocation succeeds, but the first RGBA-to-YUV conversion again fails at the strict minigbm CPU lock check. `screenrecord` exits with status `218`/`-38`, and the output remains zero bytes.

### 8.4 Architectural decision

Runtime Resource Overlay is not applicable because the fault is in native mapper/gralloc behavior, not an Android resource.

Permanent HW-encoder work must follow these rules:

```text
external/v4l2_codec2    clean
external/minigbm        clean
new implementation      vendor/arda only
```

The selected proof-of-concept path is a vendor-owned minigbm variant under:

```text
vendor/arda/hwencoder/minigbm
```

The copied variant keeps the strict lock guard for all normal buffers and adds a narrow exception only when the original Android usage contains:

```text
GRALLOC_USAGE_HW_VIDEO_ENCODER
```

This is deliberately narrower than disabling strict usage validation globally or switching the device to software rendering.

### 8.5 V8 vendor-only build result — 2026-07-24

Repository baselines at the start of V8:

```text
vendor/arda:
  branch arda-aaos16
  HEAD e87c9af

external/minigbm:
  branch arda-poc-checkpoint
  HEAD 3824dd9
  clean

external/v4l2_codec2:
  branch arda-aaos16
  HEAD 2bae40c
  clean
```

V8 then:

```text
copied 72 minigbm source/header files into vendor/arda
generated vendor/arda/hwencoder/minigbm/Android.bp
applied the narrow HW_VIDEO_ENCODER lock exception
built libarda_minigbm_gralloc_gbm_mesa successfully
installed build output at:
  out/target/product/rpi4/vendor/lib64/arda/libminigbm_gralloc_gbm_mesa.so
output size:
  approximately 133 KiB
AOSP-owned repositories modified:
  NO
device deployment performed:
  NO
runtime hardware test performed:
  NO
commit/push performed:
  NO
```

The script stopped at:

```text
FAIL: ARDA marker absent from built library
```

This result is not yet authoritative evidence that the marker is absent. The validator used:

```bash
strings "$LOCAL_LIB" | grep -Fq 'ARDA_MINIGBM_VIDEO_ENCODER_CPU_LOCK=ALLOW'
```

while `pipefail` was enabled. `grep -q` may exit immediately after a match, causing `strings` to receive `SIGPIPE` and the whole pipeline to return non-zero. This project already encountered the same false-negative pattern in an earlier binary validator.

The validator must be replaced with a non-SIGPIPE form, for example:

```bash
grep -aFq \
  'ARDA_MINIGBM_VIDEO_ENCODER_CPU_LOCK=ALLOW' \
  "$LOCAL_LIB"
```

or with `pipefail` disabled around a non-quiet `strings | grep` check.

### 8.6 Current repository/device state

```text
external/v4l2_codec2:
  clean

external/minigbm:
  clean

vendor/arda:
  uncommitted/untracked vendor-only tree under hwencoder/minigbm
  do not commit yet

Pi:
  no V8 allocator library deployed
  no V8 APEX bind mount active
  allocator/SurfaceFlinger/Codec2 remain on baseline binaries
  AAP service remains/restores to running
```

### 8.7 Exact continuation plan

1. Fix only the V8 marker validator.
2. Reuse or rebuild the existing vendor library.
3. Temporarily bind the vendor library over:

```text
/apex/com.android.hardware.graphics.allocator.minigbm_gbm_mesa/
  lib64/libminigbm_gralloc_gbm_mesa.so
```

4. Restart allocator, SurfaceFlinger, and V4L2 Codec2.
5. Run the 1280×720 hardware encode gate.
6. Require:
   - ARDA allow marker in runtime log,
   - non-empty H.264 output,
   - no `CPU_*` lock rejection,
   - no `Failed to convert input block`,
   - no SurfaceFlinger dequeue failure,
   - no `Encoder failed (err=-38)`.
7. Only after 1280×720 passes, test 720p60, 1080p30, then 1080p60.
8. Integrate hardware-first codec selection into `arda-senderd`, with software fallback.
9. Commit only the validated `vendor/arda` implementation and product integration.

---

## 9. Current next steps

### Immediate hardware-encoder continuation

1. Correct the V8 marker validation false-negative.
2. Continue the vendor-only allocator bind/runtime test; do not modify AOSP-owned repositories.
3. Close the 1280×720 hardware encode gate.
4. Measure 720p60, 1080p30, and 1080p60 with moving content.
5. Add hardware-first encoder selection and software fallback to `arda-senderd`.
6. Commit/push the `vendor/arda` HW-encoder implementation only after runtime and reboot/full-image gates pass.

### Parallel product tracks

- Continue the Maps Vehicle Energy Model provider PoC described above.
- Retain the userspace adapter as a regression fixture if useful.
- A production vehicle adapter can use the proven userspace adapter SDK; Manager/router must not be changed merely to onboard it.

---

## 10. Build/image warning

The latest system image build reported:

```text
system.img size: 4831838208
system partition limit: 4831838208
```

The build passed, but the image is exactly at the current limit. Future product APK growth may cause packaging failure. Do not casually add more system/product payload without planning partition headroom or removing unused content.

---

## 11. Non-negotiable constraints

- Never run `repo sync`.
- Preserve the proven AAP/TLS/SDR path in `arda-senderd`.
- Do not rewrite CarService.
- Do not map `location_data` into VHAL.
- Do not map the Vehicle Energy Model into a guessed VHAL property.
- Do not add broad `app_data_file`, `default_prop`, or `default_android_service` permissions.
- Keep ArdaPlatform out of the high-rate data path.
- Keep publish grants and command grants separate.
- Default deny remains mandatory.
- Use network ADB during live AOAP tests.
- Check SELinux after tools such as Flipper; it previously attempted to switch the device to Permissive.
- Do not claim a Git checkpoint until the actual commit SHA is captured.
- For the hardware-encoder track, keep `external/v4l2_codec2` and `external/minigbm` clean; use `vendor/arda` unless a later explicit architectural decision says otherwise.
- Do not globally weaken minigbm CPU-lock validation or force software rendering to make the encoder pass.

---

## 12. Canonical continuation documents

```text
ARDA_AUTHORITATIVE_HANDOVER_20260724.md
ARDA_MAPS_EV_HANDOVER.md
```

The first document is the general project status and now includes the vendor-only Raspberry Pi 4 hardware-encoder track. The second is the detailed Maps Vehicle Energy Model investigation and implementation plan.

---

## 13. 2026-07-25 authoritative delta

This section updates the 2026-07-24 handover without discarding any earlier project history. Where an older section conflicts with this section, the newer dated state below is authoritative.

### 13.1 Current device stability

The device was observed for 60 seconds after the final recovery:

```text
INITIAL_BOOT_ID=0b891084-93e3-404b-9567-cf660a43115f
FINAL_BOOT_ID=0b891084-93e3-404b-9567-cf660a43115f

INITIAL_LAUNCHER_PID=1699
FINAL_LAUNCHER_PID=1699

INITIAL_SYSTEMUI_PID=1406 1741
FINAL_SYSTEMUI_PID=1406 1741

BOOT_STABLE=PASS
LAUNCHER_STABLE=PASS
SYSTEMUI_STABLE=PASS
```

The filtered log contained none of these:

```text
FATAL EXCEPTION
Process: com.android.car.carlauncher
Process: com.android.systemui
All children of ConstraintLayout must have ids to use ConstraintSet
ARDA_VM_CAR_SERVICE_RESTART_PASS
```

Two SystemUI PIDs were present, but the same PID set remained stable and no fatal exception was logged. Do not treat the mere presence of two PIDs as a crash without a PID transition or fatal evidence.

### 13.2 Current ADB endpoints

```text
USB ADB:       1000000028c0b59c
network ADB:   192.168.1.185:5555
fallback ADB:  192.168.1.15:5555
runtime user:  10
```

Reboot/deploy scripts must resolve ADB again after reboot. A successful APK push, atomic move, `sync`, and reboot can be followed by a verification failure merely because the script continues with a stale USB serial while the USB gadget is re-enumerating.

---

## 14. Persistent AAP mapper prewarm closure

The Raspberry Pi 4 hardware-encoder track progressed beyond the V8 state documented above.

### 14.1 Late mapper binding

Validated mapper source:

```text
/vendor/lib64/egl/arda/libminigbm_gralloc_gbm_mesa.so
```

Validated runtime target:

```text
/apex/com.android.hardware.graphics.allocator.minigbm_gbm_mesa/
  lib64/libminigbm_gralloc_gbm_mesa.so
```

Required label:

```text
u:object_r:same_process_hal_file:s0
```

Current architecture:

```text
boot
-> late mapper bind
-> mapper ready
-> AAP prewarm
-> AAP runtime
```

The mapper is not activated by restarting SurfaceFlinger, allocator, or Codec2.

### 14.2 AAP prewarm behavior

```text
restartable service: yes
restart period:       2 seconds
service seclabel:     u:r:su:s0
max_frames:           0
selected codec:       c2.v4l2.avc.encoder
```

Validated gate:

```text
720p screenrecord PASS
```

V18.23 added once-per-process marker behavior. The known late-callback warning was deliberately left untouched because it was not a functional blocker.

Relevant checkpoints recorded during this phase:

```text
vendor/arda:    155d68...
ArdaPlatform:   3b902c...
```

Use the repository log for the full SHA when needed. Do not generate or report redundant SHA-256 file hashes unless explicitly requested.

### 14.3 Updated hardware-encoder rule

The older V8 continuation text that suggested restarting SurfaceFlinger/allocator/Codec2 is superseded by the validated late-bind/prewarm architecture.

Still mandatory:

```text
external/v4l2_codec2 stays clean
external/minigbm stays clean
vendor-owned integration only
no global minigbm validation weakening
no forced software rendering
```

---

## 15. Canonical ArdaPlatform source and Vehicle Sources UI

### 15.1 Canonical source snapshot

The canonical ArdaPlatform source snapshot is:

```text
comp180720262158.zip
```

It already contains:

```text
Vehicle Sources UI
privileged/admin AIDLs
public-SDK-safe currentUserId implementation
Process.myUid() / 100000 user derivation
known build fixes
```

Do not recreate or reapply older Platform UI patches over this snapshot.

### 15.2 Vehicle Sources ownership

```text
ArdaPlatform:
  approval UI
  configuration UI
  status
  revoke / disable / enable

ArdaVehicleManager:
  privileged policy and apply

vehicle router:
  authenticated message transport

arda-vhal-proxyd:
  selected VHAL authority
```

ArdaPlatform must not become the high-rate sensor/VHAL data path.

### 15.3 Platform map settings Apply model

Canonical 0.7.1 source package:

```text
ArdaPlatform_0.7.1_LAUNCHER_LAYOUT_APPLY_FIX_20260725.zip
```

Root issue:

```text
map options were written immediately
Apply was not the sole persistence action
dirty comparison considered only bridge configuration
```

Correct behavior:

```text
UI changes -> draft state
Apply      -> write/apply all draft settings
Reload     -> restore all persisted settings
```

Map placement settings include left/right selection and map width percentage.

### 15.4 Platform runtime package caveat

Package:

```text
com.github.deadknight.ardaplatform.debug
```

System path:

```text
/system/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk
```

A V19.5 test ended with the package reported disabled for user 0 and the current user. The Manager boot fix passed independently, but Platform per-user state must be checked with `dumpsys package`; do not infer the full state only from `pm list packages -d`.

Do not roll the Platform APK back merely to align it with an older source snapshot.

---

## 16. Vehicle profile router integration

### 16.1 Current architecture

The old root-shell supervisor was removed.

```text
ArdaPlatform
  -> IArdaVehicleSourceManager
  -> ArdaVehicleManager
  -> authenticated arda_vehicle_router
  -> arda-vhal-proxyd
  -> INFO_FUEL_TYPE / INFO_EV_CONNECTOR_TYPE
  -> CarService / Maps consumer refresh
```

AIDL addition:

```aidl
void applyVehicleInfo(
    int userId,
    in int[] fuelTypes,
    in int[] connectorTypes
);
```

Router protocol:

```text
v2 -> v3
```

VHAL proxy overrides for this feature:

```text
INFO_FUEL_TYPE         289472773
INFO_EV_CONNECTOR_TYPE 289472775
```

Integration package:

```text
ARDA_VEHICLE_PROFILE_ROUTER_INTEGRATION_20260721.zip
```

### 16.2 Electric profile result

Validated behavior:

```text
FUEL_TYPE=ELECTRIC
VHAL INFO_FUEL_TYPE applied
Maps authority=google_maps_energy
consumer refresh
EV/electric UI visible after reboot
```

Fuel type should be represented as a supported-value dropdown/multi-value control, not unrestricted text. The UI heading must reflect the selected value.

### 16.3 Result reporting

An apply result must distinguish:

```text
vhal_applied
consumers_refreshed
maps_restarted
```

A property set and a consumer restart are different operations and must not be conflated.

---

## 17. Vehicle profile boot replay V19.5

### 17.1 Root cause

Persisted profile replay previously performed:

```text
boot replay
-> apply VHAL profile
-> kill/restart com.android.car
-> restart Maps
```

Observed chain:

```text
CarService PID 1258 SIGKILL
ARDA_VM_CAR_SERVICE_RESTART_PASS old_pid=1258 new_pid=1838
ARDA_VM_MAPS_RESTART_DEFERRED reason=launch_activity_missing user=10
CarSystemUIProxyImpl.onConnected()
CarActivityManager == null
NullPointerException
```

This was a Manager boot-replay problem, not an ArdaPlatform UI problem.

### 17.2 Implemented fix

File:

```text
vendor/arda/vehicle/manager/src/com/arda/vehicle/manager/
  VehicleInfoController.java
```

Behavior split:

```text
Manual Apply:
  apply VHAL
  consumer refresh remains permitted after stable boot

Boot replay:
  apply persisted VHAL state only
  do not restart CarService
  do not restart Maps
```

Expected marker:

```text
ARDA_VM_BOOT_REPLAY_NO_CONSUMER_RESTART user=10
```

Expected result fields:

```text
consumers_refreshed=false
maps_restarted=false
```

Validated runtime:

```text
Car/SystemUI stable 45-60 seconds
boot replay no-consumer-restart marker present
ARDA_VM_CAR_SERVICE_RESTART_PASS count 0
```

If a future manual Apply still exposes the CarSystemUI null path, handle `CarActivityManager` defensively in a separate, narrow resilience patch. Do not reintroduce boot-time service killing.

---

## 18. CarLauncher / CarSystemUI requirements

### 18.1 Required Home behavior

From another app:

```text
Home -> return to CarLauncher only
```

While CarLauncher and the embedded Maps surface are already visible:

```text
first Home repress:
  hide Launcher media card
  hide Launcher weather card
  keep Maps task visible
  expand map into the released area

second Home repress:
  restore media and weather cards
```

This is not immersive fullscreen:

```text
top status/clock bar remains
persistent automotive nav controls remain
only Launcher media/weather cards toggle
```

Correct semantic marker:

```text
LAUNCHER_CARDS_HIDDEN=MEDIA_AND_WEATHER_ONLY
```

The older banner `MAP_FULLSCREEN_MEANS=TOP_AND_BOTTOM_CARDS_GONE` is misleading and must not be treated as the requirement.

### 18.2 White Maps area root cause

Initial diagnosis considered:

```text
android.car.usb.handler/.UsbHostManagementActivity
```

The package was disabled for users 0 and 10, but the behavior remained.

Actual sequence:

```text
Home press
-> embedded Maps task TO_BACK
-> map surface moves down/disappears
-> Launcher maps_card remains as white area
-> touching the map brings the Maps task TO_FRONT
```

The old routing relied on:

```java
homeButton.getSelected()
```

The embedded Maps task could own focus while the Launcher surface remained visible, causing `selected=false` and incorrectly invoking normal Home navigation.

USB Handler was not the root cause.

### 18.3 V19.4E routing

V19.4E checks the visible Launcher/home task relationship rather than only Home-button selected state.

Markers:

```text
ARDA_HOME_SURFACE_ACTIVE
ARDA_LAUNCHER_MAP_FULLSCREEN
ARDA_LAUNCHER_HOME_CARDS_HIDDEN
```

Relevant files:

```text
packages/apps/Car/Launcher/app/src/com/android/car/carlauncher/CarLauncher.java
packages/apps/Car/Launcher/app/res/layout/car_launcher.xml
packages/apps/Car/Launcher/app/res/layout-land/car_launcher.xml

packages/apps/Car/SystemUI/src/com/android/systemui/car/systembar/HomeButtonController.java
packages/apps/Car/SystemUI/res/values/dimens.xml
packages/apps/Car/SystemUI/res/values/styles.xml
packages/apps/Car/SystemUI/res/layout/car_top_system_bar.xml
```

A direct `FocusParkingView` child must have an ID before ConstraintSet cloning.

### 18.4 Clock runtime resource

Changing only XML text size is insufficient because `Clock.reloadDimens()` reloads the resource.

Current value:

```text
status_bar_clock_size=22sp
```

---

## 19. V19.4E deployment and apparent loop

### 19.1 Build/deploy

Validated build outputs:

```text
CarLauncher build PASS
CarSystemUI build PASS
```

System paths:

```text
/system/priv-app/CarLauncher/CarLauncher.apk
/system_ext/priv-app/CarSystemUI/CarSystemUI.apk
```

### 19.2 Why the first rollback did not actually roll back the APKs

The source restore used timestamp-preserving copies. Ninja printed:

```text
ninja: no work to do
```

The restored sources were older than the existing output, so no fresh APK was generated. The same V19.4E APKs were deployed again.

The recovery also removed:

```text
/system/priv-app/CarLauncher/oat
/system_ext/priv-app/CarSystemUI/oat
```

After reboot, the existing V19.4E runtime stabilized and Home worked.

Strongest current explanation:

```text
stale APK/preopt mismatch
```

This is an evidence-based inference, not a proven low-level DRM/runtime root cause.

### 19.3 Future direct privileged-APK deployment rule

```text
atomic APK replacement
root:root ownership
0644 mode
restorecon
remove only module-specific stale oat directory
sync
single reboot
resolve ADB again
verify boot_id and process PIDs for at least 60 seconds
```

A rollback build must prove fresh output:

```text
source mtime refreshed
targeted intermediates removed
APK mtime >= build start
```

---

## 20. Source/runtime synchronization

The working V19.4E sources were preserved at:

```text
/home/edo/arda-source-backups/
  20260725_164539-v19-4e-failed-source-rescue
```

The V19.4E pre-change backup is:

```text
/home/edo/arda-source-backups/
  20260725_161902-car-ui-home-cards-clock-v19-4e
```

The current source-only commit operation must restore the rescue copy if the markers are absent.

It must not:

```text
build
use ADB
deploy APKs
reboot
push
run repo sync
```

---

## 21. android.car.usb.handler diagnostic state

Package:

```text
android.car.usb.handler
```

It was set to `disabled-user` for users 0 and 10 during diagnosis.

This state is independent of the Home/Maps fix. Final policy remains a separate decision:

```text
leave disabled -> no USB host-management Activity UI
re-enable      -> restore the standard host-management UI
```

Do not cite this package as the white-map root cause.

---

## 22. Source-only targeted commit plan

Expected commits:

```text
vendor/arda:
  arda: keep vehicle-profile boot replay non-disruptive

packages/apps/Car/Launcher:
  CarLauncher: toggle home cards without hiding embedded Maps

packages/apps/Car/SystemUI:
  CarSystemUI: route Home repress and enlarge status clock

vendor/arda docs:
  docs: update authoritative ARDA handover through V19.5 and V19.4E
```

Only the targeted paths are committed. Existing unrelated dirty or staged work must not be included.

Before commit:

```text
V19.4E markers present
22sp clock resource present
V19.5 boot-replay marker present
consumers_refreshed field present
no merge-conflict markers
git diff --check PASS
```

The script reports real commit IDs. It does not push.

---

## 23. Current open items

```text
[OPEN] exact ArdaPlatform enabled/installed/stopped state for user 0 and user 10
[OPEN] final android.car.usb.handler enable/disable policy
[OPEN] measured map bounds expansion gate after Home repress
[OPEN] Platform map left/right/width runtime validation
[OPEN] manual vehicle-profile Apply consumer-refresh resilience
[OPEN] Bluetooth-to-Wi-Fi direct listener port 5284 runtime gate
[OPEN] device-unique hotspot credential provisioning
[OPEN] separate Git push after reviewing commit output
```

Closed or currently validated:

```text
[PASS] AAP/DHU/TLS/SDR
[PASS] InputBridge abstract AF_UNIX security gate
[PASS] location/GNSS authoritative path
[PASS] 7/7 sensor-to-VHAL path
[PASS] dynamic userspace adapter onboarding and command round trip
[PASS] persistent AAP mapper late-bind/prewarm
[PASS] c2.v4l2.avc.encoder 720p gate
[PASS] vehicle profile router v3 architecture
[PASS] electric fuel type application
[PASS] V19.5 boot replay without consumer restart
[PASS] V19.4E current Home routing
[PASS] current 60-second boot/Launcher/SystemUI stability
```

---

## 24. Updated non-negotiable constraints

All earlier constraints remain, plus:

- Do not kill CarService or restart Maps during persisted vehicle-profile boot replay.
- Do not use `homeButton.getSelected()` as the sole test for whether the Launcher home surface is active.
- Do not send the embedded Maps task to the back when toggling Launcher cards.
- Do not deploy a direct privileged APK without considering stale module-specific preopt output.
- Do not call `ninja: no work to do` a successful rollback build unless fresh output was independently verified.
- Do not roll back the stable Platform APK solely to make it match an older source archive.
- Do not treat `android.car.usb.handler` as the cause of the white Maps container.
- Do not claim the source-only commit script built, deployed, rebooted, pushed, or touched the device.

---

## 25. Updated AI continuation prompt

```text
ARDA authoritative state received.

Build host:
edo@192.168.1.55
AOSP root:
/home/edo/aosp-rpi-aaos16
Lunch:
aosp_rpi4_car-bp4a-userdebug

Never run repo sync.
Do not generate SHA-256 unless requested.
Preserve AAP/TLS/SDR.
Keep location_data on Android GPS/location path, never VHAL.
Keep external/v4l2_codec2 and external/minigbm clean.
Use vendor-owned mapper/prewarm integration.

Canonical ArdaPlatform source:
comp180720262158.zip
Do not reapply old UI patches.

Vehicle profile:
Platform -> AIDL -> Manager -> authenticated router v3
-> VHAL proxy -> INFO_FUEL_TYPE / INFO_EV_CONNECTOR_TYPE.

Boot replay:
ARDA_VM_BOOT_REPLAY_NO_CONSUMER_RESTART.
Never restart CarService or Maps during boot replay.

Car UI:
normal Home from another app returns to Launcher.
Home repress on visible Launcher/embedded Maps toggles only media/weather cards.
Maps remains visible and expands.
Clock resource is 22sp.

Current device runtime is stable with V19.4E binary behavior.
Working source rescue:
/home/edo/arda-source-backups/
20260725_164539-v19-4e-failed-source-rescue

The next operation is source-only targeted commits plus this handover.
No build, deploy, reboot, push, or repo sync.
```

---

## 26. Canonical continuation documents — updated

```text
ARDA_AUTHORITATIVE_HANDOVER_20260725.md
ARDA_MAPS_EV_HANDOVER.md
```

`ARDA_AUTHORITATIVE_HANDOVER_20260725.md` supersedes the short delta-only draft `ARDA_HANDOVER_20260725_V19_5_V19_4E.md` as the general project handover.

<!-- ARDA_FINAL_COMMIT_PUSH_RECORD_BEGIN -->

# 44. Final targeted commit and push record

Generated by the source-only finalization script.

```text
timestamp=20260725_174803
vehicle_manager_commit=bac8a7f0d48d4d43e7e31605f9223362d88ff098
launcher_home_feature_commit=08e47c725a8634281e5086d8b08c5cdc32a5c8d0
launcher_control_layout_permission_commit=b806e0ee320f369456184a78843765909f3cff29
systemui_home_clock_feature_commit=b4ea8902ed7baa4a949c6df461e2efabee9aeb7f
systemui_control_layout_permission_commit=c13f1da285c6836710ef1e64483bdb610aeeadb8
authoritative_handover_base_commit=7384b099a6ab95c890468e61e1b522ebb4a37fbd

launcher_push_target=aosp/arda-poc-checkpoint
systemui_push_target=arda/arda-aaos16
vendor_arda_push_target=origin/arda-aaos16

force_push=false
build_performed=false
device_touched=false
apk_deployed=false
reboot_performed=false
repo_sync_called=false
```

Remote SHA equality is verified by the script after each ordinary fast-forward
push. The script output/report is the authoritative proof of push success.

<!-- ARDA_FINAL_COMMIT_PUSH_RECORD_END -->

<!-- ARDA_CREATED_LAUNCHER_REPO_PUSH_RECORD_BEGIN -->

# 45. Project-owned Launcher repository creation and push record

The Google AOSP remote remains the read-only upstream. A separate project-owned
GitHub repository was created for the ARDA CarLauncher changes.

```text
timestamp=20260725_175747

launcher_repository=Deadknight/android_packages_apps_Car_Launcher
launcher_push_target=arda-github/arda-aaos16
launcher_remote_url=https://github.com/Deadknight/android_packages_apps_Car_Launcher.git

systemui_push_target=arda/arda-aaos16
systemui_remote_url=https://github.com/Deadknight/android_packages_apps_Car_SystemUI.git

vendor_arda_push_target=origin/arda-aaos16
vendor_arda_remote_url=https://github.com/Deadknight/android_vendor_arda.git

previous_aosp_push_succeeded=false
aosp_remote_modified=false
local_upstream_modified=false
force_push=false
build_performed=false
device_touched=false
apk_deployed=false
reboot_performed=false
repo_sync_called=false
```

All three repositories are checked for fast-forward safety before ordinary push.
The script verifies each remote branch SHA against its local HEAD afterwards.

<!-- ARDA_CREATED_LAUNCHER_REPO_PUSH_RECORD_END -->
