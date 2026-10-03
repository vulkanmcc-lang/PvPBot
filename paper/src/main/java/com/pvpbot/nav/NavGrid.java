package com.pvpbot.nav;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

public final class NavGrid {
    private static final boolean[] SOLID;
    private static final boolean[] PASSABLE;
    private static final boolean[] CLIMBABLE;
    private static final boolean[] WATER;
    private static final boolean[] DANGER;
    private static final boolean[] STICKY;

    private static final boolean[] AUTO_STEP;
    private static final boolean[] DOOR;

    private static final boolean[] TALL;

    static {
        Material[] all = Material.values();
        int n = all.length;
        SOLID = new boolean[n];
        PASSABLE = new boolean[n];
        CLIMBABLE = new boolean[n];
        WATER = new boolean[n];
        DANGER = new boolean[n];
        STICKY = new boolean[n];
        AUTO_STEP = new boolean[n];
        DOOR = new boolean[n];
        TALL = new boolean[n];

        for (Material m : all) {
            int i = m.ordinal();
            String s = m.name();

            boolean solid;
            try {
                solid = m.isSolid();
            } catch (Throwable t) {
                solid = false;
            }

            boolean thin = thinBlocking(m);
            if (thin) solid = true;

            boolean water = m == Material.WATER || m == Material.BUBBLE_COLUMN;
            boolean climb = s.contains("LADDER") || s.contains("VINE") || s.contains("SCAFFOLD");
            boolean door = (s.endsWith("_DOOR") || s.endsWith("_FENCE_GATE"))
                    && !s.startsWith("IRON");
            boolean danger = m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                    || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                    || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW
                    || m == Material.WITHER_ROSE;
            boolean sticky = m == Material.COBWEB || m == Material.SOUL_SAND
                    || m == Material.HONEY_BLOCK || m == Material.SLIME_BLOCK;

            AUTO_STEP[i] = s.endsWith("_STAIRS") || s.endsWith("_SLAB")
                    || s.equals("SNOW") || s.endsWith("_CARPET") || s.equals("MOSS_CARPET")
                    || s.equals("DIRT_PATH") || s.equals("FARMLAND")
                    || s.endsWith("_BED") || s.equals("SOUL_SAND") || s.equals("MUD");

            SOLID[i] = solid && !climb && !door;
            WATER[i] = water;
            CLIMBABLE[i] = climb;
            DOOR[i] = door;
            DANGER[i] = danger;
            STICKY[i] = sticky;

            TALL[i] = s.endsWith("_FENCE") || s.endsWith("_WALL");

            PASSABLE[i] = (!solid || climb || door || water)
                    && !thin
                    && m != Material.LAVA
                    && m != Material.COBWEB
                    && !(m == Material.POWDER_SNOW);
        }
    }

    private final ChunkSnapshot[] chunks;
    private final int minCx, minCz, spanCx, spanCz;
    private final int minY, maxY;

    private NavGrid(ChunkSnapshot[] chunks, int minCx, int minCz,
                    int spanCx, int spanCz, int minY, int maxY) {
        this.chunks = chunks;
        this.minCx = minCx;
        this.minCz = minCz;
        this.spanCx = spanCx;
        this.spanCz = spanCz;
        this.minY = minY;
        this.maxY = maxY;
    }

    public static NavGrid capture(World world,
                                  int minX, int minZ, int maxX, int maxZ,
                                  int minY, int maxY) {
        int minCx = minX >> 4, maxCx = maxX >> 4;
        int minCz = minZ >> 4, maxCz = maxZ >> 4;
        int spanCx = maxCx - minCx + 1;
        int spanCz = maxCz - minCz + 1;

        ChunkSnapshot[] arr = new ChunkSnapshot[spanCx * spanCz];
        boolean any = false;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                ChunkSnapshot snap = NavChunkCache.acquire(world, cx, cz);
                arr[(cx - minCx) * spanCz + (cz - minCz)] = snap;
                if (snap != null) any = true;
            }
        }
        if (!any) return null;

        return new NavGrid(arr, minCx, minCz, spanCx, spanCz,
                Math.max(world.getMinHeight(), minY),
                Math.min(world.getMaxHeight() - 1, maxY));
    }

    public Material materialAt(int x, int y, int z) {
        if (y < minY || y > maxY) return null;
        int ix = (x >> 4) - minCx;
        int iz = (z >> 4) - minCz;
        if (ix < 0 || iz < 0 || ix >= spanCx || iz >= spanCz) return null;
        ChunkSnapshot snap = chunks[ix * spanCz + iz];
        if (snap == null) return null;
        try {
            return snap.getBlockType(x & 15, y, z & 15);
        } catch (Throwable t) {
            return null;
        }
    }

    // True when every block within `margin` of (x,y,z) is inside the captured
    // window and backed by a loaded chunk snapshot - i.e. "can't walk there"
    // answers around this cell reflect the world, not missing data.
    public boolean fullyLoaded(int x, int y, int z, int margin) {
        if (y - margin < minY || y + margin + 2 > maxY) return false;
        int cx0 = ((x - margin) >> 4) - minCx, cx1 = ((x + margin) >> 4) - minCx;
        int cz0 = ((z - margin) >> 4) - minCz, cz1 = ((z + margin) >> 4) - minCz;
        if (cx0 < 0 || cz0 < 0 || cx1 >= spanCx || cz1 >= spanCz) return false;
        for (int ix = cx0; ix <= cx1; ix++) {
            for (int iz = cz0; iz <= cz1; iz++) {
                if (chunks[ix * spanCz + iz] == null) return false;
            }
        }
        return true;
    }

    public boolean passable(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && PASSABLE[m.ordinal()];
    }

    public boolean solid(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && SOLID[m.ordinal()];
    }

    public boolean water(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && WATER[m.ordinal()];
    }

    public boolean climbable(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && CLIMBABLE[m.ordinal()];
    }

    public boolean dangerous(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && DANGER[m.ordinal()];
    }

    public boolean sticky(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && STICKY[m.ordinal()];
    }

    public boolean door(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && DOOR[m.ordinal()];
    }

    public boolean autoStep(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && AUTO_STEP[m.ordinal()];
    }

    public static boolean thinBlocking(Material m) {
        if (m == null) return false;
        String n = m.name();
        return n.endsWith("_PANE") || n.equals("IRON_BARS") || n.equals("CHAIN");
    }

    public boolean tall(int x, int y, int z) {
        Material m = materialAt(x, y, z);
        return m != null && TALL[m.ordinal()];
    }

    public boolean bodyFits(int x, int y, int z) {
        if (!passable(x, y, z) || !passable(x, y + 1, z)) return false;
        return !dangerous(x, y, z) && !dangerous(x, y + 1, z);
    }

    public boolean standable(int x, int y, int z) {
        if (!bodyFits(x, y, z)) return false;
        if (climbable(x, y, z) || water(x, y, z)) return true;
        if (dangerous(x, y - 1, z)) return false;

        if (tall(x, y - 1, z)) return false;
        return solid(x, y - 1, z);
    }

    public static final int MAX_DROP = 3;
    public static final int MAX_JUMP_GAP = 2;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public interface MoveSink {
        void accept(int x, int y, int z, double cost);
    }

    public void forEachMove(int x, int y, int z, MoveSink sink) {
        final MoveSink out = cellCosts == null ? sink
                : (nx, ny, nz, cost) -> sink.accept(nx, ny, nz,
                        cost + cellCosts.transitionCost(x, y, z, nx, ny, nz));
        boolean inWater = water(x, y, z);
        boolean onClimb = climbable(x, y, z);

        if (onClimb || climbable(x, y + 1, z)) {
            if (bodyFits(x, y + 1, z) && (climbable(x, y + 1, z) || standable(x, y + 1, z))) {
                out.accept(x, y + 1, z, 1.3);
            }
        }
        if (onClimb && bodyFits(x, y - 1, z)
                && (climbable(x, y - 1, z) || standable(x, y - 1, z))) {
            out.accept(x, y - 1, z, 1.1);
        }

        if (inWater) {
            if (water(x, y + 1, z) && bodyFits(x, y + 1, z)) out.accept(x, y + 1, z, 1.6);
            if (water(x, y - 1, z) && bodyFits(x, y - 1, z)) out.accept(x, y - 1, z, 1.4);
        }

        for (int[] d : CARDINALS) {
            emitHorizontal(x, y, z, d[0], d[1], 1.0, true, out);
        }

        for (int[] d : DIAGONALS) {
            if (!bodyFits(x + d[0], y, z) || !bodyFits(x, y, z + d[1])) continue;
            if (!diagonalHasWalkableSide(x, y, z, d[0], d[1])) continue;

            emitHorizontal(x, y, z, d[0], d[1], 1.414, false, out);
        }
    }

    private boolean diagonalHasWalkableSide(int x, int y, int z, int dx, int dz) {
        return standable(x + dx, y, z)
                || standable(x + dx, y + 1, z)
                || standable(x + dx, y - 1, z)
                || standable(x, y, z + dz)
                || standable(x, y + 1, z + dz)
                || standable(x, y - 1, z + dz);
    }

    private void emitHorizontal(int x, int y, int z, int dx, int dz,
                                double baseCost, boolean allowVertical, MoveSink out) {
        int nx = x + dx, nz = z + dz;

        if (standable(nx, y, nz)) {
            out.accept(nx, y, nz, baseCost + surfacePenalty(nx, y, nz));
            return;
        }

        if (!allowVertical) return;

        boolean autoStep = autoStep(nx, y, nz);
        if (standable(nx, y + 1, nz) && passable(nx, y + 2, nz)
                && (autoStep || passable(x, y + 2, z))) {
            double cost = baseCost + (autoStep ? 0.25 : 0.6);
            out.accept(nx, y + 1, nz, cost + surfacePenalty(nx, y + 1, nz));
            return;
        }

        if (!bodyFits(nx, y, nz)) return;

        for (int k = 1; k <= MAX_DROP; k++) {
            int dy = y - k;
            if (standable(nx, dy, nz)) {
                out.accept(nx, dy, nz, baseCost + 0.4 * k + surfacePenalty(nx, dy, nz));
                return;
            }
            if (!bodyFits(nx, dy, nz)) return;
        }

        if (dx != 0 && dz != 0) return;
        if (!passable(x, y + 2, z)) return;

        for (int gap = 2; gap <= MAX_JUMP_GAP + 1; gap++) {
            int jx = x + dx * gap, jz = z + dz * gap;
            if (standable(jx, y, jz)) {
                out.accept(jx, y, jz, 1.0 + 1.8 * gap + surfacePenalty(jx, y, jz));
                return;
            }

            if (!bodyFits(jx, y, jz) || !passable(jx, y + 2, jz)) return;
        }
    }

    private double surfacePenalty(int x, int y, int z) {
        double p = 0.0;
        if (water(x, y, z)) p += 2.0;
        if (sticky(x, y, z) || sticky(x, y - 1, z)) p += 3.0;
        if (door(x, y, z)) p += 1.5;
        if (dangerous(x, y - 1, z)) p += 40.0;
        p += clearancePenalty(x, y, z);
        p += avoidPenalty(x, y, z);
        return p;
    }

    // Small cost for cells that hug an obstacle at body height (tree trunks,
    // walls, fence posts). The bot is 0.6 wide and follows a smoothed line,
    // so a route that brushes a trunk corner is the one it snags on; paying a
    // little to keep a block of margin where the terrain allows it keeps the
    // path out of that situation. Uniform in 1-wide corridors, so it never
    // changes which corridor wins, only where in open ground the path runs.
    private double clearancePenalty(int x, int y, int z) {
        int blocked = 0;
        for (int[] d : CARDINALS) {
            int ax = x + d[0], az = z + d[1];
            if (solid(ax, y, az) || solid(ax, y + 1, az)) blocked++;
        }
        return blocked * CLEARANCE_COST;
    }

    public static final double CLEARANCE_COST = 0.12;

    private CellCostMap cellCosts;

    public void setCellCosts(CellCostMap costs) {
        this.cellCosts = (costs == null || costs.isEmpty()) ? null : costs;
    }

    public double frontierBias(int x, int y, int z) {
        return cellCosts == null ? 0.0 : cellCosts.frontierBias(x, y, z);
    }

    public boolean knownDeadEnd(int x, int y, int z) {
        return cellCosts != null && cellCosts.deadEnd(x, y, z);
    }

    private int[] avoidX;
    private int[] avoidY;
    private int[] avoidZ;
    private int avoidCount = 0;
    private double avoidCost = 0.0;

    public void setAvoid(int[] xs, int[] ys, int[] zs, int count, double cost) {
        this.avoidX = xs;
        this.avoidY = ys;
        this.avoidZ = zs;
        this.avoidCount = count;
        this.avoidCost = cost;
    }

    private int[] memX;
    private int[] memY;
    private int[] memZ;
    private int memCount = 0;
    private double memCost = 0.0;

    private int[] crowdX;
    private int[] crowdZ;
    private int crowdCount = 0;
    private double crowdCost = 0.0;

    public void setCrowd(int[] xs, int[] zs, int count, double cost) {
        this.crowdX = xs;
        this.crowdZ = zs;
        this.crowdCount = count;
        this.crowdCost = cost;
    }

    public void setTerrainMemory(int[] xs, int[] ys, int[] zs, int count, double cost) {
        this.memX = xs;
        this.memY = ys;
        this.memZ = zs;
        this.memCount = count;
        this.memCost = cost;
    }

    private double avoidPenalty(int x, int y, int z) {
        double p = 0.0;

        if (avoidCount > 0) {
            for (int i = 0; i < avoidCount; i++) {
                if (Math.abs(avoidX[i] - x) <= 1
                        && Math.abs(avoidZ[i] - z) <= 1
                        && Math.abs(avoidY[i] - y) <= 2) {
                    p += avoidCost;
                    break;
                }
            }
        }

        if (memCount > 0) {
            for (int i = 0; i < memCount; i++) {
                if (Math.abs(memX[i] - x) <= 1
                        && Math.abs(memZ[i] - z) <= 1
                        && Math.abs(memY[i] - y) <= 2) {
                    p += memCost;
                    break;
                }
            }
        }

        if (crowdCount > 0) {
            for (int i = 0; i < crowdCount; i++) {
                int dx = crowdX[i] - x;
                int dz = crowdZ[i] - z;
                if (dx * dx + dz * dz <= 1) {
                    p += crowdCost;
                    break;
                }
            }
        }

        return p;
    }

    // Half-width used when sweeping the bot's hitbox along a straight
    // segment. The real player box is 0.3; a hair of extra margin keeps the
    // smoothed path from grazing trunk/wall corners it would snag on.
    public static final double SWEEP_HALF_WIDTH = 0.34;

    // Can the bot walk the straight segment between two cell centres? Sweeps
    // the full hitbox (not just the centre line), so a shortcut that clips
    // the corner of a log, a fence post or a maze wall is rejected - those
    // grazing shortcuts are what used to leave bots pinned against trees.
    public boolean walkableLine(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        int cells = Math.max(Math.abs(dx), Math.abs(dz));
        if (cells == 0) return Math.abs(dy) <= 1;

        if (Math.abs(dy) > 1) return false;

        double ax = x1 + 0.5, az = z1 + 0.5;
        double bx = x2 + 0.5, bz = z2 + 0.5;
        // Sample by true (euclidean) length, finer than the hitbox width, so
        // long diagonal shortcuts can't step over a trunk corner between
        // samples.
        int samples = Math.max(2, (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dz * dz) / 0.125));

        int prevY = y1;
        int prevCx = x1, prevCz = z1;
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double px = ax + (bx - ax) * t;
            double pz = az + (bz - az) * t;
            int cx = (int) Math.floor(px);
            int cz = (int) Math.floor(pz);

            int foundY = prevY;
            if (cx != prevCx || cz != prevCz) {
                boolean ok = false;
                for (int yy = prevY + 1; yy >= prevY - 1; yy--) {
                    if (standable(cx, yy, cz)) { ok = true; foundY = yy; break; }
                }
                if (!ok) return false;

                // Stepping up mid-line needs head room above where we were.
                if (foundY > prevY && !passable(prevCx, prevY + 2, prevCz)) return false;

                if (cx != prevCx && cz != prevCz) {
                    if (!bodyFits(prevCx, foundY, cz) || !bodyFits(cx, foundY, prevCz)) return false;
                }
            }

            int bodyY = Math.max(foundY, prevY);
            if (!hitboxClear(px, bodyY, pz)) return false;

            prevY = foundY;
            prevCx = cx;
            prevCz = cz;
        }
        return Math.abs(prevY - y2) <= 1;
    }

    private boolean hitboxClear(double px, int y, double pz) {
        int x0 = (int) Math.floor(px - SWEEP_HALF_WIDTH);
        int x1 = (int) Math.floor(px + SWEEP_HALF_WIDTH);
        int z0 = (int) Math.floor(pz - SWEEP_HALF_WIDTH);
        int z1 = (int) Math.floor(pz + SWEEP_HALF_WIDTH);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (passable(x, y, z) && passable(x, y + 1, z)) continue;
                // Overlapping a one-block rise is fine - that's a step the
                // line is about to climb (dy is capped at 1), not a snag.
                if (standable(x, y + 1, z)) continue;
                return false;
            }
        }
        return true;
    }
}
