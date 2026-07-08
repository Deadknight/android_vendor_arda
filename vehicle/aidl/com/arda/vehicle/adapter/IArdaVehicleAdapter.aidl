package com.arda.vehicle.adapter;

import com.arda.vehicle.adapter.IArdaVehicleHost;
import com.arda.vehicle.adapter.VehicleCommand;

interface IArdaVehicleAdapter {
    oneway void connect(IArdaVehicleHost host);
    oneway void disconnect();
    oneway void onVehicleCommands(in VehicleCommand[] commands);
}
