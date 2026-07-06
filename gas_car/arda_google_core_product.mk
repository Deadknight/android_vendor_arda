# Auto-generated ARDA Google core product makefile
# APKs are included through android_app_import modules in Android.bp.

PRODUCT_PACKAGES += \
    ArdaApp_AndroidAutoIMEPrebuilt \
    ArdaApp_GoogleTTS \
    ArdaApp_WebViewGoogle \
    ArdaApp_GmsCoreAuto \
    ArdaApp_GoogleOneTimeInitializer \
    ArdaApp_GoogleSearchCarPrebuilt \
    ArdaApp_GoogleServicesFramework \
    ArdaApp_GoogleTemplatesHostInternal \
    ArdaApp_MapsCarPrebuilt \
    ArdaApp_PartnerSetupPrebuilt \
    ArdaApp_PhoneskyCarPrebuilt

PRODUCT_COPY_FILES += \
    vendor/arda/gas_car/files/product/bin/arda-google-postboot.sh:$(TARGET_COPY_OUT_PRODUCT)/bin/arda-google-postboot.sh \
    vendor/arda/gas_car/files/product/etc/default-permissions/default-gas-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-gas-permissions.xml \
    vendor/arda/gas_car/files/product/etc/default-permissions/default-permissions-arda-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions-arda-google.xml \
    vendor/arda/gas_car/files/product/etc/default-permissions/default-permissions-sdk-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/default-permissions-sdk-google.xml \
    vendor/arda/gas_car/files/product/etc/init/arda-google-postboot.rc:$(TARGET_COPY_OUT_PRODUCT)/etc/init/arda-google-postboot.rc \
    vendor/arda/gas_car/files/product/etc/permissions/com.google.android.car.templates.host.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/com.google.android.car.templates.host.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-arda-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-arda-google.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-car.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-car.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-google-p.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-google-p.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp-permissions-sdk-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp-permissions-sdk-google.xml \
    vendor/arda/gas_car/files/product/etc/permissions/privapp_com.google.android.car.templateshost.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/privapp_com.google.android.car.templateshost.xml \
    vendor/arda/gas_car/files/product/etc/permissions/split-permissions-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/split-permissions-google.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/arda-google-core.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/arda-google-core.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/car-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/car-hiddenapi-package-whitelist.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/car-initial-package-stopped-states.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/car-initial-package-stopped-states.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/gas_features.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/gas_features.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google-hiddenapi-package-whitelist.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google-hiddenapi-package-whitelist.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google-initial-package-stopped-states.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google-initial-package-stopped-states.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google_build.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google_build.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/google_car.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/google_car.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/preinstalled-packages-product-car-google.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/preinstalled-packages-product-car-google.xml \
    vendor/arda/gas_car/files/product/etc/sysconfig/preinstalled-packages-product-gcar-setup.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/sysconfig/preinstalled-packages-product-gcar-setup.xml

