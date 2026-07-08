package com.arda.vehicle.manager;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;

import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.VehicleCommand;
import com.arda.vehicle.adapter.VehicleValue;
import com.arda.vehicle.router.proto.ArdaVehicleRouterProto;
import com.arda.vehicle.sdk.ArdaVehicleContract;
import com.google.protobuf.ByteString;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class RouterBridge {
    private static final String TAG = "ArdaVehicleManager";
    private static final String SOCKET_NAME = "arda_vehicle_router";
    private static final String SOCKET_PATH = "/dev/socket/" + SOCKET_NAME;
    private static final int PROTOCOL_VERSION = 2;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int MAX_COMMAND_BATCH = 128;
    private static final int MAX_BYTES_VALUE = 64 * 1024;
    private static final int MAX_PENDING_SIGNALS = 512;
    private static final int HANDSHAKE_TIMEOUT_MS = 5_000;
    private static final long RECONNECT_DELAY_MS = 1_000L;

    private final Object mLock = new Object();
    private final ArrayMap<Integer, SourceLink> mSources = new ArrayMap<>();

    void activate(AdapterConnection connection) {
        SourceLink previous;
        SourceLink created;
        synchronized (mLock) {
            previous = mSources.get(connection.grant.sourceId);
            if (previous != null && previous.isFor(connection)) {
                return;
            }
            created = new SourceLink(connection);
            mSources.put(connection.grant.sourceId, created);
        }
        if (previous != null) {
            previous.stop("source replaced");
        }
        created.start();
    }

    void deactivate(AdapterConnection connection, String reason) {
        SourceLink removed = null;
        synchronized (mLock) {
            SourceLink current = mSources.get(connection.grant.sourceId);
            if (current != null && current.isFor(connection)) {
                removed = mSources.remove(connection.grant.sourceId);
            }
        }
        if (removed != null) {
            removed.stop(reason);
        }
    }

    void publish(AdapterConnection connection, VehicleValue[] signals) {
        SourceLink link = find(connection);
        if (link == null) {
            Slog.w(TAG, "ARDA_VM_ROUTER_SIGNAL_DROP source=" + connection.grant.sourceId
                    + " reason=source_not_active count=" + signals.length);
            return;
        }
        link.publish(signals);
    }

    void acknowledge(AdapterConnection connection, CommandAck[] acknowledgements) {
        SourceLink link = find(connection);
        if (link == null) {
            Slog.w(TAG, "ARDA_VM_ROUTER_ACK_DROP source=" + connection.grant.sourceId
                    + " reason=source_not_active count=" + acknowledgements.length);
            return;
        }
        link.acknowledge(acknowledgements);
    }

    String describe() {
        List<String> states = new ArrayList<>();
        synchronized (mLock) {
            for (int index = 0; index < mSources.size(); index++) {
                states.add(mSources.valueAt(index).describe());
            }
        }
        if (states.isEmpty()) {
            return "ROUTER sources=0";
        }
        return "ROUTER sources=" + states.size() + " " + String.join(" | ", states);
    }

    private SourceLink find(AdapterConnection connection) {
        synchronized (mLock) {
            SourceLink link = mSources.get(connection.grant.sourceId);
            return link != null && link.isFor(connection) ? link : null;
        }
    }

    private static final class SourceLink {
        private final AdapterConnection mConnection;
        private final AdapterGrant mGrant;
        private final Object mIoLock = new Object();
        private final Object mPendingLock = new Object();
        private final LinkedHashMap<String, ArdaVehicleRouterProto.VehicleSignal> mPendingSignals =
                new LinkedHashMap<>();

        private volatile boolean mActive = true;
        private volatile boolean mRegistered;
        private volatile String mState = "STARTING";
        private volatile String mLastError = "";

        private LocalSocket mSocket;
        private DataOutputStream mOutput;
        private Thread mThread;

        SourceLink(AdapterConnection connection) {
            mConnection = connection;
            mGrant = connection.grant;
        }

        boolean isFor(AdapterConnection connection) {
            return mConnection == connection;
        }

        void start() {
            Thread thread = new Thread(this::run, "ArdaVehicleRouter-" + mGrant.sourceId);
            thread.setDaemon(true);
            mThread = thread;
            thread.start();
        }

        void stop(String reason) {
            mActive = false;
            mState = "STOPPED";
            mLastError = clean(reason);
            synchronized (mIoLock) {
                disconnectLocked();
            }
            Thread thread = mThread;
            if (thread != null) {
                thread.interrupt();
            }
            Slog.i(TAG, "ARDA_VM_ROUTER_SOURCE_STOP source=" + mGrant.sourceId
                    + " reason=\"" + clean(reason) + "\"");
        }

        void publish(VehicleValue[] signals) {
            if (!mActive) {
                Slog.w(TAG, "ARDA_VM_ROUTER_SIGNAL_DROP source=" + mGrant.sourceId
                        + " reason=source_stopped count=" + signals.length);
                return;
            }
            List<ArdaVehicleRouterProto.VehicleSignal> wireSignals = new ArrayList<>(signals.length);
            for (VehicleValue value : signals) {
                wireSignals.add(toWireSignal(mGrant.sourceId, value));
            }

            synchronized (mIoLock) {
                if (mRegistered && mOutput != null) {
                    try {
                        writeEnvelopeLocked(signalEnvelope(wireSignals));
                        Slog.d(TAG, "ARDA_VM_ROUTER_SIGNAL_SENT source=" + mGrant.sourceId
                                + " count=" + wireSignals.size());
                        return;
                    } catch (IOException e) {
                        mLastError = clean(e.getMessage());
                        disconnectLocked();
                    }
                }
            }

            queueSignals(wireSignals);
        }

        void acknowledge(CommandAck[] acknowledgements) {
            if (!mActive) {
                Slog.w(TAG, "ARDA_VM_ROUTER_ACK_DROP source=" + mGrant.sourceId
                        + " reason=source_stopped count=" + acknowledgements.length);
                return;
            }
            ArdaVehicleRouterProto.CommandAcks.Builder batch =
                    ArdaVehicleRouterProto.CommandAcks.newBuilder();
            for (CommandAck acknowledgement : acknowledgements) {
                batch.addAcknowledgements(
                        ArdaVehicleRouterProto.CommandAck.newBuilder()
                                .setCommandId(acknowledgement.commandId)
                                .setStatus(commandStatus(acknowledgement.status))
                                .setError(acknowledgement.error != null
                                        ? acknowledgement.error
                                        : "")
                                .build());
            }
            ArdaVehicleRouterProto.Envelope envelope = envelopeBuilder()
                    .setCommandAcks(batch.build())
                    .build();

            synchronized (mIoLock) {
                if (!mRegistered || mOutput == null) {
                    Slog.w(TAG, "ARDA_VM_ROUTER_ACK_DROP source=" + mGrant.sourceId
                            + " reason=router_not_registered count=" + acknowledgements.length);
                    return;
                }
                try {
                    writeEnvelopeLocked(envelope);
                    Slog.i(TAG, "ARDA_VM_ROUTER_ACK_SENT source=" + mGrant.sourceId
                            + " count=" + acknowledgements.length);
                } catch (IOException e) {
                    mLastError = clean(e.getMessage());
                    disconnectLocked();
                    Slog.e(TAG, "ARDA_VM_ROUTER_ACK_DROP source=" + mGrant.sourceId
                            + " reason=write_failed error=\"" + mLastError + "\"");
                }
            }
        }

        String describe() {
            int pending;
            synchronized (mPendingLock) {
                pending = mPendingSignals.size();
            }
            return "source=" + mGrant.sourceId
                    + " state=" + mState
                    + " registered=" + mRegistered
                    + " pending_signals=" + pending
                    + " last_error=\"" + clean(mLastError) + "\"";
        }

        private void run() {
            int failures = 0;
            while (mActive) {
                LocalSocket socket = null;
                try {
                    mState = "CONNECTING";
                    socket = new LocalSocket();
                    socket.connect(new LocalSocketAddress(
                            SOCKET_NAME,
                            LocalSocketAddress.Namespace.RESERVED));
                    // LocalSocket#setSoTimeout() does not create the underlying fd.
                    // Connect first so LocalSocketImpl has a socket before setting options.
                    socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());

                    hello(output, input);
                    registerSource(output, input);
                    socket.setSoTimeout(0);

                    synchronized (mIoLock) {
                        if (!mActive) {
                            closeQuietly(socket);
                            break;
                        }
                        mSocket = socket;
                        mOutput = output;
                        mRegistered = true;
                        mState = "REGISTERED";
                        mLastError = "";
                        flushPendingSignalsLocked();
                    }

                    failures = 0;
                    Slog.i(TAG, "ARDA_VM_ROUTER_REGISTERED source=" + mGrant.sourceId
                            + " priority=" + mGrant.priority
                            + " publish=" + java.util.Arrays.toString(mGrant.publishCapabilities)
                            + " commands=" + java.util.Arrays.toString(mGrant.commandCapabilities)
                            + " socket=" + SOCKET_PATH);
                    readLoop(input);
                    throw new EOFException("router socket closed");
                } catch (IOException | RuntimeException e) {
                    failures++;
                    mLastError = clean(e.getMessage());
                    if (mActive && (failures == 1 || failures % 10 == 0)) {
                        Slog.w(TAG, "ARDA_VM_ROUTER_CONNECT_RETRY source=" + mGrant.sourceId
                                + " attempts=" + failures
                                + " socket=" + SOCKET_PATH
                                + " error=\"" + mLastError + "\"");
                    }
                } finally {
                    synchronized (mIoLock) {
                        if (mSocket == socket || mSocket == null) {
                            disconnectLocked();
                        }
                    }
                    if (socket != null) {
                        closeQuietly(socket);
                    }
                }

                if (mActive) {
                    mState = "DISCONNECTED";
                    SystemClock.sleep(RECONNECT_DELAY_MS);
                }
            }
            mState = "STOPPED";
        }

        private void hello(DataOutputStream output, DataInputStream input) throws IOException {
            ArdaVehicleRouterProto.Hello hello = ArdaVehicleRouterProto.Hello.newBuilder()
                    .setProtocolVersion(PROTOCOL_VERSION)
                    .setRole(ArdaVehicleRouterProto.ClientRole.ROLE_MANAGER)
                    .setClientName("ArdaVehicleManager/source-" + mGrant.sourceId)
                    .build();
            writeEnvelope(output, envelopeBuilder().setHello(hello).build());

            ArdaVehicleRouterProto.Envelope response = readEnvelope(input);
            if (!response.hasHelloAck()) {
                throw new IOException("router did not return HelloAck");
            }
            ArdaVehicleRouterProto.HelloAck ack = response.getHelloAck();
            if (!ack.getAccepted()) {
                throw new IOException("router rejected manager: " + ack.getError());
            }
            if (ack.getProtocolVersion() != PROTOCOL_VERSION) {
                throw new IOException("router protocol mismatch expected=" + PROTOCOL_VERSION
                        + " actual=" + ack.getProtocolVersion());
            }
        }

        private void registerSource(DataOutputStream output, DataInputStream input)
                throws IOException {
            ArdaVehicleRouterProto.RegisterSource.Builder request =
                    ArdaVehicleRouterProto.RegisterSource.newBuilder()
                            .setSourceId(mGrant.sourceId)
                            .setSourceName(mConnection.candidate.adapterId
                                    + "@user" + mGrant.userId)
                            .setPriority(mGrant.priority);
            for (int capability : mGrant.publishCapabilities) {
                request.addPublishCapabilities(capability);
            }
            for (int capability : mGrant.commandCapabilities) {
                request.addCommandCapabilities(capability);
            }
            writeEnvelope(output, envelopeBuilder().setRegisterSource(request.build()).build());

            ArdaVehicleRouterProto.Envelope response = readEnvelope(input);
            if (!response.hasRegisterSourceResult()) {
                throw new IOException("router did not return RegisterSourceResult");
            }
            ArdaVehicleRouterProto.RegisterSourceResult result =
                    response.getRegisterSourceResult();
            if (!result.getAccepted()) {
                throw new IOException("router rejected source " + mGrant.sourceId
                        + ": " + result.getError());
            }
            if (result.getSourceId() != mGrant.sourceId) {
                throw new IOException("router source mismatch expected=" + mGrant.sourceId
                        + " actual=" + result.getSourceId());
            }
        }

        private void readLoop(DataInputStream input) throws IOException {
            while (mActive) {
                ArdaVehicleRouterProto.Envelope envelope = readEnvelope(input);
                if (envelope.getProtocolVersion() != PROTOCOL_VERSION) {
                    throw new IOException("incoming protocol mismatch="
                            + envelope.getProtocolVersion());
                }
                if (envelope.hasVehicleCommands()) {
                    handleCommands(envelope.getVehicleCommands());
                } else if (envelope.hasPong()) {
                    Slog.i(TAG, "ARDA_VM_ROUTER_PONG source=" + mGrant.sourceId
                            + " nonce=" + envelope.getPong().getNonce());
                } else {
                    Slog.w(TAG, "ARDA_VM_ROUTER_MESSAGE_DROP source=" + mGrant.sourceId
                            + " reason=unexpected_payload");
                }
            }
        }

        private void handleCommands(ArdaVehicleRouterProto.VehicleCommands batch)
                throws IOException {
            if (batch.getCommandsCount() == 0
                    || batch.getCommandsCount() > MAX_COMMAND_BATCH) {
                throw new IOException("invalid command batch size=" + batch.getCommandsCount());
            }
            VehicleCommand[] commands = new VehicleCommand[batch.getCommandsCount()];
            for (int index = 0; index < batch.getCommandsCount(); index++) {
                ArdaVehicleRouterProto.VehicleCommand wire = batch.getCommands(index);
                if (wire.getSourceId() != mGrant.sourceId) {
                    throw new IOException("command source mismatch expected=" + mGrant.sourceId
                            + " actual=" + wire.getSourceId());
                }
                if (wire.getCommandId() <= 0) {
                    throw new IOException("invalid command id=" + wire.getCommandId());
                }
                VehicleCommand command = new VehicleCommand();
                command.commandId = wire.getCommandId();
                command.value = fromWireValue(wire);
                commands[index] = command;
            }

            Slog.i(TAG, "ARDA_VM_ROUTER_COMMAND source=" + mGrant.sourceId
                    + " count=" + commands.length);
            if (!mConnection.deliverCommands(commands)) {
                sendDeliveryFailure(commands, "adapter is not ready");
            }
        }

        private void sendDeliveryFailure(VehicleCommand[] commands, String error) {
            ArdaVehicleRouterProto.CommandAcks.Builder acks =
                    ArdaVehicleRouterProto.CommandAcks.newBuilder();
            for (VehicleCommand command : commands) {
                acks.addAcknowledgements(
                        ArdaVehicleRouterProto.CommandAck.newBuilder()
                                .setCommandId(command.commandId)
                                .setStatus(ArdaVehicleRouterProto.CommandStatus.COMMAND_IO_ERROR)
                                .setError(error)
                                .build());
            }
            synchronized (mIoLock) {
                if (!mRegistered || mOutput == null) {
                    return;
                }
                try {
                    writeEnvelopeLocked(envelopeBuilder().setCommandAcks(acks.build()).build());
                } catch (IOException e) {
                    mLastError = clean(e.getMessage());
                    disconnectLocked();
                }
            }
        }

        private void queueSignals(List<ArdaVehicleRouterProto.VehicleSignal> signals) {
            if (!mActive) {
                return;
            }
            int dropped = 0;
            synchronized (mPendingLock) {
                for (ArdaVehicleRouterProto.VehicleSignal signal : signals) {
                    String key = signal.getPropertyId() + ":" + signal.getAreaId();
                    mPendingSignals.remove(key);
                    mPendingSignals.put(key, signal);
                    while (mPendingSignals.size() > MAX_PENDING_SIGNALS) {
                        String eldest = mPendingSignals.keySet().iterator().next();
                        mPendingSignals.remove(eldest);
                        dropped++;
                    }
                }
                Slog.d(TAG, "ARDA_VM_ROUTER_SIGNAL_QUEUED source=" + mGrant.sourceId
                        + " latest=" + mPendingSignals.size()
                        + " incoming=" + signals.size()
                        + " dropped=" + dropped);
            }
        }

        private void flushPendingSignalsLocked() throws IOException {
            List<ArdaVehicleRouterProto.VehicleSignal> pending;
            synchronized (mPendingLock) {
                if (mPendingSignals.isEmpty()) {
                    return;
                }
                pending = new ArrayList<>(mPendingSignals.values());
            }
            for (int start = 0; start < pending.size(); start += 128) {
                int end = Math.min(start + 128, pending.size());
                writeEnvelopeLocked(signalEnvelope(pending.subList(start, end)));
            }
            synchronized (mPendingLock) {
                for (ArdaVehicleRouterProto.VehicleSignal signal : pending) {
                    String key = signal.getPropertyId() + ":" + signal.getAreaId();
                    ArdaVehicleRouterProto.VehicleSignal current = mPendingSignals.get(key);
                    if (signal.equals(current)) {
                        mPendingSignals.remove(key);
                    }
                }
            }
            Slog.i(TAG, "ARDA_VM_ROUTER_SIGNAL_FLUSH source=" + mGrant.sourceId
                    + " count=" + pending.size());
        }

        private void writeEnvelopeLocked(ArdaVehicleRouterProto.Envelope envelope)
                throws IOException {
            if (mOutput == null) {
                throw new IOException("router output is unavailable");
            }
            writeEnvelope(mOutput, envelope);
        }

        private void disconnectLocked() {
            mRegistered = false;
            mOutput = null;
            LocalSocket socket = mSocket;
            mSocket = null;
            if (socket != null) {
                closeQuietly(socket);
            }
        }
    }

    private static ArdaVehicleRouterProto.Envelope.Builder envelopeBuilder() {
        return ArdaVehicleRouterProto.Envelope.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION);
    }

    private static ArdaVehicleRouterProto.Envelope signalEnvelope(
            List<ArdaVehicleRouterProto.VehicleSignal> signals) {
        return envelopeBuilder()
                .setVehicleSignals(
                        ArdaVehicleRouterProto.VehicleSignals.newBuilder()
                                .addAllSignals(signals)
                                .build())
                .build();
    }

    private static ArdaVehicleRouterProto.VehicleSignal toWireSignal(
            int sourceId,
            VehicleValue value) {
        ArdaVehicleRouterProto.VehicleSignal.Builder signal =
                ArdaVehicleRouterProto.VehicleSignal.newBuilder()
                        .setSourceId(sourceId)
                        .setPropertyId(value.propertyId)
                        .setAreaId(value.areaId)
                        .setValueType(valueType(value.valueType))
                        .setTimestampNanos(value.timestampNanos);
        switch (value.valueType) {
            case ArdaVehicleContract.VALUE_BOOL -> signal.setBoolValue(value.boolValue);
            case ArdaVehicleContract.VALUE_I32 -> signal.setInt32Value(value.int32Value);
            case ArdaVehicleContract.VALUE_I64 -> signal.setInt64Value(value.int64Value);
            case ArdaVehicleContract.VALUE_F32 -> signal.setFloatValue(value.floatValue);
            case ArdaVehicleContract.VALUE_F64 -> signal.setDoubleValue(value.doubleValue);
            case ArdaVehicleContract.VALUE_BYTES -> signal.setBytesValue(
                    ByteString.copyFrom(value.bytesValue != null
                            ? value.bytesValue
                            : new byte[0]));
            default -> throw new IllegalArgumentException("invalid value type=" + value.valueType);
        }
        return signal.build();
    }

    private static VehicleValue fromWireValue(
            ArdaVehicleRouterProto.VehicleCommand command) throws IOException {
        int valueType = command.getValueTypeValue();
        if (!ArdaVehicleContract.isValueType(valueType)) {
            throw new IOException("invalid command value type=" + valueType);
        }
        if (command.getPropertyId() <= 0) {
            throw new IOException("invalid command property=" + command.getPropertyId());
        }
        if (command.getTimestampNanos() < 0) {
            throw new IOException("negative command timestamp=" + command.getTimestampNanos());
        }
        if ((valueType == ArdaVehicleContract.VALUE_F32
                && !Float.isFinite(command.getFloatValue()))
                || (valueType == ArdaVehicleContract.VALUE_F64
                && !Double.isFinite(command.getDoubleValue()))) {
            throw new IOException("non-finite command value");
        }
        if (valueType == ArdaVehicleContract.VALUE_BYTES
                && command.getBytesValue().size() > MAX_BYTES_VALUE) {
            throw new IOException("command bytes too large=" + command.getBytesValue().size());
        }
        VehicleValue value = new VehicleValue();
        value.propertyId = command.getPropertyId();
        value.areaId = command.getAreaId();
        value.valueType = valueType;
        value.boolValue = command.getBoolValue();
        value.int32Value = command.getInt32Value();
        value.int64Value = command.getInt64Value();
        value.floatValue = command.getFloatValue();
        value.doubleValue = command.getDoubleValue();
        value.bytesValue = command.getBytesValue().toByteArray();
        value.timestampNanos = command.getTimestampNanos();
        return value;
    }

    private static ArdaVehicleRouterProto.ValueType valueType(int valueType) {
        return switch (valueType) {
            case ArdaVehicleContract.VALUE_BOOL -> ArdaVehicleRouterProto.ValueType.VALUE_BOOL;
            case ArdaVehicleContract.VALUE_I32 -> ArdaVehicleRouterProto.ValueType.VALUE_I32;
            case ArdaVehicleContract.VALUE_I64 -> ArdaVehicleRouterProto.ValueType.VALUE_I64;
            case ArdaVehicleContract.VALUE_F32 -> ArdaVehicleRouterProto.ValueType.VALUE_F32;
            case ArdaVehicleContract.VALUE_F64 -> ArdaVehicleRouterProto.ValueType.VALUE_F64;
            case ArdaVehicleContract.VALUE_BYTES -> ArdaVehicleRouterProto.ValueType.VALUE_BYTES;
            default -> throw new IllegalArgumentException("invalid value type=" + valueType);
        };
    }

    private static ArdaVehicleRouterProto.CommandStatus commandStatus(int status) {
        return switch (status) {
            case ArdaVehicleContract.COMMAND_OK ->
                    ArdaVehicleRouterProto.CommandStatus.COMMAND_OK;
            case ArdaVehicleContract.COMMAND_REJECTED ->
                    ArdaVehicleRouterProto.CommandStatus.COMMAND_REJECTED;
            case ArdaVehicleContract.COMMAND_UNSUPPORTED ->
                    ArdaVehicleRouterProto.CommandStatus.COMMAND_UNSUPPORTED;
            case ArdaVehicleContract.COMMAND_IO_ERROR ->
                    ArdaVehicleRouterProto.CommandStatus.COMMAND_IO_ERROR;
            case ArdaVehicleContract.COMMAND_TIMEOUT ->
                    ArdaVehicleRouterProto.CommandStatus.COMMAND_TIMEOUT;
            default -> throw new IllegalArgumentException("invalid command status=" + status);
        };
    }

    private static void writeEnvelope(
            DataOutputStream output,
            ArdaVehicleRouterProto.Envelope envelope) throws IOException {
        byte[] body = envelope.toByteArray();
        if (body.length == 0 || body.length > MAX_FRAME_BYTES) {
            throw new IOException("invalid outgoing router frame length=" + body.length);
        }
        output.writeInt(body.length);
        output.write(body);
        output.flush();
    }

    private static ArdaVehicleRouterProto.Envelope readEnvelope(DataInputStream input)
            throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("invalid router frame length=" + length);
        }
        byte[] body = new byte[length];
        input.readFully(body);
        return ArdaVehicleRouterProto.Envelope.parseFrom(body);
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }
}
