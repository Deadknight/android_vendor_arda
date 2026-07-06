# ARDA Golden Prebuilt Set

Captured from the known-good Raspberry Pi runtime:

`/home/edo/arda-import/golden/golden-stable-pi-20260705_105903`

Integrated on:

Sun Jul  5 11:27:05 +03 2026

## Golden active package updates

### Google Maps

- package: `com.google.android.apps.maps`
- versionCode: `1068622136`
- versionName: `26.06.080003.E`
- APK cluster:
  - `base.apk`
  - `split_config.arm64_v8a.apk`
  - `split_config.mdpi.apk`

### Google Play services

- package: `com.google.android.gms`
- versionCode: `262434109`
- versionName: `26.24.34 (280400-938041327)`
- APK cluster:
  - `base.apk`
  - `split_all_locale_splits.apk`
  - `split_config.en.apk`
  - `split_config.mdpi.apk`

### Google Play Store / Vending

- package: `com.android.vending`
- versionCode: `85202219`
- versionName: `52.0.22-31 [9] [PR] 936819141`
- APK cluster:
  - `base.apk`
  - `split_config.arm64_v8a.apk`
  - `split_config.en.apk`

### Automotive Input Method

- package: `com.google.android.apps.automotive.inputmethod`
- versionCode: `24256642`
- versionName: `26.18.00.arm64-v8a`

### Automotive Templates Host

- package: `com.google.android.apps.automotive.templates.host`
- versionCode: `18350`
- versionName: `1.007.929830456.00-arm64-v8a`

### Car Assistant

- package: `com.google.android.carassistant`
- versionCode: `800965920`
- versionName: `13.1.676.RC03.tng.release.auto`

### Google WebView

- package: `com.google.android.webview`
- versionCode: `782715903`
- versionName: `149.0.7827.159`

## GSF

Google Services Framework is intentionally not replaced.

The source APK and golden runtime APK have identical SHA-256:

`ab85f5bd229e8983e67ed5b1b035dcfd25f33f4a3727535cddbacfad896eade6`

## Maps tile root-cause fixes

The package refresh is separate from the root-cause fix.

Permanent tile fixes remain:

1. GMS app-data isolation allowlist:

`<app-data-isolation-whitelisted-app package="com.google.android.gms" />`

2. Framework force-queryable packages:

- `com.google.android.gms`
- `com.android.vending`

3. Do not ship:

`persist.zygote.app_data_isolation=false`

That global property was an A/B diagnostic only.

4. Keep the VMS early-start overlay.

5. Keep the VmsHalService retry patch.

## Build target

`lunch aosp_rpi4_car-bp4a-userdebug`

Expected:

- `TARGET_PRODUCT=aosp_rpi4_car`
- `TARGET_BUILD_VARIANT=userdebug`
- `TARGET_RELEASE=bp4a`
