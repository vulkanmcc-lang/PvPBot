package com.pvpbot.nav;

// Immutable-after-build per-cell navigation knowledge handed to a NavGrid for
// one search. Built on the main thread (from MazeMemory), then only read by
// the async A* worker, so it needs no synchronisation once published.
//
// Two layers:
//   - visit cost: a soft Tremaux-style penalty for cells the bot has already
//     walked through (discourages re-exploring the same corridors), also used
//     to bias which frontier node a partial search heads for;
//   - dead ends: cells proven to lead nowhere. Entering one from a live cell is
//     expensive, but moving inside/out of one is free so a bot standing in a
//     dead end can still route out without blowing the search budget.
public final class CellCostMap {
    public static final double DEAD_END_ENTRY_COST = 60.0;
    public static final double DEAD_END_FRONTIER_BIAS = 40.0;

    private final Pathfinder.LongDoubleMap visitCost = new Pathfinder.LongDoubleMap(256);
    private final Pathfinder.LongDoubleMap deadEnds = new Pathfinder.LongDoubleMap(256);
    private int visitCount = 0;
    private int deadCount = 0;

    public void putVisitCost(int x, int y, int z, double cost) {
        if (cost <= 0.0) return;
        visitCost.put(Pathfinder.key(x, y, z), cost);
        visitCount++;
    }

    public void putDeadEnd(int x, int y, int z) {
        deadEnds.put(Pathfinder.key(x, y, z), 1.0);
        deadCount++;
    }

    public boolean isEmpty() {
        return visitCount == 0 && deadCount == 0;
    }

    public boolean deadEnd(int x, int y, int z) {
        return deadCount > 0 && deadEnds.get(Pathfinder.key(x, y, z)) < Double.POSITIVE_INFINITY;
    }

    public double visitCost(int x, int y, int z) {
        if (visitCount == 0) return 0.0;
        double v = visitCost.get(Pathfinder.key(x, y, z));
        return v == Double.POSITIVE_INFINITY ? 0.0 : v;
    }

    // Extra cost of the move (x,y,z) -> (nx,ny,nz).
    public double transitionCost(int x, int y, int z, int nx, int ny, int nz) {
        double c = visitCost(nx, ny, nz);
        if (deadCount > 0 && deadEnd(nx, ny, nz) && !deadEnd(x, y, z)) {
            c += DEAD_END_ENTRY_COST;
        }
        return c;
    }

    // How unattractive a cell is as the end point of a partial (budget-capped)
    // path: a known dead end or a well-trodden cell is a poor frontier.
    public double frontierBias(int x, int y, int z) {
        double b = visitCost(x, y, z) * 1.5;
        if (deadEnd(x, y, z)) b += DEAD_END_FRONTIER_BIAS;
        return b;
    }
}
