# ARDA GAS car integration stage1
# No runtime data/chimera transplant.
# Google APKs are installed only through arda_google_core_product.mk android_app_import modules.
# XML/sysconfig/non-APK files stay in PRODUCT_COPY_FILES.
# CarLauncherSmallMapOverlay intentionally excluded in stage1.

PRODUCT_PACKAGES += \
    GASOtaConfigRRO \
    ArdaCarServiceOverlayGMS \
    ArdaGoogleWebViewOverlay \
    ArdaGoogleCaruiVendingOverlay \
    ArdaGoogleCaruiTemplatesHostOverlay \
    ArdaGoogleCaruiCarassistantOverlay \
    ArdaGoogleCaruiGmsOverlay \
    ArdaGoogleCaruiGsfOverlay \
    ArdaGoogleCaruiTtsOverlay

PRODUCT_COPY_FILES += \
    vendor/arda/gas_car/files/product/etc/default-permissions/default-gas-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-gas-permissions.xml \
    vendor/arda/gas_car/files/product/etc/default-permissions/default-permissions-sdk-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions-sdk-google.xml \
    vendor/arda/gas_car/files/product/etc/permissions/com.google.android.car.templates.host.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/com.google.android.car.templates.host.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-car.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-car.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-google-p.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-google-p.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-sdk-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-sdk-google.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp_com.google.android.car.templateshost.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp_com.google.android.car.templateshost.xml \
    vendor/arda/gas_car/files/product/etc/permissions/split-permissions-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/split-permissions-google.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/car-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/car-hiddenapi-package-whitelist.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/car-initial-package-stopped-states.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/car-initial-package-stopped-states.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/gas_features.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/gas_features.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google-hiddenapi-package-whitelist.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google-initial-package-stopped-states.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google-initial-package-stopped-states.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google_build.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google_build.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google_car.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google_car.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/preinstalled-packages-product-car-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/preinstalled-packages-product-car-google.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/preinstalled-packages-product-gcar-setup.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/preinstalled-packages-product-gcar-setup.xml \
    vendor/arda/gas_car/files/system/etc/permissions/com.google.android.embedded.projection.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/com.google.android.embedded.projection.xml \
    vendor/arda/gas_car/files/system/etc/permissions/privapp-permissions-google.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-google.xml \
    vendor/arda/gas_car/files/system/etc/sysconfig/zz-arda-package-shareduid-allowlist-google.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/zz-arda-package-shareduid-allowlist-google.xml \
    vendor/arda/gas_car/files/system_ext/etc/permissions/com.android.gcarsetup.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/com.android.gcarsetup.xml \
    vendor/arda/gas_car/files/system_ext/etc/permissions/privapp-permissions-google-se.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/permissions/privapp-permissions-google-se.xml \
    vendor/arda/gas_car/files/vendor/etc/permissions/com.google.android.automotive.gas.hardware.version.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/com.google.android.automotive.gas.hardware.version.xml \
    vendor/arda/gas_car/files/vendor/etc/permissions/com.google.android.automotive.gas.software.version.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/com.google.android.automotive.gas.software.version.xml
