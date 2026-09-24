package com.pvpbot;

import io.netty.channel.embedded.EmbeddedChannel;

public class FakeChannel extends EmbeddedChannel {
    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public boolean isOpen() {
        return true;
    }
}
