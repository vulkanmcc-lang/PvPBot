package com.pvpbot.ai;

public class NavAvoid {
    private static final int CAPACITY = 8;

    private static final int TTL = 300;

    public static final double PENALTY = 25.0;

    private final int[] xs = new int[CAPACITY];
    private final int[] ys = new int[CAPACITY];
    private final int[] zs = new int[CAPACITY];
    private final int[] expiry = new int[CAPACITY];
    private int count = 0;
    private int writeCursor = 0;

    public void mark(int x, int y, int z, int tick) {
        for (int i = 0; i < count; i++) {
            if (near(i, x, y, z)) {
                expiry[i] = tick + TTL;
                return;
            }
        }

        int slot;
        if (count < CAPACITY) {
            slot = count++;
        } else {
            slot = writeCursor;
            writeCursor = (writeCursor + 1) % CAPACITY;
        }
        xs[slot] = x;
        ys[slot] = y;
        zs[slot] = z;
        expiry[slot] = tick + TTL;
    }

    public void mark(org.bukkit.Location loc, int tick) {
        if (loc == null) return;
        mark(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), tick);
    }

    public void expire(int tick) {
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (expiry[i] > tick) {
                if (w != i) {
                    xs[w] = xs[i];
                    ys[w] = ys[i];
                    zs[w] = zs[i];
                    expiry[w] = expiry[i];
                }
                w++;
            }
        }
        count = w;
        if (writeCursor >= count) writeCursor = 0;
    }

    public void clear() {
        count = 0;
        writeCursor = 0;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }

    public int snapshot(int[] outX, int[] outY, int[] outZ) {
        if (outX == null || outY == null || outZ == null) return 0;
        int n = Math.min(count, Math.min(outX.length, Math.min(outY.length, outZ.length)));
        System.arraycopy(xs, 0, outX, 0, n);
        System.arraycopy(ys, 0, outY, 0, n);
        System.arraycopy(zs, 0, outZ, 0, n);
        return n;
    }

    public static int capacity() {
        return CAPACITY;
    }

    private boolean near(int i, int x, int y, int z) {
        return Math.abs(xs[i] - x) <= 1
                && Math.abs(zs[i] - z) <= 1
                && Math.abs(ys[i] - y) <= 2;
    }
}
