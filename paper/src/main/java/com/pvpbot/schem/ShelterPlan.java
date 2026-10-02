package com.pvpbot.schem;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Geometry for "everyone take cover": a flat end stone roof over the group,
// held up by a pillar in each corner.
//
//   roof    #########    3 clear blocks over the highest ground under it
//   pillar  |       |    one per corner, ground to roof
//   spots    . . . .     where everyone stands, nearest the middle first
//
// Big enough for the whole group (about 2x2 per head, 5x5 at least, 13x13
// at most). Columns that already have something solid overhead (a cave
// roof, a tree, a ceiling) are left alone - they're already cover.
public final class ShelterPlan {
    public static final Material BLOCK = Material.END_STONE;

    public static final int HEADROOM = 3;
    private static final int MIN_SIDE = 5;
    private static final int MAX_HALF = 6;
    // How far above the ground something solid still counts as cover.
    private static final int COVER_SCAN = 24;
    private static final int NO_GROUND = Integer.MIN_VALUE;

    private ShelterPlan() {
    }

    public record Plan(List<BuildJob.Placement> blocks, List<Location> spots,
                       int half, int roofY, boolean alreadyCovered) {
    }

    public static Plan plan(Location center, int people) {
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();

        int side = Math.max(MIN_SIDE, (int) Math.ceil(Math.sqrt(people * 4.0)) + 2);
        if (side % 2 == 0) side++;
        int half = Math.min(MAX_HALF, side / 2);
        int n = half * 2 + 1;

        int[][] ground = new int[n][n];
        int maxGround = NO_GROUND;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int g = groundY(w, cx - half + i, cz - half + j, cy);
                ground[i][j] = g;
                if (g != NO_GROUND) maxGround = Math.max(maxGround, g);
            }
        }
        if (maxGround == NO_GROUND) maxGround = cy - 1;
        int roofY = maxGround + HEADROOM + 1;

        List<BuildJob.Placement> blocks = new ArrayList<>();
        BlockData data = BLOCK.createBlockData();
        boolean anyOpen = false;

        // Roof: every column without cover already.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int x = cx - half + i, z = cz - half + j;
                int g = ground[i][j] == NO_GROUND ? maxGround : ground[i][j];
                if (coveredAbove(w, x, g, z)) continue;
                anyOpen = true;
                if (!solid(w.getBlockAt(x, roofY, z))) blocks.add(new BuildJob.Placement(x, roofY, z, data));
            }
        }

        if (anyOpen) {
            // Corner pillars, ground up to just under the roof, so the roof
            // grows out from something instead of hanging in the air.
            int[][] corners = {{0, 0}, {0, n - 1}, {n - 1, 0}, {n - 1, n - 1}};
            for (int[] c : corners) {
                int x = cx - half + c[0], z = cz - half + c[1];
                int g = ground[c[0]][c[1]];
                if (g == NO_GROUND) continue;
                for (int y = g + 1; y < roofY; y++) {
                    if (!solid(w.getBlockAt(x, y, z))) blocks.add(new BuildJob.Placement(x, y, z, data));
                }
            }
        }

        // Standing spots: every column with ground and room to stand,
        // middle first; never a pillar corner.
        List<int[]> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean corner = (i == 0 || i == n - 1) && (j == 0 || j == n - 1);
                if (corner || ground[i][j] == NO_GROUND) continue;
                cells.add(new int[]{i, j});
            }
        }
        cells.sort(Comparator.comparingInt(c -> (c[0] - half) * (c[0] - half) + (c[1] - half) * (c[1] - half)));
        List<Location> spots = new ArrayList<>();
        for (int[] c : cells) {
            int x = cx - half + c[0], z = cz - half + c[1];
            Location spot = new Location(w, x + 0.5, ground[c[0]][c[1]] + 1, z + 0.5);
            // Face out of the shelter.
            spot.setYaw((float) Math.toDegrees(Math.atan2(-(c[0] - half), c[1] - half)));
            spots.add(spot);
        }

        return new Plan(blocks, spots, half, roofY, !anyOpen);
    }

    // Top of the ground in this column near `nearY`: a solid block with
    // two passable blocks over it.
    private static int groundY(World w, int x, int z, int nearY) {
        for (int y = nearY + 3; y >= nearY - 8; y--) {
            if (solid(w.getBlockAt(x, y, z))
                    && !solid(w.getBlockAt(x, y + 1, z))
                    && !solid(w.getBlockAt(x, y + 2, z))) {
                return y;
            }
        }
        return NO_GROUND;
    }

    private static boolean coveredAbove(World w, int x, int groundY, int z) {
        for (int y = groundY + 3; y <= groundY + COVER_SCAN; y++) {
            if (solid(w.getBlockAt(x, y, z))) return true;
        }
        return false;
    }

    private static boolean solid(Block b) {
        return b.getType().isSolid() && !b.isPassable();
    }
}
