package com.arda.vehicle.admin;

import com.arda.vehicle.admin.VehicleSourceGrantRequest;
import com.arda.vehicle.admin.VehicleSourceInfo;

interface IArdaVehicleSourceManager {
    VehicleSourceInfo[] listSources(int userId);
    void rescan(int userId);
    void approveSource(in VehicleSourceGrantRequest request);
    void revokeSource(int userId, String componentName);
    void setSourceEnabled(int userId, String componentName, boolean enabled);
    void applyVehicleInfo(int userId, in int[] fuelTypes, in int[] connectorTypes);
}
