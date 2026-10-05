package com.pvpbot;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

public class NetworkManager extends Connection {
    public NetworkManager(PacketFlow packetFlow) {
        super(packetFlow);
    }

    @Override
    public void send(Packet<?> packet) {
    }
}
