package com.pvpbot.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

// Plans how a builder bot gets within reach of a block it has to place when
// plain walking can't get it there: a single A* over three kinds of move,
//
//   WALK   - ordinary movement over existing terrain (flat, diagonal, step
//            up one block, drop down up to three),
//   BRIDGE - step sideways onto a new floor block placed at the edge (sneak
//            bridging), for gaps, water and open air,
//   PILLAR - jump and place a block under your own feet to go straight up,
//
// with placements costing far more than walking, so the plan uses the fewest
// scaffold blocks that work and just walks when walking is enough.
//
// Scaffold is never planned into a cell of the build itself (that would block
// the schematic), and every standing spot the plan ends on must have a clear
// line of sight to the target within reach.
//
// Pure logic over a Terrain callback - no Bukkit types - so it runs on the
// main thread against live blocks and is also unit-testable.
public final class ScaffoldPlanner {
    public interface Terrain {
        // Can be stood on / blocks movement and line of sight.
        boolean solid(int x, int y, int z);

        // A body can occupy this cell (air, grass, water...).
        boolean passable(int x, int y, int z);

        // A scaffold block may be placed here (empty, not part of the build).
        boolean placeable(int x, int y, int z);

        // Lava, fire, magma, cactus... never stand in or on it.
        boolean dangerous(int x, int y, int z);

        // A build cell that isn't placed yet: fine to pass through, but
        // standing in one means the bot is in the way of the build.
        boolean pendingBuild(int x, int y, int z);
    }

    public enum Kind { WALK, BRIDGE, PILLAR }

    // Destination feet cell of one move. BRIDGE and PILLAR both place their
    // block at (x, y - 1, z) - the floor the bot then stands on.
    public record Step(Kind kind, int x, int y, int z) {
        public boolean placesBlock() {
            return kind != Kind.WALK;
        }
    }

    public static final double EYE_HEIGHT = 1.62;

    private static final double COST_WALK = 1.0;
    private static final double COST_DIAG = 1.42;
    private static final double COST_STEP_UP = 1.6;
    private static final double COST_BRIDGE = 5.0;
    private static final double COST_PILLAR = 4.0;
    private static final double COST_IN_BUILD = 1.5;
    private static final int MAX_DROP = 3;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private ScaffoldPlanner() {
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final Node parent;
        final Kind via;
        final double g, f;

        Node(int x, int y, int z, Node parent, Kind via, double g, double f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.via = via;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    // Returns the moves (not including the start) that end in a spot from
    // which (tx,ty,tz) is within `reach` and visible; an empty list if the
    // start already is such a spot; null when nothing works inside the
    // search box / budget.
    public static List<Step> plan(Terrain t,
                                  int sx, int sy, int sz,
                                  int tx, int ty, int tz,
                                  double reach, int horizontalPad, int maxExpansions) {
        int minX = Math.min(sx, tx) - horizontalPad, maxX = Math.max(sx, tx) + horizontalPad;
        int minZ = Math.min(sz, tz) - horizontalPad, maxZ = Math.max(sz, tz) + horizontalPad;
        int minY = Math.min(sy, ty) - 6, maxY = Math.max(sy, ty) + 4;

        double reachSq = (reach - 0.25) * (reach - 0.25);

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();

        Node start = new Node(sx, sy, sz, null, Kind.WALK, 0.0,
                heuristic(sx, sy, sz, tx, ty, tz, reach));
        open.add(start);
        best.put(key(sx, sy, sz), 0.0);

        int expansions = 0;
        while (!open.isEmpty() && expansions < maxExpansions) {
            Node cur = open.poll();
            Double known = best.get(key(cur.x, cur.y, cur.z));
            if (known != null && cur.g > known + 1e-9) continue;
            expansions++;

            if (isGoal(t, cur.x, cur.y, cur.z, tx, ty, tz, reachSq)) {
                return unwind(cur);
            }

            expand(t, cur, tx, ty, tz, reach, minX, maxX, minY, maxY, minZ, maxZ, open, best);
        }
        return null;
    }

    private static void expand(Terrain t, Node cur, int tx, int ty, int tz, double reach,
                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                               PriorityQueue<Node> open, Map<Long, Double> best) {
        int x = cur.x, y = cur.y, z = cur.z;

        for (int[] d : CARDINALS) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;

            // Flat walk.
            if (standable(t, nx, y, nz)) {
                push(t, cur, nx, y, nz, Kind.WALK, COST_WALK, tx, ty, tz, reach, open, best);
                continue;
            }

            // Step up one block (needs head room for the jump).
            if (y + 1 <= maxY && standable(t, nx, y + 1, nz) && t.passable(x, y + 2, z)) {
                push(t, cur, nx, y + 1, nz, Kind.WALK, COST_STEP_UP, tx, ty, tz, reach, open, best);
            }

            if (!bodyFits(t, nx, y, nz)) continue;

            // Walk off an edge and land up to MAX_DROP below.
            for (int k = 1; k <= MAX_DROP; k++) {
                int ny = y - k;
                if (ny < minY) break;
                if (standable(t, nx, ny, nz)) {
                    push(t, cur, nx, ny, nz, Kind.WALK, COST_WALK + 0.5 * k,
                            tx, ty, tz, reach, open, best);
                    break;
                }
                if (!t.passable(nx, ny, nz)) break;
            }

            // Bridge: place the missing floor and step onto it. Only cardinal
            // (the new block goes against the side of the floor we're on),
            // and never into the build or next to something nasty.
            if (!t.solid(nx, y - 1, nz) && t.placeable(nx, y - 1, nz)) {
                push(t, cur, nx, y, nz, Kind.BRIDGE, COST_BRIDGE, tx, ty, tz, reach, open, best);
            }
        }

        for (int[] d : DIAGONALS) {
            int nx = x + d[0], nz = z + d[1];
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
            if (!bodyFits(t, x + d[0], y, z) || !bodyFits(t, x, y, z + d[1])) continue;
            if (standable(t, nx, y, nz)) {
                push(t, cur, nx, y, nz, Kind.WALK, COST_DIAG, tx, ty, tz, reach, open, best);
            }
        }

        // Pillar: jump and place a block where our feet are now.
        if (y + 1 <= maxY
                && t.placeable(x, y, z)
                && t.passable(x, y + 2, z)
                && !t.dangerous(x, y + 2, z)) {
            push(t, cur, x, y + 1, z, Kind.PILLAR, COST_PILLAR, tx, ty, tz, reach, open, best);
        }
    }

    private static void push(Terrain t, Node parent, int nx, int ny, int nz, Kind via, double cost,
                             int tx, int ty, int tz, double reach,
                             PriorityQueue<Node> open, Map<Long, Double> best) {
        if (nx == tx && nz == tz && (ny == ty || ny + 1 == ty)) return;
        if (t.dangerous(nx, ny, nz) || t.dangerous(nx, ny + 1, nz)) return;

        double g = parent.g + cost;
        if (t.pendingBuild(nx, ny, nz) || t.pendingBuild(nx, ny + 1, nz)) g += COST_IN_BUILD;

        long k = key(nx, ny, nz);
        Double known = best.get(k);
        if (known != null && known <= g + 1e-9) return;
        best.put(k, g);
        open.add(new Node(nx, ny, nz, parent, via, g, g + heuristic(nx, ny, nz, tx, ty, tz, reach)));
    }

    public static boolean standable(Terrain t, int x, int y, int z) {
        return bodyFits(t, x, y, z) && t.solid(x, y - 1, z) && !t.dangerous(x, y - 1, z);
    }

    private static boolean bodyFits(Terrain t, int x, int y, int z) {
        return t.passable(x, y, z) && t.passable(x, y + 1, z);
    }

    private static boolean isGoal(Terrain t, int x, int y, int z,
                                  int tx, int ty, int tz, double reachSq) {
        // Can't place into a cell our own body is in.
        if (x == tx && z == tz && (y == ty || y + 1 == ty)) return false;
        double ex = x + 0.5, ey = y + EYE_HEIGHT, ez = z + 0.5;
        double dx = tx + 0.5 - ex, dy = ty + 0.5 - ey, dz = tz + 0.5 - ez;
        if (dx * dx + dy * dy + dz * dz > reachSq) return false;
        return clearSight(t, ex, ey, ez, tx, ty, tz);
    }

    // Voxel walk from the eye to the target cell; any solid cell in between
    // (other than the target itself) blocks it.
    public static boolean clearSight(Terrain t, double ox, double oy, double oz,
                                     int tx, int ty, int tz) {
        double dx = tx + 0.5 - ox, dy = ty + 0.5 - oy, dz = tz + 0.5 - oz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        dx /= len;
        dy /= len;
        dz /= len;

        int cx = (int) Math.floor(ox), cy = (int) Math.floor(oy), cz = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tMaxX = boundary(ox, dx, stepX), tMaxY = boundary(oy, dy, stepY), tMaxZ = boundary(oz, dz, stepZ);
        double tdX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        for (int guard = 0; guard < 32; guard++) {
            if (cx == tx && cy == ty && cz == tz) return true;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cx += stepX;
                tMaxX += tdX;
            } else if (tMaxY < tMaxZ) {
                cy += stepY;
                tMaxY += tdY;
            } else {
                cz += stepZ;
                tMaxZ += tdZ;
            }
            if (cx == tx && cy == ty && cz == tz) return true;
            if (t.solid(cx, cy, cz)) return false;
        }
        return true;
    }

    private static double boundary(double origin, double dir, int step) {
        if (step == 0) return Double.MAX_VALUE;
        double cell = Math.floor(origin);
        double edge = step > 0 ? cell + 1.0 : cell;
        return (edge - origin) / dir;
    }

    private static double heuristic(int x, int y, int z, int tx, int ty, int tz, double reach) {
        double dx = tx + 0.5 - (x + 0.5), dy = ty + 0.5 - (y + EYE_HEIGHT), dz = tz + 0.5 - (z + 0.5);
        return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach);
    }

    private static List<Step> unwind(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            out.add(new Step(n.via, n.x, n.y, n.z));
        }
        Collections.reverse(out);
        return out;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
