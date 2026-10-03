package com.pvpbot.ai;

import org.bukkit.Location;

public class AimHistory {
    private static final int SIZE = 8;

    private final double[] xs = new double[SIZE];
    private final double[] ys = new double[SIZE];
    private final double[] zs = new double[SIZE];
    private final Location scratch = new Location(null, 0, 0, 0);

    private int head = 0;
    private int filled = 0;

    public Location push(Location current, int delayTicks) {
        if (current == null || current.getWorld() == null) {
            scratch.setWorld(null);
            return scratch;
        }

        xs[head] = current.getX();
        ys[head] = current.getY();
        zs[head] = current.getZ();

        int available = Math.min(filled, SIZE - 1);
        int delay = Math.max(0, Math.min(delayTicks, available));
        int idx = ((head - delay) % SIZE + SIZE) % SIZE;

        scratch.setWorld(current.getWorld());
        scratch.setX(xs[idx]);
        scratch.setY(ys[idx]);
        scratch.setZ(zs[idx]);

        head = (head + 1) % SIZE;
        if (filled < SIZE) filled++;

        return scratch;
    }

    public void reset() {
        head = 0;
        filled = 0;
    }
}
