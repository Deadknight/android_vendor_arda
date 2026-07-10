/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "ArdaGnssAidl"

#include "Gnss.h"
#include <cerrno>
#include <cstdlib>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <optional>
#include <poll.h>
#include <sstream>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <vector>
#include <inttypes.h>
#include <log/log.h>
#include <utils/Timers.h>
#include "AGnss.h"
#include "AGnssRil.h"
#include "DeviceFileReader.h"
#include "FixLocationParser.h"
#include "GnssAntennaInfo.h"
#include "GnssAssistanceInterface.h"
#include "GnssBatching.h"
#include "GnssConfiguration.h"
#include "GnssDebug.h"
#include "GnssGeofence.h"
#include "GnssNavigationMessageInterface.h"
#include "GnssPsds.h"
#include "GnssVisibilityControl.h"
#include "MeasurementCorrectionsInterface.h"
#include "Utils.h"

namespace aidl::android::hardware::gnss {
using ::android::hardware::gnss::common::Utils;

using ndk::ScopedAStatus;
using GnssSvInfo = IGnssCallback::GnssSvInfo;

constexpr int TTFF_MILLIS = 2200;

namespace {

constexpr char kArdaGnssSocketName[] = "arda_gnss_hal";
constexpr char kArdaGnssWireVersion[] = "ARDA_GNSS_HAL_V1";
constexpr size_t kMaxWireBufferBytes = 16384;

std::vector<std::string> splitTabs(const std::string& value) {
    std::vector<std::string> fields;
    std::stringstream stream(value);
    std::string field;

    while (std::getline(stream, field, '\t')) {
        fields.push_back(field);
    }

    return fields;
}

bool parseDouble(const std::string& value, double* output) {
    if (value.empty()) {
        return false;
    }

    errno = 0;

    char* end = nullptr;
    const char* begin = value.c_str();
    const double parsed = std::strtod(begin, &end);

    if (end == begin
            || end == nullptr
            || *end != '\0'
            || errno == ERANGE
            || !std::isfinite(parsed)) {
        return false;
    }

    *output = parsed;
    return true;
}

bool parseOptionalDouble(
        const std::string& value,
        std::optional<double>* output) {
    if (value == "-") {
        *output = std::nullopt;
        return true;
    }

    double parsed = 0.0;

    if (!parseDouble(value, &parsed)) {
        return false;
    }

    *output = parsed;
    return true;
}

bool parseArdaFix(const std::string& line, GnssLocation* location) {
    const auto fields = splitTabs(line);

    if (fields.size() != 9
            || fields[0] != kArdaGnssWireVersion
            || fields[1] != "FIX") {
        return false;
    }

    double latitude = 0.0;
    double longitude = 0.0;
    std::optional<double> accuracy;
    std::optional<double> altitude;
    std::optional<double> speed;
    std::optional<double> bearing;

    if (!parseDouble(fields[3], &latitude)
            || !parseDouble(fields[4], &longitude)
            || !parseOptionalDouble(fields[5], &accuracy)
            || !parseOptionalDouble(fields[6], &altitude)
            || !parseOptionalDouble(fields[7], &speed)
            || !parseOptionalDouble(fields[8], &bearing)) {
        return false;
    }

    if (latitude < -90.0 || latitude > 90.0
            || longitude < -180.0 || longitude > 180.0
            || (accuracy.has_value() && *accuracy < 0.0)
            || (speed.has_value() && *speed < 0.0)
            || (bearing.has_value()
                    && (*bearing < 0.0 || *bearing >= 360.0))) {
        return false;
    }

    constexpr int kHasLatLong = 1 << 0;
    constexpr int kHasAltitude = 1 << 1;
    constexpr int kHasSpeed = 1 << 2;
    constexpr int kHasBearing = 1 << 3;
    constexpr int kHasHorizontalAccuracy = 1 << 4;
    constexpr int kElapsedRealtimeHasTimestampNs = 1 << 0;

    int flags = kHasLatLong | kHasHorizontalAccuracy;

    location->latitudeDegrees = latitude;
    location->longitudeDegrees = longitude;
    location->horizontalAccuracyMeters =
            accuracy.value_or(100.0);

    if (altitude.has_value()) {
        flags |= kHasAltitude;
        location->altitudeMeters = *altitude;
    }

    if (speed.has_value()) {
        flags |= kHasSpeed;
        location->speedMetersPerSec = *speed;
    }

    if (bearing.has_value()) {
        flags |= kHasBearing;
        location->bearingDegrees = *bearing;
    }

    location->gnssLocationFlags = flags;

    location->timestampMillis =
            std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch())
                    .count();

    location->elapsedRealtime.flags =
            kElapsedRealtimeHasTimestampNs;
    location->elapsedRealtime.timestampNs =
            static_cast<int64_t>(::android::elapsedRealtimeNano());
    location->elapsedRealtime.timeUncertaintyNs = 0.0;

    return true;
}

int createArdaGnssServerSocket() {
    const int fd = socket(
            AF_UNIX,
            SOCK_STREAM | SOCK_CLOEXEC,
            0);

    if (fd < 0) {
        ALOGE(
                "ARDA_GNSS_HAL_SERVER_ERROR stage=socket errno=%d",
                errno);
        return -1;
    }

    sockaddr_un address = {};
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';

    const size_t nameLength = strlen(kArdaGnssSocketName);

    if (nameLength + 1 > sizeof(address.sun_path)) {
        ALOGE(
                "ARDA_GNSS_HAL_SERVER_ERROR stage=name_length length=%zu",
                nameLength);
        close(fd);
        return -1;
    }

    memcpy(
            address.sun_path + 1,
            kArdaGnssSocketName,
            nameLength);

    const socklen_t addressLength = static_cast<socklen_t>(
            offsetof(sockaddr_un, sun_path)
            + 1
            + nameLength);

    if (bind(
                fd,
                reinterpret_cast<const sockaddr*>(&address),
                addressLength) != 0) {
        ALOGE(
                "ARDA_GNSS_HAL_SERVER_ERROR stage=bind errno=%d",
                errno);
        close(fd);
        return -1;
    }

    if (listen(fd, 4) != 0) {
        ALOGE(
                "ARDA_GNSS_HAL_SERVER_ERROR stage=listen errno=%d",
                errno);
        close(fd);
        return -1;
    }

    return fd;
}

}  // namespace

std::shared_ptr<IGnssCallback> Gnss::sGnssCallback = nullptr;

Gnss::Gnss()
    : mMinIntervalMs(1000),
      mIsActive(false),
      mIsSvStatusActive(false),
      mIsNmeaActive(false),
      mFirstFixReceived(false) {}

ScopedAStatus Gnss::setCallback(
        const std::shared_ptr<IGnssCallback>& callback) {
    ALOGD("setCallback");

    if (callback == nullptr) {
        ALOGE("%s: Null callback ignored", __func__);
        return ScopedAStatus::fromExceptionCode(
                STATUS_INVALID_OPERATION);
    }

    {
        std::unique_lock<std::mutex> lock(mMutex);
        sGnssCallback = callback;
    }

    auto status = callback->gnssSetCapabilitiesCb(
            IGnssCallback::CAPABILITY_SCHEDULING);

    if (!status.isOk()) {
        ALOGE(
                "%s: Unable to invoke gnssSetCapabilitiesCb",
                __func__);
    }

    IGnssCallback::GnssSystemInfo systemInfo = {
            .yearOfHw = 2026,
            .name = "ARDA AAP GNSS AIDL v3",
    };

    status = callback->gnssSetSystemInfoCb(systemInfo);

    if (!status.isOk()) {
        ALOGE(
                "%s: Unable to invoke gnssSetSystemInfoCb",
                __func__);
    }

    status = callback->gnssSetSignalTypeCapabilitiesCb(
            std::vector<GnssSignalType>{});

    if (!status.isOk()) {
        ALOGE(
                "%s: Unable to invoke gnssSetSignalTypeCapabilitiesCb",
                __func__);
    }

    ALOGI("ARDA_GNSS_HAL_CALLBACK_READY capabilities=scheduling signal_types=0");
    return ScopedAStatus::ok();
}

std::unique_ptr<GnssLocation> Gnss::getLocationFromHW() {
    if (!::android::hardware::gnss::common::ReplayUtils::hasFixedLocationDeviceFile()) {
        return nullptr;
    }
    std::string inputStr =
            ::android::hardware::gnss::common::DeviceFileReader::Instance().getLocationData();
    return ::android::hardware::gnss::common::FixLocationParser::getLocationFromInputStr(inputStr);
}

ScopedAStatus Gnss::start() {
    ALOGD("start()");

    if (mIsActive) {
        ALOGW("GNSS already active; restarting");
        stop();
    }

    mIsActive = true;
    mThreadBlocker.reset();

    reportGnssStatusValue(
            IGnssCallback::GnssStatusValue::SESSION_BEGIN);

    ALOGI("ARDA_GNSS_HAL_SESSION_BEGIN");

    mThread = std::thread([this]() {
        const int serverFd = createArdaGnssServerSocket();

        if (serverFd < 0) {
            return;
        }

        ALOGI(
                "ARDA_GNSS_HAL_READY socket=%s",
                kArdaGnssSocketName);

        while (mIsActive) {
            pollfd serverPoll = {
                    .fd = serverFd,
                    .events = POLLIN,
                    .revents = 0,
            };

            const int pollResult = poll(&serverPoll, 1, 500);

            if (!mIsActive) {
                break;
            }

            if (pollResult < 0) {
                if (errno == EINTR) {
                    continue;
                }

                ALOGE(
                        "ARDA_GNSS_HAL_SERVER_ERROR stage=poll_server errno=%d",
                        errno);
                break;
            }

            if (pollResult == 0
                    || !(serverPoll.revents & POLLIN)) {
                continue;
            }

            const int clientFd = accept4(
                    serverFd,
                    nullptr,
                    nullptr,
                    SOCK_CLOEXEC);

            if (clientFd < 0) {
                ALOGE(
                        "ARDA_GNSS_HAL_SERVER_ERROR stage=accept errno=%d",
                        errno);
                continue;
            }

            ucred credentials = {};
            socklen_t credentialsLength = sizeof(credentials);

            if (getsockopt(
                        clientFd,
                        SOL_SOCKET,
                        SO_PEERCRED,
                        &credentials,
                        &credentialsLength) != 0) {
                ALOGE(
                        "ARDA_GNSS_HAL_SERVER_ERROR stage=peercred errno=%d",
                        errno);
                ::close(clientFd);
                continue;
            }

            if (credentials.uid != 0) {
                ALOGW(
                        "ARDA_GNSS_HAL_CLIENT_REJECT uid=%d pid=%d",
                        credentials.uid,
                        credentials.pid);
                ::close(clientFd);
                continue;
            }

            ALOGI(
                    "ARDA_GNSS_HAL_CLIENT uid=%d pid=%d",
                    credentials.uid,
                    credentials.pid);

            std::string buffer;
            GnssLocation latestLocation = {};
            bool hasLatestLocation = false;
            int64_t lastReportNs = 0;

            const auto reportLatestIfDue =
                    [this,
                     &latestLocation,
                     &hasLatestLocation,
                     &lastReportNs](const char* reason) {
                if (!hasLatestLocation) {
                    return;
                }

                const int64_t nowNs = static_cast<int64_t>(
                        ::android::elapsedRealtimeNano());
                const int64_t intervalMs = std::max<int64_t>(
                        100,
                        static_cast<int64_t>(mMinIntervalMs.load()));
                const int64_t intervalNs = intervalMs * 1000000LL;

                if (lastReportNs != 0
                        && nowNs - lastReportNs < intervalNs) {
                    return;
                }

                GnssLocation reportedLocation = latestLocation;
                reportedLocation.timestampMillis =
                        std::chrono::duration_cast<std::chrono::milliseconds>(
                                std::chrono::system_clock::now().time_since_epoch())
                                .count();
                reportedLocation.elapsedRealtime.timestampNs = nowNs;

                reportLocation(reportedLocation);
                lastReportNs = nowNs;

                if (std::string(reason) != "active_replay") {
                    ALOGI(
                            "ARDA_GNSS_HAL_FIX_REPORTED lat=%.7f lon=%.7f reason=%s",
                            latestLocation.latitudeDegrees,
                            latestLocation.longitudeDegrees,
                            reason);
                }
            };

            while (mIsActive) {
                pollfd clientPoll = {
                        .fd = clientFd,
                        .events = POLLIN,
                        .revents = 0,
                };

                const int64_t intervalMs = std::max<int64_t>(
                        100,
                        static_cast<int64_t>(mMinIntervalMs.load()));
                const int pollTimeoutMs = static_cast<int>(
                        std::min<int64_t>(intervalMs, 100));

                const int clientPollResult =
                        poll(&clientPoll, 1, pollTimeoutMs);

                if (!mIsActive) {
                    break;
                }

                if (clientPollResult < 0) {
                    if (errno == EINTR) {
                        continue;
                    }

                    ALOGE(
                            "ARDA_GNSS_HAL_SERVER_ERROR stage=poll_client errno=%d",
                            errno);
                    break;
                }

                if (clientPollResult == 0) {
                    reportLatestIfDue("active_replay");
                    continue;
                }

                if (clientPoll.revents
                        & (POLLERR | POLLHUP | POLLNVAL)) {
                    break;
                }

                if (!(clientPoll.revents & POLLIN)) {
                    reportLatestIfDue("active_replay");
                    continue;
                }

                char chunk[2048];
                const ssize_t received =
                        recv(clientFd, chunk, sizeof(chunk), 0);

                if (received <= 0) {
                    break;
                }

                buffer.append(
                        chunk,
                        static_cast<size_t>(received));

                if (buffer.size() > kMaxWireBufferBytes) {
                    ALOGW(
                            "ARDA_GNSS_HAL_WIRE_REJECT reason=buffer_limit bytes=%zu",
                            buffer.size());
                    break;
                }

                size_t newline = std::string::npos;

                while ((newline = buffer.find('\n'))
                        != std::string::npos) {
                    std::string line = buffer.substr(0, newline);
                    buffer.erase(0, newline + 1);

                    if (!line.empty() && line.back() == '\r') {
                        line.pop_back();
                    }

                    GnssLocation location = {};

                    if (!parseArdaFix(line, &location)) {
                        ALOGW(
                                "ARDA_GNSS_HAL_WIRE_REJECT reason=invalid_fix");
                        continue;
                    }

                    ALOGI(
                            "ARDA_GNSS_HAL_FIX_RECEIVED lat=%.7f lon=%.7f accuracy_m=%.1f altitude_present=%d speed_present=%d bearing_present=%d",
                            location.latitudeDegrees,
                            location.longitudeDegrees,
                            location.horizontalAccuracyMeters,
                            (location.gnssLocationFlags & (1 << 1)) != 0,
                            (location.gnssLocationFlags & (1 << 2)) != 0,
                            (location.gnssLocationFlags & (1 << 3)) != 0);

                    latestLocation = location;
                    hasLatestLocation = true;
                    reportLatestIfDue("source_update");
                }

                reportLatestIfDue("active_replay");
            }

            ALOGI(
                    "ARDA_GNSS_HAL_SOURCE_DISCONNECTED had_fix=%d",
                    hasLatestLocation ? 1 : 0);
            ::close(clientFd);
        }

        ::close(serverFd);
    });

    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::stop() {
    ALOGD("stop");

    const bool wasActive = mIsActive.exchange(false);
    mThreadBlocker.notify();

    if (mThread.joinable()) {
        mThread.join();
    }

    if (wasActive) {
        reportGnssStatusValue(
                IGnssCallback::GnssStatusValue::SESSION_END);
        ALOGI("ARDA_GNSS_HAL_SESSION_END");
    }

    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::close() {
    ALOGD("close");
    stop();

    std::unique_lock<std::mutex> lock(mMutex);
    sGnssCallback = nullptr;

    return ScopedAStatus::ok();
}

void Gnss::reportLocation(
        const GnssLocation& location) {
    std::unique_lock<std::mutex> lock(mMutex);

    if (sGnssCallback == nullptr) {
        ALOGE("%s: GnssCallback is null.", __func__);
        return;
    }

    auto status = sGnssCallback->gnssLocationCb(location);

    if (!status.isOk()) {
        ALOGE(
                "%s: Unable to invoke gnssLocationCb",
                __func__);
    }
}

void Gnss::reportSvStatus() const {
    if (mIsSvStatusActive) {
        ALOGV(
                "ARDA_GNSS_HAL_SV_NOT_REPORTED reason=source_semantics_incomplete");
    }
}

void Gnss::reportSvStatus(const std::vector<GnssSvInfo>& svInfoList) const {
    std::shared_ptr<IGnssCallback> callback;
    {
        std::unique_lock<std::mutex> lock(mMutex);
        if (sGnssCallback == nullptr) {
            ALOGE("%s: GnssCallback is null.", __func__);
            return;
        }
        callback = sGnssCallback;
    }
    auto status = callback->gnssSvStatusCb(svInfoList);
    if (!status.isOk()) {
        ALOGE("%s: Unable to invoke callback", __func__);
    }
}

std::vector<GnssSvInfo> Gnss::filterBlocklistedSatellites(
        std::vector<GnssSvInfo> gnssSvInfoList) const {
    for (uint32_t i = 0; i < gnssSvInfoList.size(); i++) {
        if (mGnssConfiguration->isBlocklisted(gnssSvInfoList[i])) {
            gnssSvInfoList[i].svFlag &= ~(uint32_t)IGnssCallback::GnssSvFlags::USED_IN_FIX;
        }
    }
    return gnssSvInfoList;
}

void Gnss::reportGnssStatusValue(const IGnssCallback::GnssStatusValue gnssStatusValue) const {
    std::shared_ptr<IGnssCallback> callback;
    {
        std::unique_lock<std::mutex> lock(mMutex);
        if (sGnssCallback == nullptr) {
            ALOGE("%s: GnssCallback is null.", __func__);
            return;
        }
        callback = sGnssCallback;
    }
    auto status = callback->gnssStatusCb(gnssStatusValue);
    if (!status.isOk()) {
        ALOGE("%s: Unable to invoke gnssStatusCb", __func__);
    }
}

void Gnss::reportNmea() const {
    if (mIsNmeaActive) {
        ALOGV(
                "ARDA_GNSS_HAL_NMEA_NOT_REPORTED reason=aap_has_no_nmea");
    }
}

ScopedAStatus Gnss::startSvStatus() {
    ALOGD("startSvStatus");
    mIsSvStatusActive = true;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::stopSvStatus() {
    ALOGD("stopSvStatus");
    mIsSvStatusActive = false;
    return ScopedAStatus::ok();
}
ScopedAStatus Gnss::startNmea() {
    ALOGD("startNmea");
    mIsNmeaActive = true;
    return ScopedAStatus::ok();
}
ScopedAStatus Gnss::stopNmea() {
    ALOGD("stopNmea");
    mIsNmeaActive = false;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionAGnss(std::shared_ptr<IAGnss>* iAGnss) {
    ALOGD("Gnss::getExtensionAGnss");
    *iAGnss = SharedRefBase::make<AGnss>();
    return ndk::ScopedAStatus::ok();
}

ScopedAStatus Gnss::injectTime(int64_t timeMs, int64_t timeReferenceMs, int uncertaintyMs) {
    ALOGD("injectTime. timeMs:%" PRId64 ", timeReferenceMs:%" PRId64 ", uncertaintyMs:%d", timeMs,
          timeReferenceMs, uncertaintyMs);
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionAGnssRil(std::shared_ptr<IAGnssRil>* iAGnssRil) {
    ALOGD("Gnss::getExtensionAGnssRil");
    *iAGnssRil = SharedRefBase::make<AGnssRil>();
    return ndk::ScopedAStatus::ok();
}

ScopedAStatus Gnss::injectLocation(const GnssLocation& location) {
    ALOGD("injectLocation. lat:%lf, lng:%lf, acc:%f", location.latitudeDegrees,
          location.longitudeDegrees, location.horizontalAccuracyMeters);
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::injectBestLocation(const GnssLocation& location) {
    ALOGD("injectBestLocation. lat:%lf, lng:%lf, acc:%f", location.latitudeDegrees,
          location.longitudeDegrees, location.horizontalAccuracyMeters);
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::deleteAidingData(GnssAidingData aidingDataFlags) {
    ALOGD("deleteAidingData. flags:%d", (int)aidingDataFlags);
    mFirstFixReceived = false;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::setPositionMode(
        const PositionModeOptions& options) {
    ALOGD(
            "setPositionMode. minIntervalMs:%d, lowPowerMode:%d",
            options.minIntervalMs,
            static_cast<int>(options.lowPowerMode));

    mMinIntervalMs = std::max(100, options.minIntervalMs);

    ALOGI(
            "ARDA_GNSS_HAL_POSITION_MODE min_interval_ms=%ld",
            mMinIntervalMs.load());

    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionPsds(std::shared_ptr<IGnssPsds>* iGnssPsds) {
    ALOGD("getExtensionPsds");
    *iGnssPsds = SharedRefBase::make<GnssPsds>();
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssConfiguration(
        std::shared_ptr<IGnssConfiguration>* iGnssConfiguration) {
    ALOGD("getExtensionGnssConfiguration");
    if (mGnssConfiguration == nullptr) {
        mGnssConfiguration = SharedRefBase::make<GnssConfiguration>();
    }
    *iGnssConfiguration = mGnssConfiguration;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssPowerIndication(
        std::shared_ptr<IGnssPowerIndication>* iGnssPowerIndication) {
    ALOGD("getExtensionGnssPowerIndication");
    if (mGnssPowerIndication == nullptr) {
        mGnssPowerIndication = SharedRefBase::make<GnssPowerIndication>();
    }

    *iGnssPowerIndication = mGnssPowerIndication;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssMeasurement(
        std::shared_ptr<IGnssMeasurementInterface>* iGnssMeasurement) {
    ALOGD("getExtensionGnssMeasurement");
    if (mGnssMeasurementInterface == nullptr) {
        mGnssMeasurementInterface = SharedRefBase::make<GnssMeasurementInterface>();
        mGnssMeasurementInterface->setGnssInterface(static_cast<std::shared_ptr<Gnss>>(this));
    }
    *iGnssMeasurement = mGnssMeasurementInterface;
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssBatching(std::shared_ptr<IGnssBatching>* iGnssBatching) {
    ALOGD("getExtensionGnssBatching");

    *iGnssBatching = SharedRefBase::make<GnssBatching>();
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssGeofence(std::shared_ptr<IGnssGeofence>* iGnssGeofence) {
    ALOGD("getExtensionGnssGeofence");

    *iGnssGeofence = SharedRefBase::make<GnssGeofence>();
    return ScopedAStatus::ok();
}

ScopedAStatus Gnss::getExtensionGnssNavigationMessage(
        std::shared_ptr<IGnssNavigationMessageInterface>* iGnssNavigationMessage) {
    ALOGD("getExtensionGnssNavigationMessage");

    *iGnssNavigationMessage = SharedRefBase::make<GnssNavigationMessageInterface>();
    return ScopedAStatus::ok();
}

ndk::ScopedAStatus Gnss::getExtensionGnssDebug(std::shared_ptr<IGnssDebug>* iGnssDebug) {
    ALOGD("Gnss::getExtensionGnssDebug");
    *iGnssDebug = SharedRefBase::make<GnssDebug>();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Gnss::getExtensionGnssVisibilityControl(
        std::shared_ptr<visibility_control::IGnssVisibilityControl>* iGnssVisibilityControl) {
    ALOGD("Gnss::getExtensionGnssVisibilityControl");

    *iGnssVisibilityControl = SharedRefBase::make<visibility_control::GnssVisibilityControl>();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Gnss::getExtensionGnssAntennaInfo(
        std::shared_ptr<IGnssAntennaInfo>* iGnssAntennaInfo) {
    ALOGD("Gnss::getExtensionGnssAntennaInfo");

    *iGnssAntennaInfo = SharedRefBase::make<GnssAntennaInfo>();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Gnss::getExtensionMeasurementCorrections(
        std::shared_ptr<measurement_corrections::IMeasurementCorrectionsInterface>*
                iMeasurementCorrections) {
    ALOGD("Gnss::getExtensionMeasurementCorrections");

    *iMeasurementCorrections =
            SharedRefBase::make<measurement_corrections::MeasurementCorrectionsInterface>();
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Gnss::getExtensionGnssAssistanceInterface(
        std::shared_ptr<gnss_assistance::IGnssAssistanceInterface>* iGnssAssistanceInterface) {
    ALOGD("Gnss::getExtensionGnssAssistanceInterface");

    *iGnssAssistanceInterface = SharedRefBase::make<gnss_assistance::GnssAssistanceInterface>();
    return ndk::ScopedAStatus::ok();
}

void Gnss::setGnssMeasurementEnabled(const bool enabled) {
    mGnssMeasurementEnabled = enabled;
}

void Gnss::setGnssMeasurementInterval(const long intervalMs) {
    mGnssMeasurementIntervalMs = intervalMs;
}

std::shared_ptr<GnssLocation> Gnss::getLastLocation() const {
    return mLastLocation;
}

}  // namespace aidl::android::hardware::gnss
