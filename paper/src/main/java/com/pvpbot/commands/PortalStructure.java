package com.pvpbot.commands;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import java.util.ArrayList;
import java.util.List;

public final class PortalStructure {
    private static final int INNER_W = 2;
    private static final int INNER_H = 3;

    private final World world;

    private final int bx, by, bz;

    private final int ux, uz;
    private final Axis axis;

    private final List<Block> touched = new ArrayList<>();
    private final List<BlockData> previous = new ArrayList<>();
    private boolean built = false;

    private PortalStructure(World world, int bx, int by, int bz, int ux, int uz, Axis axis) {
        this.world = world;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.ux = ux;
        this.uz = uz;
        this.axis = axis;
    }

    static PortalStructure at(Location ground, double toCentreX, double toCentreZ, int height) {
        World world = ground.getWorld();
        if (world == null) return null;

        boolean spanZ = Math.abs(toCentreX) > Math.abs(toCentreZ);
        int ux = spanZ ? 0 : 1;
        int uz = spanZ ? 1 : 0;
        Axis axis = spanZ ? Axis.Z : Axis.X;

        int bx = ground.getBlockX();
        int bz = ground.getBlockZ();

        int by = ground.getBlockY() + height + 1;

        return new PortalStructure(world, bx, by, bz, ux, uz, axis);
    }

    boolean build() {
        if (built) return true;

        for (int i = -1; i <= INNER_W; i++) {
            for (int j = -1; j <= INNER_H; j++) {
                if (!replaceable(blockAt(i, j))) return false;
            }
        }

        BlockData portalData = Material.NETHER_PORTAL.createBlockData();
        if (portalData instanceof Orientable o) {
            o.setAxis(axis);
            portalData = o;
        }
        BlockData frameData = Material.OBSIDIAN.createBlockData();

        for (int i = -1; i <= INNER_W; i++) {
            for (int j = -1; j <= INNER_H; j++) {
                if (isInterior(i, j)) continue;
                set(blockAt(i, j), frameData);
            }
        }
        for (int i = 0; i < INNER_W; i++) {
            for (int j = 0; j < INNER_H; j++) {
                set(blockAt(i, j), portalData);
            }
        }

        built = true;
        return true;
    }

    void restore() {
        for (int i = touched.size() - 1; i >= 0; i--) {
            try {
                touched.get(i).setBlockData(previous.get(i), false);
            } catch (Throwable ignored) {
            }
        }
        touched.clear();
        previous.clear();
        built = false;
    }

    Location emergePoint() {
        double cx = bx + (ux * (INNER_W - 1)) / 2.0 + 0.5;
        double cz = bz + (uz * (INNER_W - 1)) / 2.0 + 0.5;
        return new Location(world, cx, by, cz);
    }

    public World world() {
        return world;
    }

    public boolean nearMouth(Location l, double slack) {
        if (l.getWorld() != world) return false;
        Location c = centre();
        double dx = l.getX() - c.getX();
        double dy = l.getY() - c.getY();
        double dz = l.getZ() - c.getZ();
        double reach = slack + INNER_H;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    public Location centre() {
        Location l = emergePoint();
        l.setY(l.getY() + INNER_H / 2.0);
        return l;
    }

    private Block blockAt(int i, int j) {
        return world.getBlockAt(bx + ux * i, by + j, bz + uz * i);
    }

    private static boolean isInterior(int i, int j) {
        return i >= 0 && i < INNER_W && j >= 0 && j < INNER_H;
    }

    private void set(Block b, BlockData data) {
        touched.add(b);
        previous.add(b.getBlockData());

        b.setBlockData(data, false);
    }

    private static boolean replaceable(Block b) {
        Material m = b.getType();
        if (m.isAir()) return true;

        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                || m == Material.FERN || m == Material.LARGE_FERN
                || m == Material.SNOW || m == Material.VINE
                || m == Material.DEAD_BUSH || m == Material.SEAGRASS
                || m == Material.WATER;
    }
}
