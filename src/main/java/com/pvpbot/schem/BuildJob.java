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
        this.schematicName = schem.name;
        this.world = world;
        this.originX = ox;
        this.originY = oy;
        this.originZ = oz;
        this.faction = faction;

        List<Task> list = new ArrayList<>();
        double cx = schem.width / 2.0, cz = schem.length / 2.0;

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

        final double fcx = cx, fcz = cz;
        list.sort(Comparator
                .comparingInt((Task t) -> t.y)
                .thenComparingDouble(t -> {
                    double dx = (t.x - ox) - fcx, dz = (t.z - oz) - fcz;
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

    public void tick() {
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
