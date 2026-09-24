package com.pvpbot.commands;

import com.pvpbot.BotManager;
import com.pvpbot.NameGenerator;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

final class PortalWave {
    private static final double PORTAL_ARC_SPACING = 5.0;
    private static final double MIN_RADIUS = 6.0;

    private static final double DEFAULT_RADIUS = 100.0;
    private static final double MAX_RADIUS = 512.0;

    private static final int DEFAULT_HEIGHT = 3;
    private static final int MAX_HEIGHT = 40;

    private static final int LINGER_TICKS = 60;

    private static final int MAX_PORTALS = 32;
    private static final int MAX_BOTS = 200;

    private static final List<BukkitRunnable> ACTIVE = new ArrayList<>();

    private static final List<PortalStructure> STANDING = new ArrayList<>();

    private PortalWave() {
    }

    static boolean handle(PvPBotPlugin plugin, Player player, String[] args, BotManager manager) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            int n = cancelAll();
            player.sendMessage(n == 0
                    ? "\u00a77No portal waves are running."
                    : "\u00a7aStopped \u00a7e" + n + "\u00a7a wave(s). \u00a77Portals left standing \u00a78(/pvpbot portals close)");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("close")) {
            int n = closeAll();
            player.sendMessage(n == 0
                    ? "\u00a77No portals from this session are standing."
                    : "\u00a7aClosed \u00a7e" + n + "\u00a7a portal(s).");
            return true;
        }

        if (args.length < 4) {
            player.sendMessage("\u00a7cUsage: \u00a7f/pvpbot portals <random|normal> <portals> <bots> [faction] [delay] [kit <name>] [r<radius>] [h<height>]");
            player.sendMessage("\u00a77Example: \u00a7f/pvpbot portals random 10 25 test 0.5s kit diamond");
            return true;
        }

        final NameGenerator.NameStyle nameStyle;
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "random" -> nameStyle = NameGenerator.NameStyle.ALT;
            case "normal" -> nameStyle = NameGenerator.NameStyle.CLEAN;
            default -> {
                player.sendMessage("\u00a7cChoose a portal name mode: \u00a7frandom \u00a7cor \u00a7fnormal\u00a7c.");
                return true;
            }
        }

        int portals = parsePositive(args[2]);
        int bots = parsePositive(args[3]);
        if (portals < 1 || bots < 1) {
            player.sendMessage("\u00a7cPortal count and bot count must both be whole numbers above zero.");
            return true;
        }
        if (portals > MAX_PORTALS) {
            player.sendMessage("\u00a7eCapped portals at \u00a7f" + MAX_PORTALS + "\u00a7e.");
            portals = MAX_PORTALS;
        }
        if (bots > MAX_BOTS) {
            player.sendMessage("\u00a7eCapped bots at \u00a7f" + MAX_BOTS + "\u00a7e.");
            bots = MAX_BOTS;
        }

        if (portals > bots) portals = bots;

        String faction = null;
        String kit = null;
        int delayTicks = 10;
        double radius = DEFAULT_RADIUS;
        int height = DEFAULT_HEIGHT;

        com.pvpbot.KitManager kits = plugin.getKitManager();

        for (int i = 4; i < args.length; i++) {
            String tok = args[i];

            Double r = parseRadius(tok);
            if (r != null) {
                radius = Math.min(MAX_RADIUS, Math.max(MIN_RADIUS, r));
                continue;
            }

            Integer h = parseHeight(tok);
            if (h != null) {
                height = Math.min(MAX_HEIGHT, Math.max(0, h));
                continue;
            }

            if (tok.equalsIgnoreCase("kit") && i + 1 < args.length) {
                kit = args[++i];
                continue;
            }
            if (tok.regionMatches(true, 0, "kit:", 0, 4) && tok.length() > 4) {
                kit = tok.substring(4);
                continue;
            }

            Integer parsed = parseDelayTicks(tok);
            if (parsed != null) {
                delayTicks = parsed;
                continue;
            }

            if (!tok.equalsIgnoreCase("none") && !tok.equals("-")) {
                faction = tok;
            }
        }

        if (kit != null && kits != null && !kits.kitExists(kit)) {
            player.sendMessage("\u00a7cNo kit named \u00a7e" + kit + "\u00a7c. Spawning without one.");
            kit = null;
        }

        if (faction != null && !manager.factionExists(faction)) {
            manager.createFaction(faction);
            player.sendMessage("\u00a77Created faction \u00a7f" + faction + "\u00a77.");
        }

        Location centre = player.getLocation();
        World world = centre.getWorld();
        if (world == null) {
            player.sendMessage("\u00a7cCould not read your world.");
            return true;
        }

        List<Location> ring = buildRing(world, centre, portals, radius);
        int[] quota = distribute(bots, portals);

        List<PortalStructure> structures = new ArrayList<>(portals);
        for (int i = 0; i < portals; i++) structures.add(null);

        player.sendMessage("\u00a75\u00a7lPortals \u00a7ropening \u00a7e" + portals
                + "\u00a77 portals at \u00a7e" + (int) radius + "\u00a77 blocks, \u00a7e"
                + bots + "\u00a77 bots"
                + "\u00a77 using \u00a7e" + (nameStyle == NameGenerator.NameStyle.ALT ? "random" : "normal")
                + "\u00a77 names"
                + (faction == null ? "" : " for \u00a7f" + faction)
                + (kit == null ? "" : "\u00a77 with kit \u00a7f" + kit)
                + "\u00a77, \u00a7e" + fmtDelay(delayTicks) + "\u00a77 apart.");
        player.sendMessage("\u00a78/pvpbot portals stop \u00a77halts spawning, \u00a78/pvpbot portals close \u00a77removes the ring");

        Wave wave = new Wave(plugin, manager, ring, structures, quota, faction, kit, nameStyle,
                delayTicks, height, centre.clone());
        wave.runTaskTimer(plugin, 0L, 1L);
        ACTIVE.add(wave);
        return true;
    }

    static int cancelAll() {
        int n = 0;
        for (BukkitRunnable r : new ArrayList<>(ACTIVE)) {
            try {
                if (r instanceof Wave w) w.handOverStructures();
                r.cancel();
                n++;
            } catch (IllegalStateException ignored) {
            }
        }
        ACTIVE.clear();
        return n;
    }

    static int closeAll() {
        int n = 0;
        for (PortalStructure ps : new ArrayList<>(STANDING)) {
            if (ps == null) continue;
            World w = ps.world();
            if (w != null) {
                spawnParticle(w, REVERSE_PORTAL, ps.centre(), 40, 0.6, 1.0, 0.6, 0.12);
                w.playSound(ps.centre(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.6f);
            }
            ps.restore();
            n++;
        }
        STANDING.clear();
        return n;
    }

    public static List<PortalStructure> standing() {
        return STANDING;
    }

    private static List<Location> buildRing(World world, Location centre, int portals,
                                            double requested) {
        final double SECTOR_LO = 0.2;
        final double SECTOR_HI = 0.8;

        final double WORST_CASE_GAP = 1.0 - (SECTOR_HI - SECTOR_LO);

        final double JITTER_HEADROOM = 1.6;

        double minRadius = JITTER_HEADROOM * portals * PORTAL_ARC_SPACING
                / (2.0 * Math.PI * WORST_CASE_GAP);
        double radius = Math.max(requested, minRadius);

        List<Location> out = new ArrayList<>(portals);

        java.util.concurrent.ThreadLocalRandom rng =
                java.util.concurrent.ThreadLocalRandom.current();

        double sector = (2.0 * Math.PI) / portals;

        double spin = rng.nextDouble(0.0, 2.0 * Math.PI);

        for (int i = 0; i < portals; i++) {
            double angle = spin + sector * (i + rng.nextDouble(SECTOR_LO, SECTOR_HI));
            double r = radius * rng.nextDouble(0.7, 1.3);

            double x = centre.getX() + Math.cos(angle) * r;
            double z = centre.getZ() + Math.sin(angle) * r;

            Location spot = groundAt(world, x, centre.getY(), z);

            float toCentre = (float) Math.toDegrees(Math.atan2(
                    -(centre.getX() - x), centre.getZ() - z));
            spot.setYaw(toCentre + (float) rng.nextDouble(-12.0, 12.0));
            out.add(spot);
        }
        return out;
    }

    private static Location groundAt(World world, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int start = (int) Math.floor(y);

        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return new Location(world, bx + 0.5, y, bz + 0.5);
        }

        for (int spread = 0; spread <= 24; spread++) {
            for (int sign = 0; sign < 2; sign++) {
                int by = sign == 0 ? start + spread : start - spread;
                if (by < world.getMinHeight() + 1 || by > world.getMaxHeight() - 2) continue;
                if (standable(world, bx, by, bz)) {
                    return new Location(world, bx + 0.5, by, bz + 0.5);
                }
            }
        }

        return new Location(world, bx + 0.5, world.getHighestBlockYAt(bx, bz) + 1.0, bz + 0.5);
    }

    private static boolean standable(World world, int x, int y, int z) {
        Block floor = world.getBlockAt(x, y - 1, z);
        if (!floor.getType().isSolid()) return false;
        return world.getBlockAt(x, y, z).isPassable()
                && world.getBlockAt(x, y + 1, z).isPassable();
    }

    private static int[] distribute(int bots, int portals) {
        int[] quota = new int[portals];
        int base = bots / portals;
        int extra = bots % portals;

        int prev = 0;
        for (int i = 0; i < portals; i++) {
            int cum = ((i + 1) * extra) / portals;
            quota[i] = base + (cum - prev);
            prev = cum;
        }
        return quota;
    }

    private static final class Wave extends BukkitRunnable {
        private final PvPBotPlugin plugin;
        private final BotManager manager;
        private final List<Location> ring;
        private final List<PortalStructure> structures;
        private final int[] remaining;

        private final List<PvPBot> dropping = new ArrayList<>();
        private int closeAtTick = -1;
        private final String faction;
        private final String kit;
        private final NameGenerator.NameStyle nameStyle;
        private final int delayTicks;
        private final int height;
        private final Location centre;

        private int cursor = 0;
        private int ticks = 0;
        private int nextSpawnTick = 0;
        private int left;

        Wave(PvPBotPlugin plugin, BotManager manager, List<Location> ring,
             List<PortalStructure> structures, int[] quota, String faction,
             String kit, NameGenerator.NameStyle nameStyle, int delayTicks, int height,
             Location centre) {
            this.plugin = plugin;
            this.manager = manager;
            this.ring = ring;
            this.structures = structures;
            this.remaining = quota;
            this.faction = faction;
            this.kit = kit;
            this.nameStyle = nameStyle;
            this.delayTicks = delayTicks;
            this.height = height;
            this.centre = centre;
            int sum = 0;
            for (int q : quota) sum += q;
            this.left = sum;
        }

        @Override
        public void run() {
            ticks++;
            catchFallers();

            if (ticks % 40 == 0) {
                for (int i = 0; i < ring.size(); i++) {
                    if (structures.get(i) == null) continue;
                    World w = ring.get(i).getWorld();
                    if (w != null) {
                        w.playSound(ring.get(i), Sound.BLOCK_PORTAL_AMBIENT, 0.35f, 1.0f);
                    }
                }
            }

            if (left <= 0) {
                if (closeAtTick < 0) closeAtTick = ticks + LINGER_TICKS;
                if (ticks >= closeAtTick && dropping.isEmpty()) finish();
                return;
            }

            if (ticks < nextSpawnTick) return;

            int tries = 0;
            while (remaining[cursor] <= 0 && tries++ <= ring.size()) {
                cursor = (cursor + 1) % ring.size();
            }
            if (remaining[cursor] <= 0) {
                finish();
                return;
            }

            openPortal(cursor);
            spawnFrom(cursor);
            remaining[cursor]--;
            left--;
            cursor = (cursor + 1) % ring.size();

            nextSpawnTick = ticks + delayTicks;
        }

        private void openPortal(int index) {
            if (structures.get(index) != null) return;

            Location spot = ring.get(index);

            int h = height <= 0 ? 0 : Math.max(0,
                    height + java.util.concurrent.ThreadLocalRandom.current()
                            .nextInt(-1, Math.max(2, height)));

            PortalStructure ps = PortalStructure.at(spot,
                    centre.getX() - spot.getX(), centre.getZ() - spot.getZ(), h);
            if (ps == null || !ps.build()) return;

            structures.set(index, ps);
            World w = spot.getWorld();
            if (w != null) {
                w.playSound(spot, Sound.BLOCK_PORTAL_TRIGGER, 0.7f, 0.8f);
                spawnParticle(w, REVERSE_PORTAL, ps.centre(), 60, 0.6, 1.0, 0.6, 0.1);
            }
        }

        private void spawnFrom(int index) {
            Location spot = ring.get(index);
            PortalStructure ps = structures.get(index);
            Location emerge = ps != null ? ps.emergePoint() : spot.clone();

            emerge.setYaw(spot.getYaw());
            emerge.setPitch(0f);

            try {
                final PvPBot[] made = new PvPBot[1];
                PvPBot.spawningQuietly(() ->
                        made[0] = manager.spawnBot(emerge.clone(), faction,
                                nameStyle));
                PvPBot bot = made[0];
                if (bot == null) return;

                if (kit != null) {
                    Player bp = bot.getBukkitPlayer();
                    if (bp != null) bp.getInventory().clear();
                    bot.equipKit(kit);
                    if (bp != null) bp.updateInventory();
                }

                dropping.add(bot);

                World w = emerge.getWorld();
                if (w == null) return;
                w.playSound(emerge, Sound.ENTITY_PLAYER_TELEPORT, 0.8f, 1.0f);
                w.playSound(emerge, Sound.BLOCK_PORTAL_TRIGGER, 0.25f, 1.7f);
                spawnParticle(w, REVERSE_PORTAL, ps != null ? ps.centre() : emerge,
                        50, 0.4, 0.9, 0.4, 0.07);
            } catch (Throwable t) {
                plugin.getLogger().warning("Portal spawn failed: " + t);
            }
        }

        private void catchFallers() {
            if (dropping.isEmpty()) return;
            java.util.Iterator<PvPBot> it = dropping.iterator();
            while (it.hasNext()) {
                PvPBot bot = it.next();
                Player bp = (bot == null || !bot.isAlive()) ? null : bot.getBukkitPlayer();
                if (bp == null) {
                    it.remove();
                    continue;
                }
                if (bp.isOnGround()) {
                    bp.setFallDistance(0f);
                    it.remove();
                } else {
                    bp.setFallDistance(0f);
                }
            }
        }

        void handOverStructures() {
            for (PortalStructure ps : structures) {
                if (ps != null && !STANDING.contains(ps)) STANDING.add(ps);
            }
            structures.clear();
            dropping.clear();
        }

        private void finish() {
            handOverStructures();
            ACTIVE.remove(this);
            cancel();
        }
    }

    private static final Particle REVERSE_PORTAL = resolve("REVERSE_PORTAL", "PORTAL", "CRIT");

    private static Particle resolve(String... names) {
        for (String n : names) {
            try {
                return Particle.valueOf(n);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static void spawnParticle(World w, Particle p, Location at, int count,
                                      double dx, double dy, double dz, double speed) {
        if (p == null) return;
        try {
            w.spawnParticle(p, at, count, dx, dy, dz, speed);
        } catch (Throwable ignored) {
        }
    }

    private static Double parseRadius(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        String num;
        if (s.startsWith("radius:")) num = s.substring(7);
        else if (s.startsWith("radius")) num = s.substring(6);
        else if (s.startsWith("r=")) num = s.substring(2);
        else if (s.startsWith("r") && s.length() > 1) num = s.substring(1);
        else return null;

        try {
            double v = Double.parseDouble(num);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseHeight(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        String num;
        if (s.startsWith("height:")) num = s.substring(7);
        else if (s.startsWith("height")) num = s.substring(6);
        else if (s.startsWith("h=")) num = s.substring(2);
        else if (s.startsWith("h") && s.length() > 1) num = s.substring(1);
        else return null;

        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parsePositive(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Integer parseDelayTicks(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);

        double mult;
        String num;
        if (s.endsWith("ms")) {
            num = s.substring(0, s.length() - 2);
            mult = 20.0 / 1000.0;
        } else if (s.endsWith("t") || s.endsWith("tick") || s.endsWith("ticks")) {
            num = s.substring(0, s.length() - (s.endsWith("t") ? 1 : s.endsWith("tick") ? 4 : 5));
            mult = 1.0;
        } else if (s.endsWith("s") || s.endsWith("sec")) {
            num = s.substring(0, s.length() - (s.endsWith("s") ? 1 : 3));
            mult = 20.0;
        } else {
            num = s;
            mult = 20.0;
        }

        try {
            double v = Double.parseDouble(num);
            if (v < 0) return null;

            long ticks = Math.round(v * mult);
            if (v > 0 && ticks < 1) ticks = 1;
            return (int) Math.min(ticks, 20L * 60L);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmtDelay(int ticks) {
        if (ticks <= 0) return "no delay";
        return String.format("%.2fs", ticks / 20.0).replace(".00", "");
    }
}
