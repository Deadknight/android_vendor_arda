package com.arda.vehicle.smoke;

import android.os.SystemClock;
import android.util.Log;

import com.arda.vehicle.adapter.AdapterDescriptor;
import com.arda.vehicle.adapter.VehicleValue;
import com.arda.vehicle.sdk.ArdaVehicleAdapterService;
import com.arda.vehicle.sdk.ArdaVehicleContract;

/**
 * Publish-only sample source used by the external Vehicle Sources acceptance gate.
 *
 * Values deliberately use properties already owned by the ARDA VHAL overlay so the
 * smoke path proves Adapter -> Manager -> router -> VHAL -> CarPropertyManager.
 */
public final class SmokeAdapterService extends ArdaVehicleAdapterService {
    private static final String TAG = "ArdaVehicleSmoke";

    private static final int PROP_PERF_VEHICLE_SPEED = 291_504_647;
    private static final int PROP_PERF_ODOMETER = 291_504_644;
    private static final int PROP_GEAR_SELECTION = 289_408_000;

    // Android VehicleProperty enum value for GEAR_DRIVE.
    private static final int GEAR_DRIVE = 8;

    @Override
    protected AdapterDescriptor onCreateAdapterDescriptor() {
        AdapterDescriptor descriptor = new AdapterDescriptor();
        descriptor.apiVersion = ArdaVehicleContract.API_VERSION;
        descriptor.adapterId = "arda.smoke.adapter";
        descriptor.displayName = "ARDA smoke adapter";
        descriptor.requestedPriority = 100;
        descriptor.publishCapabilities = new int[] {
                ArdaVehicleContract.CAP_POWERTRAIN,
                ArdaVehicleContract.CAP_ENERGY,
        };
        descriptor.commandCapabilities = new int[0];
        descriptor.requestedPublishPropertyIds = new int[] {
                PROP_PERF_VEHICLE_SPEED,
                PROP_PERF_ODOMETER,
                PROP_GEAR_SELECTION,
        };
        descriptor.requestedCommandPropertyIds = new int[0];
        return descriptor;
    }

    @Override
    protected void onArdaHostConnected() {
        Log.i(TAG, "SMOKE_ADAPTER_HOST_CONNECTED");
        publishSignals(
                f32(PROP_PERF_VEHICLE_SPEED, 0, 12.5f),
                f32(PROP_PERF_ODOMETER, 0, 42_125.0f),
                i32(PROP_GEAR_SELECTION, 0, GEAR_DRIVE));
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

    private static VehicleValue i32(int propertyId, int areaId, int value) {
        VehicleValue signal = new VehicleValue();
        signal.propertyId = propertyId;
        signal.areaId = areaId;
        signal.valueType = ArdaVehicleContract.VALUE_I32;
        signal.int32Value = value;
        signal.timestampNanos = SystemClock.elapsedRealtimeNanos();
        return signal;
    }
}
