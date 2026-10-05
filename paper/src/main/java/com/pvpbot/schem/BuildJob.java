package com.pvpbot.schem;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BuildJob {
    public static class Task {
        public final int x, y, z;
        public final BlockData data;
        public final Material item;

        // The other half of a two-block block (bed head, door/tall-plant top)
        // - placed together with this one, from the same single item, the
        // way vanilla places them. Null for ordinary blocks.
        public final BlockData partnerData;
        public final int px, py, pz;

        UUID owner;

        int leaseTicks;
        boolean done;

        Task(int x, int y, int z, BlockData data, Material item) {
            this(x, y, z, data, item, null, 0, 0, 0);
        }

        Task(int x, int y, int z, BlockData data, Material item,
             BlockData partnerData, int px, int py, int pz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.item = item;
            this.partnerData = partnerData;
            this.px = px;
            this.py = py;
            this.pz = pz;
        }

        public boolean hasPartner() {
            return partnerData != null;
        }

        public Location location(World w) {
            return new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    public static final Material SCAFFOLD = Material.COBBLESTONE;

    // Schematic builds scaffold with cobblestone; everything else the bots
    // build (voice paths) uses end stone throughout.
    private Material scaffoldMaterial = SCAFFOLD;

    public Material scaffoldMaterial() {
        return scaffoldMaterial;
    }

    public void setScaffoldMaterial(Material m) {
        if (m != null) scaffoldMaterial = m;
    }

    // Generated builds (voice paths, take-cover roofs): any solid block
    // counts, and every bot builds - and scaffolds - with its own build
    // block (end stone for random-named bots, plain blocks for the rest)
    // instead of the material in the plan.
    private boolean anyBuildBlock = false;

    public boolean anyBuildBlock() {
        return anyBuildBlock;
    }

    public void setAnyBuildBlock(boolean any) {
        anyBuildBlock = any;
    }

    // A temporary block a bot placed to reach part of the build (a pillar
    // step or a bridge floor). Tracked here, not just in the bot, so the job
    // can guarantee none are left behind even if the bot that placed them
    // dies, is removed, or gets pulled into a fight and never comes back.
    public static final class Scaffold {
        public final int x, y, z;
        public final Material material;
        public final UUID owner;
        public final boolean pillar;
        // Where the bot stood when it placed this block: a spot within reach
        // that doesn't depend on the block itself (for a bridge block, the
        // cell it was placed from).
        public final int standX, standY, standZ;
        public boolean abandoned;

        Scaffold(int x, int y, int z, Material material, UUID owner, boolean pillar,
                 int standX, int standY, int standZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.owner = owner;
            this.pillar = pillar;
            this.standX = standX;
            this.standY = standY;
            this.standZ = standZ;
        }
    }

    private final List<Scaffold> scaffold = new ArrayList<>();
    private final java.util.HashSet<Long> scaffoldCells = new java.util.HashSet<>();
    private final Map<UUID, Integer> heartbeat = new HashMap<>();
    private int jobTicks = 0;

    // Owner considered gone (dead, removed, reassigned) after this long
    // without checking in; its scaffold then gets cleaned up by the job.
    private static final int OWNER_TIMEOUT_TICKS = 200;

    private static final int STALL_TICKS = 100;

    private static final int LEASE_TICKS = 400;

    public final String schematicName;
    public final World world;
    public final int originX, originY, originZ;
    public final String faction;

    private final List<Task> tasks;

    private final java.util.HashSet<Long> cells = new java.util.HashSet<>();
    private final Map<Material, Integer> reserve = new HashMap<>();
    private int completed = 0;
    private int currentLayer;
    private final int minY;
    private final int maxY;

    private int stallTicks = 0;

    private boolean relaxSupport = false;
    private boolean cancelled = false;

    public BuildJob(Schematic schem, World world, int ox, int oy, int oz, String faction) {
        this(schem.name, world, ox, oy, oz, faction, tasksFrom(schem, ox, oy, oz),
                ox + schem.width / 2.0, oz + schem.length / 2.0);
    }

    private static List<Task> tasksFrom(Schematic schem, int ox, int oy, int oz) {
        List<Task> list = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int y = 0; y < schem.height; y++) {
            for (int z = 0; z < schem.length; z++) {
                for (int x = 0; x < schem.width; x++) {
                    BlockData d = schem.at(x, y, z);
                    if (d == null) continue;
                    Task t = taskFor(ox + x, oy + y, oz + z, d);
                    if (t != null && seen.add(key(t.x, t.y, t.z))) list.add(t);
                }
            }
        }
        return list;
    }

    // ---- two-block blocks
    //
    // Beds (foot + head) and doors / tall flowers / tall grass / small
    // dripleaf (bottom + top) are one item that fills two cells. Building
    // each half as its own task put down lone halves - a second item for
    // the other half, and a lone half pops off at the next block update -
    // so a pair becomes one task on its foot/bottom cell that places both.

    private static boolean isTwoTall(BlockData d) {
        return d instanceof org.bukkit.block.data.Bisected
                && !(d instanceof org.bukkit.block.data.type.Stairs)
                && !(d instanceof org.bukkit.block.data.type.TrapDoor);
    }

    private static boolean isPairBlock(BlockData d) {
        return d instanceof org.bukkit.block.data.type.Bed || isTwoTall(d);
    }

    private static boolean isPrimaryHalf(BlockData d) {
        if (d instanceof org.bukkit.block.data.type.Bed bed) {
            return bed.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT;
        }
        return ((org.bukkit.block.data.Bisected) d).getHalf()
                == org.bukkit.block.data.Bisected.Half.BOTTOM;
    }

    // Offset from the foot/bottom half to the head/top half.
    private static int[] partnerOffset(BlockData primary) {
        if (primary instanceof org.bukkit.block.data.type.Bed bed) {
            org.bukkit.block.BlockFace f = bed.getFacing();
            return new int[]{f.getModX(), 0, f.getModZ()};
        }
        return new int[]{0, 1, 0};
    }

    // The same block as its foot/bottom (primary=true) or head/top half.
    private static BlockData asHalf(BlockData d, boolean primary) {
        BlockData c = d.clone();
        if (c instanceof org.bukkit.block.data.type.Bed bed) {
            bed.setPart(primary ? org.bukkit.block.data.type.Bed.Part.FOOT
                    : org.bukkit.block.data.type.Bed.Part.HEAD);
        } else if (c instanceof org.bukkit.block.data.Bisected b) {
            b.setHalf(primary ? org.bukkit.block.data.Bisected.Half.BOTTOM
                    : org.bukkit.block.data.Bisected.Half.TOP);
        }
        return c;
    }

    // The task that builds the block at (x, y, z). Either half of a pair
    // maps to the same task on the primary half's cell (so a pair is
    // listed once, and a pair cut in half by the schematic edge still gets
    // built whole).
    private static Task taskFor(int x, int y, int z, BlockData d) {
        Material m = d.getMaterial();
        if (m.isAir() || !m.isBlock() || !m.isItem()) return null;
        if (!isPairBlock(d)) return new Task(x, y, z, d, m);

        BlockData primary = isPrimaryHalf(d) ? d : asHalf(d, true);
        int[] off = partnerOffset(primary);
        int bx = x, by = y, bz = z;
        if (!isPrimaryHalf(d)) {
            bx -= off[0];
            by -= off[1];
            bz -= off[2];
        }
        return new Task(bx, by, bz, primary, m, asHalf(primary, false),
                bx + off[0], by + off[1], bz + off[2]);
    }

    // One block of a generated build (no schematic file): a voice-ordered
    // bridge, a wall, etc.
    public record Placement(int x, int y, int z, BlockData data) {
    }

    // Build job from a generated block list. Work is handed out layer by
    // layer and, within a layer, nearest to (startX, startZ) first - so a
    // bridge grows outward from where it was ordered.
    public static BuildJob fromPlacements(String name, World world, List<Placement> blocks,
                                          int startX, int startY, int startZ, String faction) {
        List<Task> list = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Placement p : blocks) {
            Task t = taskFor(p.x(), p.y(), p.z(), p.data());
            if (t == null || !seen.add(key(t.x, t.y, t.z))) continue;
            list.add(t);
        }
        return new BuildJob(name, world, startX, startY, startZ, faction, list, startX + 0.5, startZ + 0.5);
    }

    // Materials needed, for handing out to the crew.
    public Map<Material, Integer> bill() {
        Map<Material, Integer> out = new java.util.LinkedHashMap<>();
        for (Task t : tasks) if (!t.done) out.merge(t.item, 1, Integer::sum);
        return out;
    }

    private BuildJob(String name, World world, int ox, int oy, int oz, String faction,
                     List<Task> list, double sortCx, double sortCz) {
        this.schematicName = name;
        this.world = world;
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
        this.faction = faction;

        list.sort(Comparator
                .comparingInt((Task t) -> t.y)
                .thenComparingDouble(t -> {
                    double dx = (t.x + 0.5) - sortCx, dz = (t.z + 0.5) - sortCz;
                    return dx * dx + dz * dz;
                }));

        for (Task t : list) {
            cells.add(key(t.x, t.y, t.z));
            if (t.hasPartner()) cells.add(key(t.px, t.py, t.pz));
        }

        this.tasks = list;
        this.currentLayer = list.isEmpty() ? 0 : list.get(0).y;
        this.minY = this.currentLayer;
        this.maxY = list.isEmpty() ? 0 : list.get(list.size() - 1).y;
    }

    public boolean isBuildCell(int x, int y, int z) {
        return cells.contains(key(x, y, z));
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y & 0xFFF);
    }

    public boolean isBaseLayer(int y) {
        return y <= minY;
    }

    public int total() {
        return tasks.size();
    }

    public int completed() {
        return completed;
    }

    public boolean isFinished() {
        return cancelled || completed >= tasks.size();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ---------------------------------------------------------------------
    // Scaffold registry
    // ---------------------------------------------------------------------

    public void heartbeat(UUID bot) {
        heartbeat.put(bot, jobTicks);
    }

    public Scaffold addScaffold(int x, int y, int z, Material m, UUID owner, boolean pillar,
                                int standX, int standY, int standZ) {
        Scaffold sc = new Scaffold(x, y, z, m, owner, pillar, standX, standY, standZ);
        scaffold.add(sc);
        scaffoldCells.add(key(x, y, z));
        return sc;
    }

    public void removeScaffold(Scaffold sc) {
        if (sc == null) return;
        scaffold.remove(sc);
        scaffoldCells.remove(key(sc.x, sc.y, sc.z));
    }

    public boolean isScaffoldCell(int x, int y, int z) {
        return scaffoldCells.contains(key(x, y, z));
    }

    public boolean hasScaffold() {
        return !scaffold.isEmpty();
    }

    public int scaffoldCount() {
        return scaffold.size();
    }

    // This bot's scaffold, oldest first (tear down from the end).
    public List<Scaffold> scaffoldOf(UUID owner) {
        List<Scaffold> out = new ArrayList<>();
        for (Scaffold sc : scaffold) {
            if (sc.owner.equals(owner) && !sc.abandoned) out.add(sc);
        }
        return out;
    }

    // Pops a scaffold block straight back out of the world (no mining
    // animation). The fallback that guarantees nothing is left behind.
    public void removeScaffoldNow(Scaffold sc) {
        if (sc == null) return;
        org.bukkit.block.Block b = world.getBlockAt(sc.x, sc.y, sc.z);
        if (b.getType() == sc.material) b.setType(Material.AIR, true);
        removeScaffold(sc);
    }

    public void removeScaffoldNow(UUID owner) {
        for (Scaffold sc : new ArrayList<>(scaffold)) {
            if (owner == null || sc.owner.equals(owner)) removeScaffoldNow(sc);
        }
    }

    private void cleanOrphanedScaffold() {
        if (scaffold.isEmpty()) return;
        for (Scaffold sc : new ArrayList<>(scaffold)) {
            org.bukkit.block.Block b = world.getBlockAt(sc.x, sc.y, sc.z);
            if (b.getType() != sc.material) {
                // Already gone (mined, exploded, replaced) - just forget it.
                removeScaffold(sc);
                continue;
            }
            Integer seen = heartbeat.get(sc.owner);
            boolean ownerGone = seen == null || jobTicks - seen > OWNER_TIMEOUT_TICKS;
            boolean finishedAndIdle = isFinished() && ownerGone;
            if ((sc.abandoned || ownerGone || finishedAndIdle) && !someoneStandsOn(sc)) {
                removeScaffoldNow(sc);
            }
        }
    }

    private boolean someoneStandsOn(Scaffold sc) {
        return someoneStandsOn(sc, -1);
    }

    // Any living entity (other than excludeEntityId) resting on top of it.
    public boolean someoneStandsOn(Scaffold sc, int excludeEntityId) {
        try {
            org.bukkit.util.BoundingBox above = new org.bukkit.util.BoundingBox(
                    sc.x - 0.3, sc.y + 1.0, sc.z - 0.3, sc.x + 1.3, sc.y + 3.0, sc.z + 1.3);
            return !world.getNearbyEntities(above,
                    e -> e instanceof org.bukkit.entity.LivingEntity
                            && e.getEntityId() != excludeEntityId).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    public void tick() {
        jobTicks++;
        if ((jobTicks % 20) == 0) cleanOrphanedScaffold();
        if (isFinished()) return;
        if (segmented) {
            for (Task t : tasks) {
                if (!t.done && t.owner != null && --t.leaseTicks <= 0) t.owner = null;
            }
            // Nothing claimable anywhere for a while (floating bits with no
            // support yet): let them be placed against anything.
            if (jobTicks - lastClaimTick > STALL_TICKS) relaxSupport = true;
            return;
        }

        boolean openBelow = false;
        boolean readyBelow = false;

        for (Task t : tasks) {
            if (t.done) continue;
            if (t.y > currentLayer) break;
            openBelow = true;

            if (t.owner != null && --t.leaseTicks <= 0) {
                t.owner = null;
            }
            if (t.owner == null && isSupported(t)) readyBelow = true;
        }

        if (!openBelow) {
            if (!tasks.isEmpty()) currentLayer++;
            stallTicks = 0;
            return;
        }

        if (readyBelow) {
            stallTicks = 0;
            return;
        }

        if (++stallTicks > STALL_TICKS) {
            stallTicks = 0;
            if (currentLayer < maxY) {
                currentLayer++;
            } else {
                relaxSupport = true;
            }
        }
    }

    private boolean isSupported(Task t) {
        return relaxSupport || t.y <= minY || hasWorldSupport(t);
    }

    public boolean isSupportRelaxed() {
        return relaxSupport;
    }

    public Task claim(UUID bot, Player botPlayer, double botX, double botY, double botZ) {
        return claim(bot, botPlayer, botX, botY, botZ, null);
    }

    public Task claim(UUID bot, Player botPlayer,
                      double botX, double botY, double botZ, Task avoid) {
        if (cancelled) return null;
        if (segmented) return claimSegmented(bot, botPlayer, botX, botY, botZ, avoid);

        Task best = null;
        double bestDist = Double.MAX_VALUE;

        for (Task t : tasks) {
            if (t.done || t.owner != null) continue;
            if (t == avoid) continue;

            if (t.y > currentLayer + 1) break;
            if (!anyBuildBlock && !hasItem(botPlayer, t.item)) continue;

            if (!isSupported(t)) continue;

            double dx = t.x + 0.5 - botX, dy = t.y - botY, dz = t.z + 0.5 - botZ;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }

        if (best != null) {
            best.owner = bot;
            best.leaseTicks = LEASE_TICKS;
        }
        return best;
    }

    private boolean hasWorldSupport(Task t) {
        Material a = world.getBlockAt(t.x, t.y - 1, t.z).getType();
        if (a.isSolid()) return true;
        Material b = world.getBlockAt(t.x, t.y + 1, t.z).getType();
        if (b.isSolid()) return true;
        Material c = world.getBlockAt(t.x + 1, t.y, t.z).getType();
        if (c.isSolid()) return true;
        Material d = world.getBlockAt(t.x - 1, t.y, t.z).getType();
        if (d.isSolid()) return true;
        Material e = world.getBlockAt(t.x, t.y, t.z + 1).getType();
        if (e.isSolid()) return true;
        Material f = world.getBlockAt(t.x, t.y, t.z - 1).getType();
        return f.isSolid();
    }

    public void release(Task t) {
        if (t != null && !t.done) t.owner = null;
    }

    public void complete(Task t) {
        if (t == null || t.done) return;
        t.done = true;
        t.owner = null;
        completed++;
    }

    private static final int MAX_HANDOUT_SLOTS = 24;

    public void distribute(List<Player> crew, Map<Material, Integer> bill) {
        if (crew.isEmpty()) return;

        for (Player p : crew) giveItems(p, scaffoldMaterial, 64);

        int[] slotsUsed = new int[crew.size()];

        for (Map.Entry<Material, Integer> e : bill.entrySet()) {
            Material m = e.getKey();
            int total = e.getValue();
            int each = total / crew.size();
            int extra = total % crew.size();
            int stackSize = Math.max(1, m.getMaxStackSize());

            for (int i = 0; i < crew.size(); i++) {
                int give = each + (i < extra ? 1 : 0);
                if (give <= 0) continue;

                int slotsFree = MAX_HANDOUT_SLOTS - slotsUsed[i];
                if (slotsFree <= 0) {
                    reserve.merge(m, give, Integer::sum);
                    continue;
                }

                int canCarry = Math.min(give, slotsFree * stackSize);
                int leftover = giveItems(crew.get(i), m, canCarry);
                int actuallyGiven = canCarry - leftover;
                slotsUsed[i] += (actuallyGiven + stackSize - 1) / stackSize;

                int toReserve = (give - canCarry) + leftover;
                if (toReserve > 0) reserve.merge(m, toReserve, Integer::sum);
            }
        }
    }

    public int withdraw(Player botPlayer, Material m, int want) {
        Integer held = reserve.get(m);
        if (held == null || held <= 0) return 0;
        int give = Math.min(held, want);
        int leftover = giveItems(botPlayer, m, give);
        int actual = give - leftover;
        int remaining = held - actual;
        if (remaining <= 0) reserve.remove(m);
        else reserve.put(m, remaining);
        return actual;
    }

    public Material nextNeededMaterial() {
        if (segmented) {
            for (Task t : tasks) if (!t.done && t.owner == null) return t.item;
            return null;
        }
        for (Task t : tasks) {
            if (t.done || t.owner != null) continue;
            if (t.y > currentLayer + 1) break;
            if (!isSupported(t)) continue;
            return t.item;
        }
        return null;
    }

    public void supply(Player botPlayer, Material m, int amount) {
        if (botPlayer == null || m == null || amount <= 0) return;

        if (botPlayer.getInventory().firstEmpty() == -1) {
            freeASlot(botPlayer, m);
        }

        int fromReserve = withdraw(botPlayer, m, amount);
        if (fromReserve <= 0) giveItems(botPlayer, m, amount);
    }

    private void freeASlot(Player botPlayer, Material wanted) {
        java.util.Set<Material> stillNeeded = new java.util.HashSet<>();
        for (Task t : tasks) {
            if (!t.done) stillNeeded.add(t.item);
        }

        org.bukkit.inventory.ItemStack[] contents = botPlayer.getInventory().getStorageContents();
        int bestSlot = -1;
        int bestScore = -1;

        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir()) continue;
            if (s.getType() == wanted) continue;
            if (s.getType() == scaffoldMaterial) continue;

            int score = stillNeeded.contains(s.getType()) ? s.getAmount() : 100_000;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) return;
        ItemStack evicted = contents[bestSlot];
        reserve.merge(evicted.getType(), evicted.getAmount(), Integer::sum);
        botPlayer.getInventory().setItem(bestSlot, null);
    }

    public Map<Material, Integer> reserve() {
        return reserve;
    }

    // ---------------------------------------------------------------------
    // Segmented building (voice builds): the footprint is cut into columns,
    // each bot works its own column bottom-up, nobody waits for a global
    // layer - a bot that can't do anything in its column just takes over
    // another one. Blocks aren't handed out up front: a bot is given the
    // block for a placement, in its hand, when it takes it on.
    // ---------------------------------------------------------------------

    private boolean segmented = false;
    private boolean autoSupply = false;
    private int segmentSize = 4;
    private final Map<Long, List<Task>> segments = new HashMap<>();
    private final Map<UUID, Long> segmentOf = new HashMap<>();
    private int lastClaimTick = 0;

    public void enableSegments(int size, boolean supplyOnClaim) {
        segmented = true;
        autoSupply = supplyOnClaim;
        segmentSize = Math.max(2, size);
        segments.clear();
        for (Task t : tasks) {
            long k = segKey(Math.floorDiv(t.x, segmentSize), Math.floorDiv(t.z, segmentSize));
            segments.computeIfAbsent(k, kk -> new ArrayList<>()).add(t);
        }
        for (List<Task> l : segments.values()) l.sort(Comparator.comparingInt(t -> t.y));
        lastClaimTick = jobTicks;
    }

    private static long segKey(int sx, int sz) {
        return ((long) sx << 32) ^ (sz & 0xffffffffL);
    }

    private Task claimSegmented(UUID bot, Player botPlayer, double bx, double by, double bz, Task avoid) {
        Long mine = segmentOf.get(bot);
        Task pick = mine == null ? null : pickInSegment(segments.get(mine), bx, by, bz, avoid);
        if (pick == null) {
            // Our column is done or waiting on something: take the least
            // crowded column that has work, nearest first.
            Map<Long, Integer> workers = new HashMap<>();
            for (Long seg : segmentOf.values()) workers.merge(seg, 1, Integer::sum);
            Long bestSeg = null;
            Task bestTask = null;
            double bestScore = Double.MAX_VALUE;
            for (Map.Entry<Long, List<Task>> e : segments.entrySet()) {
                if (e.getKey().equals(mine)) continue;
                Task t = pickInSegment(e.getValue(), bx, by, bz, avoid);
                if (t == null) continue;
                double dx = t.x + 0.5 - bx, dz = t.z + 0.5 - bz;
                int w = workers.getOrDefault(e.getKey(), 0);
                double score = w * 400.0 + dx * dx + dz * dz;
                if (score < bestScore) {
                    bestScore = score;
                    bestSeg = e.getKey();
                    bestTask = t;
                }
            }
            if (bestTask == null) return null;
            segmentOf.put(bot, bestSeg);
            pick = bestTask;
        }
        pick.owner = bot;
        pick.leaseTicks = LEASE_TICKS;
        lastClaimTick = jobTicks;
        if (autoSupply && !hasItem(botPlayer, pick.item)) {
            Long seg = segmentOf.get(bot);
            int need = 0;
            for (Task t : segments.get(seg)) if (!t.done && t.item == pick.item) need++;
            supplyToHand(botPlayer, pick.item, Math.max(1, Math.min(need, pick.item.getMaxStackSize())));
        }
        return pick;
    }

    // In a column: the lowest open layer (and the one above it), supported,
    // nearest to the bot.
    private Task pickInSegment(List<Task> list, double bx, double by, double bz, Task avoid) {
        if (list == null) return null;
        int lowest = Integer.MAX_VALUE;
        for (Task t : list) {
            if (!t.done) {
                lowest = t.y;
                break;
            }
        }
        if (lowest == Integer.MAX_VALUE) return null;
        Task best = null;
        double bestD = Double.MAX_VALUE;
        for (Task t : list) {
            if (t.y > lowest + 1) break;
            if (t.done || t.owner != null || t == avoid) continue;
            if (!isSupported(t)) continue;
            double dx = t.x + 0.5 - bx, dy = t.y - by, dz = t.z + 0.5 - bz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    // Put `amount` of `m` straight into the bot's hand: the held slot if it's
    // empty (or already that block), else an empty hotbar slot it switches
    // to, else the held item moves to the backpack first.
    public void supplyToHand(Player p, Material m, int amount) {
        if (p == null || m == null || amount <= 0) return;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack h = inv.getItem(held);
        int stack = Math.min(amount, Math.max(1, m.getMaxStackSize()));
        if (h == null || h.getType().isAir()) {
            inv.setItem(held, new ItemStack(m, stack));
            return;
        }
        if (h.getType() == m) {
            h.setAmount(Math.min(m.getMaxStackSize(), h.getAmount() + stack));
            return;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                inv.setItem(i, new ItemStack(m, stack));
                inv.setHeldItemSlot(i);
                return;
            }
        }
        int free = firstEmptyStorage(inv);
        if (free < 0) {
            freeASlot(p, m);
            free = firstEmptyStorage(inv);
        }
        if (free >= 0) {
            inv.setItem(free, h.clone());
            inv.setItem(held, new ItemStack(m, stack));
        } else {
            giveItems(p, m, stack);
        }
    }

    // Before a voice build: hold nothing (an empty hotbar slot, or the held
    // item tucked into the backpack), so the blocks it's handed go to hand.
    public void emptyMainHand(Player p) {
        if (p == null) return;
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        ItemStack h = inv.getItem(held);
        if (h == null || h.getType().isAir()) return;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) {
                inv.setHeldItemSlot(i);
                return;
            }
        }
        int free = firstEmptyStorage(inv);
        if (free >= 0) {
            inv.setItem(free, h.clone());
            inv.setItem(held, null);
        } else {
            reserve.merge(h.getType(), h.getAmount(), Integer::sum);
            inv.setItem(held, null);
        }
    }

    private static int firstEmptyStorage(org.bukkit.inventory.PlayerInventory inv) {
        for (int i = 9; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return i;
        }
        return -1;
    }

    public void leaveSegment(UUID bot) {
        segmentOf.remove(bot);
    }

    private static int giveItems(Player p, Material m, int amount) {
        int remaining = amount;
        int stackSize = Math.max(1, m.getMaxStackSize());
        while (remaining > 0) {
            int n = Math.min(stackSize, remaining);
            Map<Integer, ItemStack> rejected = p.getInventory().addItem(new ItemStack(m, n));
            remaining -= n;
            if (!rejected.isEmpty()) {
                for (ItemStack s : rejected.values()) remaining += s.getAmount();
                break;
            }
        }
        return Math.max(0, remaining);
    }

    private static boolean hasItem(Player p, Material m) {
        return p != null && p.getInventory().contains(m);
    }
}
