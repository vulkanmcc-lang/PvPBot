package com.pvpbot.schem;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SchematicPreview {
    private static final int DEFAULT_SECONDS = 45;

    private static final int PER_TICK = 600;

    private static final Map<UUID, SchematicPreview> ACTIVE = new HashMap<>();

    private final Plugin plugin;
    private final Player viewer;
    private final World world;
    private final List<long[]> sent = new ArrayList<>();
    private BukkitTask task;
    private BukkitTask expiry;

    private SchematicPreview(Plugin plugin, Player viewer, World world) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.world = world;
    }

    public static int show(Plugin plugin, Player viewer, Schematic schem,
                           int ox, int oy, int oz, int seconds) {
        clear(viewer);

        SchematicPreview p = new SchematicPreview(plugin, viewer, viewer.getWorld());
        ACTIVE.put(viewer.getUniqueId(), p);

        List<Object[]> queue = new ArrayList<>();
        for (int y = 0; y < schem.height; y++) {
            for (int z = 0; z < schem.length; z++) {
                for (int x = 0; x < schem.width; x++) {
                    BlockData d = schem.at(x, y, z);
                    if (d == null || d.getMaterial().isAir()) continue;
                    queue.add(new Object[]{ox + x, oy + y, oz + z, d});
                }
            }
        }

        final int[] cursor = {0};
        p.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!viewer.isOnline()) {
                clear(viewer);
                return;
            }
            int end = Math.min(cursor[0] + PER_TICK, queue.size());
            for (int i = cursor[0]; i < end; i++) {
                Object[] e = queue.get(i);
                int bx = (int) e[0], by = (int) e[1], bz = (int) e[2];
                viewer.sendBlockChange(new Location(p.world, bx, by, bz), (BlockData) e[3]);
                p.sent.add(new long[]{bx, by, bz});
            }
            cursor[0] = end;
            if (cursor[0] >= queue.size() && p.task != null) {
                p.task.cancel();
                p.task = null;
            }
        }, 1L, 1L);

        int secs = seconds > 0 ? seconds : DEFAULT_SECONDS;
        p.expiry = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> clear(viewer), secs * 20L);

        return queue.size();
    }

    public static void clear(Player viewer) {
        SchematicPreview p = ACTIVE.remove(viewer.getUniqueId());
        if (p == null) return;

        if (p.task != null) p.task.cancel();
        if (p.expiry != null) p.expiry.cancel();

        if (!viewer.isOnline()) return;
        for (long[] c : p.sent) {
            Location loc = new Location(p.world, c[0], c[1], c[2]);

            viewer.sendBlockChange(loc, p.world.getBlockAt(loc).getBlockData());
        }
        p.sent.clear();
    }

    public static boolean isShowing(Player viewer) {
        return ACTIVE.containsKey(viewer.getUniqueId());
    }

    public static void clearAll() {
        for (UUID id : new ArrayList<>(ACTIVE.keySet())) {
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null) clear(p);
            else ACTIVE.remove(id);
        }
    }

    public static int[] originFor(Player p, Schematic s) {
        Location l = p.getLocation();
        return new int[]{
                l.getBlockX() - s.width / 2,
                l.getBlockY(),
                l.getBlockZ() - s.length / 2};
    }

    public static boolean tooBig(Schematic s) {
        return s.volume() > 200_000;
    }

    public static String describeSize(Schematic s) {
        return s.width + "x" + s.height + "x" + s.length;
    }
}
