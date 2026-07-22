package com.arda.vehicle.admin;

parcelable VehicleSourceGrantRequest {
    int userId;
    String componentName;
    int priority;
    int[] publishCapabilities;
    int[] commandCapabilities;
    int[] publishPropertyIds;
    int[] commandPropertyIds;
}
