package com.pvpbot.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

public final class Pathfinder {
    public static int maxExpansions = 12000;

    public static double heuristicWeight = 1.05;

    private static boolean atGoal(int x, int y, int z, int gx, int gy, int gz) {
        int dx = x - gx, dy = y - gy, dz = z - gz;
        return dx * dx + dz * dz <= 9 && dy >= -1 && dy <= 1;
    }

    private static double trueDistance(int x, int y, int z, int gx, int gy, int gz) {
        double dx = x - gx, dy = y - gy, dz = z - gz;
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy);
    }

    public static final class Step {
        public final int x, y, z;

        Step(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class Result {
        public final List<Step> steps;

        public final boolean complete;
        public final int expansions;

        Result(List<Step> steps, boolean complete, int expansions) {
            this.steps = steps;
            this.complete = complete;
            this.expansions = expansions;
        }
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final Node parent;
        final double g, f;

        Node(int x, int y, int z, Node parent, double g, double f) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    private Pathfinder() {
    }

    public static Result find(NavGrid grid,
                              int sx, int sy, int sz,
                              int gx, int gy, int gz) {
        return find(grid, sx, sy, sz, gx, gy, gz, maxExpansions);
    }

    public static Result find(NavGrid grid,
                              int sx, int sy, int sz,
                              int gx, int gy, int gz,
                              int expansionBudget) {
        int[] start = snapToStandable(grid, sx, sy, sz);
        if (start == null) return new Result(new ArrayList<>(), false, 0);
        sx = start[0]; sy = start[1]; sz = start[2];

        int[] goal = snapToStandable(grid, gx, gy, gz);
        if (goal != null) { gx = goal[0]; gy = goal[1]; gz = goal[2]; }

        final int fgx = gx, fgy = gy, fgz = gz;

        PriorityQueue<Node> open = new PriorityQueue<>(512);
        LongDoubleMap best = new LongDoubleMap(4096);

        double h0 = heuristic(sx, sy, sz, fgx, fgy, fgz);
        Node startNode = new Node(sx, sy, sz, null, 0.0, h0 * heuristicWeight);
        open.add(startNode);
        best.put(key(sx, sy, sz), 0.0);

        Node closest = startNode;
        double closestD = trueDistance(sx, sy, sz, fgx, fgy, fgz);
        Node goalNode = null;
        int expansions = 0;

        while (!open.isEmpty() && expansions < expansionBudget) {
            Node cur = open.poll();

            long ck = key(cur.x, cur.y, cur.z);
            if (cur.g > best.get(ck) + 1e-9) continue;
            expansions++;

            double trueD = trueDistance(cur.x, cur.y, cur.z, fgx, fgy, fgz);
            if (trueD < closestD) {
                closestD = trueD;
                closest = cur;
            }
            if (atGoal(cur.x, cur.y, cur.z, fgx, fgy, fgz)) {
                goalNode = cur;
                break;
            }

            final Node parent = cur;
            grid.forEachMove(cur.x, cur.y, cur.z, (nx, ny, nz, cost) -> {
                double ng = parent.g + cost;
                long nk = key(nx, ny, nz);
                if (ng + 1e-9 >= best.get(nk)) return;
                best.put(nk, ng);
                double nh = heuristic(nx, ny, nz, fgx, fgy, fgz);
                open.add(new Node(nx, ny, nz, parent, ng, ng + nh * heuristicWeight));
            });
        }

        boolean complete = goalNode != null;
        Node end = complete ? goalNode : closest;

        if (end == null || end.parent == null) {
            return new Result(new ArrayList<>(), false, expansions);
        }

        List<Step> raw = new ArrayList<>(64);
        for (Node n = end; n != null; n = n.parent) raw.add(new Step(n.x, n.y, n.z));
        java.util.Collections.reverse(raw);

        return new Result(smooth(grid, raw), complete, expansions);
    }

    private static List<Step> smooth(NavGrid grid, List<Step> path) {
        if (path.size() <= 2) return path;

        List<Step> out = new ArrayList<>(path.size());
        out.add(path.get(0));

        int i = 0;
        while (i < path.size() - 1) {
            int furthest = i + 1;

            int limit = Math.min(path.size() - 1, i + 8);
            for (int j = limit; j > i + 1; j--) {
                Step a = path.get(i), b = path.get(j);
                if (grid.walkableLine(a.x, a.y, a.z, b.x, b.y, b.z)) {
                    furthest = j;
                    break;
                }
            }
            out.add(path.get(furthest));
            i = furthest;
        }
        return out;
    }

    private static int[] snapToStandable(NavGrid grid, int x, int y, int z) {
        if (grid.standable(x, y, z)) return new int[]{x, y, z};
        for (int d = 1; d <= 3; d++) {
            if (grid.standable(x, y - d, z)) return new int[]{x, y - d, z};
            if (grid.standable(x, y + d, z)) return new int[]{x, y + d, z};
        }

        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : around) {
            if (grid.standable(x + o[0], y, z + o[1])) return new int[]{x + o[0], y, z + o[1]};
        }
        return null;
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        double dx = x - gx, dz = z - gz;
        int dy = y - gy;

        double vertical = dy > 0 ? dy * 0.4 : -dy * 1.2;
        return Math.sqrt(dx * dx + dz * dz) + vertical;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (long) (z & 0x3FFFFFF);
    }

    static final class LongDoubleMap {
        private long[] keys;
        private double[] values;
        private boolean[] used;
        private int mask, size, threshold;

        LongDoubleMap(int capacity) {
            int cap = Integer.highestOneBit(Math.max(16, capacity - 1)) << 1;
            keys = new long[cap];
            values = new double[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
        }

        double get(long k) {
            int i = idx(k);
            while (used[i]) {
                if (keys[i] == k) return values[i];
                i = (i + 1) & mask;
            }
            return Double.POSITIVE_INFINITY;
        }

        void put(long k, double v) {
            int i = idx(k);
            while (used[i]) {
                if (keys[i] == k) { values[i] = v; return; }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = k;
            values[i] = v;
            if (++size > threshold) rehash();
        }

        private int idx(long k) {
            return (int) ((k * 0x9E3779B97F4A7C15L) >>> 40) & mask;
        }

        private void rehash() {
            long[] ok = keys;
            double[] ov = values;
            boolean[] ou = used;
            int cap = keys.length << 1;
            keys = new long[cap];
            values = new double[cap];
            used = new boolean[cap];
            mask = cap - 1;
            threshold = (int) (cap * 0.7f);
            size = 0;
            for (int j = 0; j < ok.length; j++) if (ou[j]) put(ok[j], ov[j]);
        }
    }
}
