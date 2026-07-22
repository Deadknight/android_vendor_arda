package com.arda.vehicle.admin;

parcelable VehicleSourceInfo {
    int userId;
    String packageName;
    String componentName;
    String adapterId;
    String displayName;
    int uid;
    int apiVersion;
    String signerSummary;
    int requestedPriority;
    int effectivePriority;
    int sourceId;
    int[] requestedPublishCapabilities;
    int[] requestedCommandCapabilities;
    int[] requestedPublishPropertyIds;
    int[] requestedCommandPropertyIds;
    int[] grantedPublishCapabilities;
    int[] grantedCommandCapabilities;
    int[] grantedPublishPropertyIds;
    int[] grantedCommandPropertyIds;
    String state;
    boolean enabled;
    boolean connected;
    boolean ready;
    boolean routerRegistered;
    int pendingSignals;
    boolean additionalApproval;
    String lastError;
}
