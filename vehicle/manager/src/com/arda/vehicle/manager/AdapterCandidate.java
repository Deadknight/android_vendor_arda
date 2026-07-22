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
    final int[] requestedPublishPropertyIds;
    final int[] requestedCommandPropertyIds;
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
            int[] requestedPublishPropertyIds,
            int[] requestedCommandPropertyIds,
            SigningIdentity signingIdentity) {
        this.userId = userId;
        this.component = component;
        this.uid = uid;
        this.adapterId = adapterId;
        this.displayName = displayName;
        this.apiVersion = apiVersion;
        this.requestedPriority = requestedPriority;
        this.publishCapabilities = CapabilityParser.sortedDistinct(publishCapabilities);
        this.commandCapabilities = CapabilityParser.sortedDistinct(commandCapabilities);
        this.requestedPublishPropertyIds = PropertyIdParser.sortedDistinctPositive(
                requestedPublishPropertyIds, "candidate publish");
        this.requestedCommandPropertyIds = PropertyIdParser.sortedDistinctPositive(
                requestedCommandPropertyIds, "candidate command");
        this.signingIdentity = signingIdentity;
    }

    String key() {
        return userId + ":" + component.flattenToShortString();
    }

    boolean sameBinding(AdapterCandidate other) {
        return other != null
                && userId == other.userId
                && component.equals(other.component)
                && uid == other.uid
                && adapterId.equals(other.adapterId)
                && displayName.equals(other.displayName)
                && apiVersion == other.apiVersion
                && requestedPriority == other.requestedPriority
                && Arrays.equals(publishCapabilities, other.publishCapabilities)
                && Arrays.equals(commandCapabilities, other.commandCapabilities)
                && Arrays.equals(requestedPublishPropertyIds,
                        other.requestedPublishPropertyIds)
                && Arrays.equals(requestedCommandPropertyIds,
                        other.requestedCommandPropertyIds)
                && signingIdentity.summary().equals(other.signingIdentity.summary());
    }

    boolean hasAdditionalRequests(AdapterGrant grant) {
        return grant != null
                && (!containsAll(grant.publishCapabilities, publishCapabilities)
                || !containsAll(grant.commandCapabilities, commandCapabilities)
                || !containsAll(grant.publishPropertyIds, requestedPublishPropertyIds)
                || !containsAll(grant.commandPropertyIds, requestedCommandPropertyIds));
    }

    private static boolean containsAll(int[] granted, int[] requested) {
        int[] grantedSorted = granted != null ? granted : new int[0];
        int[] requestedSorted = requested != null ? requested : new int[0];
        for (int value : requestedSorted) {
            if (Arrays.binarySearch(grantedSorted, value) < 0) {
                return false;
            }
        }
        return true;
    }

    String summary() {
        return "user=" + userId
                + " component=" + component.flattenToShortString()
                + " uid=" + uid
                + " adapter_id=" + adapterId
                + " api=" + apiVersion
                + " requested_priority=" + requestedPriority
                + " publish_caps=" + Arrays.toString(publishCapabilities)
                + " command_caps=" + Arrays.toString(commandCapabilities)
                + " publish_properties=" + Arrays.toString(requestedPublishPropertyIds)
                + " command_properties=" + Arrays.toString(requestedCommandPropertyIds)
                + " signer=" + signingIdentity.summary();
    }
}
