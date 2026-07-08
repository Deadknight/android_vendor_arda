package com.arda.vehicle.adapter;

import com.arda.vehicle.adapter.AdapterDescriptor;
import com.arda.vehicle.adapter.CommandAck;
import com.arda.vehicle.adapter.VehicleValue;

interface IArdaVehicleHost {
    void registerAdapter(in AdapterDescriptor descriptor);
    oneway void publishSignals(in VehicleValue[] signals);
    oneway void acknowledgeCommands(in CommandAck[] acknowledgements);
}
