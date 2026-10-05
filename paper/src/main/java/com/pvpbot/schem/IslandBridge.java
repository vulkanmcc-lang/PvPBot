package com.pvpbot.schem;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// "Everyone bridge to the next island" (the End): find the island the
// speaker stands on, the nearest other island, and a 3-wide bridge between
// the closest edges of the two.
//
//  1. Land = a column whose top block is solid (End islands float over the
//     void, so a column is either land or nothing).
//  2. Flood-fill the speaker's island over land columns.
//  3. Scan outward ring by ring for the first land column that isn't part
//     of it and belongs to a real island (not a lone block / chorus stalk).
//  4. Bridge from the speaker's island's column nearest that island to the
//     island's column nearest back - straight, 3 wide, rising or falling at
//     most one block per block so it can be walked.
//
// Only loaded chunks are looked at: the bots bridge to what's around, not
// to something they'd have to generate the world to find.
public final class IslandBridge {
    public static final int SEARCH_RADIUS = 140;
    public static final int MAX_LENGTH = 160;
    private static final int MAX_ISLAND_COLUMNS = 80000;
    private static final int MIN_ISLAND_COLUMNS = 8;

    // center: the walkway's middle line, one cell per step, every step a
    // single cardinal move from the last (so a bot can always step onto the
    // next block); index 0 is on the home island. sides: {x, y, z, index}
    // - the two outer lanes beside center cell `index`.
    public record Plan(List<int[]> center, List<int[]> sides, Location from, Location to, int length) {
        public int blocksToPlace(World w) {
            int n = 0;
            for (int[] c : center) if (!w.getBlockAt(c[0], c[1], c[2]).getType().isSolid()) n++;
            for (int[] c : sides) if (!w.getBlockAt(c[0], c[1], c[2]).getType().isSolid()) n++;
            return n;
        }
    }

    private IslandBridge() {
    }

    public static Plan plan(Location at) {
        World w = at.getWorld();
        if (w == null) return null;
        int sx = at.getBlockX(), sz = at.getBlockZ();

        int[] home = nearestLand(w, sx, sz, 4);
        if (home == null) return null;

        // 2. The island we're on.
        Set<Long> island = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        island.add(key(home[0], home[1]));
        queue.add(home);
        int r2 = (SEARCH_RADIUS + 16) * (SEARCH_RADIUS + 16);
        while (!queue.isEmpty() && island.size() < MAX_ISLAND_COLUMNS) {
            int[] c = queue.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + d[0], z = c[1] + d[1];
                if ((x - sx) * (x - sx) + (z - sz) * (z - sz) > r2) continue;
                long k = key(x, z);
                if (island.contains(k) || topY(w, x, z) == Integer.MIN_VALUE) continue;
                island.add(k);
                queue.add(new int[]{x, z});
            }
        }

        // 3. Nearest land that isn't ours (and is a real island).
        Set<Long> rejected = new HashSet<>();
        int[] other = null;
        outer:
        for (int r = 2; r <= SEARCH_RADIUS; r++) {
            for (int[] off : ring(r)) {
                {
                    int x = sx + off[0], z = sz + off[1];
                    long k = key(x, z);
                    if (island.contains(k) || rejected.contains(k)) continue;
                    if (topY(w, x, z) == Integer.MIN_VALUE) continue;
                    Set<Long> piece = smallComponent(w, x, z, island);
                    if (piece == null) {
                        // Touches our island after all (the fill hit its radius
                        // cap) - not another island.
                        rejected.add(k);
                        continue;
                    }
                    if (piece.size() < MIN_ISLAND_COLUMNS) {
                        rejected.addAll(piece);
                        continue;
                    }
                    other = new int[]{x, z};
                    break outer;
                }
            }
        }
        if (other == null) return null;

        // 4. Closest edge of ours to it, then closest edge of theirs back.
        int[] from = nearestIn(island, other[0], other[1]);
        int[] to = nearestLandOfIsland(w, other[0], other[1], from[0], from[1], island);
        int fy = topY(w, from[0], from[1]);
        int ty = topY(w, to[0], to[1]);
        double len = Math.hypot(to[0] - from[0], to[1] - from[1]);
        if (len < 2.0 || len > MAX_LENGTH) return null;

        // Centre line: a 4-connected walk that stays as close as possible to
        // the straight line.
        int tx = to[0], tz = to[1];
        int totX = Math.abs(tx - from[0]), totZ = Math.abs(tz - from[1]);
        List<int[]> cols = new ArrayList<>();
        int x = from[0], z = from[1];
        cols.add(new int[]{x, z});
        while (x != tx || z != tz) {
            int remX = Math.abs(tx - x), remZ = Math.abs(tz - z);
            // Step along whichever axis is further behind its share.
            if (remZ == 0 || (remX > 0 && (long) remX * Math.max(1, totZ) >= (long) remZ * Math.max(1, totX))) {
                x += Integer.signum(tx - x);
            } else {
                z += Integer.signum(tz - z);
            }
            cols.add(new int[]{x, z});
        }
        int steps = cols.size() - 1;
        List<int[]> center = new ArrayList<>();
        java.util.Set<Long> centerKeys = new HashSet<>();
        for (int i = 0; i < cols.size(); i++) {
            int y = fy + PathPlan.deckRise(i, Math.max(1, steps), ty - fy);
            center.add(new int[]{cols.get(i)[0], y, cols.get(i)[1]});
            centerKeys.add(key3(cols.get(i)[0], y, cols.get(i)[1]));
        }
        // Side lanes across the main direction.
        boolean alongX = totX >= totZ;
        int ox = alongX ? 0 : 1, oz = alongX ? 1 : 0;
        List<int[]> sides = new ArrayList<>();
        java.util.Set<Long> sideKeys = new HashSet<>();
        for (int i = 0; i < center.size(); i++) {
            int[] c = center.get(i);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int sx2 = c[0] + ox * sgn, sz2 = c[2] + oz * sgn;
                long k = key3(sx2, c[1], sz2);
                if (centerKeys.contains(k) || !sideKeys.add(k)) continue;
                if (c[1] <= w.getMinHeight() || c[1] >= w.getMaxHeight() - 1) continue;
                sides.add(new int[]{sx2, c[1], sz2, i});
            }
        }
        return new Plan(center, sides, new Location(w, from[0] + 0.5, fy + 1, from[1] + 0.5),
                new Location(w, to[0] + 0.5, ty + 1, to[1] + 0.5), (int) Math.round(len));
    }

    private static long key3(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    // The square ring at Chebyshev distance r (its perimeter only).
    private static List<int[]> ring(int r) {
        List<int[]> out = new ArrayList<>(8 * r);
        for (int d = -r; d <= r; d++) {
            out.add(new int[]{d, -r});
            out.add(new int[]{d, r});
        }
        for (int d = -r + 1; d <= r - 1; d++) {
            out.add(new int[]{-r, d});
            out.add(new int[]{r, d});
        }
        return out;
    }

    // Top solid block of a column, or MIN_VALUE for void / unloaded.
    static int topY(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return Integer.MIN_VALUE;
        int y = w.getHighestBlockYAt(x, z);
        if (y <= w.getMinHeight()) return Integer.MIN_VALUE;
        Material m = w.getBlockAt(x, y, z).getType();
        return m.isSolid() ? y : Integer.MIN_VALUE;
    }

    private static int[] nearestLand(World w, int x, int z, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    if (topY(w, x + dx, z + dz) != Integer.MIN_VALUE) return new int[]{x + dx, z + dz};
                }
            }
        }
        return null;
    }

    // The piece of land around (x, z), up to a little over the minimum size
    // (enough to tell an island from a speck) - or null if it runs into the
    // home island.
    private static Set<Long> smallComponent(World w, int x, int z, Set<Long> home) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen.add(key(x, z));
        q.add(new int[]{x, z});
        while (!q.isEmpty() && seen.size() < MIN_ISLAND_COLUMNS * 4) {
            int[] c = q.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                long k = key(nx, nz);
                if (seen.contains(k)) continue;
                if (topY(w, nx, nz) == Integer.MIN_VALUE) continue;
                if (home.contains(k)) return null;
                seen.add(k);
                q.add(new int[]{nx, nz});
            }
        }
        return seen;
    }

    private static int[] nearestIn(Set<Long> cols, int x, int z) {
        long best = 0;
        double bestD = Double.MAX_VALUE;
        for (long k : cols) {
            int cx = (int) (k >> 32), cz = (int) k;
            double d = (double) (cx - x) * (cx - x) + (double) (cz - z) * (cz - z);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return new int[]{(int) (best >> 32), (int) best};
    }

    // The other island's column closest to (fx, fz): walk its land from the
    // column we found, a bounded number of steps.
    private static int[] nearestLandOfIsland(World w, int x, int z, int fx, int fz, Set<Long> home) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        seen.add(key(x, z));
        q.add(new int[]{x, z});
        int[] best = {x, z};
        double bestD = Math.hypot(x - fx, z - fz);
        while (!q.isEmpty() && seen.size() < 6000) {
            int[] c = q.poll();
            double d = Math.hypot(c[0] - fx, c[1] - fz);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
            for (int[] dd : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = c[0] + dd[0], nz = c[1] + dd[1];
                long k = key(nx, nz);
                if (seen.contains(k) || home.contains(k)) continue;
                if (topY(w, nx, nz) == Integer.MIN_VALUE) continue;
                seen.add(k);
                q.add(new int[]{nx, nz});
            }
        }
        return best;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
