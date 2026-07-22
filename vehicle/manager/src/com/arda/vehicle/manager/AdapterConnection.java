package com.arda.vehicle.manager;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.arda.vehicle.adapter.AdapterDescriptor;
import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.IArdaVehicleAdapter;
import com.arda.vehicle.adapter.IArdaVehicleHost;
import com.arda.vehicle.adapter.VehicleCommand;
import com.arda.vehicle.adapter.VehicleValue;
import com.arda.vehicle.sdk.ArdaVehicleContract;

import java.util.Arrays;

final class AdapterConnection implements ServiceConnection {
    interface Listener {
        void onAdapterReady(AdapterConnection connection);
        void onAdapterUnavailable(AdapterConnection connection, String reason);
        void onSignals(AdapterConnection connection, VehicleValue[] signals);
        void onCommandAcks(AdapterConnection connection, CommandAck[] acknowledgements);
        void onConnectionRejected(AdapterConnection connection, String reason);
    }

    private static final String TAG = "ArdaVehicleManager";
    private static final int MAX_SIGNAL_BATCH = 128;
    private static final int MAX_ACK_BATCH = 128;
    private static final int MAX_BYTES_VALUE = 64 * 1024;

    final AdapterCandidate candidate;
    final AdapterGrant grant;

    private final Context mContext;
    private final Listener mListener;
    private final Object mStateLock = new Object();

    private IArdaVehicleAdapter mAdapter;
    private boolean mBound;
    private boolean mReady;
    private boolean mClosed;

    private final IArdaVehicleHost.Stub mHost = new IArdaVehicleHost.Stub() {
        @Override
        public void registerAdapter(AdapterDescriptor descriptor) {
            enforceAdapterCaller();
            try {
                validateDescriptor(descriptor);
                synchronized (mStateLock) {
                    enforceOpenLocked();
                    if (mReady) {
                        throw new SecurityException("adapter already registered");
                    }
                    mReady = true;
                }
                Slog.i(TAG, "ARDA_VM_ADAPTER_READY component="
                        + candidate.component.flattenToShortString()
                        + " user=" + candidate.userId
                        + " source=" + grant.sourceId
                        + " priority=" + grant.priority
                        + " publish_caps=" + Arrays.toString(grant.publishCapabilities)
                        + " command_caps=" + Arrays.toString(grant.commandCapabilities)
                        + " publish_properties=" + Arrays.toString(grant.publishPropertyIds)
                        + " command_properties=" + Arrays.toString(grant.commandPropertyIds));
                mListener.onAdapterReady(AdapterConnection.this);
            } catch (RuntimeException e) {
                mListener.onConnectionRejected(
                        AdapterConnection.this,
                        "descriptor rejected: " + e.getMessage());
                throw e;
            }
        }

        @Override
        public void publishSignals(VehicleValue[] signals) {
            enforceAdapterCaller();
            enforceReady();
            try {
                VehicleValue[] validated = validateSignals(signals);
                mListener.onSignals(AdapterConnection.this, validated);
            } catch (SecurityException e) {
                Slog.w(TAG, "ARDA_VM_SIGNAL_REJECT_PROPERTY_NOT_GRANTED component="
                        + candidate.component.flattenToShortString()
                        + " source=" + grant.sourceId
                        + " reason=" + e.getMessage());
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "ARDA_VM_SIGNAL_REJECT_INVALID component="
                        + candidate.component.flattenToShortString()
                        + " source=" + grant.sourceId
                        + " reason=" + e.getMessage());
            }
        }

        @Override
        public void acknowledgeCommands(CommandAck[] acknowledgements) {
            enforceAdapterCaller();
            enforceReady();
            CommandAck[] validated = validateAcks(acknowledgements);
            mListener.onCommandAcks(AdapterConnection.this, validated);
        }
    };

    AdapterConnection(
            Context context,
            AdapterCandidate candidate,
            AdapterGrant grant,
            Listener listener) {
        mContext = context;
        this.candidate = candidate;
        this.grant = grant;
        mListener = listener;
    }

    boolean bind() {
        Intent intent = new Intent(ArdaVehicleContract.ACTION_ADAPTER)
                .setComponent(candidate.component);
        synchronized (mStateLock) {
            enforceOpenLocked();
            mBound = mContext.bindServiceAsUser(
                    intent,
                    this,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                    UserHandle.of(candidate.userId));
            return mBound;
        }
    }

    void close() {
        IArdaVehicleAdapter adapter;
        boolean wasBound;
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            adapter = mAdapter;
            mAdapter = null;
            mReady = false;
            wasBound = mBound;
            mBound = false;
        }
        if (adapter != null) {
            try {
                adapter.disconnect();
            } catch (RemoteException ignored) {
            }
        }
        if (wasBound) {
            try {
                mContext.unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    boolean isReady() {
        synchronized (mStateLock) {
            return !mClosed && mReady;
        }
    }

    boolean matches(AdapterCandidate candidate, AdapterGrant grant) {
        return this.candidate.sameBinding(candidate)
                && this.grant.sameRuntimeConfig(grant);
    }

    boolean deliverCommands(VehicleCommand[] commands) {
        if (commands == null || commands.length == 0) {
            return false;
        }
        for (VehicleCommand command : commands) {
            int propertyId = command != null && command.value != null
                    ? command.value.propertyId : 0;
            if (!grant.allowsCommandProperty(propertyId)) {
                Slog.w(TAG, "ARDA_VM_COMMAND_REJECT_PROPERTY_NOT_GRANTED source="
                        + grant.sourceId
                        + " component=" + candidate.component.flattenToShortString()
                        + " property=" + propertyId);
                return false;
            }
        }
        IArdaVehicleAdapter adapter;
        synchronized (mStateLock) {
            if (mClosed || !mReady || mAdapter == null) {
                return false;
            }
            adapter = mAdapter;
        }
        try {
            adapter.onVehicleCommands(commands);
            Slog.i(TAG, "ARDA_VM_COMMAND_DELIVER source=" + grant.sourceId
                    + " component=" + candidate.component.flattenToShortString()
                    + " count=" + commands.length);
            return true;
        } catch (RemoteException e) {
            mListener.onConnectionRejected(
                    this,
                    "command transaction failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IArdaVehicleAdapter adapter = IArdaVehicleAdapter.Stub.asInterface(service);
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
            mAdapter = adapter;
            mReady = false;
        }
        try {
            adapter.connect(mHost);
        } catch (RemoteException e) {
            mListener.onConnectionRejected(this, "connect transaction failed: " + e.getMessage());
            return;
        }

        // close() may race after the oneway connect transaction is queued. If so, queue a
        // matching disconnect and keep host callbacks fail-closed through mClosed.
        synchronized (mStateLock) {
            if (!mClosed) {
                return;
            }
        }
        try {
            adapter.disconnect();
        } catch (RemoteException ignored) {
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
            mAdapter = null;
            mReady = false;
        }
        Slog.w(TAG, "ARDA_VM_ADAPTER_DISCONNECTED component=" + name.flattenToShortString());
        mListener.onAdapterUnavailable(this, "service disconnected");
    }

    @Override
    public void onBindingDied(ComponentName name) {
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
            mAdapter = null;
            mReady = false;
        }
        Slog.w(TAG, "ARDA_VM_ADAPTER_BINDING_DIED component=" + name.flattenToShortString());
        mListener.onConnectionRejected(this, "binding died");
    }

    @Override
    public void onNullBinding(ComponentName name) {
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
        }
        mListener.onConnectionRejected(this, "null binding");
    }

    private void validateDescriptor(AdapterDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("null descriptor");
        }
        if (descriptor.apiVersion != candidate.apiVersion
                || descriptor.apiVersion != ArdaVehicleContract.API_VERSION) {
            throw new IllegalArgumentException("api version mismatch");
        }
        if (!candidate.adapterId.equals(descriptor.adapterId)) {
            throw new IllegalArgumentException("adapterId mismatch");
        }
        if (!candidate.displayName.equals(descriptor.displayName)) {
            throw new IllegalArgumentException("displayName mismatch");
        }
        if (descriptor.requestedPriority != candidate.requestedPriority) {
            throw new IllegalArgumentException("requestedPriority manifest/binder mismatch");
        }
        if (!Arrays.equals(
                candidate.publishCapabilities,
                CapabilityParser.sortedDistinct(descriptor.publishCapabilities))) {
            throw new IllegalArgumentException("publish capability manifest/binder mismatch");
        }
        if (!Arrays.equals(
                candidate.commandCapabilities,
                CapabilityParser.sortedDistinct(descriptor.commandCapabilities))) {
            throw new IllegalArgumentException("command capability manifest/binder mismatch");
        }
        if (!Arrays.equals(
                candidate.requestedPublishPropertyIds,
                PropertyIdParser.sortedDistinctPositive(
                        descriptor.requestedPublishPropertyIds,
                        "descriptor publish"))) {
            throw new IllegalArgumentException("publish property manifest/binder mismatch");
        }
        if (!Arrays.equals(
                candidate.requestedCommandPropertyIds,
                PropertyIdParser.sortedDistinctPositive(
                        descriptor.requestedCommandPropertyIds,
                        "descriptor command"))) {
            throw new IllegalArgumentException("command property manifest/binder mismatch");
        }
    }

    private void enforceAdapterCaller() {
        int uid = Binder.getCallingUid();
        if (uid != candidate.uid) {
            throw new SecurityException(
                    "adapter callback uid mismatch expected=" + candidate.uid + " actual=" + uid);
        }
        synchronized (mStateLock) {
            enforceOpenLocked();
            if (mAdapter == null) {
                throw new SecurityException("adapter callback without active binding");
            }
        }
    }

    private void enforceReady() {
        synchronized (mStateLock) {
            enforceOpenLocked();
            if (!mReady) {
                throw new SecurityException("adapter callback before descriptor registration");
            }
        }
    }

    private void enforceOpenLocked() {
        if (mClosed) {
            throw new SecurityException("adapter connection is closed");
        }
    }

    private VehicleValue[] validateSignals(VehicleValue[] signals) {
        VehicleValue[] values = signals != null ? signals : new VehicleValue[0];
        if (values.length == 0 || values.length > MAX_SIGNAL_BATCH) {
            throw new IllegalArgumentException("invalid signal batch size=" + values.length);
        }
        for (VehicleValue value : values) {
            if (value == null) {
                throw new IllegalArgumentException("signal contains null value");
            }
            if (value.propertyId <= 0) {
                throw new IllegalArgumentException("invalid propertyId=" + value.propertyId);
            }
            if (!grant.allowsPublishProperty(value.propertyId)) {
                throw new SecurityException(
                        "property_not_granted=" + value.propertyId);
            }
            if (!ArdaVehicleContract.isValueType(value.valueType)) {
                throw new IllegalArgumentException("invalid valueType=" + value.valueType);
            }
            if ((value.valueType == ArdaVehicleContract.VALUE_F32
                    && !Float.isFinite(value.floatValue))
                    || (value.valueType == ArdaVehicleContract.VALUE_F64
                    && !Double.isFinite(value.doubleValue))) {
                throw new IllegalArgumentException("non-finite vehicle value");
            }
            if (value.valueType == ArdaVehicleContract.VALUE_BYTES
                    && value.bytesValue != null
                    && value.bytesValue.length > MAX_BYTES_VALUE) {
                throw new IllegalArgumentException(
                        "bytes value too large=" + value.bytesValue.length);
            }
            if (value.timestampNanos < 0) {
                throw new IllegalArgumentException("negative timestampNanos");
            }
        }
        return values;
    }

    private static CommandAck[] validateAcks(CommandAck[] acknowledgements) {
        CommandAck[] values = acknowledgements != null
                ? acknowledgements
                : new CommandAck[0];
        if (values.length == 0 || values.length > MAX_ACK_BATCH) {
            throw new IllegalArgumentException("invalid ack batch size=" + values.length);
        }
        for (CommandAck ack : values) {
            if (ack == null || ack.commandId <= 0) {
                throw new IllegalArgumentException("invalid command acknowledgement");
            }
            if (ack.status < ArdaVehicleContract.COMMAND_OK
                    || ack.status > ArdaVehicleContract.COMMAND_TIMEOUT) {
                throw new IllegalArgumentException("invalid command status=" + ack.status);
            }
        }
        return values;
    }
}
