package com.pvpbot.commands;

import com.pvpbot.BotManager;
import com.pvpbot.BotSettings;
import com.pvpbot.FormationManager;
import com.pvpbot.KitManager;
import com.pvpbot.NameGenerator;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class CinematicJoin {
    private static final int MAX_BOTS = 200;
    private static final List<Wave> ACTIVE = new ArrayList<>();

    private CinematicJoin() {
    }

    public static boolean handle(Player player, String[] args, PvPBotPlugin plugin, BotManager manager) {
        if (args.length < 2) {
            sendUsage(player);
            return true;
        }

        String sub = args[1].toLowerCase();

        if (sub.equals("stop")) {
            int n = cancelAll();
            player.sendMessage(n > 0
                    ? "§eStopped " + n + " cinematic join sequence(s)."
                    : "§7No cinematic join sequence running.");
            return true;
        }

        if (sub.equals("circle")) {
            return handleCircle(player, args, plugin, manager);
        }

        if (sub.equals("markcoordinates") || sub.equals("mark")) {
            List<Location> marks = loadMarks(plugin, player.getUniqueId());
            Location here = player.getLocation().clone();
            marks.add(here);
            saveMarks(plugin, player.getUniqueId(), marks);
            player.sendMessage(String.format(java.util.Locale.ROOT,
                    "§d§lCinematic §r§7- marked §f%d, %d, %d§7 (§e%d§7 spot%s). "
                            + "§f/pvpbot cinematic spawn <kit>§7 puts a bot on each.",
                    here.getBlockX(), here.getBlockY(), here.getBlockZ(), marks.size(), marks.size() == 1 ? "" : "s"));
            return true;
        }

        if (sub.equals("marks")) {
            List<Location> marks = loadMarks(plugin, player.getUniqueId());
            if (marks.isEmpty()) {
                player.sendMessage("§7No marked spots - stand somewhere and §f/pvpbot cinematic markcoordinates§7.");
                return true;
            }
            player.sendMessage("§d§lCinematic spots §r§7(" + marks.size() + "):");
            for (int i = 0; i < marks.size(); i++) {
                Location l = marks.get(i);
                player.sendMessage(String.format(java.util.Locale.ROOT, "  §8%d. §f%s %d, %d, %d",
                        i + 1, l.getWorld() == null ? "?" : l.getWorld().getName(),
                        l.getBlockX(), l.getBlockY(), l.getBlockZ()));
            }
            return true;
        }

        if (sub.equals("clearmarks") || sub.equals("unmark")) {
            List<Location> marks = loadMarks(plugin, player.getUniqueId());
            if (sub.equals("unmark") && !marks.isEmpty()) {
                marks.remove(marks.size() - 1);
                saveMarks(plugin, player.getUniqueId(), marks);
                player.sendMessage("§7Removed the last spot (§e" + marks.size() + "§7 left).");
            } else {
                saveMarks(plugin, player.getUniqueId(), new ArrayList<>());
                player.sendMessage("§7Cleared all your cinematic spots.");
            }
            return true;
        }

        if (!sub.equals("spawn")) {
            sendUsage(player);
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§cUsage: /pvpbot cinematic spawn <kit> §7(one bot per marked spot) "
                    + "§cor §f/pvpbot cinematic spawn <bots>");
            return true;
        }

        // "spawn <kit>": a bot on every marked spot.
        if (!args[2].matches("\\d+")) {
            return spawnOnMarks(player, args[2], plugin, manager);
        }

        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid bot count.");
            return true;
        }
        if (count < 1) {
            player.sendMessage("§cBot count must be at least 1.");
            return true;
        }
        if (count > MAX_BOTS) {
            player.sendMessage("§eCapped at " + MAX_BOTS + " bots.");
            count = MAX_BOTS;
        }

        Location spawn = player.getWorld().getSpawnLocation();
        Wave wave = new Wave(manager, spawn, count, player.getUniqueId());
        wave.runTaskTimer(plugin, 0L, 1L);
        ACTIVE.add(wave);

        player.sendMessage("§d§lCinematic §r§7- trickling §e" + count
                + "§7 bot(s) into spawn, 0.5-1s apart. §f/pvpbot cinematic stop§7 to cut it short.");
        return true;
    }

    // ---- marked spots: /pvpbot cinematic markcoordinates, spawn <kit>

    private static String marksPath(UUID player) {
        return "cinematic-marks." + player;
    }

    private static List<Location> loadMarks(PvPBotPlugin plugin, UUID player) {
        List<Location> out = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList(marksPath(player))) {
            String[] p = line.split(",");
            if (p.length < 6) continue;
            org.bukkit.World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            try {
                out.add(new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void saveMarks(PvPBotPlugin plugin, UUID player, List<Location> marks) {
        List<String> lines = new ArrayList<>();
        for (Location l : marks) {
            if (l.getWorld() == null) continue;
            lines.add(String.format(java.util.Locale.ROOT, "%s,%.3f,%.3f,%.3f,%.1f,%.1f",
                    l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        }
        plugin.getConfig().set(marksPath(player), lines.isEmpty() ? null : lines);
        plugin.saveConfig();
    }

    private static boolean spawnOnMarks(Player player, String kit, PvPBotPlugin plugin, BotManager manager) {
        KitManager kitManager = plugin.getKitManager();
        if (!kitManager.kitExists(kit)) {
            player.sendMessage("§cKit '§e" + kit + "§c' does not exist.");
            return true;
        }
        List<Location> marks = loadMarks(plugin, player.getUniqueId());
        if (marks.isEmpty()) {
            player.sendMessage("§cNo marked spots - stand somewhere and §f/pvpbot cinematic markcoordinates§c first.");
            return true;
        }
        if (marks.size() > MAX_BOTS) {
            player.sendMessage("§eCapped at " + MAX_BOTS + " bots.");
            marks = marks.subList(0, MAX_BOTS);
        }

        // They join whatever faction the player running this is in - or, if
        // they're in none, a shared "cinematic" faction, so the bots are
        // always on the same side and never treat each other as targets.
        String playerFaction = manager.getPlayerFaction(player.getUniqueId());
        final String faction = playerFaction != null ? playerFaction : CINEMATIC_FACTION;
        if (!manager.factionExists(faction)) manager.createFaction(faction);

        List<PvPBot> spawned = new ArrayList<>();
        List<Location> spots = marks;
        // Random-style names, black skins.
        PvPBot.spawningWithBlackSkin(() -> {
            for (Location spot : spots) {
                PvPBot bot = manager.spawnBot(spot.clone(), faction, NameGenerator.NameStyle.ALT);
                if (bot == null) continue;
                bot.equipKit(kit);
                // Frozen: no moving, no fighting, nothing - just standing
                // there. Weapons down on top, so even a bot that somehow got
                // unfrozen won't pick a fight on its own.
                freeze(manager, bot);
                spawned.add(bot);
            }
        });
        if (spawned.isEmpty()) {
            player.sendMessage("§cCouldn't spawn any bots there.");
            return true;
        }

        new WatchLeader(manager, playerFaction, player.getUniqueId(), spawned).runTaskTimer(plugin, 1L, 1L);

        // Two seconds on, make sure every one of them really is frozen - put
        // any that isn't back, and say so.
        UUID owner = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int fixed = 0;
            for (PvPBot b : spawned) {
                if (!b.isAlive()) continue;
                BotSettings bs = manager.getBotSettings(b.getUUID());
                if (bs != null && bs.isFrozen() && !bs.isHostile()) continue;
                freeze(manager, b);
                fixed++;
            }
            Player p = Bukkit.getPlayer(owner);
            if (p != null && fixed > 0) {
                p.sendMessage("§e" + fixed + " cinematic bot(s) had lost their freeze - frozen again.");
            }
        }, 40L);
        player.sendMessage("§d§lCinematic §r§7- §e" + spawned.size() + "§7 frozen bot(s) on your spots with kit §e"
                + kit.toLowerCase() + "§7 in faction §e" + faction.toLowerCase()
                + "§7 - they look around confused for a bit, then watch "
                + (playerFaction != null ? "the faction leader" : "you") + ".");
        player.sendMessage("§8Unfreeze them when the scene's done: /pvpbot set "
                + faction.toLowerCase() + " frozen false");
        return true;
    }

    private static final String CINEMATIC_FACTION = "cinematic";

    private static void freeze(BotManager manager, PvPBot bot) {
        BotSettings bs = manager.getBotSettings(bot.getUUID());
        bs.setFrozen(true);
        bs.setHostile(false);
        var ctx = bot.getAI().getContext();
        ctx.holdFire = true;
        ctx.target = null;
        bot.setForcedTarget(null);
    }

    // How long a freshly spawned bot looks around, lost, before it settles
    // its eyes on the leader.
    private static final int CONFUSED_MIN_TICKS = 100;
    private static final int CONFUSED_MAX_TICKS = 200;

    // Frozen bots first glance around like they just woke up somewhere they
    // don't recognise - quick head turns, looking up and down, the odd
    // double-take - then keep their eyes on their faction's leader (or
    // whoever ran the command, if the faction has no leader online). A bot
    // drops out once it's unfrozen or gone.
    private static final class WatchLeader extends BukkitRunnable {
        private final BotManager manager;
        private final String faction;
        private final UUID fallback;
        private final List<PvPBot> bots;
        private final java.util.Map<PvPBot, Integer> confusedLeft = new java.util.HashMap<>();
        private final java.util.Map<PvPBot, Integer> nextTurn = new java.util.HashMap<>();
        private final java.util.Map<PvPBot, float[]> glance = new java.util.HashMap<>();

        WatchLeader(BotManager manager, String faction, UUID fallback, List<PvPBot> bots) {
            this.manager = manager;
            this.faction = faction;
            this.fallback = fallback;
            this.bots = new ArrayList<>(bots);
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (PvPBot b : bots) {
                confusedLeft.put(b, r.nextInt(CONFUSED_MIN_TICKS, CONFUSED_MAX_TICKS + 1));
                nextTurn.put(b, r.nextInt(2, 10));
            }
        }

        // One tick of looking around lost; false once that's over.
        private boolean lookAroundConfused(PvPBot b, Player bp) {
            int left = confusedLeft.merge(b, -1, Integer::sum);
            if (left <= 0) return false;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            int turn = nextTurn.merge(b, -1, Integer::sum);
            float[] aim = glance.get(b);
            if (aim == null || turn <= 0) {
                float newYaw;
                if (aim != null && r.nextDouble() < 0.25) {
                    // Double-take: whip back the way it just looked.
                    newYaw = aim[0] + (r.nextBoolean() ? 160f : -160f) + (float) r.nextGaussian() * 15f;
                } else {
                    newYaw = bp.getLocation().getYaw() + (float) ((r.nextDouble() - 0.5) * 300.0);
                }
                float newPitch = (float) Math.max(-50.0, Math.min(55.0, r.nextGaussian() * 25.0));
                aim = new float[]{newYaw, newPitch};
                glance.put(b, aim);
                nextTurn.put(b, r.nextInt(12, 36));
            }
            b.getAI().getContext().requestLook(aim[0], aim[1], com.pvpbot.ai.BotAIContext.LOOK_CRITICAL - 1, false);
            return true;
        }

        @Override
        public void run() {
            Player watch = null;
            if (faction != null) {
                UUID leader = manager.getFactionLeader(faction);
                if (leader != null) watch = Bukkit.getPlayer(leader);
            }
            if (watch == null || !watch.isOnline()) watch = Bukkit.getPlayer(fallback);

            for (java.util.Iterator<PvPBot> it = bots.iterator(); it.hasNext(); ) {
                PvPBot b = it.next();
                Player bp = b.getBukkitPlayer();
                BotSettings bs = manager.getBotSettings(b.getUUID());
                if (!b.isAlive() || bp == null || bs == null || !bs.isFrozen()) {
                    // Unfrozen for the next scene: weapons back up too.
                    if (b.isAlive() && bs != null && !bs.isFrozen()) b.getAI().getContext().holdFire = false;
                    it.remove();
                    continue;
                }
                if (lookAroundConfused(b, bp)) continue;
                if (watch == null || watch.getWorld() != bp.getWorld()
                        || watch.getUniqueId().equals(bp.getUniqueId())) continue;
                Location eye = watch.getEyeLocation();
                Location be = bp.getEyeLocation();
                double dx = eye.getX() - be.getX(), dy = eye.getY() - be.getY(), dz = eye.getZ() - be.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                b.getAI().getContext().requestLook(yaw, pitch, com.pvpbot.ai.BotAIContext.LOOK_CRITICAL - 1, false);
            }
            if (bots.isEmpty()) cancel();
        }
    }

    private static void sendUsage(Player player) {
        player.sendMessage("§6§lCinematic:");
        player.sendMessage("§e /pvpbot cinematic markcoordinates §7- mark where you stand (as many as you like)");
        player.sendMessage("§e /pvpbot cinematic spawn <kit> §7- a frozen, black-skinned, random-named bot with that kit on every mark, in your faction - looks around confused, then watches its leader");
        player.sendMessage("§e /pvpbot cinematic marks §7/ §eunmark §7/ §eclearmarks §7- list / drop the last / drop all marks");
        player.sendMessage("§e /pvpbot cinematic spawn <bots> §7- simulates players joining at world spawn");
        player.sendMessage("§e /pvpbot cinematic circle <amount> <faction> <kit> §7- rings of invisible bots around you, armor in their pack");
        player.sendMessage("§e /pvpbot cinematic stop §7- cancels the current sequence");
    }

    private static final int MAX_CIRCLE_BOTS = 100;

    private static boolean handleCircle(Player player, String[] args, PvPBotPlugin plugin, BotManager manager) {
        if (args.length < 5) {
            player.sendMessage("§cUsage: /pvpbot cinematic circle <amount> <faction> <kit>");
            return true;
        }

        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid bot count.");
            return true;
        }
        if (count < 1) {
            player.sendMessage("§cBot count must be at least 1.");
            return true;
        }
        if (count > MAX_CIRCLE_BOTS) {
            player.sendMessage("§eCapped at " + MAX_CIRCLE_BOTS + " bots.");
            count = MAX_CIRCLE_BOTS;
        }

        String faction = args[3];
        String kit = args[4];

        KitManager kitManager = plugin.getKitManager();
        if (!kitManager.kitExists(kit)) {
            player.sendMessage("§cKit '§e" + kit + "§c' does not exist.");
            return true;
        }

        boolean created = !manager.factionExists(faction);

        Location anchor = player.getLocation();
        double spacing = Math.max(FormationManager.DEFAULT_SPACING, (count * 2.2) / (2 * Math.PI));
        List<Location> slots = FormationManager.compute(anchor, FormationManager.Shape.CIRCLE, count, spacing);

        int[] spawned = {0};
        int finalCount = count;
        PvPBot.spawningQuietly(() -> PvPBot.spawningWithBlackSkin(() -> PvPBot.spawningInvisible(() -> {
            for (int i = 0; i < finalCount && i < slots.size(); i++) {
                Location loc = slots.get(i).clone();
                org.bukkit.util.Vector toAnchor = anchor.toVector().subtract(loc.toVector());
                if (toAnchor.lengthSquared() > 0.0001) loc.setDirection(toAnchor);

                PvPBot bot = manager.spawnBot(loc, faction, NameGenerator.NameStyle.CLEAN);
                if (bot == null) continue;

                bot.equipKit(kit, false);
                // They should still fight - just not pick the fight. assistOnly
                // blocks proactive/opportunistic targeting unconditionally
                // (unlike noAutoTargetWhileIdle it doesn't stand down just
                // because the faction has a leader); the team-assist path
                // (teamTarget, on by default) still kicks them in when a
                // faction-mate or their leader actually takes a hit.
                BotSettings bs = manager.getBotSettings(bot.getUUID());
                bs.setHostile(true);
                bs.setAssistOnly(true);
                spawned[0]++;
            }
        })));

        if (created) player.sendMessage("§7Faction §e" + faction.toLowerCase()
                + "§7 didn't exist yet — created it.");
        player.sendMessage("§d§lCinematic §r§7- ringed §e" + spawned[0]
                + "§7 invisible bot(s) around you in faction §e" + faction.toLowerCase()
                + "§7, wearing kit §e" + kit.toLowerCase() + "§7 with armor stashed, not worn.");
        player.sendMessage("§8/pvpbot faction armor on " + faction.toLowerCase() + "§8 to have them put it on.");
        return true;
    }

    static int cancelAll() {
        int n = 0;
        for (Wave w : new ArrayList<>(ACTIVE)) {
            try {
                w.cancel();
                n++;
            } catch (IllegalStateException ignored) {
            }
        }
        ACTIVE.clear();
        return n;
    }

    private static final class Wave extends BukkitRunnable {
        private final BotManager manager;
        private final Location spawn;
        private final UUID starter;
        private int remaining;
        private int spawned = 0;
        private int ticks = 0;
        private int nextSpawnTick = 0;

        Wave(BotManager manager, Location spawn, int count, UUID starter) {
            this.manager = manager;
            this.spawn = spawn;
            this.remaining = count;
            this.starter = starter;
        }

        @Override
        public void run() {
            if (remaining <= 0) {
                finish();
                return;
            }

            if (ticks++ >= nextSpawnTick) {
                spawnOne();
                remaining--;
                spawned++;
                nextSpawnTick = ticks + ThreadLocalRandom.current().nextInt(10, 21);
            }
        }

        private static final double HOSTILE_CHANCE = 0.2;

        private void spawnOne() {
            double dx = (ThreadLocalRandom.current().nextDouble() - 0.5) * 3.0;
            double dz = (ThreadLocalRandom.current().nextDouble() - 0.5) * 3.0;
            Location loc = spawn.clone().add(dx, 0, dz);

            PvPBot bot = manager.spawnBot(loc, null, NameGenerator.NameStyle.CLEAN);
            if (bot == null) return;

            boolean hostile = ThreadLocalRandom.current().nextDouble() < HOSTILE_CHANCE;
            manager.getBotSettings(bot.getUUID()).setHostile(hostile);

        }

        private void finish() {
            ACTIVE.remove(this);
            cancel();

            Player p = Bukkit.getPlayer(starter);
            if (p != null) {
            }
        }
    }
}
