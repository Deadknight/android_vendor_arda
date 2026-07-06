# ARDA Google Maps Tile Root Cause and Permanent Fix

Date: 2026-07-04

## Symptom

Google Maps AAOS started normally on the Raspberry Pi 4.

Search and Maps UI worked, but the map canvas remained grey and map tiles did
not render.

The same Maps/GMS versions rendered map tiles on the working GAS emulator.

## Cronet divergence

Raspberry Pi:

    JavaCronetEngine

Working GAS emulator:

    CronetDynamite
    native Cronet 149.0.7827.102

The Raspberry Pi Maps process fell back to JavaCronet.

## Package visibility blocker

Runtime AppsFilter logging on the Pi showed:

    com.google.android.apps.maps
        ->
    com.android.vending
    BLOCKED

The Pi framework resource did not make Vending queryable by Maps.

The working emulator force-queryable list included:

    com.google.android.gms
    com.android.vending

A runtime force-queryable A/B removed the AppsFilter blocker.

After this change the real Maps call stack reached:

    xub.a()
        ->
    PlayServicesCronetProvider.isEnabled()
        ->
    tbh.a()
        ->
    soy.b(context, 11925000)
        ->
    swd.d(..., "com.google.android.gms.cronet_dynamite")

Permanent build fix:

    ArdaFrameworkGoogleVisibilityOverlay

overlays:

    android:array/config_forceQueryablePackages

with:

    com.android.settings
    com.android.providers.settings
    com.google.android.gms
    com.android.vending

## Decisive Chimera failure

After the availability gate progressed, Maps logged:

    ClassLoader referenced unknown path
    Failed to validate PathClassLoader
    DexPathList[[]
    ClassNotFoundException:
    com.google.android.gms.chimera.DynamiteModuleInitializer

Affected modules included:

    CronetDynamite.uncompressed.apk
    DynamiteLoader.uncompressed.apk
    GoogleCertificates.uncompressed.apk

The module files existed in the root filesystem:

    /data/user_de/10/com.google.android.gms/app_chimera/m/00000008/CronetDynamite.uncompressed.apk
    /data/user_de/10/com.google.android.gms/app_chimera/m/00000009/DynamiteLoader.uncompressed.apk
    /data/user_de/10/com.google.android.gms/app_chimera/m/0000000c/GoogleCertificates.uncompressed.apk

Root and a shell launched with the Maps numeric UID could read the APKs.

However that test did not use the real Maps process mount namespace.

The decisive test was:

    /proc/<maps-pid>/root/data/user_de/10/com.google.android.gms/...

Raspberry Pi Maps process:

    MAPS_PROC_ROOT_EXISTS=NO
    MAPS_PROC_ROOT_IS_FILE=NO

Working GAS emulator Maps process:

    MAPS_PROC_ROOT_EXISTS=YES
    MAPS_PROC_ROOT_IS_FILE=YES

Therefore the Chimera module paths literally did not exist inside the real
Maps process mount namespace on the Pi.

This explains:

    ClassLoader referenced unknown path
        ->
    DexPathList element removed
        ->
    DynamiteModuleInitializer unavailable
        ->
    CronetDynamite fails
        ->
    PlayServicesCronetProvider unavailable
        ->
    JavaCronet fallback
        ->
    grey Maps tile canvas

## Emulator sysconfig divergence

The working GAS emulator contains:

    <app-data-isolation-whitelisted-app package="com.google.android.gms" />

The Pi image did not.

## Decisive runtime A/B

The Pi was temporarily tested with:

    setprop persist.zygote.app_data_isolation false
    reboot

After reboot:

    GOOGLE MAPS TILES RENDERED

This proved the app-data isolation / process mount namespace root cause.

IMPORTANT:

    persist.zygote.app_data_isolation=false

is only a diagnostic A/B.

It must NOT be used as the production ARDA fix.

## Permanent scoped app-data isolation fix

ARDA adds:

    vendor/arda/configs/arda-google-sysconfig.xml

The product makefile copies it to:

    /product/etc/sysconfig/arda-google-sysconfig.xml

Content:

    <config>
        <app-data-isolation-whitelisted-app package="com.google.android.gms" />
    </config>

This allows GMS app data to be exposed through the Android app-data isolation
allowlist instead of globally disabling app-data isolation.

## Build integration

Product:

    device/brcm/rpi4/aosp_rpi4_car.mk

contains:

    # ARDA_MAPS_TILE_FIX_BEGIN

    PRODUCT_PACKAGES += \
        ArdaFrameworkGoogleVisibilityOverlay

    PRODUCT_COPY_FILES += \
        vendor/arda/configs/arda-google-sysconfig.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/arda-google-sysconfig.xml

    # ARDA_MAPS_TILE_FIX_END

## Build

    cd /home/edo/aosp-rpi-aaos16
    source build/envsetup.sh
    lunch aosp_rpi4_car-bp4a-userdebug

Expected:

    TARGET_PRODUCT=aosp_rpi4_car
    TARGET_BUILD_VARIANT=userdebug
    TARGET_RELEASE=bp4a

Build overlay first:

    m ArdaFrameworkGoogleVisibilityOverlay -j"$(nproc)"

Then full build:

    m -j"$(nproc)"

Create Raspberry Pi image:

    ./rpi4-mkimg.sh

## Clean-burn verification

The old test SD contains runtime A/B state:

    persist.zygote.app_data_isolation=false

and a Vending force-queryable package install override.

Therefore the permanent build fix must be verified using a clean SD burn /
clean data state.

After the clean image boots:

    adb root
    adb shell getprop persist.zygote.app_data_isolation

The build must NOT explicitly ship:

    false

Verify app-data isolation allowlist:

    adb shell grep -R -n \
        "app-data-isolation-whitelisted-app.*com.google.android.gms" \
        /product/etc/sysconfig \
        /system/product/etc/sysconfig \
        2>/dev/null

Expected:

    /product/etc/sysconfig/arda-google-sysconfig.xml

Verify force-queryable resource:

    adb shell cmd overlay lookup \
        android \
        android:array/config_forceQueryablePackages

Expected entries:

    com.google.android.gms
    com.android.vending

The following errors should disappear:

    ClassLoader referenced unknown path
    Failed to validate PathClassLoader
    DynamiteModuleInitializer
    Unable to load Cronet module

Google Maps tiles must render.

## Do not reopen without new evidence

Do not return to the following as primary Maps tile causes:

    generic Ethernet/Wi-Fi
    simple DNS
    basic location/ADAS
    Maps data/cache
    API key/certificate basic theory
    BootCount
    GoogleCarUI RRO
    ro.com.google.acsa=true
    GMS/GSF shared UID surgery
    GMS APK byte/topology mismatch
    CronetDynamite module APK byte mismatch
    single Google CarService transplant
    fake VHAL layer seed
    OEM token

OEM token was a valid classloader-context lead before the process mount
namespace divergence was identified.

The app-data-isolation A/B directly restored tile rendering.

Do not re-add:

    com.google.android.gms.permission.ACTIVITY_RECOGNITION

to split-permission configuration.

That previous experiment caused a bootloop.

VMS RRO and VmsHalService retry changes are separate existing ARDA work and
must remain intact.
