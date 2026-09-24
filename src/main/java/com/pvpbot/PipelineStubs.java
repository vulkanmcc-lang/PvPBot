package com.pvpbot;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPipeline;

public final class PipelineStubs {
    private PipelineStubs() {
    }

    private static final String[] NAMES = {
            "timeout",
            "splitter",
            "decoder",
            "prepender",
            "encoder",
            "unbundler",
            "bundler",
    };

    public static void install(ChannelPipeline pipeline) {
        for (String name : NAMES) {
            if (pipeline.get(name) != null) continue;
            try {
                pipeline.addLast(name, new NoOp());
            } catch (Throwable t) {
                Bukkit_warn(name, t);
            }
        }
    }

    private static void Bukkit_warn(String name, Throwable t) {
        org.bukkit.Bukkit.getLogger().warning(
                "[PvPBot] Could not add pipeline stub '" + name + "': " + t);
    }

    private static final class NoOp extends ChannelDuplexHandler {
    }
}
