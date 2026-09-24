package com.pvpbot.nav;

import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NavChunkCache {
    public static int ttlTicks = 200;

    public static int maxEntries = 768;

    public static int capturesPerTick = 12;

    private record Key(UUID world, int cx, int cz) {
    }

    private record Entry(ChunkSnapshot snapshot, long capturedTick) {
    }

    private static final Map<Key, Entry> CACHE = new ConcurrentHashMap<>();

    private static volatile long currentTick = 0;
    private static int capturesThisTick = 0;

    private static long hits = 0;
    private static long misses = 0;
    private static long denied = 0;

    private NavChunkCache() {
    }

    public static void tick(long tick) {
        currentTick = tick;
        capturesThisTick = 0;

        if ((tick & 31L) == 0L) sweep();
    }

    public static ChunkSnapshot acquire(World world, int cx, int cz) {
        Key key = new Key(world.getUID(), cx, cz);
        Entry e = CACHE.get(key);

        if (e != null && currentTick - e.capturedTick() <= ttlTicks) {
            hits++;
            return e.snapshot();
        }

        if (!world.isChunkLoaded(cx, cz)) return null;

        if (capturesThisTick >= capturesPerTick) {
            denied++;

            return e != null ? e.snapshot() : null;
        }

        try {
            ChunkSnapshot snap = world.getChunkAt(cx, cz)
                    .getChunkSnapshot(false, false, false);
            CACHE.put(key, new Entry(snap, currentTick));
            capturesThisTick++;
            misses++;
            if (CACHE.size() > maxEntries) sweep();
            return snap;
        } catch (Throwable t) {
            return e != null ? e.snapshot() : null;
        }
    }

    public static void invalidate(World world, int blockX, int blockZ) {
        if (world == null) return;
        CACHE.remove(new Key(world.getUID(), blockX >> 4, blockZ >> 4));
    }

    public static void invalidateChunk(World world, int cx, int cz) {
        if (world == null) return;
        CACHE.remove(new Key(world.getUID(), cx, cz));
    }

    public static void clear() {
        CACHE.clear();
    }

    private static void sweep() {
        long now = currentTick;
        Iterator<Map.Entry<Key, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Entry> en = it.next();
            if (now - en.getValue().capturedTick() > ttlTicks) it.remove();
        }

        if (CACHE.size() > maxEntries) {
            long cutoff = now - (ttlTicks / 2L);
            CACHE.entrySet().removeIf(en -> en.getValue().capturedTick() < cutoff);
        }
    }

    public static int size() {
        return CACHE.size();
    }

    public static long getHits() {
        return hits;
    }

    public static long getMisses() {
        return misses;
    }

    public static long getDenied() {
        return denied;
    }
}
