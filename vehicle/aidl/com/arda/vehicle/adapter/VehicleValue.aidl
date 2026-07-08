package com.arda.vehicle.adapter;

parcelable VehicleValue {
    int propertyId;
    int areaId;
    int valueType;
    boolean boolValue;
    int int32Value;
    long int64Value;
    float floatValue;
    double doubleValue;
    @nullable byte[] bytesValue;
    long timestampNanos;
}
