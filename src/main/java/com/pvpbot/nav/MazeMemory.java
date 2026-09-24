package com.pvpbot.nav;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Per-bot spatial memory used to get out of (and through) mazes. Main thread
// only; the search thread only ever sees the CellCostMap snapshot exported
// from here.
//
// It combines two classic maze-solving ideas on top of the A* planner:
//
//  1. Tremaux marking - every cell the bot physically walks through gets a
//     visit count. While the bot is in maze-like terrain those counts become
//     a soft path cost and a frontier bias, so when the planner can only
//     return a partial route (the budget ran out before the exit was found)
//     it heads for corridors the bot has NOT walked yet instead of bouncing
//     back to the same spot.
//
//  2. Dead-end filling - when the bot stands in a pocket (a cell whose only
//     way out is the way it came in), that cell is a dead end. The fill then
//     walks back up the corridor marking every cell that, with the dead part
//     pruned away, is again a pocket, and stops at the first real junction.
//     Because the check ignores already-dead neighbours, a junction whose
//     other branches all turned out to be dead ends becomes a pocket itself
//     and gets filled on a later pass - the dead region grows until only
//     live routes are left, exactly like pen-and-paper dead-end filling.
//     Entering a dead region is then very expensive for the planner, while
//     moving within/out of one stays cheap so a bot inside one can leave.
public final class MazeMemory {
    private static final int MAX_CELLS = 8192;

    private static final int VISIT_MEMORY_TICKS = 20 * 120;

    private static final int DEAD_END_TTL_TICKS = 20 * 60 * 5;

    private static final int MAZE_MODE_TICKS = 20 * 60;

    private static final int FILL_LIMIT = 192;

    private static final int SCAN_RADIUS = 16;

    private static final int GOAL_EXEMPT_RADIUS = 3;

    private static final int EXPORT_CAP = 6000;

    private static final class Cell {
        int visits;
        int lastVisitTick;
        int deadSinceTick = -1;
    }

    private final Map<Long, Cell> cells = new HashMap<>();
    private UUID worldId;

    private boolean hasLast = false;
    private int lastX, lastY, lastZ;

    private boolean hasGoal = false;
    private int goalX, goalY, goalZ;

    private int lastDeadEndTick = Integer.MIN_VALUE / 2;
    private int lastFrontierTick = Integer.MIN_VALUE / 2;
    private int partialStreak = 0;
    private int lastScanTick = Integer.MIN_VALUE / 2;
    private int lastPruneTick = 0;
    private int deadEndCells = 0;

    public void clear() {
        cells.clear();
        hasLast = false;
        partialStreak = 0;
        lastDeadEndTick = Integer.MIN_VALUE / 2;
        lastFrontierTick = Integer.MIN_VALUE / 2;
        deadEndCells = 0;
    }

    public void setGoal(int x, int y, int z) {
        hasGoal = true;
        goalX = x;
        goalY = y;
        goalZ = z;
    }

    public boolean isMazeLike(int tick) {
        return tick - lastDeadEndTick < MAZE_MODE_TICKS
                || tick - lastFrontierTick < MAZE_MODE_TICKS
                || partialStreak >= 2;
    }

    public int deadEndCellCount() {
        return deadEndCells;
    }

    public int partialStreak() {
        return partialStreak;
    }

    public void onPathResult(boolean complete, boolean exhausted) {
        if (complete) {
            partialStreak = 0;
        } else if (!exhausted) {
            partialStreak = Math.min(partialStreak + 1, 16);
        }
    }

    // The bot walked to the end of a partial route without reaching the goal:
    // that frontier didn't pay off, so weigh it down for the next search.
    public void onFrontierReached(int x, int y, int z, int tick) {
        Cell c = cell(x, y, z, true);
        if (c == null) return;
        c.visits += 3;
        c.lastVisitTick = tick;
        lastFrontierTick = tick;
    }

    // Called every tick with the bot's feet block; does real work only when
    // the bot enters a new cell.
    public void observe(World world, int x, int y, int z, int tick) {
        if (world == null) return;
        if (worldId == null || !worldId.equals(world.getUID())) {
            clear();
            worldId = world.getUID();
        }

        if (tick - lastPruneTick >= 200) {
            prune(tick);
            lastPruneTick = tick;
        }

        if (hasLast && x == lastX && y == lastY && z == lastZ) return;
        hasLast = true;
        lastX = x;
        lastY = y;
        lastZ = z;

        Cell c = cell(x, y, z, true);
        if (c != null) {
            c.visits++;
            c.lastVisitTick = tick;
        }

        if (tick - lastScanTick < 3) return;
        lastScanTick = tick;
        scanForDeadEnd(world, x, y, z, tick);
    }

    // =====================================================================
    // Dead-end detection & filling
    // =====================================================================

    private void scanForDeadEnd(World world, int x, int y, int z, int tick) {
        NavGrid grid;
        try {
            grid = NavGrid.capture(world, x - SCAN_RADIUS, z - SCAN_RADIUS,
                    x + SCAN_RADIUS, z + SCAN_RADIUS, y - 8, y + 8);
        } catch (Throwable t) {
            return;
        }
        if (grid == null) return;

        int[] here = snap(grid, x, y, z);
        if (here == null) return;
        int cx = here[0], cy = here[1], cz = here[2];
        if (!grid.fullyLoaded(cx, cy, cz, 2)) return;
        if (nearGoal(cx, cy, cz)) return;
        if (isDead(cx, cy, cz, tick)) return;

        List<long[]> exits = liveExits(grid, cx, cy, cz, tick);
        long[] next;
        if (exits.isEmpty()) {
            next = null;
        } else if (exits.size() == 1 && connectsBack(grid, exits.get(0), cx, cy, cz)) {
            next = exits.get(0);
        } else {
            return;
        }

        markDead(cx, cy, cz, tick);
        lastDeadEndTick = tick;

        int filled = 1;
        while (next != null && filled < FILL_LIMIT) {
            int nx = (int) next[0], ny = (int) next[1], nz = (int) next[2];
            // Near the edge of the captured window (or an unloaded chunk)
            // neighbours read as solid, which would fake a dead end.
            if (!grid.fullyLoaded(nx, ny, nz, 2)) break;
            if (nearGoal(nx, ny, nz)) break;
            if (isDead(nx, ny, nz, tick)) break;

            List<long[]> onward = liveExits(grid, nx, ny, nz, tick);
            if (onward.size() != 1 || !connectsBack(grid, onward.get(0), nx, ny, nz)) break;

            markDead(nx, ny, nz, tick);
            filled++;
            next = onward.get(0);
        }
    }

    private List<long[]> liveExits(NavGrid grid, int x, int y, int z, int tick) {
        List<long[]> out = new ArrayList<>(8);
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        grid.forEachMove(x, y, z, (nx, ny, nz, cost) -> {
            if (isDead(nx, ny, nz, tick)) return;
            if (seen.add(Pathfinder.key(nx, ny, nz))) out.add(new long[]{nx, ny, nz});
        });
        return out;
    }

    private static boolean connectsBack(NavGrid grid, long[] from, int x, int y, int z) {
        final boolean[] found = {false};
        grid.forEachMove((int) from[0], (int) from[1], (int) from[2], (nx, ny, nz, cost) -> {
            if (nx == x && ny == y && nz == z) found[0] = true;
        });
        return found[0];
    }

    private static int[] snap(NavGrid grid, int x, int y, int z) {
        if (grid.standable(x, y, z)) return new int[]{x, y, z};
        if (grid.standable(x, y + 1, z)) return new int[]{x, y + 1, z};
        if (grid.standable(x, y - 1, z)) return new int[]{x, y - 1, z};
        return null;
    }

    private boolean nearGoal(int x, int y, int z) {
        if (!hasGoal) return false;
        return Math.abs(x - goalX) <= GOAL_EXEMPT_RADIUS
                && Math.abs(z - goalZ) <= GOAL_EXEMPT_RADIUS
                && Math.abs(y - goalY) <= GOAL_EXEMPT_RADIUS;
    }

    private void markDead(int x, int y, int z, int tick) {
        Cell c = cell(x, y, z, true);
        if (c == null) return;
        if (c.deadSinceTick < 0 || tick - c.deadSinceTick > DEAD_END_TTL_TICKS) deadEndCells++;
        c.deadSinceTick = tick;
    }

    private boolean isDead(int x, int y, int z, int tick) {
        Cell c = cells.get(Pathfinder.key(x, y, z));
        return c != null && c.deadSinceTick >= 0 && tick - c.deadSinceTick <= DEAD_END_TTL_TICKS;
    }

    // =====================================================================
    // Export for a search
    // =====================================================================

    public CellCostMap export(World world, int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ, int tick) {
        CellCostMap map = new CellCostMap();
        if (world == null || worldId == null || !worldId.equals(world.getUID())) return map;

        boolean maze = isMazeLike(tick);
        int n = 0;
        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            if (n >= EXPORT_CAP) break;
            long k = e.getKey();
            int x = unpackX(k), y = unpackY(k), z = unpackZ(k);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
            if (nearGoal(x, y, z)) continue;

            Cell c = e.getValue();
            boolean exported = false;
            if (c.deadSinceTick >= 0 && tick - c.deadSinceTick <= DEAD_END_TTL_TICKS) {
                map.putDeadEnd(x, y, z);
                exported = true;
            }
            if (maze && c.visits > 0 && tick - c.lastVisitTick <= VISIT_MEMORY_TICKS) {
                map.putVisitCost(x, y, z, Math.min(15.0, 0.7 * c.visits));
                exported = true;
            }
            if (exported) n++;
        }
        return map;
    }

    // =====================================================================
    // Bookkeeping
    // =====================================================================

    private Cell cell(int x, int y, int z, boolean create) {
        long k = Pathfinder.key(x, y, z);
        Cell c = cells.get(k);
        if (c == null && create) {
            if (cells.size() >= MAX_CELLS) return null;
            c = new Cell();
            cells.put(k, c);
        }
        return c;
    }

    private void prune(int tick) {
        int dead = 0;
        Iterator<Map.Entry<Long, Cell>> it = cells.entrySet().iterator();
        while (it.hasNext()) {
            Cell c = it.next().getValue();
            boolean deadLive = c.deadSinceTick >= 0 && tick - c.deadSinceTick <= DEAD_END_TTL_TICKS;
            boolean visitLive = tick - c.lastVisitTick <= VISIT_MEMORY_TICKS;
            if (!deadLive) c.deadSinceTick = -1;
            if (!deadLive && !visitLive) {
                it.remove();
            } else if (deadLive) {
                dead++;
            }
        }
        deadEndCells = dead;
        if (partialStreak > 0 && tick - lastDeadEndTick > MAZE_MODE_TICKS * 2
                && tick - lastFrontierTick > MAZE_MODE_TICKS * 2) {
            partialStreak = 0;
        }
    }

    private static int unpackX(long k) {
        int v = (int) ((k >> 38) & 0x3FFFFFFL);
        return (v << 6) >> 6;
    }

    private static int unpackY(long k) {
        int v = (int) ((k >> 26) & 0xFFFL);
        return (v << 20) >> 20;
    }

    private static int unpackZ(long k) {
        int v = (int) (k & 0x3FFFFFFL);
        return (v << 6) >> 6;
    }
}
