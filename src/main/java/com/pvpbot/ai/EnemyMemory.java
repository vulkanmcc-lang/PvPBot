package com.pvpbot.ai;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EnemyMemory {
    private static final int SIGHTING_TTL = 500;

    private static final int DISTURBANCE_TTL = 200;

    private static final int MAX_ENTRIES = 12;

    public static final class Sighting {
        public final UUID who;
        public String worldName;
        public double x, y, z;
        public int tick;
        public boolean investigated;

        public double health = 20.0;

        Sighting(UUID who) {
            this.who = who;
        }

        public Location toLocation(World world) {
            return new Location(world, x, y, z);
        }
    }

    private final Map<UUID, Sighting> sightings = new HashMap<>();
    private final List<Sighting> disturbances = new ArrayList<>();

    public void noteSeen(Player p, double x, double y, double z, int tick) {
        if (p == null) return;
        Sighting s = sightings.computeIfAbsent(p.getUniqueId(), Sighting::new);
        s.worldName = p.getWorld() == null ? null : p.getWorld().getName();
        s.x = x;
        s.y = y;
        s.z = z;
        s.tick = tick;
        s.health = p.getHealth();

        s.investigated = false;
    }

    public void noteDisturbance(Location loc, int tick) {
        if (loc == null || loc.getWorld() == null) return;

        String worldName = loc.getWorld().getName();
        double locX = loc.getX();
        double locY = loc.getY();
        double locZ = loc.getZ();

        for (Sighting s : disturbances) {
            if (s.worldName != null && s.worldName.equals(worldName)
                    && Math.abs(s.x - locX) < 4.0
                    && Math.abs(s.y - locY) < 4.0
                    && Math.abs(s.z - locZ) < 4.0) {
                s.tick = tick;
                s.investigated = false;
                return;
            }
        }

        Sighting s = new Sighting(UUID.randomUUID());
        s.worldName = worldName;
        s.x = locX;
        s.y = locY;
        s.z = locZ;
        s.tick = tick;
        disturbances.add(s);
        if (disturbances.size() > MAX_ENTRIES) disturbances.remove(0);
    }

    public void expire(int tick) {
        sightings.values().removeIf(s -> tick - s.tick > SIGHTING_TTL);
        disturbances.removeIf(s -> tick - s.tick > DISTURBANCE_TTL);
    }

    public void clear() {
        sightings.clear();
        disturbances.clear();
    }

    public void markInvestigated(Sighting s) {
        if (s != null) s.investigated = true;
    }

    public Sighting lastSeen(UUID who) {
        return who == null ? null : sightings.get(who);
    }

    public Sighting bestLead(String worldName, double x, double y, double z,
                             int tick, double maxRangeSq) {
        Sighting best = null;
        int bestAge = Integer.MAX_VALUE;

        for (Sighting s : sightings.values()) {
            if (!leadUsable(s, worldName, x, y, z, tick, maxRangeSq, SIGHTING_TTL)) continue;
            int age = tick - s.tick;
            if (age < bestAge) {
                bestAge = age;
                best = s;
            }
        }
        if (best != null) return best;

        for (Sighting s : disturbances) {
            if (!leadUsable(s, worldName, x, y, z, tick, maxRangeSq, DISTURBANCE_TTL)) continue;
            int age = tick - s.tick;
            if (age < bestAge) {
                bestAge = age;
                best = s;
            }
        }
        return best;
    }

    private boolean leadUsable(Sighting s, String worldName, double x, double y, double z,
                               int tick, double maxRangeSq, int ttl) {
        if (s.investigated) return false;
        if (s.worldName == null || !s.worldName.equals(worldName)) return false;
        if (tick - s.tick > ttl) return false;

        double dx = s.x - x, dy = s.y - y, dz = s.z - z;
        double d = dx * dx + dy * dy + dz * dz;
        if (d < 4.0) {
            s.investigated = true;
            return false;
        }
        return d <= maxRangeSq;
    }
}
