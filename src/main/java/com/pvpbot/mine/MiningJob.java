package com.pvpbot.mine;

import com.pvpbot.PvPBot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MiningJob {
    private static final Map<String, MiningJob> ACTIVE = new HashMap<>();

    private final String faction;
    private final World world;
    private final Material target;

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private final long endTick;

    private final UUID requester;

    private final Map<UUID, Integer> lanes = new HashMap<>();
    private int nextLane = 0;

    private int found;
    private int blocksMined;
    private boolean closed;

    private final java.util.LinkedHashSet<Long> hazards = new java.util.LinkedHashSet<>();
    private static final int MAX_HAZARDS = 512;

    private MiningJob(String faction, World world, Material target,
                      int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                      long endTick, UUID requester) {
        this.faction = faction;
        this.world = world;
        this.target = target;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.endTick = endTick;
        this.requester = requester;
    }

    public static MiningJob start(String faction, World world, Material target,
                                  Location c1, Location c2, int seconds, UUID requester) {
        String key = faction == null ? "" : faction.toLowerCase();

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        try {
            minY = Math.max(minY, world.getMinHeight() + 1);
            maxY = Math.min(maxY, world.getMaxHeight() - 3);
        } catch (Throwable ignored) {
        }

        MiningJob job = new MiningJob(key, world, target,
                minX, minY, minZ, maxX, maxY, maxZ,
                now() + (long) seconds * 1000L,
                requester);

        MiningJob previous = ACTIVE.put(key, job);
        if (previous != null) previous.closed = true;
        return job;
    }

    public static MiningJob forFaction(String faction) {
        MiningJob job = ACTIVE.get(faction == null ? "" : faction.toLowerCase());
        if (job != null && job.closed) return null;
        return job;
    }

    public static void stop(String faction) {
        MiningJob job = ACTIVE.remove(faction == null ? "" : faction.toLowerCase());
        if (job != null) job.closed = true;
    }

    public static void stopAll() {
        for (MiningJob job : ACTIVE.values()) job.closed = true;
        ACTIVE.clear();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    public boolean isExpired() {
        return closed || now() >= endTick;
    }

    public void close() {
        closed = true;
        ACTIVE.remove(faction, this);
    }

    public int secondsLeft() {
        long left = endTick - now();
        return left <= 0 ? 0 : (int) (left / 1000L);
    }

    public World getWorld() {
        return world;
    }

    public Material getTarget() {
        return target;
    }

    public UUID getRequester() {
        return requester;
    }

    public int getFound() {
        return found;
    }

    public int getBlocksMined() {
        return blocksMined;
    }

    public void noteFound(int n) {
        found += n;
    }

    public void noteMined() {
        blocksMined++;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public Location laneStart(int lane, int crewSize) {
        int spanX = maxX - minX;
        int spanZ = maxZ - minZ;
        int n = Math.max(1, crewSize);

        double f = (lane + 0.5) / n;

        int x;
        int z;
        if (spanX >= spanZ) {
            x = minX + (int) Math.round(f * spanX);
            z = minZ + spanZ / 2;
        } else {
            x = minX + spanX / 2;
            z = minZ + (int) Math.round(f * spanZ);
        }

        int levels = Math.max(1, Math.min(4, (maxY - minY) / 4));
        int y = minY + 1 + (lane % levels) * 4;
        if (y > maxY - 2) y = Math.max(minY + 1, maxY - 2);

        return new Location(world, x + 0.5, y, z + 0.5);
    }

    public int laneFor(PvPBot bot) {
        return lanes.computeIfAbsent(bot.getUUID(), u -> nextLane++);
    }

    public int crewSize() {
        return Math.max(1, lanes.size());
    }

    public List<UUID> members() {
        return new ArrayList<>(lanes.keySet());
    }

    public boolean insideHorizontally(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static long packCell(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFF);
    }

    public void noteHazard(int x, int y, int z) {
        long k = packCell(x, y, z);
        if (hazards.contains(k)) return;
        if (hazards.size() >= MAX_HAZARDS) {
            java.util.Iterator<Long> it = hazards.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        hazards.add(k);
    }

    public boolean hazardNear(int x, int y, int z, int r) {
        if (hazards.isEmpty()) return false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (hazards.contains(packCell(x + dx, y + dy, z + dz))) return true;
                }
            }
        }
        return false;
    }

    public int hazardCount() {
        return hazards.size();
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }
}
