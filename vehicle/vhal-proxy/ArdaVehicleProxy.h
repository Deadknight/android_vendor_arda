#pragma once

#include <aidl/android/hardware/automotive/vehicle/BnVehicle.h>
#include <aidl/android/hardware/automotive/vehicle/IVehicle.h>
#include <aidl/android/hardware/automotive/vehicle/IVehicleCallback.h>

#include <android/binder_auto_utils.h>

#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <utility>
#include <vector>

namespace arda::vehicle::vhal {

namespace aidlvhal = ::aidl::android::hardware::automotive::vehicle;

class ArdaVehicleProxy final : public aidlvhal::BnVehicle {
  public:
    explicit ArdaVehicleProxy(std::shared_ptr<aidlvhal::IVehicle> base);
    ~ArdaVehicleProxy() override;

    bool start();

    ndk::ScopedAStatus getAllPropConfigs(aidlvhal::VehiclePropConfigs* returnConfigs) override;
    ndk::ScopedAStatus getPropConfigs(const std::vector<int32_t>& props,
                                      aidlvhal::VehiclePropConfigs* returnConfigs) override;
    ndk::ScopedAStatus getValues(const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
                                 const aidlvhal::GetValueRequests& requests) override;
    ndk::ScopedAStatus setValues(const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
                                 const aidlvhal::SetValueRequests& requests) override;
    ndk::ScopedAStatus subscribe(const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
                                 const std::vector<aidlvhal::SubscribeOptions>& options,
                                 int32_t maxSharedMemoryFileCount) override;
    ndk::ScopedAStatus unsubscribe(const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
                                   const std::vector<int32_t>& propIds) override;
    ndk::ScopedAStatus returnSharedMemory(
            const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
            int64_t sharedMemoryId) override;
    ndk::ScopedAStatus getSupportedValuesLists(
            const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds,
            aidlvhal::SupportedValuesListResults* results) override;
    ndk::ScopedAStatus getMinMaxSupportedValue(
            const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds,
            aidlvhal::MinMaxSupportedValueResults* results) override;
    ndk::ScopedAStatus registerSupportedValueChangeCallback(
            const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
            const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds) override;
    ndk::ScopedAStatus unregisterSupportedValueChangeCallback(
            const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
            const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds) override;
    binder_status_t dump(int fd, const char** args, uint32_t numArgs) override;

  private:
    struct SubscriptionState {
        std::weak_ptr<aidlvhal::IVehicleCallback> callback;
        std::vector<aidlvhal::SubscribeOptions> options;
    };

    static bool isOverlayProperty(int32_t propId);
    ndk::ScopedAStatus validateOverlaySubscriptions(
            const std::vector<aidlvhal::SubscribeOptions>& options,
            int32_t maxSharedMemoryFileCount) const;
    void routerLoop();
    void handleRouterEvents(const std::vector<aidlvhal::VehiclePropValue>& values);

    std::shared_ptr<aidlvhal::IVehicle> mBase;
    std::atomic<bool> mStop{false};
    std::atomic<int> mRouterFd{-1};
    std::thread mRouterThread;

    mutable std::mutex mLock;
    std::unordered_map<int32_t, aidlvhal::VehiclePropConfig> mOverlayConfigs;
    std::map<std::pair<int32_t, int32_t>, aidlvhal::VehiclePropValue> mLatestValues;
    std::unordered_map<const AIBinder*, SubscriptionState> mSubscriptions;
};

}  // namespace arda::vehicle::vhal
