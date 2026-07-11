#define LOG_TAG "ArdaVhalBaseService"

#include <DefaultVehicleHal.h>
#include <FakeVehicleHardware.h>

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <utils/Log.h>

using ::android::hardware::automotive::vehicle::DefaultVehicleHal;
using ::android::hardware::automotive::vehicle::fake::FakeVehicleHardware;

namespace {
constexpr const char* kBaseService =
        "android.hardware.automotive.vehicle.IVehicle/arda_base";
}

int main() {
    ALOGI("ARDA_VHAL_BASE_STARTING");
    if (!ABinderProcess_setThreadPoolMaxThreadCount(4)) {
        ALOGE("ARDA_VHAL_BASE_THREAD_POOL_FAIL");
        return 1;
    }
    ABinderProcess_startThreadPool();

    std::unique_ptr<FakeVehicleHardware> hardware = std::make_unique<FakeVehicleHardware>();
    std::shared_ptr<DefaultVehicleHal> vhal =
            ::ndk::SharedRefBase::make<DefaultVehicleHal>(std::move(hardware));

    binder_exception_t err = AServiceManager_addService(vhal->asBinder().get(), kBaseService);
    if (err != EX_NONE) {
        ALOGE("ARDA_VHAL_BASE_REGISTER_FAIL exception=%d", err);
        return 1;
    }

    ALOGI("ARDA_VHAL_BASE_READY instance=%s", kBaseService);
    ABinderProcess_joinThreadPool();
    return 0;
}
