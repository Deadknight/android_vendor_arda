#define LOG_TAG "ArdaVhalProxyService"

#include "ArdaVehicleProxy.h"

#include <aidl/android/hardware/automotive/vehicle/IVehicle.h>
#include <android/binder_auto_utils.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <utils/Log.h>

using ::aidl::android::hardware::automotive::vehicle::IVehicle;
using ::arda::vehicle::vhal::ArdaVehicleProxy;

namespace {
constexpr const char* kBaseService =
        "android.hardware.automotive.vehicle.IVehicle/arda_base";
constexpr const char* kDefaultService =
        "android.hardware.automotive.vehicle.IVehicle/default";
}

int main() {
    ALOGI("ARDA_VHAL_PROXY_STARTING base=%s", kBaseService);
    if (!ABinderProcess_setThreadPoolMaxThreadCount(8)) {
        ALOGE("ARDA_VHAL_PROXY_THREAD_POOL_FAIL");
        return 1;
    }
    ABinderProcess_startThreadPool();

    ndk::SpAIBinder baseBinder(AServiceManager_waitForService(kBaseService));
    if (baseBinder.get() == nullptr) {
        ALOGE("ARDA_VHAL_PROXY_BASE_NOT_FOUND");
        return 1;
    }
    std::shared_ptr<IVehicle> base = IVehicle::fromBinder(baseBinder);
    if (base == nullptr) {
        ALOGE("ARDA_VHAL_PROXY_BASE_BINDER_CAST_FAIL");
        return 1;
    }

    std::shared_ptr<ArdaVehicleProxy> proxy =
            ndk::SharedRefBase::make<ArdaVehicleProxy>(std::move(base));
    if (!proxy->start()) {
        ALOGE("ARDA_VHAL_PROXY_START_FAIL");
        return 1;
    }

    binder_exception_t err =
            AServiceManager_addService(proxy->asBinder().get(), kDefaultService);
    if (err != EX_NONE) {
        ALOGE("ARDA_VHAL_PROXY_REGISTER_FAIL exception=%d", err);
        return 1;
    }

    ALOGI("ARDA_VHAL_PROXY_READY instance=%s", kDefaultService);
    ABinderProcess_joinThreadPool();
    return 0;
}
