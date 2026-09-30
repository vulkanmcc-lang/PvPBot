package com.pvpbot.schem;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Geometry for "everyone make a path there": a covered bridge from the
// speaker's feet to the block they're looking at.
//
//   roof   ###########   slabs, 5 wide, 3 above the deck
//   posts  |    ...    | every 4 blocks, holding the roof up
//   rails  |    ...    | walls on both edges (auto-connected)
//   deck   ===========   5 wide: a 3-wide walkway between the rails
//
// The deck follows the height difference at most one block per block
// travelled so it can always be walked. Blocks the world already has (a
// hillside, the ground at either end) are left alone - bots never dig for
// a path, they only fill in the gaps.
public final class PathPlan {
    public static final int MAX_LENGTH = 64;

    public static final Material DECK = Material.STONE_BRICKS;
    public static final Material RAIL = Material.STONE_BRICK_WALL;
    public static final Material ROOF = Material.STONE_BRICK_SLAB;

    private static final double WALK_HALF = 1.5;  // |dist| <= 1.5: walkway
    private static final double EDGE_HALF = 2.5;  // 1.5 < |dist| <= 2.5: rail edge
    private static final int POST_EVERY = 4;
    private static final int ROOF_HEIGHT = 3;

    private PathPlan() {
    }

    public static List<BuildJob.Placement> plan(Location from, Location to) {
        World w = from.getWorld();
        double sx = from.getBlockX() + 0.5, sz = from.getBlockZ() + 0.5;
        int y0 = from.getBlockY() - 1;
        double ex = to.getBlockX() + 0.5, ez = to.getBlockZ() + 0.5;
        int y1 = to.getBlockY();

        double dx = ex - sx, dz = ez - sz;
        double len = Math.sqrt(dx * dx + dz * dz);
        List<BuildJob.Placement> out = new ArrayList<>();
        if (len < 2.0) return out;
        dx /= len;
        dz /= len;
        len = Math.min(len, MAX_LENGTH);
        final double total = len;
        final int rise = y1 - y0;

        // Classify every cell near the segment by where it sits across and
        // along the path.
        Map<Long, int[]> deck = new HashMap<>();   // key -> {x, y, z, edge?1:0, postColumn?1:0}
        int minX = (int) Math.floor(Math.min(sx, sx + dx * len) - 4);
        int maxX = (int) Math.floor(Math.max(sx, sx + dx * len) + 4);
        int minZ = (int) Math.floor(Math.min(sz, sz + dz * len) - 4);
        int maxZ = (int) Math.floor(Math.max(sz, sz + dz * len) + 4);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double rx = x + 0.5 - sx, rz = z + 0.5 - sz;
                double t = rx * dx + rz * dz;             // along
                double across = Math.abs(rx * -dz + rz * dx);
                if (t < 1.0 || t > total + 0.5 || across > EDGE_HALF) continue;
                int y = y0 + deckRise(t, total, rise);
                boolean edge = across > WALK_HALF;
                boolean post = edge && ((int) Math.floor(t)) % POST_EVERY == 0;
                deck.put(key(x, z), new int[]{x, y, z, edge ? 1 : 0, post ? 1 : 0});
            }
        }

        // Which cells hold wall blocks (rail at deck+1, post also at deck+2).
        List<int[]> walls = new ArrayList<>();
        for (int[] c : deck.values()) {
            if (c[3] == 0) continue;
            walls.add(new int[]{c[0], c[1] + 1, c[2]});
            if (c[4] == 1) walls.add(new int[]{c[0], c[1] + 2, c[2]});
        }
        java.util.Set<Long> wallSet = new java.util.HashSet<>();
        for (int[] wl : walls) wallSet.add(key3(wl[0], wl[1], wl[2]));

        for (int[] c : deck.values()) {
            place(out, w, c[0], c[1], c[2], Bukkit.createBlockData(DECK));
        }
        for (int[] wl : walls) {
            place(out, w, wl[0], wl[1], wl[2], wallData(wl[0], wl[1], wl[2], wallSet));
        }
        Slab slab = (Slab) Bukkit.createBlockData(ROOF);
        slab.setType(Slab.Type.BOTTOM);
        for (int[] c : deck.values()) {
            place(out, w, c[0], c[1] + ROOF_HEIGHT, c[2], slab.clone());
        }
        return out;
    }

    // Height of the deck `t` blocks along: straight line to the target, but
    // never more than one block of climb per block travelled.
    static int deckRise(double t, double total, int rise) {
        int ideal = (int) Math.round(rise * Math.min(1.0, t / total));
        int cap = (int) Math.floor(t);
        return Math.max(-cap, Math.min(cap, ideal));
    }

    private static BlockData wallData(int x, int y, int z, java.util.Set<Long> walls) {
        Wall wall = (Wall) Bukkit.createBlockData(RAIL);
        int links = 0;
        boolean ns = false, ew = false;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            boolean joined = walls.contains(key3(x + f.getModX(), y, z + f.getModZ()));
            wall.setHeight(f, joined ? Wall.Height.LOW : Wall.Height.NONE);
            if (joined) {
                links++;
                if (f == BlockFace.NORTH || f == BlockFace.SOUTH) ns = true;
                else ew = true;
            }
        }
        boolean straight = links == 2 && (ns != ew);
        boolean postAbove = walls.contains(key3(x, y + 1, z));
        wall.setUp(!straight || postAbove);
        return wall;
    }

    private static void place(List<BuildJob.Placement> out, World w, int x, int y, int z, BlockData d) {
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 1) return;
        Material there = w.getBlockAt(x, y, z).getType();
        if (there.isSolid()) return; // world already has something here - leave it
        out.add(new BuildJob.Placement(x, y, z, d));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long key3(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
