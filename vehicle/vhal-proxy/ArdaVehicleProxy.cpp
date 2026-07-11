#define LOG_TAG "ArdaVhalProxy"

#include "ArdaVehicleProxy.h"

#include <ParcelableUtils.h>
#include <VehicleUtils.h>
#include <aidl/android/hardware/automotive/vehicle/GetValueResult.h>
#include <aidl/android/hardware/automotive/vehicle/GetValueResults.h>
#include <aidl/android/hardware/automotive/vehicle/StatusCode.h>
#include <aidl/android/hardware/automotive/vehicle/VehiclePropertyChangeMode.h>
#include <aidl/android/hardware/automotive/vehicle/VehiclePropertyStatus.h>
#include <android/binder_ibinder.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <utils/Log.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <optional>
#include <set>
#include <string>
#include <utility>

#include "arda_vehicle_router.pb.h"

namespace arda::vehicle::vhal {
namespace {

namespace routerpb = ::arda::vehicle::router::v2;
using ::android::hardware::automotive::vehicle::fromStableLargeParcelable;
using ::android::hardware::automotive::vehicle::vectorToStableLargeParcelable;
using ::aidl::android::hardware::automotive::vehicle::GetValueRequest;
using ::aidl::android::hardware::automotive::vehicle::GetValueResult;
using ::aidl::android::hardware::automotive::vehicle::GetValueRequests;
using ::aidl::android::hardware::automotive::vehicle::GetValueResults;
using ::aidl::android::hardware::automotive::vehicle::StatusCode;
using ::aidl::android::hardware::automotive::vehicle::SubscribeOptions;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropConfigs;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropertyChangeMode;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropertyStatus;
using ::aidl::android::hardware::automotive::vehicle::VehiclePropValue;
using ::ndk::ScopedAStatus;

constexpr uint32_t kRouterProtocolVersion = 2;
constexpr uint32_t kMaxFrameBytes = 1024 * 1024;
constexpr const char* kRouterSocket = "/dev/socket/arda_vehicle_router";
constexpr const char* kRouterClientName = "arda-vhal-proxy";

constexpr int32_t kPerfVehicleSpeed = 291504647;
constexpr int32_t kPerfOdometer = 291504644;
constexpr int32_t kFuelLevelLow = 287310853;
constexpr int32_t kParkingBrakeOn = 287310850;
constexpr int32_t kGearSelection = 289408000;
constexpr int32_t kNightMode = 287310855;
constexpr int32_t kEtcCardStatus = 289410874;

const std::vector<int32_t> kOverlayProps = {
        kPerfVehicleSpeed,
        kPerfOdometer,
        kFuelLevelLow,
        kParkingBrakeOn,
        kGearSelection,
        kNightMode,
        kEtcCardStatus,
};

ScopedAStatus serviceError(StatusCode status, const char* message) {
    return ScopedAStatus::fromServiceSpecificErrorWithMessage(
            static_cast<int32_t>(status), message);
}

int64_t elapsedRealtimeNanos() {
    timespec ts{};
    if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) {
        return 0;
    }
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

bool readAll(int fd, void* data, size_t size) {
    auto* out = static_cast<uint8_t*>(data);
    size_t offset = 0;
    while (offset < size) {
        ssize_t rc = TEMP_FAILURE_RETRY(read(fd, out + offset, size - offset));
        if (rc <= 0) {
            return false;
        }
        offset += static_cast<size_t>(rc);
    }
    return true;
}

bool writeAll(int fd, const void* data, size_t size) {
    const auto* in = static_cast<const uint8_t*>(data);
    size_t offset = 0;
    while (offset < size) {
        ssize_t rc = send(fd, in + offset, size - offset, MSG_NOSIGNAL);
        if (rc < 0 && errno == EINTR) {
            continue;
        }
        if (rc <= 0) {
            return false;
        }
        offset += static_cast<size_t>(rc);
    }
    return true;
}

bool readEnvelope(int fd, routerpb::Envelope* envelope) {
    uint32_t networkLength = 0;
    if (!readAll(fd, &networkLength, sizeof(networkLength))) {
        return false;
    }
    const uint32_t length = ntohl(networkLength);
    if (length == 0 || length > kMaxFrameBytes) {
        ALOGE("ARDA_VHAL_PROXY_ROUTER_BAD_FRAME length=%u", length);
        return false;
    }
    std::string body(length, '\0');
    if (!readAll(fd, body.data(), body.size())) {
        return false;
    }
    return envelope->ParseFromArray(body.data(), static_cast<int>(body.size()));
}

bool writeEnvelope(int fd, const routerpb::Envelope& envelope) {
    std::string body;
    if (!envelope.SerializeToString(&body) || body.empty() || body.size() > kMaxFrameBytes) {
        return false;
    }
    const uint32_t networkLength = htonl(static_cast<uint32_t>(body.size()));
    return writeAll(fd, &networkLength, sizeof(networkLength)) &&
            writeAll(fd, body.data(), body.size());
}

int connectRouter() {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    if (strlen(kRouterSocket) >= sizeof(addr.sun_path)) {
        close(fd);
        return -1;
    }
    strncpy(addr.sun_path, kRouterSocket, sizeof(addr.sun_path) - 1);
    if (connect(fd, reinterpret_cast<const sockaddr*>(&addr), sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

std::optional<VehiclePropValue> fromRouterEvent(const routerpb::VhalEvent& event) {
    VehiclePropValue value;
    value.prop = event.property_id();
    value.areaId = event.area_id();
    value.timestamp = event.timestamp_nanos() > 0 ? event.timestamp_nanos() : elapsedRealtimeNanos();
    value.status = VehiclePropertyStatus::AVAILABLE;

    switch (event.value_type()) {
        case routerpb::VALUE_BOOL:
            value.value.int32Values = {event.bool_value() ? 1 : 0};
            break;
        case routerpb::VALUE_I32:
            value.value.int32Values = {event.int32_value()};
            break;
        case routerpb::VALUE_I64:
            value.value.int64Values = {event.int64_value()};
            break;
        case routerpb::VALUE_F32:
            value.value.floatValues = {event.float_value()};
            break;
        case routerpb::VALUE_BYTES:
            value.value.byteValues.assign(event.bytes_value().begin(), event.bytes_value().end());
            break;
        case routerpb::VALUE_F64:
            ALOGE("ARDA_VHAL_PROXY_ROUTER_DROP_F64 property=%d area=%d",
                  event.property_id(), event.area_id());
            return std::nullopt;
        case routerpb::VALUE_UNSPECIFIED:
        default:
            ALOGE("ARDA_VHAL_PROXY_ROUTER_DROP_VALUE_TYPE property=%d area=%d type=%d",
                  event.property_id(), event.area_id(), static_cast<int>(event.value_type()));
            return std::nullopt;
    }
    return value;
}

bool optionMatches(const SubscribeOptions& option, const VehiclePropValue& value) {
    if (option.propId != value.prop) {
        return false;
    }
    return option.areaIds.empty() ||
            std::find(option.areaIds.begin(), option.areaIds.end(), value.areaId) !=
                    option.areaIds.end();
}

void sleepBeforeReconnect(const std::atomic<bool>& stop) {
    for (int i = 0; i < 10 && !stop.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
}

}  // namespace

ArdaVehicleProxy::ArdaVehicleProxy(std::shared_ptr<aidlvhal::IVehicle> base)
    : mBase(std::move(base)) {}

ArdaVehicleProxy::~ArdaVehicleProxy() {
    mStop.store(true);
    int fd = mRouterFd.load();
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
    }
    if (mRouterThread.joinable()) {
        mRouterThread.join();
    }
}

bool ArdaVehicleProxy::start() {
    VehiclePropConfigs configs;
    ScopedAStatus status = mBase->getPropConfigs(kOverlayProps, &configs);
    if (!status.isOk()) {
        ALOGE("ARDA_VHAL_PROXY_CONFIG_LOAD_FAIL error=%s", status.getMessage());
        return false;
    }

    auto parsed = fromStableLargeParcelable(configs);
    if (!parsed.ok()) {
        ALOGE("ARDA_VHAL_PROXY_CONFIG_PARSE_FAIL error=%s", parsed.error().getMessage());
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mLock);
        for (const auto& config : parsed.value().getObject()->payloads) {
            if (isOverlayProperty(config.prop)) {
                mOverlayConfigs[config.prop] = config;
            }
        }
    }

    if (mOverlayConfigs.size() != kOverlayProps.size()) {
        ALOGE("ARDA_VHAL_PROXY_CONFIG_COUNT_FAIL expected=%zu actual=%zu",
              kOverlayProps.size(), mOverlayConfigs.size());
        return false;
    }

    mRouterThread = std::thread(&ArdaVehicleProxy::routerLoop, this);
    ALOGI("ARDA_VHAL_PROXY_OVERLAY_READY properties=%zu", mOverlayConfigs.size());
    return true;
}

bool ArdaVehicleProxy::isOverlayProperty(int32_t propId) {
    return std::find(kOverlayProps.begin(), kOverlayProps.end(), propId) != kOverlayProps.end();
}

ScopedAStatus ArdaVehicleProxy::getAllPropConfigs(VehiclePropConfigs* returnConfigs) {
    return mBase->getAllPropConfigs(returnConfigs);
}

ScopedAStatus ArdaVehicleProxy::getPropConfigs(const std::vector<int32_t>& props,
                                                VehiclePropConfigs* returnConfigs) {
    return mBase->getPropConfigs(props, returnConfigs);
}

ScopedAStatus ArdaVehicleProxy::getValues(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const GetValueRequests& requests) {
    if (callback == nullptr) {
        return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }

    auto parsed = fromStableLargeParcelable(requests);
    if (!parsed.ok()) {
        return std::move(parsed.error());
    }

    const std::vector<GetValueRequest>& allRequests = parsed.value().getObject()->payloads;
    std::set<int64_t> requestIds;
    std::set<std::pair<int32_t, int32_t>> requestedProps;
    std::vector<GetValueRequest> baseRequests;
    std::vector<GetValueResult> overlayResults;

    for (const auto& request : allRequests) {
        if (!requestIds.insert(request.requestId).second) {
            return serviceError(StatusCode::INVALID_ARG, "duplicate get request ID");
        }
        const auto key = std::make_pair(request.prop.prop, request.prop.areaId);
        if (!requestedProps.insert(key).second) {
            return serviceError(StatusCode::INVALID_ARG, "duplicate get property and area");
        }

        if (!isOverlayProperty(request.prop.prop)) {
            baseRequests.push_back(request);
            continue;
        }

        GetValueResult result{
                .requestId = request.requestId,
                .status = StatusCode::TRY_AGAIN,
                .prop = std::nullopt,
        };
        {
            std::lock_guard<std::mutex> lock(mLock);
            auto it = mLatestValues.find(key);
            if (it != mLatestValues.end()) {
                result.status = StatusCode::OK;
                result.prop = it->second;
            }
        }
        overlayResults.push_back(std::move(result));
    }

    if (!baseRequests.empty()) {
        GetValueRequests baseParcelable;
        ScopedAStatus status = vectorToStableLargeParcelable(std::move(baseRequests), &baseParcelable);
        if (!status.isOk()) {
            return status;
        }
        status = mBase->getValues(callback, baseParcelable);
        if (!status.isOk()) {
            return status;
        }
    }

    if (!overlayResults.empty()) {
        GetValueResults results;
        ScopedAStatus status =
                vectorToStableLargeParcelable(std::move(overlayResults), &results);
        if (!status.isOk()) {
            return status;
        }
        status = callback->onGetValues(results);
        if (!status.isOk()) {
            ALOGE("ARDA_VHAL_PROXY_GET_CALLBACK_FAIL error=%s", status.getMessage());
            return status;
        }
    }

    return ScopedAStatus::ok();
}

ScopedAStatus ArdaVehicleProxy::setValues(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const aidlvhal::SetValueRequests& requests) {
    return mBase->setValues(callback, requests);
}

ScopedAStatus ArdaVehicleProxy::validateOverlaySubscriptions(
        const std::vector<SubscribeOptions>& options,
        int32_t maxSharedMemoryFileCount) const {
    if (maxSharedMemoryFileCount < 0 ||
        maxSharedMemoryFileCount >= aidlvhal::IVehicle::MAX_SHARED_MEMORY_FILES_PER_CLIENT) {
        return serviceError(StatusCode::INVALID_ARG, "invalid shared memory file count");
    }

    std::lock_guard<std::mutex> lock(mLock);
    for (const auto& option : options) {
        auto configIt = mOverlayConfigs.find(option.propId);
        if (configIt == mOverlayConfigs.end()) {
            return serviceError(StatusCode::INVALID_ARG, "overlay property config missing");
        }
        const auto& config = configIt->second;
        if (config.changeMode == VehiclePropertyChangeMode::CONTINUOUS && option.sampleRate <= 0) {
            return serviceError(StatusCode::INVALID_ARG,
                                "continuous overlay property requires positive sample rate");
        }
        for (int32_t areaId : option.areaIds) {
            if (config.areaConfigs.empty()) {
                if (areaId != 0) {
                    return serviceError(StatusCode::INVALID_ARG, "invalid global overlay area");
                }
                continue;
            }
            const bool found = std::any_of(
                    config.areaConfigs.begin(), config.areaConfigs.end(),
                    [areaId](const auto& areaConfig) { return areaConfig.areaId == areaId; });
            if (!found) {
                return serviceError(StatusCode::INVALID_ARG, "invalid overlay area");
            }
        }
    }
    return ScopedAStatus::ok();
}

ScopedAStatus ArdaVehicleProxy::subscribe(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const std::vector<SubscribeOptions>& options,
        int32_t maxSharedMemoryFileCount) {
    if (callback == nullptr) {
        return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }

    std::vector<SubscribeOptions> overlayOptions;
    std::vector<SubscribeOptions> baseOptions;
    for (const auto& option : options) {
        (isOverlayProperty(option.propId) ? overlayOptions : baseOptions).push_back(option);
    }

    ScopedAStatus status = validateOverlaySubscriptions(overlayOptions, maxSharedMemoryFileCount);
    if (!status.isOk()) {
        return status;
    }

    if (!baseOptions.empty()) {
        status = mBase->subscribe(callback, baseOptions, maxSharedMemoryFileCount);
        if (!status.isOk()) {
            return status;
        }
    }

    if (!overlayOptions.empty()) {
        std::lock_guard<std::mutex> lock(mLock);
        const AIBinder* clientId = callback->asBinder().get();
        auto& state = mSubscriptions[clientId];
        state.callback = callback;
        for (const auto& option : overlayOptions) {
            state.options.erase(
                    std::remove_if(state.options.begin(), state.options.end(),
                                   [&option](const auto& existing) {
                                       return existing.propId == option.propId;
                                   }),
                    state.options.end());
            state.options.push_back(option);
        }
    }

    return ScopedAStatus::ok();
}

ScopedAStatus ArdaVehicleProxy::unsubscribe(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const std::vector<int32_t>& propIds) {
    if (callback == nullptr) {
        return ScopedAStatus::fromExceptionCode(EX_NULL_POINTER);
    }

    std::vector<int32_t> basePropIds;
    std::vector<int32_t> overlayPropIds;
    for (int32_t propId : propIds) {
        (isOverlayProperty(propId) ? overlayPropIds : basePropIds).push_back(propId);
    }

    if (!basePropIds.empty()) {
        ScopedAStatus status = mBase->unsubscribe(callback, basePropIds);
        if (!status.isOk()) {
            return status;
        }
    }

    if (!overlayPropIds.empty()) {
        std::lock_guard<std::mutex> lock(mLock);
        const AIBinder* clientId = callback->asBinder().get();
        auto it = mSubscriptions.find(clientId);
        if (it != mSubscriptions.end()) {
            it->second.options.erase(
                    std::remove_if(it->second.options.begin(), it->second.options.end(),
                                   [&overlayPropIds](const auto& option) {
                                       return std::find(overlayPropIds.begin(), overlayPropIds.end(),
                                                        option.propId) != overlayPropIds.end();
                                   }),
                    it->second.options.end());
            if (it->second.options.empty()) {
                mSubscriptions.erase(it);
            }
        }
    }

    return ScopedAStatus::ok();
}

ScopedAStatus ArdaVehicleProxy::returnSharedMemory(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        int64_t sharedMemoryId) {
    return mBase->returnSharedMemory(callback, sharedMemoryId);
}

ScopedAStatus ArdaVehicleProxy::getSupportedValuesLists(
        const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds,
        aidlvhal::SupportedValuesListResults* results) {
    return mBase->getSupportedValuesLists(propIdAreaIds, results);
}

ScopedAStatus ArdaVehicleProxy::getMinMaxSupportedValue(
        const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds,
        aidlvhal::MinMaxSupportedValueResults* results) {
    return mBase->getMinMaxSupportedValue(propIdAreaIds, results);
}

ScopedAStatus ArdaVehicleProxy::registerSupportedValueChangeCallback(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds) {
    return mBase->registerSupportedValueChangeCallback(callback, propIdAreaIds);
}

ScopedAStatus ArdaVehicleProxy::unregisterSupportedValueChangeCallback(
        const std::shared_ptr<aidlvhal::IVehicleCallback>& callback,
        const std::vector<aidlvhal::PropIdAreaId>& propIdAreaIds) {
    return mBase->unregisterSupportedValueChangeCallback(callback, propIdAreaIds);
}

binder_status_t ArdaVehicleProxy::dump(int fd, const char** args, uint32_t numArgs) {
    size_t subscriptionCount = 0;
    size_t cacheCount = 0;
    {
        std::lock_guard<std::mutex> lock(mLock);
        subscriptionCount = mSubscriptions.size();
        cacheCount = mLatestValues.size();
    }
    dprintf(fd, "ARDA VHAL proxy: router_fd=%d subscriptions=%zu cached_values=%zu\n",
            mRouterFd.load(), subscriptionCount, cacheCount);
    return AIBinder_dump(mBase->asBinder().get(), fd, args, numArgs);
}

void ArdaVehicleProxy::handleRouterEvents(const std::vector<VehiclePropValue>& values) {
    struct Dispatch {
        std::shared_ptr<aidlvhal::IVehicleCallback> callback;
        std::vector<VehiclePropValue> values;
    };
    std::vector<Dispatch> dispatches;

    {
        std::lock_guard<std::mutex> lock(mLock);
        for (const auto& value : values) {
            mLatestValues[{value.prop, value.areaId}] = value;
        }

        for (auto it = mSubscriptions.begin(); it != mSubscriptions.end();) {
            std::shared_ptr<aidlvhal::IVehicleCallback> callback = it->second.callback.lock();
            if (callback == nullptr) {
                it = mSubscriptions.erase(it);
                continue;
            }

            Dispatch dispatch{.callback = std::move(callback), .values = {}};
            for (const auto& value : values) {
                if (std::any_of(it->second.options.begin(), it->second.options.end(),
                                [&value](const auto& option) {
                                    return optionMatches(option, value);
                                })) {
                    dispatch.values.push_back(value);
                }
            }
            if (!dispatch.values.empty()) {
                dispatches.push_back(std::move(dispatch));
            }
            ++it;
        }
    }

    for (auto& dispatch : dispatches) {
        aidlvhal::VehiclePropValues parcelable;
        parcelable.payloads = std::move(dispatch.values);
        parcelable.sharedMemoryId = aidlvhal::IVehicle::INVALID_MEMORY_ID;
        parcelable.sharedMemoryFd = ndk::ScopedFileDescriptor();
        ScopedAStatus status = dispatch.callback->onPropertyEvent(parcelable, 0);
        if (!status.isOk()) {
            ALOGE("ARDA_VHAL_PROXY_EVENT_CALLBACK_FAIL error=%s", status.getMessage());
        }
    }
}

void ArdaVehicleProxy::routerLoop() {
    while (!mStop.load()) {
        int fd = connectRouter();
        if (fd < 0) {
            ALOGW("ARDA_VHAL_PROXY_ROUTER_CONNECT_RETRY errno=%d", errno);
            sleepBeforeReconnect(mStop);
            continue;
        }
        mRouterFd.store(fd);

        routerpb::Envelope helloEnvelope;
        helloEnvelope.set_protocol_version(kRouterProtocolVersion);
        auto* hello = helloEnvelope.mutable_hello();
        hello->set_protocol_version(kRouterProtocolVersion);
        hello->set_role(routerpb::ROLE_VHAL);
        hello->set_client_name(kRouterClientName);

        routerpb::Envelope ackEnvelope;
        if (!writeEnvelope(fd, helloEnvelope) || !readEnvelope(fd, &ackEnvelope) ||
            ackEnvelope.protocol_version() != kRouterProtocolVersion ||
            !ackEnvelope.has_hello_ack() || !ackEnvelope.hello_ack().accepted() ||
            ackEnvelope.hello_ack().protocol_version() != kRouterProtocolVersion) {
            ALOGE("ARDA_VHAL_PROXY_ROUTER_HANDSHAKE_FAIL");
            close(fd);
            mRouterFd.store(-1);
            sleepBeforeReconnect(mStop);
            continue;
        }

        ALOGI("ARDA_VHAL_PROXY_ROUTER_CONNECTED peer_uid=%u",
              ackEnvelope.hello_ack().peer_uid());

        while (!mStop.load()) {
            routerpb::Envelope envelope;
            if (!readEnvelope(fd, &envelope)) {
                break;
            }
            if (envelope.protocol_version() != kRouterProtocolVersion) {
                ALOGE("ARDA_VHAL_PROXY_ROUTER_PROTOCOL_MISMATCH actual=%u",
                      envelope.protocol_version());
                break;
            }

            if (envelope.has_vhal_events()) {
                std::vector<VehiclePropValue> values;
                for (const auto& event : envelope.vhal_events().events()) {
                    if (!isOverlayProperty(event.property_id())) {
                        ALOGW("ARDA_VHAL_PROXY_ROUTER_DROP_NON_OVERLAY property=%d area=%d",
                              event.property_id(), event.area_id());
                        continue;
                    }
                    std::optional<VehiclePropValue> value = fromRouterEvent(event);
                    if (value.has_value()) {
                        values.push_back(std::move(*value));
                    }
                }
                if (!values.empty()) {
                    ALOGI("ARDA_VHAL_PROXY_ROUTER_EVENTS count=%zu", values.size());
                    handleRouterEvents(values);
                }
                continue;
            }

            if (envelope.has_ping()) {
                routerpb::Envelope pongEnvelope;
                pongEnvelope.set_protocol_version(kRouterProtocolVersion);
                pongEnvelope.mutable_pong()->set_nonce(envelope.ping().nonce());
                if (!writeEnvelope(fd, pongEnvelope)) {
                    break;
                }
            }
        }

        shutdown(fd, SHUT_RDWR);
        close(fd);
        mRouterFd.store(-1);
        if (!mStop.load()) {
            ALOGW("ARDA_VHAL_PROXY_ROUTER_DISCONNECTED");
            sleepBeforeReconnect(mStop);
        }
    }
}

}  // namespace arda::vehicle::vhal
