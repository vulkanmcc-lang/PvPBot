package com.pvpbot.mine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// A box of blocks a group of bots clears together ("everyone mine the area",
// "everyone destroy the area").
//
// Blocks are handed out top layer first (a bot only ever claims blocks in
// the two highest layers still standing), so the crew digs down evenly
// instead of undercutting itself or leaving floating chunks. Each block is
// leased to one bot at a time; a bot that can't reach or break one gives
// it back and it's skipped after a few failed tries so the job always ends.
//
// DESTROY adds blast points on the surface every few blocks: bots carrying
// TNT and flint & steel blow those first, then everyone cleans up with
// tools.
public final class ExcavationJob {
    public enum Mode { MINE, DESTROY }

    public static final class Cell {
        public final int x, y, z;
        UUID owner;
        int leaseTicks;
        int failures;
        boolean done;

        Cell(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class BlastPoint {
        public final int x, y, z; // the air cell the TNT goes into
        UUID owner;
        int leaseTicks;
        boolean done;

        BlastPoint(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final Map<UUID, ExcavationJob> BY_REQUESTER = new HashMap<>();

    private static final int LEASE_TICKS = 600;
    private static final int MAX_CELL_FAILURES = 3;
    private static final long MAX_DURATION_MS = 20L * 60L * 1000L;
    private static final int BLAST_SPACING = 5;

    public final Mode mode;
    public final World world;
    public final UUID requester;
    public final int minX, minY, minZ, maxX, maxY, maxZ;

    private final List<Cell> cells = new ArrayList<>();
    private final Map<Long, Cell> byPos = new HashMap<>();
    private final List<BlastPoint> blasts = new ArrayList<>();
    private final long deadline;

    private int topY;
    private int lastTick = Integer.MIN_VALUE;
    private int broken = 0;
    private int blasted = 0;
    private boolean cancelled = false;

    private ExcavationJob(Mode mode, World world, UUID requester,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.mode = mode;
        this.world = world;
        this.requester = requester;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.deadline = System.currentTimeMillis() + MAX_DURATION_MS;
    }

    // Starts (replacing any previous job by the same speaker) a job for the
    // area centred on `center`.
    public static ExcavationJob start(Mode mode, UUID requester, Location center) {
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int r = mode == Mode.MINE ? 5 : 7;
        int up = mode == Mode.MINE ? 2 : 10;
        int down = mode == Mode.MINE ? 4 : 2;
        int minY = Math.max(w.getMinHeight() + 1, cy - down);
        int maxY = Math.min(w.getMaxHeight() - 1, cy + up);

        ExcavationJob job = new ExcavationJob(mode, w, requester,
                cx - r, minY, cz - r, cx + r, maxY, cz + r);
        job.scan(cx, cz);

        ExcavationJob old = BY_REQUESTER.put(requester, job);
        if (old != null) old.cancel();
        return job;
    }

    public static ExcavationJob of(UUID requester) {
        ExcavationJob j = BY_REQUESTER.get(requester);
        return j == null || j.isFinished() ? null : j;
    }

    public static void stop(UUID requester) {
        ExcavationJob j = BY_REQUESTER.remove(requester);
        if (j != null) j.cancel();
    }

    public static void stopAll() {
        for (ExcavationJob j : BY_REQUESTER.values()) j.cancel();
        BY_REQUESTER.clear();
    }

    private void scan(int cx, int cz) {
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (breakable(world.getBlockAt(x, y, z).getType())) {
                        Cell c = new Cell(x, y, z);
                        cells.add(c);
                        byPos.put(key(x, y, z), c);
                    }
                }
            }
        }
        cells.sort(Comparator.comparingInt((Cell c) -> -c.y)
                .thenComparingInt(c -> (c.x - cx) * (c.x - cx) + (c.z - cz) * (c.z - cz)));
        topY = cells.isEmpty() ? minY : cells.get(0).y;

        if (mode == Mode.DESTROY) {
            for (int x = minX + 2; x <= maxX - 1; x += BLAST_SPACING) {
                for (int z = minZ + 2; z <= maxZ - 1; z += BLAST_SPACING) {
                    for (int y = maxY; y >= minY; y--) {
                        Material below = world.getBlockAt(x, y - 1, z).getType();
                        Material at = world.getBlockAt(x, y, z).getType();
                        if (below.isSolid() && (at.isAir() || !at.isSolid()) && at != Material.WATER
                                && at != Material.LAVA) {
                            blasts.add(new BlastPoint(x, y, z));
                            break;
                        }
                    }
                }
            }
        }
    }

    // Blocks worth clearing: anything solid-ish that can actually be broken.
    public static boolean breakable(Material m) {
        if (m.isAir() || m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return false;
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.LIGHT
                || m == Material.END_PORTAL_FRAME || m == Material.REINFORCED_DEEPSLATE) return false;
        String n = m.name();
        if (n.contains("PORTAL") || n.contains("GATEWAY") || n.contains("COMMAND_BLOCK")
                || n.startsWith("STRUCTURE_") || n.equals("JIGSAW") || n.contains("SPAWNER")) return false;
        try {
            if (m.getHardness() < 0) return false;
        } catch (Throwable ignored) {
        }
        return m.isBlock();
    }

    // Called by every crew member each tick; does the bookkeeping once.
    public void tick() {
        int now = Bukkit.getCurrentTick();
        if (now == lastTick) return;
        int elapsed = lastTick == Integer.MIN_VALUE ? 1 : Math.max(1, now - lastTick);
        lastTick = now;

        int newTop = Integer.MIN_VALUE;
        for (Cell c : cells) {
            if (c.done) continue;
            if (c.owner != null && (c.leaseTicks -= elapsed) <= 0) c.owner = null;
            if ((now & 15) == 0 && !breakable(world.getBlockAt(c.x, c.y, c.z).getType())) {
                // Cleared by someone/something else (TNT, a player, gravity).
                c.done = true;
                continue;
            }
            if (c.y > newTop) newTop = c.y;
        }
        topY = newTop == Integer.MIN_VALUE ? minY : newTop;

        for (BlastPoint b : blasts) {
            if (!b.done && b.owner != null && (b.leaseTicks -= elapsed) <= 0) b.owner = null;
        }
    }

    public Cell claim(UUID bot, Player botPlayer, java.util.function.Predicate<Block> canBreak) {
        if (isFinished()) return null;
        Location at = botPlayer.getLocation();
        Cell best = null;
        double bestD = Double.MAX_VALUE;
        for (Cell c : cells) {
            if (c.done || c.owner != null) continue;
            if (c.y < topY - 1) break; // sorted top-down: nothing claimable below
            Block b = world.getBlockAt(c.x, c.y, c.z);
            if (!breakable(b.getType())) {
                c.done = true;
                continue;
            }
            if (underSomeone(c, botPlayer)) continue;
            if (!canBreak.test(b)) continue;
            double dx = c.x + 0.5 - at.getX(), dy = c.y + 0.5 - at.getY(), dz = c.z + 0.5 - at.getZ();
            double d = dx * dx + dy * dy * 2.0 + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best != null) {
            best.owner = bot;
            best.leaseTicks = LEASE_TICKS;
        }
        return best;
    }

    // Never dig out the floor under a player (bots are fine - they cope).
    private boolean underSomeone(Cell c, Player self) {
        for (Player p : world.getPlayers()) {
            if (p == self || p.isDead()) continue;
            if (com.pvpbot.PvPBotPlugin.getInstance().getBotManager().getBots().containsKey(p.getUniqueId())) {
                continue;
            }
            Location l = p.getLocation();
            if (Math.abs(l.getX() - (c.x + 0.5)) < 1.4 && Math.abs(l.getZ() - (c.z + 0.5)) < 1.4
                    && c.y <= l.getY() && c.y >= l.getY() - 3) {
                return true;
            }
        }
        return false;
    }

    public BlastPoint claimBlast(UUID bot, Location at) {
        if (isFinished()) return null;
        BlastPoint best = null;
        double bestD = Double.MAX_VALUE;
        for (BlastPoint b : blasts) {
            if (b.done || b.owner != null) continue;
            double d = at.distanceSquared(new Location(world, b.x + 0.5, b.y, b.z + 0.5));
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        if (best != null) {
            best.owner = bot;
            best.leaseTicks = LEASE_TICKS;
        }
        return best;
    }

    public boolean hasBlastsLeft() {
        for (BlastPoint b : blasts) if (!b.done) return true;
        return false;
    }

    public void completeBlast(BlastPoint b, boolean exploded) {
        if (b == null || b.done) return;
        b.done = true;
        b.owner = null;
        if (exploded) blasted++;
    }

    public void release(BlastPoint b) {
        if (b != null && !b.done) b.owner = null;
    }

    public void complete(Cell c) {
        if (c == null || c.done) return;
        c.done = true;
        c.owner = null;
        broken++;
    }

    // Something in the way got broken as part of reaching another block.
    public void noteBroken(int x, int y, int z) {
        Cell c = byPos.get(key(x, y, z));
        if (c != null && !c.done) {
            c.done = true;
            c.owner = null;
        }
        broken++;
    }

    public void fail(Cell c) {
        if (c == null || c.done) return;
        c.owner = null;
        if (++c.failures >= MAX_CELL_FAILURES) c.done = true; // give up on it
    }

    public void release(Cell c) {
        if (c != null && !c.done) c.owner = null;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean isFinished() {
        if (cancelled || System.currentTimeMillis() > deadline) return true;
        for (Cell c : cells) if (!c.done) return false;
        return !hasBlastsLeft();
    }

    public void cancel() {
        cancelled = true;
        BY_REQUESTER.remove(requester, this);
    }

    public int total() {
        return cells.size();
    }

    public int broken() {
        return broken;
    }

    public int blasted() {
        return blasted;
    }

    public int blastPoints() {
        return blasts.size();
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
}
