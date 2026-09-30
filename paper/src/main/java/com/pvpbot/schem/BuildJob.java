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

        UUID owner;

        int leaseTicks;
        boolean done;

        Task(int x, int y, int z, BlockData data, Material item) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.item = item;
        }

        public Location location(World w) {
            return new Location(w, x + 0.5, y, z + 0.5);
        }
    }

    public static final Material SCAFFOLD = Material.COBBLESTONE;

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
        for (int y = 0; y < schem.height; y++) {
            for (int z = 0; z < schem.length; z++) {
                for (int x = 0; x < schem.width; x++) {
                    BlockData d = schem.at(x, y, z);
                    if (d == null) continue;
                    Material m = d.getMaterial();
                    if (m.isAir()) continue;
                    if (!m.isBlock() || !m.isItem()) continue;
                    list.add(new Task(ox + x, oy + y, oz + z, d, m));
                }
            }
        }
        return list;
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
            Material m = p.data().getMaterial();
            if (m.isAir() || !m.isBlock() || !m.isItem()) continue;
            if (!seen.add(key(p.x(), p.y(), p.z()))) continue;
            list.add(new Task(p.x(), p.y(), p.z(), p.data(), m));
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

        for (Task t : list) cells.add(key(t.x, t.y, t.z));

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

        Task best = null;
        double bestDist = Double.MAX_VALUE;

        for (Task t : tasks) {
            if (t.done || t.owner != null) continue;
            if (t == avoid) continue;

            if (t.y > currentLayer + 1) break;
            if (!hasItem(botPlayer, t.item)) continue;

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

        for (Player p : crew) giveItems(p, SCAFFOLD, 64);

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
            if (s.getType() == SCAFFOLD) continue;

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
