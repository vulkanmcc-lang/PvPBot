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
    public enum Mode { MINE, DESTROY, TUNNEL }

    public static final class Cell {
        public final int x, y, z;
        UUID owner;
        int leaseTicks;
        int failures;
        boolean done;
        // Crew members without the right tool for it (stone with no pickaxe).
        java.util.Set<UUID> cannot;
        // TUNNEL only: which bot's tunnel this cell belongs to, and its order
        // along it (dug front to back).
        int lane = -1;
        int seq;

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
    private final java.util.Set<UUID> crew = new java.util.HashSet<>();

    private int topY;
    private int lastTick = Integer.MIN_VALUE;
    private int broken = 0;
    private int blasted = 0;
    private boolean cancelled = false;

    private ExcavationJob(Mode mode, World world, UUID requester, long durationMs,
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
        this.deadline = System.currentTimeMillis() + durationMs;
    }

    // Starts (replacing any previous job by the same speaker) a job for the
    // area centred on `center`.
    public static ExcavationJob start(Mode mode, UUID requester, Location center) {
        return mode == Mode.MINE
                ? start(mode, requester, center, 5, 2, 4, MAX_DURATION_MS)
                : start(mode, requester, center, 7, 6, 2, MAX_DURATION_MS);
    }

    // radius: half-width of the square; up/down: layers above/below center.
    public static ExcavationJob start(Mode mode, UUID requester, Location center,
                                      int radius, int up, int down, long durationMs) {
        World w = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        int r = Math.max(1, radius);
        int minY = Math.max(w.getMinHeight() + 1, cy - down);
        int maxY = Math.min(w.getMaxHeight() - 1, cy + up);

        ExcavationJob job = new ExcavationJob(mode, w, requester, durationMs,
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

    // ---------------------------------------------------------------------
    // Tunnels: one 1x2 tunnel per bot the way the speaker faces, spread out
    // sideways (0, +3, -3, +6, -6 ...) and at different depths (level, a
    // few blocks up, a few blocks down - reached by a gentle staircase so
    // the bot can walk it).
    // ---------------------------------------------------------------------

    private final Map<UUID, Integer> laneOf = new HashMap<>();

    public static ExcavationJob startTunnel(UUID requester, Location origin, int dirX, int dirZ,
                                            List<UUID> crew, int length) {
        World w = origin.getWorld();
        int sx = origin.getBlockX(), sy = origin.getBlockY(), sz = origin.getBlockZ();
        int rx = -dirZ, rz = dirX; // right-hand side of the direction

        List<Cell> made = new ArrayList<>();
        for (int lane = 0; lane < crew.size(); lane++) {
            int lat = lane == 0 ? 0 : ((lane + 1) / 2) * 3 * (lane % 2 == 1 ? 1 : -1);
            int vTarget = switch (lane % 3) {
                case 1 -> 3;
                case 2 -> -3;
                default -> 0;
            };
            int prevY = sy;
            int prevX = sx + rx * lat, prevZ = sz + rz * lat;
            int seq = 0;
            for (int k = 1; k <= length; k++) {
                int rise = Integer.signum(vTarget) * Math.min(Math.abs(vTarget), k / 2);
                int x = sx + dirX * k + rx * lat;
                int z = sz + dirZ * k + rz * lat;
                int y = sy + rise;
                if (y > prevY) {
                    // Stepping up: clear head room above the step we're on.
                    made.add(laneCell(prevX, prevY + 2, prevZ, lane, seq++));
                }
                made.add(laneCell(x, y + 1, z, lane, seq++));
                made.add(laneCell(x, y, z, lane, seq++));
                if (y < prevY) {
                    // Stepping down: the old floor ahead goes too.
                    made.add(laneCell(x, y + 2, z, lane, seq++));
                }
                prevX = x;
                prevY = y;
                prevZ = z;
            }
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : made) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        ExcavationJob job = new ExcavationJob(Mode.TUNNEL, w, requester, MAX_DURATION_MS,
                minX, minY, minZ, maxX, maxY, maxZ);
        for (Cell c : made) {
            if (c.y <= w.getMinHeight() || c.y >= w.getMaxHeight()) continue;
            job.cells.add(c);
            job.byPos.putIfAbsent(key(c.x, c.y, c.z), c);
        }
        for (int i = 0; i < crew.size(); i++) job.laneOf.put(crew.get(i), i);
        job.topY = maxY;

        ExcavationJob old = BY_REQUESTER.put(requester, job);
        if (old != null) old.cancel();
        return job;
    }

    private static Cell laneCell(int x, int y, int z, int lane, int seq) {
        Cell c = new Cell(x, y, z);
        c.lane = lane;
        c.seq = seq;
        return c;
    }

    private Cell claimInLane(UUID bot, Player botPlayer, java.util.function.Predicate<Block> canBreak) {
        Integer lane = laneOf.get(bot);
        if (lane == null) {
            lane = laneOf.size();
            laneOf.put(bot, lane);
        }
        Cell best = null;
        for (Cell c : cells) {
            if (c.lane != lane || c.done || c.owner != null) continue;
            Block b = world.getBlockAt(c.x, c.y, c.z);
            if (!breakable(b.getType())) {
                c.done = true; // already open (cave, air) - walk through
                continue;
            }
            if (!canBreak.test(b)) {
                c.done = true; // e.g. stone without a pickaxe: this lane stops being dug here
                continue;
            }
            if (best == null || c.seq < best.seq) best = c;
        }
        if (best != null) {
            best.owner = bot;
            best.leaseTicks = LEASE_TICKS;
        }
        return best;
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
        if (mode == Mode.TUNNEL) return claimInLane(bot, botPlayer, canBreak);
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
            if (!canBreak.test(b)) {
                // Nobody on the crew has the tool for it: skip it rather than
                // let the whole job wait on it forever.
                if (c.cannot == null) c.cannot = new java.util.HashSet<>();
                c.cannot.add(bot);
                if (c.cannot.size() >= Math.max(1, crew.size())) c.done = true;
                continue;
            }
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

    public void addCrew(UUID bot) {
        crew.add(bot);
    }

    public void removeCrew(UUID bot) {
        crew.remove(bot);
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
