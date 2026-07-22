package com.arda.vehicle.manager;

import android.content.ComponentName;

import java.util.Arrays;

final class AdapterGrant {
    static final int SCHEMA_VERSION = 2;

    final int userId;
    final ComponentName component;
    final int sourceId;
    final String signerAnchor;
    final int priority;
    final int[] publishCapabilities;
    final int[] commandCapabilities;
    final int[] publishPropertyIds;
    final int[] commandPropertyIds;
    final boolean enabled;
    final long grantedAtMillis;
    final long updatedAtMillis;

    AdapterGrant(
            int userId,
            ComponentName component,
            int sourceId,
            String signerAnchor,
            int priority,
            int[] publishCapabilities,
            int[] commandCapabilities,
            int[] publishPropertyIds,
            int[] commandPropertyIds,
            boolean enabled,
            long grantedAtMillis,
            long updatedAtMillis) {
        this.userId = userId;
        this.component = component;
        this.sourceId = sourceId;
        this.signerAnchor = signerAnchor;
        this.priority = priority;
        this.publishCapabilities = CapabilityParser.sortedDistinct(publishCapabilities);
        this.commandCapabilities = CapabilityParser.sortedDistinct(commandCapabilities);
        this.publishPropertyIds = PropertyIdParser.sortedDistinctPositive(
                publishPropertyIds, "grant publish");
        this.commandPropertyIds = PropertyIdParser.sortedDistinctPositive(
                commandPropertyIds, "grant command");
        this.enabled = enabled;
        this.grantedAtMillis = grantedAtMillis;
        this.updatedAtMillis = updatedAtMillis;
    }

    String key() {
        return userId + ":" + component.flattenToShortString();
    }

    boolean allowsPublishProperty(int propertyId) {
        return enabled && PropertyIdParser.contains(publishPropertyIds, propertyId);
    }

    boolean allowsCommandProperty(int propertyId) {
        return enabled && PropertyIdParser.contains(commandPropertyIds, propertyId);
    }

    boolean isCompatibleWith(AdapterCandidate candidate) {
        return candidate != null
                && isSubset(publishCapabilities, candidate.publishCapabilities)
                && isSubset(commandCapabilities, candidate.commandCapabilities)
                && isSubset(publishPropertyIds, candidate.requestedPublishPropertyIds)
                && isSubset(commandPropertyIds, candidate.requestedCommandPropertyIds);
    }

    private static boolean isSubset(int[] subset, int[] superset) {
        int[] values = subset != null ? subset : new int[0];
        int[] allowed = superset != null ? superset : new int[0];
        for (int value : values) {
            if (Arrays.binarySearch(allowed, value) < 0) {
                return false;
            }
        }
        return true;
    }

    boolean sameRuntimeConfig(AdapterGrant other) {
        return other != null
                && userId == other.userId
                && component.equals(other.component)
                && sourceId == other.sourceId
                && signerAnchor.equals(other.signerAnchor)
                && priority == other.priority
                && enabled == other.enabled
                && Arrays.equals(publishCapabilities, other.publishCapabilities)
                && Arrays.equals(commandCapabilities, other.commandCapabilities)
                && Arrays.equals(publishPropertyIds, other.publishPropertyIds)
                && Arrays.equals(commandPropertyIds, other.commandPropertyIds);
    }

    String summary() {
        return "user=" + userId
                + " component=" + component.flattenToShortString()
                + " source=" + sourceId
                + " priority=" + priority
                + " enabled=" + enabled
                + " publish_caps=" + Arrays.toString(publishCapabilities)
                + " command_caps=" + Arrays.toString(commandCapabilities)
                + " publish_properties=" + Arrays.toString(publishPropertyIds)
                + " command_properties=" + Arrays.toString(commandPropertyIds)
                + " signer_anchor=" + signerAnchor;
    }
}
