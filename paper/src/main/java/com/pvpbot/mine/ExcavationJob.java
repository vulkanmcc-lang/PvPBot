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

// A set of blocks a group of bots clears together ("everyone mine the area",
// "everyone destroy the area", "everyone tunnel this way", "everyone mine
// down to Steve").
//
// Area jobs (MINE / DESTROY) hand blocks out top layer first (a bot only
// ever claims blocks in the two highest layers still standing), so the crew
// digs down evenly instead of undercutting itself or leaving floating
// chunks. Lane jobs (TUNNEL / DIG_TO) give every bot its own ordered list of
// blocks - its own tunnel or shaft - dug front to back.
//
// Each block is leased to one bot at a time; a bot that can't reach or
// break one gives it back and it's skipped after a few failed tries so the
// job always ends.
//
// DESTROY adds blast points on the surface every few blocks: bots carrying
// TNT and flint & steel blow those first, then everyone cleans up with
// tools.
//
// A speaker can run several jobs at once (Andy mines here, the red team
// mines over there): a job lives while it has a crew and ends by itself
// when its last bot leaves for another order.
//
// Performance: everything a bot asks for every tick is O(1) or close -
// "finished?" is a counter, lanes are pre-sorted with a cursor, area claims
// only look at the top two layers and do the block lookups for the nearest
// candidates only, and the "did something else clear it" sweep is spread
// over 16 ticks.
public final class ExcavationJob {
    public enum Mode { MINE, DESTROY, TUNNEL, DIG_TO }

    public static final class Cell {
        public final int x, y, z;
        UUID owner;
        int leaseTicks;
        int failures;
        boolean done;
        // Crew members without the right tool for it (stone with no pickaxe).
        java.util.Set<UUID> cannot;
        // Lane jobs only: whose tunnel/shaft this cell belongs to, and its
        // order along it (dug front to back).
        int lane = -1;
        int seq;
        // Not before this server tick (a cell that can't be reached yet -
        // e.g. under water until the crew has dug a dry way to it).
        int retryAt;

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

    private static final Map<UUID, List<ExcavationJob>> BY_REQUESTER = new HashMap<>();

    private static final int LEASE_TICKS = 600;
    private static final int MAX_CELL_FAILURES = 3;
    private static final int MAX_CELL_DEFERS = 8;
    private static final long MAX_DURATION_MS = 20L * 60L * 1000L;
    private static final int BLAST_SPACING = 5;
    private static final int SWEEP_SLICES = 16;
    // Area claims: how many of the nearest candidates get the (block-lookup)
    // checks before giving up for this call.
    private static final int CLAIM_CHECKS = 24;

    public final Mode mode;
    public final World world;
    public final UUID requester;
    // Bounds grow when a dig-to lane is re-planned toward a moving target.
    public int minX, minY, minZ, maxX, maxY, maxZ;

    // DIG_TO: who we're digging to (followed when they move), and whether
    // this is a bot's own chase dig (combat) rather than an order.
    public UUID digTarget;
    public boolean chase;

    private final List<Cell> cells = new ArrayList<>();
    private final Map<Long, Cell> byPos = new HashMap<>();
    private final List<BlastPoint> blasts = new ArrayList<>();
    private final long deadline;
    private final java.util.Set<UUID> crew = new java.util.HashSet<>();
    // Blocks bots placed to hold water back (dive shaft caps, plugs over a
    // cell that had water next to it). Never dug out again, or the water
    // they hold back floods the dig.
    private final java.util.Set<Long> sealed = new java.util.HashSet<>();

    private int remaining = 0;     // cells not done
    private int firstLive = 0;     // area jobs: index of the highest cell not done
    private int topY;
    private int lastTick = Integer.MIN_VALUE;
    private int broken = 0;
    private int blasted = 0;
    private boolean cancelled = false;
    private boolean hadCrew = false;

    // Lane jobs
    private final Map<UUID, Integer> laneOf = new HashMap<>();
    private final Map<Integer, List<Cell>> laneCells = new HashMap<>();
    private final Map<Integer, Integer> laneCursor = new HashMap<>();

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

    // ---------------------------------------------------------------------
    // Registry
    // ---------------------------------------------------------------------

    private static void register(ExcavationJob job) {
        List<ExcavationJob> list = BY_REQUESTER.computeIfAbsent(job.requester, k -> new ArrayList<>());
        list.removeIf(ExcavationJob::isFinished);
        list.add(job);
    }

    // The speaker's most recent job still running.
    public static ExcavationJob of(UUID requester) {
        List<ExcavationJob> list = BY_REQUESTER.get(requester);
        if (list == null) return null;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!list.get(i).isFinished()) return list.get(i);
        }
        return null;
    }

    public static List<ExcavationJob> all(UUID requester) {
        List<ExcavationJob> list = BY_REQUESTER.get(requester);
        List<ExcavationJob> out = new ArrayList<>();
        if (list != null) for (ExcavationJob j : list) if (!j.isFinished()) out.add(j);
        return out;
    }

    public static void stop(UUID requester) {
        List<ExcavationJob> list = BY_REQUESTER.remove(requester);
        if (list != null) for (ExcavationJob j : new ArrayList<>(list)) j.cancel();
    }

    public static void stopAll() {
        for (List<ExcavationJob> list : new ArrayList<>(BY_REQUESTER.values())) {
            for (ExcavationJob j : new ArrayList<>(list)) j.cancel();
        }
        BY_REQUESTER.clear();
    }

    // ---------------------------------------------------------------------
    // Area jobs
    // ---------------------------------------------------------------------

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
        register(job);
        return job;
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
        remaining = cells.size();
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

    // ---------------------------------------------------------------------
    // Tunnels: one 1x2 tunnel per bot the way the speaker faces, spread out
    // sideways (0, +3, -3, +6, -6 ...) and at different depths (level, a
    // few blocks up, a few blocks down - reached by a gentle staircase so
    // the bot can walk it).
    // ---------------------------------------------------------------------

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
        return laneJob(Mode.TUNNEL, requester, w, made, crew);
    }

    // ---------------------------------------------------------------------
    // Dig down to a point below (a player in a cave, a base underground):
    // every bot digs its own 1x1 shaft straight down from where it stands
    // to the target's level, then a 1x2 tunnel across to it. Shafts sit on
    // separate columns (a block apart) so nobody digs out someone else's
    // floor.
    // ---------------------------------------------------------------------

    public static final int DIG_TO_MAX_DEPTH = 96;
    public static final int DIG_TO_MAX_ACROSS = 64;

    public static ExcavationJob startDigTo(UUID requester, List<UUID> crew, List<Location> starts,
                                           Location target) {
        return startDigTo(requester, crew, starts, target, null);
    }

    public static ExcavationJob startDigTo(UUID requester, List<UUID> crew, List<Location> starts,
                                           Location target, UUID targetId) {
        World w = target.getWorld();
        int tx = target.getBlockX(), ty = target.getBlockY(), tz = target.getBlockZ();
        List<Cell> made = new ArrayList<>();
        java.util.Set<Long> columns = new java.util.HashSet<>();
        for (int lane = 0; lane < crew.size(); lane++) {
            Location s = starts.get(lane);
            int[] col = freeColumn(s.getBlockX(), s.getBlockZ(), columns);
            columns.add(colKey(col[0], col[1]));
            planDig(made, lane, 0, col[0], s.getBlockY(), col[1], tx, ty, tz);
        }
        ExcavationJob job = laneJob(Mode.DIG_TO, requester, w, made, crew);
        job.digTarget = targetId;
        return job;
    }

    // Cells (in digging order) that take a bot standing with its feet at
    // (x, y, z) to feet at (tx, ty, tz): a straight shaft for however much
    // of the drop a staircase can't cover, then a walkable 1x2 staircase
    // tunnel (one block of height per block across) the rest of the way.
    // Returns the next free seq.
    static int planDig(List<Cell> out, int lane, int seq, int x, int y, int z, int tx, int ty, int tz) {
        int across = Math.abs(tx - x) + Math.abs(tz - z);
        int drop = y - ty;
        int shaft = Math.max(0, Math.min(DIG_TO_MAX_DEPTH, drop - across));
        for (int i = 1; i <= shaft; i++) out.add(laneCell(x, y - i, z, lane, seq++));
        y -= shaft;
        int steps = 0;
        while ((x != tx || z != tz) && steps++ < DIG_TO_MAX_ACROSS) {
            int nx = x, nz = z;
            int ddx = tx - x, ddz = tz - z;
            if (Math.abs(ddx) >= Math.abs(ddz)) nx += Integer.signum(ddx);
            else nz += Integer.signum(ddz);
            int ny = y + Integer.signum(ty - y);
            if (ny > y) {
                // Stepping up: room to jump from here, then the step's air.
                out.add(laneCell(x, y + 2, z, lane, seq++));
                out.add(laneCell(nx, ny + 1, nz, lane, seq++));
                out.add(laneCell(nx, ny, nz, lane, seq++));
            } else if (ny < y) {
                // Stepping down: our head height over there goes too.
                out.add(laneCell(nx, ny + 2, nz, lane, seq++));
                out.add(laneCell(nx, ny + 1, nz, lane, seq++));
                out.add(laneCell(nx, ny, nz, lane, seq++));
            } else {
                out.add(laneCell(nx, ny + 1, nz, lane, seq++));
                out.add(laneCell(nx, ny, nz, lane, seq++));
            }
            x = nx;
            y = ny;
            z = nz;
        }
        // Right over them but still above: straight down the last bit.
        for (int i = 1; i <= Math.min(DIG_TO_MAX_DEPTH, y - ty); i++) out.add(laneCell(x, y - i, z, lane, seq++));
        return seq;
    }

    // The target moved (or the plan ran out): drop what's left of this bot's
    // lane and plan a fresh one from where it stands to where they are now.
    public int replanLane(UUID bot, Location from, Location to) {
        Integer lane = laneOf.get(bot);
        if (lane == null) return 0;
        List<Cell> list = laneCells.computeIfAbsent(lane, k -> new ArrayList<>());
        int seq = 0;
        for (Cell c : list) {
            if (!c.done) markDone(c);
            seq = Math.max(seq, c.seq + 1);
        }
        List<Cell> fresh = new ArrayList<>();
        planDig(fresh, lane, seq, from.getBlockX(), from.getBlockY(), from.getBlockZ(),
                to.getBlockX(), to.getBlockY(), to.getBlockZ());
        for (Cell c : fresh) {
            if (c.y <= world.getMinHeight() || c.y >= world.getMaxHeight()) continue;
            cells.add(c);
            list.add(c);
            byPos.put(key(c.x, c.y, c.z), c);
            remaining++;
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        return fresh.size();
    }

    // Cells that are actually solid right now (a route through open air is
    // no dig at all).
    public int breakableLeft() {
        int n = 0;
        for (Cell c : cells) {
            if (!c.done && breakable(world.getBlockAt(c.x, c.y, c.z).getType())) n++;
        }
        return n;
    }

    // Blocks still to dig in this bot's lane.
    public int laneLeft(UUID bot) {
        Integer lane = laneOf.get(bot);
        List<Cell> list = lane == null ? null : laneCells.get(lane);
        if (list == null) return 0;
        int n = 0;
        for (int i = laneCursor.getOrDefault(lane, 0); i < list.size(); i++) if (!list.get(i).done) n++;
        return n;
    }

    // Where this bot's lane ends (its planned arrival point), or null.
    public Location laneEnd(UUID bot) {
        Integer lane = laneOf.get(bot);
        List<Cell> list = lane == null ? null : laneCells.get(lane);
        if (list == null || list.isEmpty()) return null;
        Cell last = list.get(list.size() - 1);
        return new Location(world, last.x + 0.5, last.y, last.z + 0.5);
    }

    private static int[] freeColumn(int x, int z, java.util.Set<Long> taken) {
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = x + dx, cz = z + dz;
                    boolean crowded = false;
                    for (int ax = -1; ax <= 1 && !crowded; ax++) {
                        for (int az = -1; az <= 1; az++) {
                            if (taken.contains(colKey(cx + ax, cz + az))) {
                                crowded = true;
                                break;
                            }
                        }
                    }
                    if (!crowded) return new int[]{cx, cz};
                }
            }
        }
        return new int[]{x, z};
    }

    private static long colKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static ExcavationJob laneJob(Mode mode, UUID requester, World w, List<Cell> made, List<UUID> crew) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cell c : made) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        if (made.isEmpty()) {
            minX = maxX = minY = maxY = minZ = maxZ = 0;
        }
        ExcavationJob job = new ExcavationJob(mode, w, requester, MAX_DURATION_MS,
                minX, minY, minZ, maxX, maxY, maxZ);
        for (Cell c : made) {
            if (c.y <= w.getMinHeight() || c.y >= w.getMaxHeight()) continue;
            job.cells.add(c);
            job.byPos.putIfAbsent(key(c.x, c.y, c.z), c);
            job.laneCells.computeIfAbsent(c.lane, k -> new ArrayList<>()).add(c);
        }
        for (List<Cell> lane : job.laneCells.values()) lane.sort(Comparator.comparingInt(c -> c.seq));
        for (int i = 0; i < crew.size(); i++) job.laneOf.put(crew.get(i), i);
        job.remaining = job.cells.size();
        job.topY = maxY;
        register(job);
        return job;
    }

    private static Cell laneCell(int x, int y, int z, int lane, int seq) {
        Cell c = new Cell(x, y, z);
        c.lane = lane;
        c.seq = seq;
        return c;
    }

    public boolean isLaneJob() {
        return mode == Mode.TUNNEL || mode == Mode.DIG_TO;
    }

    private Cell claimInLane(UUID bot, java.util.function.Predicate<Block> canBreak) {
        Integer lane = laneOf.get(bot);
        if (lane == null) {
            // Joined late: take a lane nobody works (or share the first).
            java.util.Set<Integer> used = new java.util.HashSet<>(laneOf.values());
            lane = 0;
            for (Integer l : laneCells.keySet()) {
                if (!used.contains(l)) {
                    lane = l;
                    break;
                }
            }
            laneOf.put(bot, lane);
        }
        List<Cell> list = laneCells.get(lane);
        if (list == null) return null;
        int now = Bukkit.getCurrentTick();
        int cursor = laneCursor.getOrDefault(lane, 0);
        // Skip past the finished front of the lane once, for good.
        while (cursor < list.size() && list.get(cursor).done) cursor++;
        laneCursor.put(lane, cursor);
        for (int i = cursor; i < list.size(); i++) {
            Cell c = list.get(i);
            if (c.done || c.owner != null) continue;
            if (c.retryAt > now) return null; // the next block isn't ready: wait, stay in order
            Block b = world.getBlockAt(c.x, c.y, c.z);
            if (!breakable(b.getType())) {
                markDone(c); // already open (cave, air) - walk through
                continue;
            }
            if (!canBreak.test(b)) {
                markDone(c); // e.g. stone without a pickaxe: this lane stops being dug here
                continue;
            }
            c.owner = bot;
            c.leaseTicks = LEASE_TICKS;
            return c;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Blocks worth clearing: anything solid-ish that can actually be broken.
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Bookkeeping
    // ---------------------------------------------------------------------

    private void markDone(Cell c) {
        if (c.done) return;
        c.done = true;
        c.owner = null;
        remaining--;
    }

    // Called by every crew member each tick; does the bookkeeping once.
    public void tick() {
        int now = Bukkit.getCurrentTick();
        if (now == lastTick) return;
        int elapsed = lastTick == Integer.MIN_VALUE ? 1 : Math.max(1, now - lastTick);
        lastTick = now;

        // Leases, and a 1/16th slice of the "cleared by something else"
        // sweep (TNT, a player, gravity) - the whole job every 16 ticks
        // without a spike.
        int slice = Math.floorMod(now, SWEEP_SLICES);
        for (int i = 0, n = cells.size(); i < n; i++) {
            Cell c = cells.get(i);
            if (c.done) continue;
            if (c.owner != null && (c.leaseTicks -= elapsed) <= 0) c.owner = null;
            if (i % SWEEP_SLICES == slice && !breakable(world.getBlockAt(c.x, c.y, c.z).getType())) {
                markDone(c);
            }
        }

        if (!isLaneJob()) {
            // Cells are sorted top-down: the top layer is the first live one.
            while (firstLive < cells.size() && cells.get(firstLive).done) firstLive++;
            topY = firstLive < cells.size() ? cells.get(firstLive).y : minY;
        }

        for (BlastPoint b : blasts) {
            if (!b.done && b.owner != null && (b.leaseTicks -= elapsed) <= 0) b.owner = null;
        }
    }

    // Candidate for an area claim, with its (cheap) distance.
    private record Candidate(Cell cell, double d) {
    }

    public Cell claim(UUID bot, Player botPlayer, java.util.function.Predicate<Block> canBreak) {
        if (isFinished()) return null;
        if (isLaneJob()) return claimInLane(bot, canBreak);
        Location at = botPlayer.getLocation();
        int now = Bukkit.getCurrentTick();

        // Players (not bots) standing in the area: never dig out their floor.
        List<Location> people = null;
        for (Player p : world.getPlayers()) {
            if (p == botPlayer || p.isDead()) continue;
            if (com.pvpbot.PvPBotPlugin.getInstance().getBotManager().getBots().containsKey(p.getUniqueId())) {
                continue;
            }
            Location l = p.getLocation();
            if (l.getX() < minX - 2 || l.getX() > maxX + 3 || l.getZ() < minZ - 2 || l.getZ() > maxZ + 3) continue;
            if (people == null) people = new ArrayList<>();
            people.add(l);
        }

        // Top two layers first. If nothing there is up for grabs (all
        // leased, or left over blocks this bot can't get to), open the next
        // two layers down rather than have the crew stand around waiting on
        // one awkward block.
        List<Candidate> cands = new ArrayList<>();
        int windowBottom = topY - 1;
        int i = firstLive, n = cells.size();
        while (true) {
            for (; i < n; i++) {
                Cell c = cells.get(i);
                if (c.y < windowBottom) break; // sorted top-down
                if (c.done || c.owner != null || c.retryAt > now) continue;
                if (c.cannot != null && c.cannot.contains(bot)) continue;
                double dx = c.x + 0.5 - at.getX(), dy = c.y + 0.5 - at.getY(), dz = c.z + 0.5 - at.getZ();
                cands.add(new Candidate(c, dx * dx + dy * dy * 2.0 + dz * dz));
            }
            if (!cands.isEmpty()) {
                cands.sort(Comparator.comparingDouble(Candidate::d));
                Cell got = checkCandidates(cands, bot, canBreak, people);
                if (got != null) return got;
                cands.clear();
            }
            if (i >= n || topY - windowBottom >= MAX_LAYER_SKIP) return null;
            windowBottom -= 2;
        }
    }

    private static final int MAX_LAYER_SKIP = 12;

    // The real checks (block lookups), nearest first, a bounded number.
    private Cell checkCandidates(List<Candidate> cands, UUID bot, java.util.function.Predicate<Block> canBreak,
                                 List<Location> people) {
        int checks = 0;
        for (Candidate cand : cands) {
            if (checks++ >= CLAIM_CHECKS) break;
            Cell c = cand.cell();
            Block b = world.getBlockAt(c.x, c.y, c.z);
            if (!breakable(b.getType())) {
                markDone(c);
                continue;
            }
            if (people != null && underSomeone(c, people)) continue;
            if (!canBreak.test(b)) {
                // Nobody on the crew has the tool for it: skip it rather than
                // let the whole job wait on it forever.
                if (c.cannot == null) c.cannot = new java.util.HashSet<>();
                c.cannot.add(bot);
                if (c.cannot.size() >= Math.max(1, crew.size())) markDone(c);
                continue;
            }
            c.owner = bot;
            c.leaseTicks = LEASE_TICKS;
            return c;
        }
        return null;
    }

    private static boolean underSomeone(Cell c, List<Location> people) {
        for (Location l : people) {
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
        hadCrew = true;
    }

    // Last bot gone (every one of them got another order): the job is over.
    public void removeCrew(UUID bot) {
        crew.remove(bot);
        if (hadCrew && crew.isEmpty()) cancel();
    }

    public int crewSize() {
        return crew.size();
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
        markDone(c);
        broken++;
    }

    // Something in the way got broken as part of reaching another block.
    public void noteBroken(int x, int y, int z) {
        Cell c = byPos.get(key(x, y, z));
        if (c != null && !c.done) markDone(c);
        broken++;
    }

    public void fail(Cell c) {
        if (c == null || c.done) return;
        c.owner = null;
        if (++c.failures >= MAX_CELL_FAILURES) markDone(c); // give up on it
    }

    public void release(Cell c) {
        if (c != null && !c.done) c.owner = null;
    }

    // This bot can't get at it (walled off from where it can stand, or the
    // walk there keeps failing). Another bot coming from elsewhere may
    // still manage, so only when every crew member has failed it is it
    // dropped for good.
    public void unreachableFor(Cell c, UUID bot) {
        if (c == null || c.done) return;
        c.owner = null;
        if (isLaneJob()) {
            if (++c.failures >= MAX_CELL_FAILURES) markDone(c);
            return;
        }
        if (c.cannot == null) c.cannot = new java.util.HashSet<>();
        c.cannot.add(bot);
        if (c.cannot.size() >= Math.max(1, crew.size())) markDone(c);
    }

    // Can't be done yet but may be later (no dry spot to stand on until the
    // dig reaches it): hand it back and don't offer it again for a while.
    // Still gives up on it eventually so the job always ends.
    public void defer(Cell c, int ticks) {
        if (c == null || c.done) return;
        c.owner = null;
        c.retryAt = Bukkit.getCurrentTick() + ticks;
        if (++c.failures >= MAX_CELL_DEFERS) markDone(c);
    }

    public void markSealed(int x, int y, int z) {
        sealed.add(key(x, y, z));
        Cell c = byPos.get(key(x, y, z));
        if (c != null && !c.done) markDone(c);
    }

    public boolean isSealed(int x, int y, int z) {
        return sealed.contains(key(x, y, z));
    }

    public boolean isCell(int x, int y, int z) {
        return byPos.containsKey(key(x, y, z));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean isFinished() {
        if (cancelled || System.currentTimeMillis() > deadline) return true;
        return remaining <= 0 && !hasBlastsLeft();
    }

    public void cancel() {
        cancelled = true;
        List<ExcavationJob> list = BY_REQUESTER.get(requester);
        if (list != null) {
            list.remove(this);
            if (list.isEmpty()) BY_REQUESTER.remove(requester);
        }
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
