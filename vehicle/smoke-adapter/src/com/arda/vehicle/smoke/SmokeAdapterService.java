package com.arda.vehicle.smoke;

import android.os.SystemClock;
import android.util.Log;

import com.arda.vehicle.adapter.AdapterDescriptor;
import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.VehicleCommand;
import com.arda.vehicle.adapter.VehicleValue;
import com.arda.vehicle.sdk.ArdaVehicleAdapterService;
import com.arda.vehicle.sdk.ArdaVehicleContract;

public final class SmokeAdapterService extends ArdaVehicleAdapterService {
    private static final String TAG = "ArdaVehicleSmoke";

    private static final int PROP_FUEL_LEVEL = 291_504_903;
    private static final int PROP_HVAC_TEMPERATURE_CURRENT = 358_614_274;

    @Override
    protected AdapterDescriptor onCreateAdapterDescriptor() {
        AdapterDescriptor descriptor = new AdapterDescriptor();
        descriptor.apiVersion = ArdaVehicleContract.API_VERSION;
        descriptor.adapterId = "arda.smoke.adapter";
        descriptor.displayName = "ARDA smoke adapter";
        descriptor.requestedPriority = 100;
        descriptor.publishCapabilities = new int[] {
                ArdaVehicleContract.CAP_ENERGY,
                ArdaVehicleContract.CAP_HVAC_STATE,
        };
        descriptor.commandCapabilities = new int[] {
                ArdaVehicleContract.CAP_HVAC_CONTROL,
        };
        return descriptor;
    }

    @Override
    protected void onArdaHostConnected() {
        Log.i(TAG, "SMOKE_ADAPTER_HOST_CONNECTED");
        publishSignals(
                f32(PROP_FUEL_LEVEL, 0, 32_500.0f),
                f32(PROP_HVAC_TEMPERATURE_CURRENT, 1, 21.5f));
    }

    @Override
    protected void onArdaVehicleCommands(VehicleCommand[] commands) {
        for (VehicleCommand command : commands) {
            CommandAck ack = new CommandAck();
            ack.commandId = command.commandId;
            ack.status = ArdaVehicleContract.COMMAND_OK;
            ack.error = "";
            acknowledgeCommands(ack);
            if (command.value != null) {
                publishSignals(command.value);
            }
        }
    }

    private static VehicleValue f32(int propertyId, int areaId, float value) {
        VehicleValue signal = new VehicleValue();
        signal.propertyId = propertyId;
        signal.areaId = areaId;
        signal.valueType = ArdaVehicleContract.VALUE_F32;
        signal.floatValue = value;
        signal.timestampNanos = SystemClock.elapsedRealtimeNanos();
        return signal;
    }
}
