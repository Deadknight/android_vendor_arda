package com.arda.vehicle.manager;

import android.content.ComponentName;

import java.util.Arrays;

final class AdapterCandidate {
    final int userId;
    final ComponentName component;
    final int uid;
    final String adapterId;
    final String displayName;
    final int apiVersion;
    final int requestedPriority;
    final int[] publishCapabilities;
    final int[] commandCapabilities;
    final SigningIdentity signingIdentity;

    AdapterCandidate(
            int userId,
            ComponentName component,
            int uid,
            String adapterId,
            String displayName,
            int apiVersion,
            int requestedPriority,
            int[] publishCapabilities,
            int[] commandCapabilities,
            SigningIdentity signingIdentity) {
        this.userId = userId;
        this.component = component;
        this.uid = uid;
        this.adapterId = adapterId;
        this.displayName = displayName;
        this.apiVersion = apiVersion;
        this.requestedPriority = requestedPriority;
        this.publishCapabilities = publishCapabilities;
        this.commandCapabilities = commandCapabilities;
        this.signingIdentity = signingIdentity;
    }

    String key() {
        return userId + ":" + component.flattenToShortString();
    }

    String summary() {
        return "user=" + userId
                + " component=" + component.flattenToShortString()
                + " uid=" + uid
                + " adapter_id=" + adapterId
                + " api=" + apiVersion
                + " requested_priority=" + requestedPriority
                + " publish=" + Arrays.toString(publishCapabilities)
                + " commands=" + Arrays.toString(commandCapabilities)
                + " signer=" + signingIdentity.summary();
    }
}
