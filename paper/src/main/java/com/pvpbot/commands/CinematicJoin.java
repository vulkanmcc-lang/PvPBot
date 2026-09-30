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

        if (!sub.equals("spawn")) {
            sendUsage(player);
            return true;
        }

        if (args.length < 3) {
            player.sendMessage("§cUsage: /pvpbot cinematic spawn <bots>");
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

    private static void sendUsage(Player player) {
        player.sendMessage("§6§lCinematic:");
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
