# ARDA AAOS platform integration

ARDA platform integration for Android Automotive OS on Raspberry Pi 4.

Current source areas include:

- persistent privileged platform input bridge
- Maps/GMS app-data-isolation and package visibility overlays
- Vehicle Map Service integration overlay
- GAS build integration glue
- platform image/cmdline helper tools
- reproducibility and bring-up documentation

Google Automotive Services payload files are not stored in this repository.
See `PROPRIETARY.md`.

## Build target

```bash
source build/envsetup.sh
lunch aosp_rpi4_car-bp4a-userdebug
m
```

Expected build variables:

```text
TARGET_PRODUCT=aosp_rpi4_car
TARGET_BUILD_VARIANT=userdebug
TARGET_RELEASE=bp4a
```
