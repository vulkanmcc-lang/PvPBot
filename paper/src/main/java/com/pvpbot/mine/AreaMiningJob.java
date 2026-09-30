package com.pvpbot.mine;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AreaMiningJob {
    private static final Map<String, AreaMiningJob> ACTIVE = new HashMap<>();

    private final String faction;
    private final World world;
    private final Location leaderLocation;
    private final double radius;
    private final long endTick;
    private final UUID requester;

    private final Map<UUID, Integer> assignedLanes = new HashMap<>();
    private int nextLane = 0;

    private int blocksMined;
    private boolean closed;

    private AreaMiningJob(String faction, World world, Location leaderLocation, 
                          double radius, long endTick, UUID requester) {
        this.faction = faction;
        this.world = world;
        this.leaderLocation = leaderLocation.clone();
        this.radius = radius;
        this.endTick = endTick;
        this.requester = requester;
    }

    public static AreaMiningJob start(String faction, World world, Location leaderLocation, 
                                      double radius, int seconds, UUID requester) {
        String key = faction == null ? "" : faction.toLowerCase();

        AreaMiningJob job = new AreaMiningJob(key, world, leaderLocation, radius,
                System.currentTimeMillis() + (long) seconds * 1000L, requester);

        AreaMiningJob previous = ACTIVE.put(key, job);
        if (previous != null) previous.closed = true;
        return job;
    }

    public static AreaMiningJob forFaction(String faction) {
        AreaMiningJob job = ACTIVE.get(faction == null ? "" : faction.toLowerCase());
        if (job != null && job.closed) return null;
        return job;
    }

    public static void stop(String faction) {
        AreaMiningJob job = ACTIVE.remove(faction == null ? "" : faction.toLowerCase());
        if (job != null) job.closed = true;
    }

    public static void stopAll() {
        for (AreaMiningJob job : ACTIVE.values()) job.closed = true;
        ACTIVE.clear();
    }

    public boolean isExpired() {
        return closed || System.currentTimeMillis() >= endTick;
    }

    public void close() {
        closed = true;
        ACTIVE.remove(faction, this);
    }

    public int secondsLeft() {
        long left = endTick - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000L);
    }

    public World getWorld() {
        return world;
    }

    public Location getLeaderLocation() {
        return leaderLocation.clone();
    }

    public double getRadius() {
        return radius;
    }

    public UUID getRequester() {
        return requester;
    }

    public int getBlocksMined() {
        return blocksMined;
    }

    public void noteMined() {
        blocksMined++;
    }

    public boolean contains(int x, int y, int z) {
        double dx = x - leaderLocation.getX();
        double dz = z - leaderLocation.getZ();
        return (dx * dx + dz * dz) <= radius * radius;
    }

    public Location getTargetPosition(int lane, int crewSize) {
        double angle = (2 * Math.PI * lane) / crewSize;
        double distance = radius * 0.5 + (radius * 0.4 * (lane % 2));
        
        double x = leaderLocation.getX() + Math.cos(angle) * distance;
        double z = leaderLocation.getZ() + Math.sin(angle) * distance;
        double y = leaderLocation.getY();
        
        return new Location(world, x, y, z);
    }

    public int assignLane(UUID botUuid) {
        return assignedLanes.computeIfAbsent(botUuid, u -> nextLane++);
    }

    public int crewSize() {
        return Math.max(1, assignedLanes.size());
    }

    public List<UUID> members() {
        return new ArrayList<>(assignedLanes.keySet());
    }
}
