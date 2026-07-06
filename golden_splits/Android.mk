LOCAL_PATH := $(call my-dir)

# -------------------------------------------------------------------
# Google Maps golden split APKs
# -------------------------------------------------------------------

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaMapsSplitArm64
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.google.android.apps.maps/split_config.arm64_v8a.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_MapsCarPrebuilt
LOCAL_MODULE_STEM := split_config.arm64_v8a.apk
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaMapsSplitMdpi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.google.android.apps.maps/split_config.mdpi.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_MapsCarPrebuilt
LOCAL_MODULE_STEM := split_config.mdpi.apk
include $(BUILD_PREBUILT)

# -------------------------------------------------------------------
# Google Play services golden split APKs
# -------------------------------------------------------------------

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaGmsSplitAllLocales
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.google.android.gms/split_all_locale_splits.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_GmsCoreAuto
LOCAL_MODULE_STEM := split_all_locale_splits.apk
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaGmsSplitEn
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.google.android.gms/split_config.en.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_GmsCoreAuto
LOCAL_MODULE_STEM := split_config.en.apk
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaGmsSplitMdpi
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.google.android.gms/split_config.mdpi.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_GmsCoreAuto
LOCAL_MODULE_STEM := split_config.mdpi.apk
include $(BUILD_PREBUILT)

# -------------------------------------------------------------------
# Google Play Store / Vending golden split APKs
# -------------------------------------------------------------------

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaVendingSplitArm64
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.android.vending/split_config.arm64_v8a.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_PhoneskyCarPrebuilt
LOCAL_MODULE_STEM := split_config.arm64_v8a.apk
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ArdaVendingSplitEn
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := com.android.vending/split_config.en.apk
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT)/priv-app/ArdaApp_PhoneskyCarPrebuilt
LOCAL_MODULE_STEM := split_config.en.apk
include $(BUILD_PREBUILT)
