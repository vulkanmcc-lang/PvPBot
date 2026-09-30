package com.pvpbot.ai;

import com.pvpbot.PvPBotPlugin;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChunkTicketHolder {
    private final int radius;
    private final Set<Long> held = new HashSet<>();

    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private World lastWorld;

    public ChunkTicketHolder(int radius) {
        this.radius = radius;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    public void refresh(Location loc) {
        if (loc == null || loc.getWorld() == null) return;

        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        World world = loc.getWorld();

        if (cx == lastChunkX && cz == lastChunkZ && world == lastWorld && !held.isEmpty()) {
            return;
        }

        if (lastWorld != null && lastWorld != world) {
            releaseIn(lastWorld);
            held.clear();
        }

        lastChunkX = cx;
        lastChunkZ = cz;
        lastWorld = world;

        PvPBotPlugin plugin = PvPBotPlugin.getInstance();
        if (plugin == null) return;

        Set<Long> wanted = new HashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                wanted.add(key(x, z));
            }
        }

        List<Long> drop = new ArrayList<>();
        for (Long k : held) {
            if (!wanted.contains(k)) drop.add(k);
        }
        for (Long k : drop) {
            try {
                world.removePluginChunkTicket(keyX(k), keyZ(k), plugin);
            } catch (Throwable ignored) {
            }
            held.remove(k);
        }

        for (Long k : wanted) {
            if (held.contains(k)) continue;
            try {
                world.addPluginChunkTicket(keyX(k), keyZ(k), plugin);
                held.add(k);
            } catch (Throwable ignored) {
            }
        }
    }

    public void release() {
        if (lastWorld != null) releaseIn(lastWorld);
        held.clear();
        lastWorld = null;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
    }

    private void releaseIn(World world) {
        PvPBotPlugin plugin = PvPBotPlugin.getInstance();
        if (plugin == null) return;
        for (Long k : held) {
            try {
                world.removePluginChunkTicket(keyX(k), keyZ(k), plugin);
            } catch (Throwable ignored) {
            }
        }
    }
}
