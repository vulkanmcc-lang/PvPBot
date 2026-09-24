package com.pvpbot.ai;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class ArrivalTracker {
    public enum State {
        TRAVELLING,

        ARRIVED,

        STALLED
    }

    private static final double PROGRESS_EPSILON = 0.25;

    private final double arriveRadius;
    private final int stallTicks;

    private Location destination;
    private double closest = Double.MAX_VALUE;
    private int noProgress;
    private boolean active;

    public ArrivalTracker(double arriveRadius, int stallTicks) {
        this.arriveRadius = arriveRadius;
        this.stallTicks = stallTicks;
    }

    public void begin(Location dest) {
        this.destination = dest == null ? null : dest.clone();
        this.closest = Double.MAX_VALUE;
        this.noProgress = 0;
        this.active = dest != null;
    }

    public void cancel() {
        destination = null;
        active = false;
        closest = Double.MAX_VALUE;
        noProgress = 0;
    }

    public boolean isActive() {
        return active;
    }

    public Location destination() {
        return destination;
    }

    public void retarget(Location dest) {
        if (dest == null) return;
        if (destination == null) {
            begin(dest);
            return;
        }
        destination.setWorld(dest.getWorld());
        destination.setX(dest.getX());
        destination.setY(dest.getY());
        destination.setZ(dest.getZ());
        active = true;
    }

    public double distance(Player botPlayer) {
        if (!active || destination == null || botPlayer == null) return Double.NaN;
        Location here = botPlayer.getLocation();
        if (here.getWorld() != destination.getWorld()) return Double.NaN;
        double dx = destination.getX() - here.getX();
        double dz = destination.getZ() - here.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public State update(Player botPlayer) {
        double d = distance(botPlayer);
        if (Double.isNaN(d)) return State.TRAVELLING;

        if (d <= arriveRadius) return State.ARRIVED;

        if (d + PROGRESS_EPSILON < closest) {
            closest = d;
            noProgress = 0;
        } else {
            noProgress++;
        }

        return noProgress >= stallTicks ? State.STALLED : State.TRAVELLING;
    }

    public int stalledFor() {
        return noProgress;
    }
}
