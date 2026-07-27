# ARDA Authoritative Handover — Mercedes Video, Touch and Density Scaling

**Checkpoint date:** 2026-07-27  
**Project:** `arda`  
**State:** Runtime persisted and verified; source commits created locally; no push performed.

---

## 1. Hard workflow constraints

```text
Never run repo sync.
Do not modify hardware encoder repositories unless explicitly requested.
Do not claim a build, deploy, commit, or push without observed output.
```

---

## 2. Canonical environment

### Mac

```text
ArdaPlatform: /Users/edo/Documents/Code/ArdaPlatform
aaos-aap:     /Users/edo/Documents/Code/aaos-aap
```

### Ubuntu

```text
host:        edo@192.168.1.55
AOSP root:   /home/edo/aosp-rpi-aaos16
lunch:       aosp_rpi4_car-bp4a-userdebug
vendor repo: /home/edo/aosp-rpi-aaos16/vendor/arda
```

### Persistent device files

```text
/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk
/system_ext/bin/arda-connectivityd
/product/etc/arda/arda-aap.toml
```

---

## 3. Source commits

### ArdaPlatform

```text
branch:  main
commit:  10bb59c0474c0fb43f3908334800aa982338a6ce
message: Add Android Auto density scaling control
versionCode: 19
versionName: 0.7.8-density-scale
```

### aaos-aap

```text
branch:  main
commit:  ae1151540b185eb563cd855a94e55cfd63d7bcf5
message: Handle HU video geometry, touch mapping, and density scaling
```

### Ubuntu vendor/arda state before this handover document

```text
branch:  arda-aaos16
HEAD:    56506d35deda9289cb55964b6cac6c59195cdfd8
message: Add
```

Historical 2026-07-26 handover commit:

```text
56506d35deda9289cb55964b6cac6c59195cdfd8
```

Latest commits touching persisted runtime prebuilts:

```text
ArdaPlatform.apk:    
arda-connectivityd: 
arda-aap.toml:       
```

The density-scale persistence produced no new vendor prebuilt diff; the
targeted build and device deployment nevertheless passed byte-identity gates.

---

## 4. Runtime checkpoint

```text
=== BUILD ===
BP4A.251205.006
16
rpi4
=== PLATFORM ===
package:/system/product/priv-app/ArdaPlatformPriv/ArdaPlatformPriv.apk
    codePath=/system/product/priv-app/ArdaPlatformPriv
    versionCode=19 minSdk=29 targetSdk=36
    versionName=0.7.8-density-scale-debug
=== DENSITY SETTING ===
10
=== AAP CONFIG ===
wm_size_mode = "content"
wm_density_mode = "real_density"
=== AAP SERVICE ===
running
 6169 arda-connectivityd aap-run --video-config-policy balanced
=== SELINUX ===
Enforcing
=== USB ===
adb
```

SELinux is currently Enforcing. The earlier one-time Permissive result did not
persist across reboot. No permissive kernel argument, bootconfig override, or
init `setenforce 0` reference was found during follow-up diagnosis.

Density setting state:

```text
raw Settings.Global value: 10
effective tenths:           10
interpretation:             persisted value 10/10x
```

---

## 5. Mercedes SDR geometry

```text
index 0:
  stream=1920x1080@60
  margins=L162 R162 T0 B0
  content=1596x1080
  content insets=T12 B21
  density=203
  real_density=169

index 1:
  stream=1280x720@60
  margins=L108 R108 T0 B0
  content=1064x720
  content insets=T8 B14
  density=135
  real_density=113

index 2:
  stream=800x480@60
  margins=0
  content=800x480
```

The selected SDR index is propagated through
`MEDIA_MESSAGE_START.configuration_index`.

For 720p the logical AAOS display is `1064x720`, encoded into a
`1280x720` canvas with black `108`-pixel side margins.

---

## 6. Touch mapping

### DHU

```text
source=1280x720
target=1064x720
mode=content-local
offset=0x0
no margin subtraction
no scaling
```

DHU coordinates were observed to be post-margin content-local despite the
advertised 1280x720 touchscreen size.

### Real Mercedes

```text
source=1888x1278
target=1064x720
mode=content-direct
offset=0x0
```

The separate Mercedes touch grid is scaled directly to visible content space.

---

## 7. Density multiplier

Platform setting:

```text
Settings.Global key:
arda_aap_density_scale_tenths

range: 0.1x to 2.0x
step:  0.1x
default when unset: 1.0x
```

Calculation:

```text
effective density =
round(base density * scale_tenths / 10)

minimum clamp = 72 dpi
```

Current AAP config:

```toml
wm_size_mode = "content"
wm_density_mode = "real_density"
```

Mercedes 720p examples:

```text
0.8x -> 90 dpi
0.9x -> 102 dpi
1.0x -> 113 dpi
1.1x -> 124 dpi
```

The setting is read at every AAP session start. Platform stores the slider
value after **Save & apply**.

---

## 8. Confirmed persistence result

Observed gates:

```text
TARGETED_AOSP_BUILD=PASS
SOONG_ARTIFACT_FETCH=PASS
TESTED_AAP_BINARY_IDENTITY=PASS
TESTED_AAP_CONFIG_IDENTITY=PASS
PLATFORM_VERSION_GATE=PASS
PLATFORM_OAT_REMOVED=PASS
AAP_BINARY_PERSIST_GATE=PASS
AAP_CONFIG_PERSIST_GATE=PASS
REBOOT_PERFORMED=YES
```

No `repo sync` and no push were performed.

---

## 9. Remaining validation

```text
Real Mercedes:
- configuration_index=1 at 720p
- 1888x1278 content-direct touch at all four edges
- density multiplier visual result
- five-minute video stability

Deferred:
- apply content/stable insets as a separate safe-area policy
- investigate intermittent solid-red Maps GL surface
- distinguish active mock GPS from cached last-known location
- resume privileged package-installer track from its dedicated handover
```

---

## 10. Continuation prompt

```text
ARDA 2026-07-27 Mercedes video/touch/density checkpoint received.

Never run repo sync.

Mac:
- ArdaPlatform: /Users/edo/Documents/Code/ArdaPlatform
- aaos-aap: /Users/edo/Documents/Code/aaos-aap

Ubuntu:
- edo@192.168.1.55
- /home/edo/aosp-rpi-aaos16
- lunch aosp_rpi4_car-bp4a-userdebug
- vendor repo /home/edo/aosp-rpi-aaos16/vendor/arda

Commits:
- Platform: 10bb59c0474c0fb43f3908334800aa982338a6ce
- aaos-aap: ae1151540b185eb563cd855a94e55cfd63d7bcf5
- vendor baseline before handover doc: 56506d35deda9289cb55964b6cac6c59195cdfd8

Platform:
- versionCode 19
- versionName 0.7.8-density-scale

Video:
- 720p canvas 1280x720
- content 1064x720
- black margins L108/R108

Touch:
- DHU content-local
- real Mercedes 1888x1278 content-direct

Density:
- arda_aap_density_scale_tenths
- 0.1x..2.0x, 0.1 step
- default 1.0x
- minimum effective WM density 72 dpi

Runtime persistence passed.
SELinux is Enforcing.
Push has not been performed.

Next: verify density and touch on the real Mercedes HU, then handle content
insets and the intermittent red Maps surface.
```
