package com.pvpbot.nav;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TerrainMemory {
    private static final int PROMOTE_HITS = 3;

    private static final int MERGE_RADIUS = 1;

    private static final long DECAY_MS = 20L * 60L * 1000L;

    private static final int MAX_PER_WORLD = 4096;

    public static final double PENALTY = 12.0;

    private static final class Cell {
        int hits;
        long lastSeenMs;
        boolean confirmed;
    }

    private static final Map<UUID, Map<Long, Cell>> WORLDS = new HashMap<>();

    private static boolean enabled = true;
    private static boolean dirty = false;

    private TerrainMemory() {
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFF);
    }

    private static int unpackX(long k) {
        int v = (int) ((k >> 38) & 0x3FFFFFFL);
        return (v << 6) >> 6;
    }

    private static int unpackY(long k) {
        return (int) ((k >> 26) & 0xFFFL);
    }

    private static int unpackZ(long k) {
        int v = (int) (k & 0x3FFFFFFL);
        return (v << 6) >> 6;
    }

    public static void reportFailure(World world, int x, int y, int z) {
        if (!enabled || world == null) return;

        Map<Long, Cell> cells = WORLDS.computeIfAbsent(world.getUID(), k -> new HashMap<>());
        long now = System.currentTimeMillis();

        for (int dx = -MERGE_RADIUS; dx <= MERGE_RADIUS; dx++) {
            for (int dy = -MERGE_RADIUS; dy <= MERGE_RADIUS; dy++) {
                for (int dz = -MERGE_RADIUS; dz <= MERGE_RADIUS; dz++) {
                    Cell existing = cells.get(pack(x + dx, y + dy, z + dz));
                    if (existing != null) {
                        existing.hits++;
                        existing.lastSeenMs = now;
                        if (existing.hits >= PROMOTE_HITS) existing.confirmed = true;
                        dirty = true;
                        return;
                    }
                }
            }
        }

        if (cells.size() >= MAX_PER_WORLD) evictOldest(cells);

        Cell c = new Cell();
        c.hits = 1;
        c.lastSeenMs = now;
        cells.put(pack(x, y, z), c);
        dirty = true;
    }

    public static void reportFailure(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        reportFailure(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static int collect(World world, int minX, int minY, int minZ,
                              int maxX, int maxY, int maxZ,
                              int[] outX, int[] outY, int[] outZ, int limit) {
        if (!enabled || world == null) return 0;
        Map<Long, Cell> cells = WORLDS.get(world.getUID());
        if (cells == null || cells.isEmpty()) return 0;

        long now = System.currentTimeMillis();
        int n = 0;

        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            if (n >= limit) break;
            Cell c = e.getValue();
            if (!c.confirmed) continue;
            if (now - c.lastSeenMs > DECAY_MS) continue;

            long k = e.getKey();
            int x = unpackX(k);
            int y = unpackY(k);
            int z = unpackZ(k);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;

            outX[n] = x;
            outY[n] = y;
            outZ[n] = z;
            n++;
        }
        return n;
    }

    public static void prune() {
        long now = System.currentTimeMillis();
        for (Map<Long, Cell> cells : WORLDS.values()) {
            cells.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > DECAY_MS);
        }
    }

    private static void evictOldest(Map<Long, Cell> cells) {
        long oldest = Long.MAX_VALUE;
        Long key = null;
        for (Map.Entry<Long, Cell> e : cells.entrySet()) {
            if (e.getValue().lastSeenMs < oldest) {
                oldest = e.getValue().lastSeenMs;
                key = e.getKey();
            }
        }
        if (key != null) cells.remove(key);
    }

    public static int size() {
        int n = 0;
        for (Map<Long, Cell> c : WORLDS.values()) n += c.size();
        return n;
    }

    public static int confirmedSize() {
        int n = 0;
        for (Map<Long, Cell> c : WORLDS.values()) {
            for (Cell cell : c.values()) if (cell.confirmed) n++;
        }
        return n;
    }

    public static void clear() {
        WORLDS.clear();
        dirty = true;
    }

    public static void save(File file) {
        if (!dirty) return;
        try {
            prune();
            FileConfiguration cfg = new YamlConfiguration();
            long now = System.currentTimeMillis();

            for (Map.Entry<UUID, Map<Long, Cell>> we : WORLDS.entrySet()) {
                World w = org.bukkit.Bukkit.getWorld(we.getKey());
                if (w == null) continue;

                List<String> packed = new ArrayList<>();
                for (Map.Entry<Long, Cell> e : we.getValue().entrySet()) {
                    Cell c = e.getValue();
                    if (!c.confirmed) continue;
                    long k = e.getKey();

                    packed.add(unpackX(k) + "," + unpackY(k) + "," + unpackZ(k)
                            + "," + ((now - c.lastSeenMs) / 1000L));
                }
                if (!packed.isEmpty()) cfg.set(w.getName(), packed);
            }

            cfg.save(file);
            dirty = false;
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("PvPBot").warning("Failed to save terrain memory: " + t.getMessage());
        }
    }

    public static void load(File file) {
        if (!file.exists()) return;
        try {
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            long now = System.currentTimeMillis();

            for (String worldName : cfg.getKeys(false)) {
                World w = org.bukkit.Bukkit.getWorld(worldName);
                if (w == null) continue;

                Map<Long, Cell> cells = WORLDS.computeIfAbsent(w.getUID(), k -> new HashMap<>());
                for (String entry : cfg.getStringList(worldName)) {
                    String[] parts = entry.split(",");
                    if (parts.length < 4) continue;
                    try {
                        int x = Integer.parseInt(parts[0]);
                        int y = Integer.parseInt(parts[1]);
                        int z = Integer.parseInt(parts[2]);
                        long ageMs = Long.parseLong(parts[3]) * 1000L;
                        if (ageMs > DECAY_MS) continue;

                        Cell c = new Cell();
                        c.hits = PROMOTE_HITS;
                        c.confirmed = true;
                        c.lastSeenMs = now - ageMs;
                        cells.put(pack(x, y, z), c);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("PvPBot").warning("Failed to load terrain memory: " + t.getMessage());
        }
    }
}
