package com.arda.vehicle.sdk;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.arda.vehicle.adapter.AdapterDescriptor;
import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.IArdaVehicleAdapter;
import com.arda.vehicle.adapter.IArdaVehicleHost;
import com.arda.vehicle.adapter.VehicleCommand;
import com.arda.vehicle.adapter.VehicleValue;

public abstract class ArdaVehicleAdapterService extends Service {
    private final Object mHostLock = new Object();
    private IArdaVehicleHost mHost;

    private final IArdaVehicleAdapter.Stub mBinder = new IArdaVehicleAdapter.Stub() {
        @Override
        public void connect(IArdaVehicleHost host) {
            if (host == null) {
                throw new IllegalArgumentException("host must not be null");
            }
            AdapterDescriptor descriptor = onCreateAdapterDescriptor();
            if (descriptor == null) {
                throw new IllegalStateException("adapter descriptor must not be null");
            }
            try {
                host.registerAdapter(descriptor);
            } catch (RemoteException e) {
                throw new IllegalStateException("failed to register adapter descriptor", e);
            }
            synchronized (mHostLock) {
                mHost = host;
            }
            onArdaHostConnected();
        }

        @Override
        public void disconnect() {
            synchronized (mHostLock) {
                mHost = null;
            }
            onArdaHostDisconnected();
        }

        @Override
        public void onVehicleCommands(VehicleCommand[] commands) {
            onArdaVehicleCommands(commands != null ? commands : new VehicleCommand[0]);
        }
    };

    @Override
    public final IBinder onBind(Intent intent) {
        if (intent == null || !ArdaVehicleContract.ACTION_ADAPTER.equals(intent.getAction())) {
            return null;
        }
        return mBinder;
    }

    protected abstract AdapterDescriptor onCreateAdapterDescriptor();

    protected void onArdaHostConnected() {}

    protected void onArdaHostDisconnected() {}

    protected void onArdaVehicleCommands(VehicleCommand[] commands) {}

    protected final boolean publishSignals(VehicleValue... signals) {
        IArdaVehicleHost host;
        synchronized (mHostLock) {
            host = mHost;
        }
        if (host == null) {
            return false;
        }
        try {
            host.publishSignals(signals != null ? signals : new VehicleValue[0]);
            return true;
        } catch (RemoteException e) {
            clearDeadHost(host);
            return false;
        }
    }

    protected final boolean acknowledgeCommands(CommandAck... acknowledgements) {
        IArdaVehicleHost host;
        synchronized (mHostLock) {
            host = mHost;
        }
        if (host == null) {
            return false;
        }
        try {
            host.acknowledgeCommands(
                    acknowledgements != null ? acknowledgements : new CommandAck[0]);
            return true;
        } catch (RemoteException e) {
            clearDeadHost(host);
            return false;
        }
    }

    private void clearDeadHost(IArdaVehicleHost host) {
        synchronized (mHostLock) {
            if (mHost == host) {
                mHost = null;
            }
        }
    }
}
