LOCAL_PATH := $(call my-dir)

# ARDA GAS stage1 prebuilt modules.
# APKs must be BUILD_PREBUILT modules; XML/sysconfig stays in PRODUCT_COPY_FILES.

define arda_product_app
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(2)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $$(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRODUCT_MODULE := true
LOCAL_ENFORCE_USES_LIBRARIES := false
LOCAL_DEX_PREOPT := false
include $$(BUILD_PREBUILT)
endef

define arda_product_priv_app
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(2)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $$(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRODUCT_MODULE := true
LOCAL_PRIVILEGED_MODULE := true
LOCAL_ENFORCE_USES_LIBRARIES := false
LOCAL_DEX_PREOPT := false
include $$(BUILD_PREBUILT)
endef

define arda_system_app
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(2)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $$(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_ENFORCE_USES_LIBRARIES := false
LOCAL_DEX_PREOPT := false
include $$(BUILD_PREBUILT)
endef

define arda_system_priv_app
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(2)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $$(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_PRIVILEGED_MODULE := true
LOCAL_ENFORCE_USES_LIBRARIES := false
LOCAL_DEX_PREOPT := false
include $$(BUILD_PREBUILT)
endef

define arda_prebuilt_overlay
include $$(CLEAR_VARS)
LOCAL_MODULE := $(1)
LOCAL_SRC_FILES := $(2)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_SUFFIX := .apk
LOCAL_MODULE_STEM := $(3)
LOCAL_INSTALLED_MODULE_STEM := $(3)
LOCAL_MODULE_PATH := $(4)
include $$(BUILD_PREBUILT)
endef

# /system/product/app
$(eval $(call arda_product_app,AndroidAutoIMEPrebuilt,files/product/app/AndroidAutoIMEPrebuilt/AndroidAutoIMEPrebuilt.apk))
$(eval $(call arda_product_app,GoogleTTS,files/product/app/GoogleTTS/GoogleTTS.apk))
$(eval $(call arda_product_app,WebViewGoogle,files/product/app/WebViewGoogle/WebViewGoogle.apk))

# /system/product/priv-app
$(eval $(call arda_product_priv_app,GoogleOneTimeInitializer,files/product/priv-app/GoogleOneTimeInitializer/GoogleOneTimeInitializer.apk))
$(eval $(call arda_product_priv_app,GoogleSearchCarPrebuilt,files/product/priv-app/GoogleSearchCarPrebuilt/GoogleSearchCarPrebuilt.apk))
$(eval $(call arda_product_priv_app,GoogleTemplatesHostInternal,files/product/priv-app/GoogleTemplatesHostInternal/GoogleTemplatesHostInternal.apk))
$(eval $(call arda_product_priv_app,PartnerSetupPrebuilt,files/product/priv-app/PartnerSetupPrebuilt/PartnerSetupPrebuilt.apk))
$(eval $(call arda_product_priv_app,PhoneskyCarPrebuilt,files/product/priv-app/PhoneskyCarPrebuilt/PhoneskyCarPrebuilt.apk))

# /system/app and /system/priv-app
$(eval $(call arda_system_app,GASOtaConfigRRO,files/system/app/GASOtaConfigRRO/GASOtaConfigRRO.apk))

# /system/product/overlay
$(eval $(call arda_prebuilt_overlay,ArdaCarServiceOverlayGMS,files/product/overlay/CarServiceOverlayGMS.apk,CarServiceOverlayGMS.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleWebViewOverlay,files/product/overlay/GoogleWebViewOverlay.apk,GoogleWebViewOverlay.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiVendingOverlay,files/product/overlay/googlecarui-com-android-vending.apk,googlecarui-com-android-vending.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiTemplatesHostOverlay,files/product/overlay/googlecarui-com-google-android-apps-automotive-templates-host.apk,googlecarui-com-google-android-apps-automotive-templates-host.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiCarassistantOverlay,files/product/overlay/googlecarui-com-google-android-carassistant.apk,googlecarui-com-google-android-carassistant.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiGmsOverlay,files/product/overlay/googlecarui-com-google-android-gms.apk,googlecarui-com-google-android-gms.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiGsfOverlay,files/product/overlay/googlecarui-com-google-android-gsf.apk,googlecarui-com-google-android-gsf.apk,$$(TARGET_OUT_PRODUCT)/overlay))
$(eval $(call arda_prebuilt_overlay,ArdaGoogleCaruiTtsOverlay,files/product/overlay/googlecarui-com-google-android-tts.apk,googlecarui-com-google-android-tts.apk,$$(TARGET_OUT_PRODUCT)/overlay))
