package com.arda.vehicle.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Slog;

import com.arda.vehicle.sdk.ArdaVehicleContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AdapterDiscovery {
    private static final String TAG = "ArdaVehicleManager";

    private final Context mContext;

    AdapterDiscovery(Context context) {
        mContext = context;
    }

    List<AdapterCandidate> discover(int userId) {
        Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
        PackageManager pm = userContext.getPackageManager();
        Intent intent = new Intent(ArdaVehicleContract.ACTION_ADAPTER);
        List<ResolveInfo> services = pm.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(
                        PackageManager.GET_META_DATA
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE));
        List<AdapterCandidate> result = new ArrayList<>();
        for (ResolveInfo resolveInfo : services) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo == null || !serviceInfo.exported) {
                continue;
            }
            if (!ArdaVehicleContract.PERMISSION_BIND_ADAPTER.equals(serviceInfo.permission)) {
                Slog.w(TAG, "ARDA_VM_DISCOVERY_REJECT component="
                        + new ComponentName(serviceInfo.packageName, serviceInfo.name)
                        + " reason=service must require "
                        + ArdaVehicleContract.PERMISSION_BIND_ADAPTER);
                continue;
            }
            try {
                result.add(readCandidate(serviceInfo, userId));
            } catch (RuntimeException | PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "ARDA_VM_DISCOVERY_REJECT component="
                        + new ComponentName(serviceInfo.packageName, serviceInfo.name)
                        + " reason=" + e.getMessage());
            }
        }
        result.sort(Comparator.comparing(candidate -> candidate.component.flattenToShortString()));
        return result;
    }

    private AdapterCandidate readCandidate(ServiceInfo serviceInfo, int userId)
            throws PackageManager.NameNotFoundException {
        Bundle metadata = serviceInfo.metaData;
        if (metadata == null) {
            throw new IllegalArgumentException("missing adapter metadata");
        }
        String adapterId = requireString(metadata, ArdaVehicleContract.META_ADAPTER_ID);
        String displayName = requireString(metadata, ArdaVehicleContract.META_DISPLAY_NAME);
        int apiVersion = metadata.getInt(ArdaVehicleContract.META_API_VERSION, 0);
        if (apiVersion != ArdaVehicleContract.API_VERSION) {
            throw new IllegalArgumentException("unsupported apiVersion=" + apiVersion);
        }
        int requestedPriority = metadata.getInt(
                ArdaVehicleContract.META_REQUESTED_PRIORITY,
                GrantStore.DEFAULT_ADAPTER_PRIORITY);
        int[] publishCapabilities = CapabilityParser.parse(
                metadata.getString(ArdaVehicleContract.META_PUBLISH_CAPABILITIES, ""),
                false);
        int[] commandCapabilities = CapabilityParser.parse(
                metadata.getString(ArdaVehicleContract.META_COMMAND_CAPABILITIES, ""),
                true);
        if (publishCapabilities.length == 0 && commandCapabilities.length == 0) {
            throw new IllegalArgumentException("adapter requests no capabilities");
        }
        ComponentName component = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        SigningIdentity identity = SigningIdentity.read(mContext, serviceInfo.packageName, userId);
        return new AdapterCandidate(
                userId,
                component,
                serviceInfo.applicationInfo.uid,
                adapterId,
                displayName,
                apiVersion,
                requestedPriority,
                publishCapabilities,
                commandCapabilities,
                identity);
    }

    private static String requireString(Bundle metadata, String key) {
        String value = metadata.getString(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing metadata " + key);
        }
        return value.trim();
    }
}
