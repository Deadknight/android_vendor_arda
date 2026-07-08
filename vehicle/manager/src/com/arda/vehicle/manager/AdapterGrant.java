package com.arda.vehicle.manager;

import android.content.ComponentName;

import java.util.Arrays;

final class AdapterGrant {
    final int userId;
    final ComponentName component;
    final int sourceId;
    final String signerAnchor;
    final int priority;
    final int[] publishCapabilities;
    final int[] commandCapabilities;
    final long grantedAtMillis;

    AdapterGrant(
            int userId,
            ComponentName component,
            int sourceId,
            String signerAnchor,
            int priority,
            int[] publishCapabilities,
            int[] commandCapabilities,
            long grantedAtMillis) {
        this.userId = userId;
        this.component = component;
        this.sourceId = sourceId;
        this.signerAnchor = signerAnchor;
        this.priority = priority;
        this.publishCapabilities = publishCapabilities;
        this.commandCapabilities = commandCapabilities;
        this.grantedAtMillis = grantedAtMillis;
    }

    String key() {
        return userId + ":" + component.flattenToShortString();
    }

    String summary() {
        return "user=" + userId
                + " component=" + component.flattenToShortString()
                + " source=" + sourceId
                + " priority=" + priority
                + " publish=" + Arrays.toString(publishCapabilities)
                + " commands=" + Arrays.toString(commandCapabilities)
                + " signer_anchor=" + signerAnchor;
    }
}
