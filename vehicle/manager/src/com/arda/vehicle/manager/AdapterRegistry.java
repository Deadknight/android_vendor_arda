package com.arda.vehicle.manager;

import android.content.ComponentName;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Slog;

import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.VehicleValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AdapterRegistry implements AdapterConnection.Listener {
    private static final String TAG = "ArdaVehicleManager";

    private final Context mContext;
    private final AdapterDiscovery mDiscovery;
    private final GrantStore mGrantStore;
    private final Object mLock = new Object();
    private final ArrayMap<String, AdapterConnection> mConnections = new ArrayMap<>();
    private int mCurrentUserId = -1;

    AdapterRegistry(Context context, GrantStore grantStore) {
        mContext = context;
        mDiscovery = new AdapterDiscovery(context);
        mGrantStore = grantStore;
    }

    void rescan(int userId) {
        List<AdapterCandidate> candidates = mDiscovery.discover(userId);
        Map<String, AdapterCandidate> byKey = new HashMap<>();
        for (AdapterCandidate candidate : candidates) {
            byKey.put(candidate.key(), candidate);
        }

        synchronized (mLock) {
            if (mCurrentUserId != userId) {
                closeAllLocked();
                mCurrentUserId = userId;
            }

            List<String> desired = new ArrayList<>();
            for (AdapterGrant grant : mGrantStore.listForUser(userId)) {
                AdapterCandidate candidate = byKey.get(grant.key());
                if (candidate == null) {
                    Slog.w(TAG, "ARDA_VM_GRANT_INACTIVE reason=component_missing " + grant.summary());
                    continue;
                }
                if (!candidate.signingIdentity.acceptsGrantAnchor(grant.signerAnchor)) {
                    Slog.e(TAG, "ARDA_VM_GRANT_REJECT reason=signer_mismatch " + grant.summary()
                            + " current=" + candidate.signingIdentity.summary());
                    continue;
                }
                desired.add(grant.key());
                if (mConnections.containsKey(grant.key())) {
                    continue;
                }
                AdapterConnection connection = new AdapterConnection(
                        mContext,
                        candidate,
                        grant,
                        this);
                if (!connection.bind()) {
                    Slog.e(TAG, "ARDA_VM_BIND_FAILED " + grant.summary());
                    continue;
                }
                mConnections.put(grant.key(), connection);
                Slog.i(TAG, "ARDA_VM_BIND_REQUEST " + grant.summary());
            }

            for (int index = mConnections.size() - 1; index >= 0; index--) {
                String key = mConnections.keyAt(index);
                if (!desired.contains(key)) {
                    AdapterConnection connection = mConnections.removeAt(index);
                    connection.close();
                }
            }
        }
    }

    String describe(int userId) {
        List<AdapterCandidate> candidates = mDiscovery.discover(userId);
        Map<String, AdapterGrant> grants = new HashMap<>();
        for (AdapterGrant grant : mGrantStore.listForUser(userId)) {
            grants.put(grant.key(), grant);
        }
        StringBuilder output = new StringBuilder();
        output.append("ARDA_VM_STATUS user=").append(userId)
                .append(" candidates=").append(candidates.size())
                .append(" grants=").append(grants.size());
        for (AdapterCandidate candidate : candidates) {
            AdapterGrant grant = grants.get(candidate.key());
            boolean signerOk = grant != null
                    && candidate.signingIdentity.acceptsGrantAnchor(grant.signerAnchor);
            boolean connected;
            boolean ready;
            synchronized (mLock) {
                AdapterConnection connection = mConnections.get(candidate.key());
                connected = connection != null;
                ready = connection != null && connection.isReady();
            }
            output.append("\nCANDIDATE ").append(candidate.summary())
                    .append(" granted=").append(grant != null)
                    .append(" signer_ok=").append(signerOk)
                    .append(" connected=").append(connected)
                    .append(" ready=").append(ready);
            if (grant != null) {
                output.append("\n  GRANT ").append(grant.summary());
            }
        }
        for (AdapterGrant grant : grants.values()) {
            boolean found = candidates.stream().anyMatch(candidate -> candidate.key().equals(grant.key()));
            if (!found) {
                output.append("\nORPHAN_GRANT ").append(grant.summary());
            }
        }
        return output.toString();
    }

    AdapterCandidate findCandidate(int userId, ComponentName component) {
        for (AdapterCandidate candidate : mDiscovery.discover(userId)) {
            if (candidate.component.equals(component)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void onSignals(AdapterConnection connection, VehicleValue[] signals) {
        for (VehicleValue value : signals) {
            Slog.i(TAG, "ARDA_VM_SIGNAL_ACCEPT source=" + connection.grant.sourceId
                    + " component=" + connection.candidate.component.flattenToShortString()
                    + " property=" + value.propertyId
                    + " area=" + value.areaId
                    + " type=" + value.valueType
                    + " ts=" + value.timestampNanos);
        }
        // Phase 2.1: frame these values into arda.vehicle.router.v2.VehicleSignals.
        // vehicle-routerd remains the authoritative property/capability validator.
    }

    @Override
    public void onCommandAcks(AdapterConnection connection, CommandAck[] acknowledgements) {
        for (CommandAck ack : acknowledgements) {
            Slog.i(TAG, "ARDA_VM_COMMAND_ACK source=" + connection.grant.sourceId
                    + " command=" + ack.commandId
                    + " status=" + ack.status
                    + " error=\"" + ack.error + "\"");
        }
        // Phase 2.1: forward acknowledgements to vehicle-routerd.
    }

    @Override
    public void onConnectionRejected(AdapterConnection connection, String reason) {
        Slog.e(TAG, "ARDA_VM_ADAPTER_REJECT component="
                + connection.candidate.component.flattenToShortString()
                + " reason=" + reason);
        synchronized (mLock) {
            AdapterConnection current = mConnections.remove(connection.grant.key());
            if (current != null) {
                current.close();
            }
        }
    }

    private void closeAllLocked() {
        for (int index = mConnections.size() - 1; index >= 0; index--) {
            mConnections.valueAt(index).close();
        }
        mConnections.clear();
    }
}
