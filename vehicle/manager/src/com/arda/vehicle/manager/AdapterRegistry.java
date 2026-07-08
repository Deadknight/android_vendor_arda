package com.arda.vehicle.manager;

import android.content.ComponentName;
import android.content.Context;
import android.util.ArrayMap;
import android.util.Slog;

import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.VehicleValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AdapterRegistry implements AdapterConnection.Listener {
    private static final String TAG = "ArdaVehicleManager";

    private final Context mContext;
    private final AdapterDiscovery mDiscovery;
    private final GrantStore mGrantStore;
    private final RouterBridge mRouterBridge;
    private final Object mLock = new Object();
    private final ArrayMap<String, AdapterConnection> mConnections = new ArrayMap<>();
    private int mCurrentUserId = -1;

    AdapterRegistry(Context context, GrantStore grantStore) {
        mContext = context;
        mDiscovery = new AdapterDiscovery(context);
        mGrantStore = grantStore;
        mRouterBridge = new RouterBridge();
    }

    void rescan(int userId) {
        List<AdapterCandidate> candidates = mDiscovery.discover(userId);
        Map<String, AdapterCandidate> byKey = new HashMap<>();
        for (AdapterCandidate candidate : candidates) {
            byKey.put(candidate.key(), candidate);
        }

        synchronized (mLock) {
            if (mCurrentUserId != userId) {
                closeAllLocked("user changed");
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
                AdapterConnection existing = mConnections.get(grant.key());
                if (existing != null) {
                    if (existing.matches(candidate, grant)) {
                        continue;
                    }
                    mConnections.remove(grant.key());
                    mRouterBridge.deactivate(existing, "candidate or grant changed");
                    existing.close();
                }
                AdapterConnection connection = new AdapterConnection(
                        mContext,
                        candidate,
                        grant,
                        this);
                // Publish the connection as current before bindServiceAsUser(). Service callbacks
                // are normally asynchronous, but correctness must not depend on callback timing.
                mConnections.put(grant.key(), connection);
                if (!connection.bind()) {
                    if (mConnections.get(grant.key()) == connection) {
                        mConnections.remove(grant.key());
                    }
                    connection.close();
                    Slog.e(TAG, "ARDA_VM_BIND_FAILED " + grant.summary());
                    continue;
                }
                Slog.i(TAG, "ARDA_VM_BIND_REQUEST " + grant.summary());
            }

            for (int index = mConnections.size() - 1; index >= 0; index--) {
                String key = mConnections.keyAt(index);
                if (!desired.contains(key)) {
                    AdapterConnection connection = mConnections.removeAt(index);
                    mRouterBridge.deactivate(connection, "grant no longer desired");
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
            boolean found = candidates.stream()
                    .anyMatch(candidate -> candidate.key().equals(grant.key()));
            if (!found) {
                output.append("\nORPHAN_GRANT ").append(grant.summary());
            }
        }
        output.append("\n").append(mRouterBridge.describe());
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
    public void onAdapterReady(AdapterConnection connection) {
        synchronized (mLock) {
            AdapterConnection current = mConnections.get(connection.grant.key());
            if (current != connection || !connection.isReady()) {
                Slog.w(TAG, "ARDA_VM_ADAPTER_READY_DROP source=" + connection.grant.sourceId
                        + " reason=stale_connection");
                return;
            }
            // Keep the registry lock through activation so a concurrent revoke/user switch cannot
            // deactivate before the source link exists and then leave a stale link behind.
            mRouterBridge.activate(connection);
        }
    }

    @Override
    public void onAdapterUnavailable(AdapterConnection connection, String reason) {
        mRouterBridge.deactivate(connection, reason);
    }

    @Override
    public void onSignals(AdapterConnection connection, VehicleValue[] signals) {
        for (VehicleValue value : signals) {
            Slog.d(TAG, "ARDA_VM_SIGNAL_ACCEPT source=" + connection.grant.sourceId
                    + " component=" + connection.candidate.component.flattenToShortString()
                    + " property=" + value.propertyId
                    + " area=" + value.areaId
                    + " type=" + value.valueType
                    + " ts=" + value.timestampNanos);
        }
        mRouterBridge.publish(connection, signals);
    }

    @Override
    public void onCommandAcks(AdapterConnection connection, CommandAck[] acknowledgements) {
        for (CommandAck ack : acknowledgements) {
            Slog.i(TAG, "ARDA_VM_COMMAND_ACK source=" + connection.grant.sourceId
                    + " command=" + ack.commandId
                    + " status=" + ack.status
                    + " error=\"" + ack.error + "\"");
        }
        mRouterBridge.acknowledge(connection, acknowledgements);
    }

    @Override
    public void onConnectionRejected(AdapterConnection connection, String reason) {
        Slog.e(TAG, "ARDA_VM_ADAPTER_REJECT component="
                + connection.candidate.component.flattenToShortString()
                + " reason=" + reason);
        mRouterBridge.deactivate(connection, "adapter rejected: " + reason);
        synchronized (mLock) {
            AdapterConnection current = mConnections.get(connection.grant.key());
            if (current == connection) {
                mConnections.remove(connection.grant.key());
                current.close();
            }
        }
    }

    private void closeAllLocked(String reason) {
        for (int index = mConnections.size() - 1; index >= 0; index--) {
            AdapterConnection connection = mConnections.valueAt(index);
            mRouterBridge.deactivate(connection, reason);
            connection.close();
        }
        mConnections.clear();
    }
}
