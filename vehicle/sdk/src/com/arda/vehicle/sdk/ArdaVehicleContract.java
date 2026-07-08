package com.arda.vehicle.sdk;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public final class ArdaVehicleContract {
    public static final int API_VERSION = 1;

    public static final String ACTION_ADAPTER = "com.arda.vehicle.ADAPTER";
    public static final String PERMISSION_BIND_ADAPTER =
            "com.arda.vehicle.permission.BIND_ADAPTER";

    public static final String META_ADAPTER_ID = "com.arda.vehicle.ADAPTER_ID";
    public static final String META_DISPLAY_NAME = "com.arda.vehicle.DISPLAY_NAME";
    public static final String META_API_VERSION = "com.arda.vehicle.API_VERSION";
    public static final String META_REQUESTED_PRIORITY = "com.arda.vehicle.REQUESTED_PRIORITY";
    public static final String META_PUBLISH_CAPABILITIES =
            "com.arda.vehicle.PUBLISH_CAPABILITIES";
    public static final String META_COMMAND_CAPABILITIES =
            "com.arda.vehicle.COMMAND_CAPABILITIES";

    public static final int VALUE_UNSPECIFIED = 0;
    public static final int VALUE_BOOL = 1;
    public static final int VALUE_I32 = 2;
    public static final int VALUE_I64 = 3;
    public static final int VALUE_F32 = 4;
    public static final int VALUE_F64 = 5;
    public static final int VALUE_BYTES = 6;

    @IntDef({
            VALUE_BOOL,
            VALUE_I32,
            VALUE_I64,
            VALUE_F32,
            VALUE_F64,
            VALUE_BYTES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ValueType {}

    public static final int CAP_UNSPECIFIED = 0;
    public static final int CAP_POWERTRAIN = 1;
    public static final int CAP_ENERGY = 2;
    public static final int CAP_HVAC_STATE = 3;
    public static final int CAP_BODY_STATE = 4;
    public static final int CAP_CHARGING_STATE = 5;
    public static final int CAP_HVAC_CONTROL = 16;
    public static final int CAP_BODY_CONTROL = 17;
    public static final int CAP_CHARGING_CONTROL = 18;

    @IntDef({
            CAP_POWERTRAIN,
            CAP_ENERGY,
            CAP_HVAC_STATE,
            CAP_BODY_STATE,
            CAP_CHARGING_STATE,
            CAP_HVAC_CONTROL,
            CAP_BODY_CONTROL,
            CAP_CHARGING_CONTROL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Capability {}

    public static final int COMMAND_OK = 0;
    public static final int COMMAND_REJECTED = 1;
    public static final int COMMAND_UNSUPPORTED = 2;
    public static final int COMMAND_IO_ERROR = 3;
    public static final int COMMAND_TIMEOUT = 4;

    @IntDef({
            COMMAND_OK,
            COMMAND_REJECTED,
            COMMAND_UNSUPPORTED,
            COMMAND_IO_ERROR,
            COMMAND_TIMEOUT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CommandStatus {}

    private ArdaVehicleContract() {}

    public static boolean isPublishCapability(int capability) {
        return capability >= CAP_POWERTRAIN && capability <= CAP_CHARGING_STATE;
    }

    public static boolean isCommandCapability(int capability) {
        return capability == CAP_HVAC_CONTROL
                || capability == CAP_BODY_CONTROL
                || capability == CAP_CHARGING_CONTROL;
    }

    public static boolean isValueType(int valueType) {
        return valueType >= VALUE_BOOL && valueType <= VALUE_BYTES;
    }

    public static String capabilityName(int capability) {
        return switch (capability) {
            case CAP_POWERTRAIN -> "POWERTRAIN";
            case CAP_ENERGY -> "ENERGY";
            case CAP_HVAC_STATE -> "HVAC_STATE";
            case CAP_BODY_STATE -> "BODY_STATE";
            case CAP_CHARGING_STATE -> "CHARGING_STATE";
            case CAP_HVAC_CONTROL -> "HVAC_CONTROL";
            case CAP_BODY_CONTROL -> "BODY_CONTROL";
            case CAP_CHARGING_CONTROL -> "CHARGING_CONTROL";
            default -> "UNKNOWN(" + capability + ")";
        };
    }

    public static int capabilityFromName(String name) {
        if (name == null) {
            return CAP_UNSPECIFIED;
        }
        return switch (name.trim().toUpperCase()) {
            case "POWERTRAIN" -> CAP_POWERTRAIN;
            case "ENERGY" -> CAP_ENERGY;
            case "HVAC_STATE" -> CAP_HVAC_STATE;
            case "BODY_STATE" -> CAP_BODY_STATE;
            case "CHARGING_STATE" -> CAP_CHARGING_STATE;
            case "HVAC_CONTROL" -> CAP_HVAC_CONTROL;
            case "BODY_CONTROL" -> CAP_BODY_CONTROL;
            case "CHARGING_CONTROL" -> CAP_CHARGING_CONTROL;
            default -> CAP_UNSPECIFIED;
        };
    }
}
