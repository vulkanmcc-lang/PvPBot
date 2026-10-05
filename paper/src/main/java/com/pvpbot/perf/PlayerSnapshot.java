package com.pvpbot.perf;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerSnapshot {
    public static final class WorldView {
        public Player[] players = new Player[16];
        public double[] x = new double[16];
        public double[] y = new double[16];
        public double[] z = new double[16];
        public int count = 0;

        void reset() {
            for (int i = 0; i < count; i++) players[i] = null;
            count = 0;
        }

        void add(Player p, double px, double py, double pz) {
            if (count == players.length) grow();
            players[count] = p;
            x[count] = px;
            y[count] = py;
            z[count] = pz;
            count++;
        }

        private void grow() {
            int n = players.length * 2;
            Player[] np = new Player[n];
            double[] nx = new double[n];
            double[] ny = new double[n];
            double[] nz = new double[n];
            System.arraycopy(players, 0, np, 0, count);
            System.arraycopy(x, 0, nx, 0, count);
            System.arraycopy(y, 0, ny, 0, count);
            System.arraycopy(z, 0, nz, 0, count);
            players = np; x = nx; y = ny; z = nz;
        }
    }

    private static final Map<UUID, WorldView> VIEWS = new HashMap<>();
    private static final WorldView EMPTY = new WorldView();

    private static Player[] realPlayers = new Player[16];
    private static double[] realX = new double[16];
    private static double[] realY = new double[16];
    private static double[] realZ = new double[16];
    private static int realCount = 0;

    private PlayerSnapshot() {
    }

    public static void rebuild(java.util.Set<UUID> botUuids) {
        for (WorldView v : VIEWS.values()) v.reset();
        for (int i = 0; i < realCount; i++) realPlayers[i] = null;
        realCount = 0;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead()) continue;

            double px, py, pz;
            try {
                var handle = ((CraftPlayer) p).getHandle();
                px = handle.getX();
                py = handle.getY();
                pz = handle.getZ();
            } catch (Throwable t) {
                org.bukkit.Location loc = p.getLocation();
                px = loc.getX();
                py = loc.getY();
                pz = loc.getZ();
            }

            boolean isBot = botUuids != null && botUuids.contains(p.getUniqueId());
            if (!isBot) {
                if (realCount == realPlayers.length) growReal();
                realPlayers[realCount] = p;
                realX[realCount] = px;
                realY[realCount] = py;
                realZ[realCount] = pz;
                realCount++;
            }

            GameMode mode = p.getGameMode();
            if (mode == GameMode.SPECTATOR || mode == GameMode.CREATIVE) continue;

            World w = p.getWorld();
            VIEWS.computeIfAbsent(w.getUID(), k -> new WorldView()).add(p, px, py, pz);
        }
    }

    private static void growReal() {
        int n = realPlayers.length * 2;
        Player[] np = new Player[n];
        double[] nx = new double[n];
        double[] ny = new double[n];
        double[] nz = new double[n];
        System.arraycopy(realPlayers, 0, np, 0, realCount);
        System.arraycopy(realX, 0, nx, 0, realCount);
        System.arraycopy(realY, 0, ny, 0, realCount);
        System.arraycopy(realZ, 0, nz, 0, realCount);
        realPlayers = np; realX = nx; realY = ny; realZ = nz;
    }

    public static WorldView forWorld(World world) {
        if (world == null) return EMPTY;
        WorldView v = VIEWS.get(world.getUID());
        return v == null ? EMPTY : v;
    }

    public static Player[] getRealPlayers() {
        return realPlayers;
    }

    public static int getRealCount() {
        return realCount;
    }

    public static double nearestRealPlayerDistSq(World world, double x, double y, double z) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < realCount; i++) {
            Player p = realPlayers[i];
            if (p == null || p.getWorld() != world) continue;
            double dx = realX[i] - x;
            double dy = realY[i] - y;
            double dz = realZ[i] - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }
}
