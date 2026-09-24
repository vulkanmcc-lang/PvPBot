package com.pvpbot.commands;

import com.pvpbot.BotSettings;
import com.pvpbot.BotDifficulty;
import com.pvpbot.BotManager;
import com.pvpbot.FormationManager;
import com.pvpbot.GroupProvider;
import com.pvpbot.KitManager;
import com.pvpbot.NameGenerator;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import com.pvpbot.WeightedSpec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PvPBotCommand implements CommandExecutor, TabCompleter {
    private final PvPBotPlugin plugin;

    public PvPBotCommand(PvPBotPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        BotManager manager = plugin.getBotManager();
        KitManager kitManager = plugin.getKitManager();

        String baseCmd = args[0].toLowerCase();

        switch (baseCmd) {
            case "spawn", "spawnrandom", "spawnneutral", "spawnfrozen" -> {
                NameGenerator.NameStyle style = baseCmd.equals("spawnrandom")
                        ? NameGenerator.NameStyle.ALT
                        : NameGenerator.NameStyle.CLEAN;
                boolean neutral = baseCmd.equals("spawnneutral");
                boolean frozen = baseCmd.equals("spawnfrozen");
                String faction = args.length >= 2 ? args[1] : null;
                String kit = args.length >= 3 ? args[2] : null;
                boolean created = faction != null && !manager.factionExists(faction);

                PvPBot bot = manager.spawnBot(player.getLocation(), faction, style);

                if (neutral) manager.getBotSettings(bot.getUUID()).setHostile(false);
                if (frozen) {
                    var st = manager.getBotSettings(bot.getUUID());
                    st.setFrozen(true);

                    st.setHostile(false);
                }

                boolean kitApplied = false;
                if (kit != null) {
                    if (!kitManager.kitExists(kit)) {
                        player.sendMessage("§cKit '§e" + kit + "§c' does not exist.");
                    } else {
                        bot.equipKit(kit);
                        kitApplied = true;
                    }
                }

                if (created) player.sendMessage("§7Faction §e" + faction.toLowerCase()
                        + "§7 didn't exist yet — created it.");
                player.sendMessage("§aSpawned "
                        + (frozen ? "§7frozen§a " : neutral ? "§7neutral§a " : "") + "PvPBot: §e" + bot.getName()
                        + (faction != null ? " §7in faction §e" + faction.toLowerCase() : "")
                        + (kitApplied ? " §7wearing kit §e" + kit.toLowerCase() : ""));
                if (frozen) player.sendMessage("§7A training dummy: it won't move or fight, "
                        + "but still takes damage and knockback. "
                        + "Use §f/pvpbot settings§7 to adjust it in the GUI.");
                else if (neutral) player.sendMessage("§7It wanders and won't fight. "
                        + "Use §f/pvpbot settings§7 to turn hostile back on.");
            }

            case "massspawn", "masspawn", "masspawnrandom", "masspawnneutral", "masspawnfrozen" -> {
                // Check if this is the new syntax: /pvpbot massspawn <number> void/normal/random [faction] [kit]
                // Or: /pvpbot massspawn <number> <faction> void/normal/random [kit]
                if (args.length >= 3) {
                    String modeArg = args[2].toLowerCase();
                    if (modeArg.equals("void") || modeArg.equals("normal") || modeArg.equals("random")) {
                        try {
                            int count = Integer.parseInt(args[1]);
                            if (count < 1) {
                                player.sendMessage("§cCount must be at least 1.");
                                return true;
                            }
                            count = Math.min(count, 50);

                            NameGenerator.NameStyle style;
                            if (modeArg.equals("void")) {
                                style = NameGenerator.NameStyle.VOID;
                            } else if (modeArg.equals("random")) {
                                style = NameGenerator.NameStyle.ALT;
                            } else {
                                style = NameGenerator.NameStyle.CLEAN;
                            }

                            String faction = args.length >= 4 ? args[3] : null;
                            String kit = args.length >= 5 ? args[4] : null;
                            boolean created = faction != null && !manager.factionExists(faction);

                            var bots = manager.massSpawn(player.getLocation(), count, faction, style);

                            if (kit != null) {
                                if (!kitManager.kitExists(kit)) {
                                    player.sendMessage("§cKit '§e" + kit + "§c' does not exist.");
                                } else {
                                    for (PvPBot b : bots) b.equipKit(kit);
                                }
                            }

                            if (created) player.sendMessage("§7Faction §e" + faction.toLowerCase()
                                    + "§7 didn't exist yet — created it.");

                            String modeDesc = modeArg.equals("void") ? "§7void§a " : "";
                            player.sendMessage("§aSpawned §e" + bots.size() + "§a " + modeDesc + "PvPBots"
                                    + (faction != null ? " §7in faction §e" + faction.toLowerCase() : "")
                                    + (kit != null ? " §7wearing kit §e" + kit.toLowerCase() : "")
                                    + "§a.");
                            return true;
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid number. Usage: /pvpbot massspawn <number> void/normal/random [faction] [kit]");
                            return true;
                        }
                    } else if (args.length >= 4 && manager.factionExists(modeArg)) {
                        // Syntax: /pvpbot massspawn <count> <faction> <mode> [kit]
                        String faction = modeArg;
                        String actualMode = args[3].toLowerCase();
                        if (!actualMode.equals("void") && !actualMode.equals("normal") && !actualMode.equals("random")) {
                            player.sendMessage("§cInvalid mode. Use: void, normal, or random");
                            return true;
                        }
                        try {
                            int count = Integer.parseInt(args[1]);
                            if (count < 1) {
                                player.sendMessage("§cCount must be at least 1.");
                                return true;
                            }
                            count = Math.min(count, 50);

                            NameGenerator.NameStyle style;
                            if (actualMode.equals("void")) {
                                style = NameGenerator.NameStyle.VOID;
                            } else if (actualMode.equals("random")) {
                                style = NameGenerator.NameStyle.ALT;
                            } else {
                                style = NameGenerator.NameStyle.CLEAN;
                            }

                            String kit = args.length >= 5 ? args[4] : null;
                            boolean created = faction != null && !manager.factionExists(faction);

                            var bots = manager.massSpawn(player.getLocation(), count, faction, style);

                            if (kit != null) {
                                if (!kitManager.kitExists(kit)) {
                                    player.sendMessage("§cKit '§e" + kit + "§c' does not exist.");
                                } else {
                                    for (PvPBot b : bots) b.equipKit(kit);
                                }
                            }

                            if (created) player.sendMessage("§7Faction §e" + faction.toLowerCase()
                                    + "§7 didn't exist yet — created it.");

                            String modeDesc = actualMode.equals("void") ? "§7void§a " : "";
                            player.sendMessage("§aSpawned §e" + bots.size() + "§a " + modeDesc + "PvPBots"
                                    + (faction != null ? " §7in faction §e" + faction.toLowerCase() : "")
                                    + (kit != null ? " §7wearing kit §e" + kit.toLowerCase() : "")
                                    + "§a.");
                            return true;
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cInvalid number. Usage: /pvpbot massspawn <number> <faction> void/normal/random [kit]");
                            return true;
                        }
                    }
                }

                // Original massspawn logic
                NameGenerator.NameStyle style = baseCmd.equals("masspawnrandom")
                        ? NameGenerator.NameStyle.ALT
                        : NameGenerator.NameStyle.CLEAN;
                boolean neutral = baseCmd.endsWith("neutral");
                boolean frozenSpawn = baseCmd.endsWith("frozen");

                int count = 5;
                if (args.length >= 2) {
                    try {
                        count = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cInvalid number. Usage: /pvpbot masspawn <count> [faction] [kit] [grid] [spacing] [difficulty]");
                        return true;
                    }
                    if (count < 1) {
                        player.sendMessage("§cCount must be at least 1.");
                        return true;
                    }
                    count = Math.min(count, 50);
                }

                boolean grid = false;
                String factionArg = null;
                String kitArg = null;
                double spacing = FormationManager.DEFAULT_SPACING;
                WeightedSpec difficultySpec = null;

                for (int i = 2; i < args.length; i++) {
                    String token = args[i];
                    if (token.equalsIgnoreCase("grid") || token.equalsIgnoreCase("formation")) {
                        grid = true;
                        continue;
                    }

                    if (WeightedSpec.looksLikeSpec(token) || parseDifficulty(token) != null) {
                        WeightedSpec parsed = parseDifficultySpec(player, token);
                        if (parsed == null) return true;
                        difficultySpec = parsed;
                        continue;
                    }
                    Double asSpacing = tryParseDouble(token);
                    if (asSpacing != null) {
                        if (asSpacing < 0.5 || asSpacing > 16.0) {
                            player.sendMessage("§cSpacing must be between 0.5 and 16 blocks.");
                            return true;
                        }
                        spacing = asSpacing;
                        grid = true;
                        continue;
                    }
                    if (token.equalsIgnoreCase("kit") && i + 1 < args.length) {
                        kitArg = args[++i];
                        continue;
                    }
                    if (token.regionMatches(true, 0, "kit:", 0, 4) && token.length() > 4) {
                        kitArg = token.substring(4);
                        continue;
                    }
                    if (factionArg == null) {
                        factionArg = token;
                    } else {
                        player.sendMessage("§cDon't know what to do with §e" + token
                                + "§c. Usage: /pvpbot masspawn <count> [faction] [kit] [grid] [spacing] [difficulty]");
                        return true;
                    }
                }

                if (kitArg != null && !kitManager.kitExists(kitArg)) {
                    player.sendMessage("§cNo kit named §e" + kitArg + "§c. Spawning without one.");
                    kitArg = null;
                }

                boolean created = factionArg != null && !manager.factionExists(factionArg);

                var bots = grid
                        ? manager.massSpawnGrid(player.getLocation(), count, spacing, factionArg, style)
                        : manager.massSpawn(player.getLocation(), count, factionArg, style);

                if (kitArg != null) {
                    for (PvPBot b : bots) b.equipKit(kitArg);
                }

                if (created) player.sendMessage("§7Faction §e" + factionArg.toLowerCase()
                        + "§7 didn't exist yet — created it.");

                if (neutral) {
                    for (PvPBot b : bots) manager.getBotSettings(b.getUUID()).setHostile(false);
                }
                if (frozenSpawn) {
                    for (PvPBot b : bots) {
                        var st = manager.getBotSettings(b.getUUID());
                        st.setFrozen(true);
                        st.setHostile(false);
                    }
                }

                String spread = null;
                if (difficultySpec != null) {
                    java.util.Map<String, Integer> rolled = new java.util.LinkedHashMap<>();
                    for (PvPBot b : bots) {
                        BotDifficulty diff = parseDifficulty(difficultySpec.roll());
                        if (diff == null) continue;
                        manager.getBotSettings(b.getUUID()).applyDifficulty(diff);
                        rolled.merge(diff.name().toLowerCase(), 1, Integer::sum);
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var e : rolled.entrySet()) {
                        if (sb.length() > 0) sb.append("§7, ");
                        sb.append("§e").append(e.getValue()).append("§7x ").append(e.getKey());
                    }
                    spread = sb.toString();
                }

                player.sendMessage("§aSpawned §e" + bots.size() + "§a PvPBots"
                        + (grid ? " §7(grid, " + spacing + "b spacing)" : "")
                        + (factionArg != null ? " §7in faction §e" + factionArg.toLowerCase() : "")
                        + (kitArg != null ? " §7wearing kit §e" + kitArg.toLowerCase() : "")
                        + "§a.");
                if (spread != null) player.sendMessage("  §7difficulty: " + spread);
            }

            case "remove" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /pvpbot remove <name>");
                    return true;
                }
                PvPBot bot = manager.getBotByName(args[1]);
                if (bot == null) {
                    player.sendMessage("§cNo bot found with name: " + args[1]);
                } else {
                    manager.removeBot(bot.getUUID());
                    player.sendMessage("§aRemoved bot: §e" + args[1]);
                }
            }

            case "removeall" -> {
                int removed = manager.removeAllBots();

                if (plugin.getBotPersistence() != null) plugin.getBotPersistence().clear();
                player.sendMessage("§aRemoved §e" + removed + "§a bot(s).");
            }

            case "settings" -> {
                SettingsGui.open(player, 0);
            }

            case "portals" ->
                    PortalWave.handle(plugin, player, args, manager);

            case "debug" -> handleDebugCommand(player, args, manager);

            case "list" -> {
                var bots = manager.getBots();
                if (bots.isEmpty()) {
                    player.sendMessage("§7No bots currently spawned.");
                } else {
                    player.sendMessage("§6§lActive Bots (" + bots.size() + "):");
                    for (PvPBot b : bots.values()) {
                        player.sendMessage("  §7- §f" + b.getName() + " §8[" + manager.getBotSettings(b.getUUID()).getDifficulty() + "]");
                    }
                }
            }

            case "performance" -> {
                var bots = manager.getBots();
                int alive = 0;
                for (PvPBot b : bots.values()) if (b.isAlive()) alive++;

                double lastMs = plugin.getLastTickNanos() / 1_000_000.0;
                double avgMs = plugin.getAverageTickMillis();
                int full = com.pvpbot.perf.BotScheduler.getFullTicks();
                int light = com.pvpbot.perf.BotScheduler.getLightTicks();

                player.sendMessage("§6§lPvPBot performance");
                player.sendMessage(String.format(
                        "  §7bots: §f%d alive  §7(budget: 50ms per server tick)", alive));
                player.sendMessage(String.format(
                        "  §7bot tick loop: §f%.2f ms §7last, §f%.2f ms §7avg", lastMs, avgMs));
                player.sendMessage(String.format(
                        "  §7AI level of detail: §f%d full §7/ §f%d light §7last tick", full, light));
                long pGrant = com.pvpbot.perf.PathBudget.getTotalSnapshots();
                long pDeny = com.pvpbot.perf.PathBudget.getTotalDenied();
                long pTotal = pGrant + pDeny;
                double denyPct = pTotal > 0 ? (pDeny * 100.0 / pTotal) : 0.0;
                player.sendMessage(String.format(
                        "  §7pathfinder: §f%d §7granted, §f%d §7chunks, §f%d §7deferred (§f%.0f%%§7), §f%d §7from reserve",
                        pGrant,
                        com.pvpbot.perf.PathBudget.getTotalChunks(),
                        pDeny, denyPct,
                        com.pvpbot.perf.PathBudget.getTotalStarvedGrants()));

                if (denyPct >= 10.0) {
                    player.sendMessage("  §ehigh deferral — raise performance.path-requests-per-tick");
                } else {
                    player.sendMessage("  §8a few percent deferred is the budget working as intended");
                }

                long navHits = com.pvpbot.nav.NavChunkCache.getHits();
                long navMiss = com.pvpbot.nav.NavChunkCache.getMisses();
                long navTotal = navHits + navMiss;
                player.sendMessage(String.format(
                        "  §7nav cache: §f%d §7chunks held, §f%.0f%% §7hit rate §8(%d captures)",
                        com.pvpbot.nav.NavChunkCache.size(),
                        navTotal == 0 ? 0.0 : (100.0 * navHits / navTotal),
                        navMiss));
                player.sendMessage("  §8a high hit rate means bots are sharing terrain rather than "
                        + "each reading their own copy");

                if (com.pvpbot.perf.BotProfiler.enabled) {
                    if (com.pvpbot.perf.BotProfiler.hasSamples()) {
                        player.sendMessage("  §7where the time goes §8(exclusive ms per server tick)");
                        for (com.pvpbot.perf.BotProfiler.Section sec
                                : com.pvpbot.perf.BotProfiler.rankedSections()) {
                            double ms = com.pvpbot.perf.BotProfiler.msPerTick(sec);
                            if (ms < 0.005) continue;
                            String bar = "█".repeat(Math.min(20, (int) Math.round(ms * 4)));
                            player.sendMessage(String.format(
                                    "    §f%-13s §e%6.2f ms §8%s", sec.name().toLowerCase(), ms, bar));
                        }
                    } else {
                        player.sendMessage("  §7profiler on — no samples yet, give it a few seconds");
                    }
                } else {
                    player.sendMessage("  §8/pvpbot performance on §7for a per-subsystem breakdown");
                }

                if (args.length > 1) {
                    String opt = args[1].toLowerCase();
                    switch (opt) {
                        case "on" -> {
                            com.pvpbot.perf.BotProfiler.enabled = true;
                            com.pvpbot.perf.BotProfiler.reset();
                            player.sendMessage("  §aProfiler on. Run /pvpbot performance again in ~10s.");
                            player.sendMessage("  §8Costs a little itself — turn it off when done.");
                        }
                        case "off" -> {
                            com.pvpbot.perf.BotProfiler.enabled = false;
                            player.sendMessage("  §aProfiler off.");
                        }
                        case "reset" -> {
                            plugin.resetPerfCounters();
                            com.pvpbot.perf.BotProfiler.reset();
                            player.sendMessage("  §aCounters reset.");
                        }
                        default -> player.sendMessage("  §7usage: /pvpbot performance [on|off|reset]");
                    }
                }
            }

            case "attack" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: §f/pvpbot attack <target> <bot>");
                    player.sendMessage("§8  /pvpbot attack Notch Steve §7— Steve hunts Notch");
                    player.sendMessage("§8  /pvpbot attack off Steve §7— cancel the order");
                    return true;
                }

                PvPBot attacker = manager.getBotByName(args[2]);
                if (attacker == null) {
                    player.sendMessage("§cBot not found: §e" + args[2]);
                    return true;
                }

                String targetName = args[1];
                if (targetName.equalsIgnoreCase("off") || targetName.equalsIgnoreCase("none")
                        || targetName.equalsIgnoreCase("stop") || targetName.equalsIgnoreCase("clear")) {
                    attacker.setForcedTarget(null);
                    player.sendMessage("§a" + attacker.getName() + " is back on its own judgement.");
                    return true;
                }

                Player victim = Bukkit.getPlayerExact(targetName);
                if (victim == null) {
                    PvPBot vb = manager.getBotByName(targetName);
                    if (vb != null) victim = vb.getBukkitPlayer();
                }
                if (victim == null || !victim.isOnline()) {
                    player.sendMessage("§cNo online player or bot named §e" + targetName);
                    return true;
                }
                if (victim.getUniqueId().equals(attacker.getUUID())) {
                    player.sendMessage("§c" + attacker.getName() + " cannot attack itself.");
                    return true;
                }

                attacker.setForcedTarget(victim);
                player.sendMessage("§a" + attacker.getName() + " is now hunting §f"
                        + org.bukkit.ChatColor.stripColor(victim.getName()) + "§a.");
                player.sendMessage("§8Ignores faction, passive mode and guard leash "
                        + "until the target dies or you cancel.");
            }

            case "mine" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: §f/pvpbot mine <bot> <block> <x1 y1 z1> <x2 y2 z2> <seconds>");
                    player.sendMessage("§8  e.g. /pvpbot mine Steve ancient_debris 100 8 100 160 22 160 300");
                    player.sendMessage("§8  Every bot in that bot's faction holding a pickaxe joins in.");
                    player.sendMessage("§8  /pvpbot mine Steve stop §7— end the shift early");
                    player.sendMessage("§8  /pvpbot mine Steve status §7| §8/pvpbot mine Steve debug");
                    return true;
                }

                PvPBot leadMiner = manager.getBotByName(args[1]);
                if (leadMiner == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }

                String faction = manager.getPlayerFaction(leadMiner.getUUID());

                if (args.length == 3) {
                    String sub = args[2].toLowerCase();
                    if (sub.equals("stop") || sub.equals("off") || sub.equals("cancel")) {
                        com.pvpbot.mine.MiningJob.stop(faction);
                        int sent = 0;
                        for (PvPBot b : crewFor(manager, leadMiner, faction)) {
                            if (b.getAI().getContext().miningController.isActive()) {
                                b.getAI().getContext().miningController.abort();
                                sent++;
                            }
                        }
                        player.sendMessage("§eMining shift ended for " + sent + " bot(s).");
                        return true;
                    }
                    if (sub.equals("debug")) {
                        boolean any = false;
                        for (PvPBot b : crewFor(manager, leadMiner, faction)) {
                            if (!b.getAI().getContext().miningController.isActive()) continue;
                            player.sendMessage("§8" + b.getName() + " §7"
                                    + b.getAI().getContext().miningController.debugLine());
                            any = true;
                        }
                        if (!any) player.sendMessage("§7No bot in that faction is mining.");
                        return true;
                    }
                    if (sub.equals("status")) {
                        com.pvpbot.mine.MiningJob job = com.pvpbot.mine.MiningJob.forFaction(faction);
                        if (job == null) {
                            player.sendMessage("§7No mining job running for that faction.");
                            return true;
                        }
                        player.sendMessage("§6Mining §f" + job.getTarget().name().toLowerCase()
                                + " §7— " + job.secondsLeft() + "s left, found §a" + job.getFound()
                                + "§7, " + job.getBlocksMined() + " blocks dug, "
                                + job.hazardCount() + " hazards mapped.");
                        for (PvPBot b : crewFor(manager, leadMiner, faction)) {
                            if (b.getAI().getContext().miningController.isActive()) {
                                player.sendMessage("§8  " + b.getName() + ": "
                                        + b.getAI().getContext().miningController.status());
                            }
                        }
                        return true;
                    }
                }

                if (args.length < 10) {
                    player.sendMessage("§cUsage: §f/pvpbot mine <bot> <block> <x1 y1 z1> <x2 y2 z2> <seconds>");
                    return true;
                }

                Material blockType = Material.matchMaterial(args[2]);
                if (blockType == null || !blockType.isBlock()) {
                    player.sendMessage("§cUnknown block: §f" + args[2]);
                    return true;
                }

                double x1;
                double y1;
                double z1;
                double x2;
                double y2;
                double z2;
                int seconds;
                try {
                    Location o = player.getLocation();
                    x1 = parseCoord(args[3], o.getX());
                    y1 = parseCoord(args[4], o.getY());
                    z1 = parseCoord(args[5], o.getZ());
                    x2 = parseCoord(args[6], o.getX());
                    y2 = parseCoord(args[7], o.getY());
                    z2 = parseCoord(args[8], o.getZ());
                    seconds = Integer.parseInt(args[9]);
                } catch (NumberFormatException ex) {
                    player.sendMessage("§cCoordinates and time must be numbers (~ allowed for coords).");
                    return true;
                }

                if (seconds < 5 || seconds > 7200) {
                    player.sendMessage("§cTime must be between 5 and 7200 seconds.");
                    return true;
                }

                org.bukkit.entity.Player leadPlayer = leadMiner.getBukkitPlayer();
                if (leadPlayer == null) {
                    player.sendMessage("§cThat bot is not alive.");
                    return true;
                }

                org.bukkit.World world = leadPlayer.getWorld();
                Location c1 = new Location(world, x1, y1, z1);
                Location c2 = new Location(world, x2, y2, z2);

                com.pvpbot.mine.MiningJob job = com.pvpbot.mine.MiningJob.start(
                        faction, world, blockType, c1, c2, seconds, player.getUniqueId());

                List<PvPBot> crew = crewFor(manager, leadMiner, faction);

                List<PvPBot> eligible = new ArrayList<>();
                int noPick = 0;
                for (PvPBot b : crew) {
                    if (b.getAI().getContext().miningController.hasPickaxe()) eligible.add(b);
                    else noPick++;
                }

                int joined = 0;
                for (PvPBot b : eligible) {
                    if (b.getAI().getContext().miningController.join(job, eligible.size()) == null) {
                        joined++;
                    }
                }

                if (joined == 0) {
                    com.pvpbot.mine.MiningJob.stop(faction);
                    player.sendMessage("§cNobody could start — no bot in that faction has a pickaxe.");
                    return true;
                }

                player.sendMessage("§a" + joined + " bot(s) mining for §f"
                        + blockType.name().toLowerCase() + "§a for " + seconds + "s.");
                if (noPick > 0) {
                    player.sendMessage("§8  " + noPick + " skipped — no pickaxe.");
                }
                player.sendMessage("§8They walk in, dig 2-high tunnels on separate lanes, "
                        + "then return to where they set off from.");
            }

            case "farm" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: §f/pvpbot farm <bot> <x1 y1 z1> <x2 y2 z2> [seconds]");
                    player.sendMessage("§8  e.g. /pvpbot farm Steve ~ ~ ~ ~10 ~ ~10 600");
                    player.sendMessage("§8  Corners go at the crop layer — the height you'd stand at to farm it.");
                    player.sendMessage("§8  Bot replants from whatever seeds are already in its own hotbar "
                            + "— give it a farm kit first.");
                    player.sendMessage("§8  /pvpbot farm Steve stop §7— end it");
                    player.sendMessage("§8  /pvpbot farm Steve status");
                    return true;
                }

                PvPBot farmer = manager.getBotByName(args[1]);
                if (farmer == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }

                if (args.length == 3) {
                    String sub = args[2].toLowerCase();
                    if (sub.equals("stop") || sub.equals("off") || sub.equals("cancel")) {
                        farmer.getAI().getContext().farmController.abort();
                        player.sendMessage("§eStopped farming for §f" + farmer.getName() + "§e.");
                        return true;
                    }
                    if (sub.equals("status")) {
                        player.sendMessage("§8" + farmer.getName() + " §7"
                                + farmer.getAI().getContext().farmController.status());
                        return true;
                    }
                }

                if (args.length < 8) {
                    player.sendMessage("§cUsage: §f/pvpbot farm <bot> <x1 y1 z1> <x2 y2 z2> [seconds]");
                    return true;
                }

                org.bukkit.entity.Player farmerPlayer = farmer.getBukkitPlayer();
                if (farmerPlayer == null) {
                    player.sendMessage("§cThat bot is not alive.");
                    return true;
                }

                double fx1, fy1, fz1, fx2, fy2, fz2;
                int fseconds = -1;
                try {
                    Location o = player.getLocation();
                    fx1 = parseCoord(args[2], o.getX());
                    fy1 = parseCoord(args[3], o.getY());
                    fz1 = parseCoord(args[4], o.getZ());
                    fx2 = parseCoord(args[5], o.getX());
                    fy2 = parseCoord(args[6], o.getY());
                    fz2 = parseCoord(args[7], o.getZ());
                    if (args.length >= 9) fseconds = Integer.parseInt(args[8]);
                } catch (NumberFormatException ex) {
                    player.sendMessage("§cCoordinates and time must be numbers (~ allowed for coords).");
                    return true;
                }

                org.bukkit.World fworld = farmerPlayer.getWorld();
                Location fc1 = new Location(fworld, fx1, fy1, fz1);
                Location fc2 = new Location(fworld, fx2, fy2, fz2);

                String farmErr = farmer.getAI().getContext().farmController.startPlot(fc1, fc2, fseconds);
                if (farmErr != null) {
                    player.sendMessage("§cCouldn't start: §f" + farmErr);
                    return true;
                }

                player.sendMessage("§a" + farmer.getName() + " is now farming that plot"
                        + (fseconds > 0 ? " for " + fseconds + "s" : "") + ".");
                player.sendMessage("§8It'll wait and recheck every ~10s whenever nothing's ready to harvest.");
            }

            case "checkchest" -> {
                if (args.length < 5) {
                    player.sendMessage("§cUsage: §f/pvpbot checkchest <bot> <x> <y> <z>");
                    return true;
                }

                PvPBot checker = manager.getBotByName(args[1]);
                if (checker == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }
                org.bukkit.entity.Player checkerPlayer = checker.getBukkitPlayer();
                if (checkerPlayer == null) {
                    player.sendMessage("§cThat bot is not alive.");
                    return true;
                }

                double cx, cy, cz;
                try {
                    Location o = player.getLocation();
                    cx = parseCoord(args[2], o.getX());
                    cy = parseCoord(args[3], o.getY());
                    cz = parseCoord(args[4], o.getZ());
                } catch (NumberFormatException ex) {
                    player.sendMessage("§cCoordinates must be numbers (~ allowed).");
                    return true;
                }

                Location chestLoc = new Location(checkerPlayer.getWorld(), cx, cy, cz);
                String chestErr = checker.getAI().getContext().farmController.beginChestCheck(chestLoc, player);
                if (chestErr != null) {
                    player.sendMessage("§cCouldn't start: §f" + chestErr);
                    return true;
                }
                player.sendMessage("§a" + checker.getName() + " is heading over to check that chest.");
            }

            case "deliver" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: §f/pvpbot deliver <bot> <x> <y> <z>");
                    player.sendMessage("§8  The bot walks to its faction leader, takes a shulker");
                    player.sendMessage("§8  box from them, then carries it to the coordinates.");
                    player.sendMessage("§8  /pvpbot deliver Steve off §7— cancel a run in progress");
                    return true;
                }

                PvPBot courier = manager.getBotByName(args[1]);
                if (courier == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }

                if (args.length > 2) {
                    String sub = args[2].toLowerCase();
                    if (sub.equals("off") || sub.equals("stop") || sub.equals("cancel")) {
                        courier.getAI().getContext().deliveryController.abort();
                        player.sendMessage("§e" + courier.getName() + " delivery cancelled.");
                        return true;
                    }
                    if (sub.equals("status")) {
                        player.sendMessage("§7" + courier.getName() + ": §f"
                                + courier.getAI().getContext().deliveryController.status());
                        return true;
                    }
                }

                if (args.length < 5) {
                    player.sendMessage("§cUsage: §f/pvpbot deliver <bot> <x> <y> <z>");
                    return true;
                }

                double dx;
                double dy;
                double dz;
                try {
                    dx = parseCoord(args[2], player.getLocation().getX());
                    dy = parseCoord(args[3], player.getLocation().getY());
                    dz = parseCoord(args[4], player.getLocation().getZ());
                } catch (NumberFormatException ex) {
                    player.sendMessage("§cCoordinates must be numbers (or ~ / ~10 relative to you).");
                    return true;
                }

                org.bukkit.entity.Player courierPlayer = courier.getBukkitPlayer();
                if (courierPlayer == null) {
                    player.sendMessage("§cThat bot is not alive.");
                    return true;
                }

                Location dest = new Location(courierPlayer.getWorld(), dx, dy, dz);
                String problem = courier.getAI().getContext()
                        .deliveryController.begin(dest, player.getUniqueId());

                if (problem != null) {
                    player.sendMessage("§cCannot start delivery: " + problem + ".");
                    return true;
                }

                player.sendMessage("§a" + courier.getName() + " is collecting a shulker box "
                        + "from the faction leader.");
                player.sendMessage("§8Destination " + (int) dx + ", " + (int) dy + ", " + (int) dz
                        + " — it will load chunks along the way and report when it arrives.");
            }

            case "guard" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: §f/pvpbot guard <bot> [radius|here|off]");
                    player.sendMessage("§8  /pvpbot guard Steve §7— hold the spot it is standing on");
                    player.sendMessage("§8  /pvpbot guard Steve 24 §7— hold it with a 24 block radius");
                    player.sendMessage("§8  /pvpbot guard Steve here §7— hold where YOU are standing");
                    player.sendMessage("§8  /pvpbot guard Steve leader §7— escort and defend the faction leader");
                    player.sendMessage("§8  /pvpbot guard Steve off §7— release the post");
                    return true;
                }

                PvPBot guardBot = manager.getBotByName(args[1]);
                if (guardBot == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }

                String arg = args.length > 2 ? args[2].toLowerCase() : "";

                if (arg.equals("off") || arg.equals("stop") || arg.equals("none")) {
                    if (!guardBot.isGuarding()) {
                        player.sendMessage("§7" + guardBot.getName() + " is not guarding anything.");
                    } else {
                        guardBot.clearGuardPost();
                        player.sendMessage("§a" + guardBot.getName() + " has left its post.");
                    }
                    return true;
                }

                if (arg.equals("leader")) {
                    Player bp0 = guardBot.getBukkitPlayer();
                    if (bp0 == null) {
                        player.sendMessage("§cThat bot is not currently in the world.");
                        return true;
                    }
                    java.util.UUID leaderId = manager.getLeaderFor(guardBot.getUUID());
                    if (leaderId == null) {
                        player.sendMessage("§c" + guardBot.getName()
                                + " has no faction leader to guard.");
                        player.sendMessage("§8Set one with §f/pvpbot faction <name> leader <who>");
                        return true;
                    }

                    double r = 16.0;
                    if (args.length > 3) {
                        try {
                            r = Double.parseDouble(args[3]);
                        } catch (NumberFormatException e) {
                            player.sendMessage("§cRadius must be a number.");
                            return true;
                        }
                        if (r < 2.0 || r > 128.0) {
                            player.sendMessage("§cRadius must be between 2 and 128.");
                            return true;
                        }
                    }

                    guardBot.setGuardLeader(bp0.getLocation(), r, bp0.getLocation().getYaw());
                    player.sendMessage("§a" + guardBot.getName() + " is now guarding its leader.");
                    player.sendMessage("§8Sticks with them and defends them, but will not start "
                            + "fights with people who walk past.");
                    return true;
                }

                boolean here = arg.equals("here");
                Location anchor;
                float facing;
                if (here) {
                    anchor = player.getLocation();
                    facing = player.getLocation().getYaw();
                } else {
                    Player bp = guardBot.getBukkitPlayer();
                    if (bp == null) {
                        player.sendMessage("§cThat bot is not currently in the world.");
                        return true;
                    }
                    anchor = bp.getLocation();
                    facing = bp.getLocation().getYaw();
                }

                double radius = 16.0;
                String radiusArg = here ? (args.length > 3 ? args[3] : null)
                                        : (args.length > 2 ? args[2] : null);
                if (radiusArg != null) {
                    try {
                        radius = Double.parseDouble(radiusArg);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cRadius must be a number.");
                        return true;
                    }
                    if (radius < 2.0 || radius > 128.0) {
                        player.sendMessage("§cRadius must be between 2 and 128.");
                        return true;
                    }
                }

                BotSettings gs = manager.getBotSettings(guardBot.getUUID());
                if (gs != null && !gs.isHostile()) {
                    player.sendMessage("§e" + guardBot.getName()
                            + " is set passive — it will hold the post but never engage.");
                    player.sendMessage("§8  /pvpbot set " + guardBot.getName() + " hostile true");
                }

                guardBot.setGuardPost(anchor, radius, facing);
                player.sendMessage(String.format(
                        "§a%s is guarding §f%.0f, %.0f, %.0f §awithin §f%.0f §ablocks.",
                        guardBot.getName(), anchor.getX(), anchor.getY(), anchor.getZ(), radius));
                player.sendMessage("§8Attacks anyone not in its faction who enters the zone, "
                        + "then returns to the post.");
            }

            case "golem" -> handleGolemCommand(player, args, manager);

            case "difficulty" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /pvpbot difficulty <botname> <Easy|Normal|Hard|Expert>");
                    return true;
                }
                String botName = args[1];
                PvPBot bot = manager.getBotByName(botName);
                if (bot == null) {
                    player.sendMessage("§cBot not found!");
                    return true;
                }
                try {
                    BotDifficulty diff = BotDifficulty.valueOf(args[2].toUpperCase());
                    manager.getBotSettings(bot.getUUID()).applyDifficulty(diff);
                    player.sendMessage("§aSet " + bot.getName() + " difficulty to §e" + diff.name());
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid difficulty! Choose: Easy, Normal, Hard, Expert.");
                }
            }

            case "kit" -> handleKitCommand(player, args, manager, kitManager);
            case "faction" -> handleFactionCommand(player, args, manager);
            case "group" -> handleTopLevelGroupCommand(player, args, manager);
            case "cinematic" -> CinematicJoin.handle(player, args, plugin, manager);
            case "schematic" -> handleSchematicCommand(player, args);
            case "path" -> handlePathCommand(player, args, manager);
            case "give" -> handleGiveCommand(player, args, manager);
            case "set" -> handleSetCommand(player, args, manager);

            default -> sendUsage(player);
        }

        return true;
    }

    private void handleFactionSchematic(Player sender, String[] args, BotManager botManager) {
        var schemMgr = plugin.getSchematicManager();
        if (schemMgr == null) {
            sender.sendMessage("§cSchematic support is not loaded.");
            return;
        }

        String faction = null;
        String schemName;
        String action;

        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) rest.add(args[i]);

        if (rest.size() >= 3) {
            faction = rest.get(0);
            schemName = rest.get(1);
            action = rest.get(2).toLowerCase();
        } else if (rest.size() == 2) {
            String second = rest.get(1).toLowerCase();
            if ((second.equals("build") || second.equals("stop") || second.equals("status"))
                    && !botManager.factionExists(rest.get(0))) {
                schemName = rest.get(0);
                action = second;
            } else {
                faction = rest.get(0);
                schemName = rest.get(1);
                action = "build";
            }
        } else {
            sender.sendMessage("§cUsage: /pvpbot faction schematic [faction] <name> build|stop|status");
            var names = schemMgr.list();
            sender.sendMessage(names.isEmpty()
                    ? "§7No schematics yet. Drop .schem files in §fplugins/PvPBot/schematics/"
                    : "§7Available: §f" + String.join(", ", names));
            return;
        }

        if (faction == null) {
            var factions = botManager.getFactionNames().stream()
                    .filter(f -> !botManager.getFactionBots(f).isEmpty())
                    .sorted().toList();
            if (factions.size() == 1) {
                faction = factions.get(0);
            } else if (factions.isEmpty()) {
                sender.sendMessage("§cNo faction has any live bots to build with.");
                return;
            } else {
                sender.sendMessage("§cSeveral factions have bots — name one: §f"
                        + String.join(", ", factions));
                return;
            }
        }

        String key = faction.toLowerCase(java.util.Locale.ROOT);
        var jobs = plugin.getBuildJobs();

        if (action.equals("stop")) {
            var existing = jobs.remove(key);
            if (existing == null) {
                sender.sendMessage("§7" + faction + " isn't building anything.");
                return;
            }
            existing.cancel();
            for (PvPBot b : botManager.getFactionBots(faction)) {
                b.getAI().getContext().buildController.abort();
            }
            sender.sendMessage("§eStopped the build. §7" + existing.completed()
                    + "/" + existing.total() + " blocks were placed — what's up stays up.");
            return;
        }

        if (action.equals("status")) {
            var existing = jobs.get(key);
            if (existing == null) {
                sender.sendMessage("§7" + faction + " isn't building anything.");
                return;
            }
            int pct = existing.total() == 0 ? 100
                    : (existing.completed() * 100) / existing.total();
            sender.sendMessage("§6" + faction + "§7 is building §e" + existing.schematicName
                    + "§7: §f" + existing.completed() + "§7/§f" + existing.total()
                    + " §8(" + pct + "%)");
            if (!existing.reserve().isEmpty()) {
                sender.sendMessage("§8  reserve holds " + existing.reserve().size()
                        + " material type(s) the bots couldn't carry");
            }
            return;
        }

        if (!action.equals("build")) {
            sender.sendMessage("§cUnknown action '" + action + "'. Use build, stop or status.");
            return;
        }

        if (jobs.containsKey(key)) {
            sender.sendMessage("§c" + faction + " is already building. §f/pvpbot faction schematic "
                    + faction + " " + schemName + " stop§c first.");
            return;
        }

        var schem = schemMgr.get(schemName);
        if (schem == null) {
            String why = schemMgr.describeFailure(schemName);
            sender.sendMessage("§cCan't load '" + schemName + "'§7: " + (why == null ? "unknown error" : why));
            return;
        }

        var crew = botManager.getFactionBots(faction);
        if (crew.isEmpty()) {
            sender.sendMessage("§cFaction §e" + faction + "§c has no live bots.");
            return;
        }

        int[] origin = com.pvpbot.schem.SchematicPreview.originFor(sender, schem);
        var job = new com.pvpbot.schem.BuildJob(schem, sender.getWorld(),
                origin[0], origin[1], origin[2], faction);

        if (job.total() == 0) {
            sender.sendMessage("§cThat schematic has no placeable blocks in it.");
            return;
        }

        java.util.List<Player> crewPlayers = new java.util.ArrayList<>();
        for (PvPBot b : crew) {
            Player bp = b.getBukkitPlayer();
            if (bp != null) crewPlayers.add(bp);
        }
        job.distribute(crewPlayers, schem.materials());

        jobs.put(key, job);
        for (PvPBot b : crew) {
            b.getAI().getContext().buildController.assign(job);
        }

        sender.sendMessage("§a" + crew.size() + " bot(s) from §e" + faction
                + "§a are building §e" + schem.name + "§a — §f" + job.total() + "§a blocks.");
        sender.sendMessage("§7Materials split evenly; leftovers sit in a shared reserve.");
        if (schem.unknownStates > 0) {
            sender.sendMessage("§e" + schem.unknownStates
                    + " palette entries didn't parse and were skipped as air.");
        }
        com.pvpbot.schem.SchematicPreview.clear(sender);
    }

    private void handlePathCommand(Player sender, String[] args, BotManager botManager) {
        var routes = plugin.getRouteManager();
        if (routes == null) {
            sender.sendMessage("§cRoutes are not loaded.");
            return;
        }

        String action = args.length >= 2 ? args[1].toLowerCase() : "";

        switch (action) {
            case "create" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot path create <name>");
                    return;
                }
                String name = args[2];
                if (routes.create(name) == null) {
                    sender.sendMessage("§cA path called §e" + name + "§c already exists.");
                    return;
                }
                routes.select(sender.getUniqueId(), name);
                sender.sendMessage("§aCreated path §e" + name + "§a and selected it.");
                sender.sendMessage("§7Walk around and run §f/pvpbot path point create§7 "
                        + "at each spot you want it to pass through.");
            }

            case "point" -> {
                String sub = args.length >= 3 ? args[2].toLowerCase() : "create";
                var route = args.length >= 4 ? routes.get(args[3])
                                             : routes.selectedFor(sender.getUniqueId());
                if (route == null) {
                    sender.sendMessage("§cNo path selected. §f/pvpbot path create <name>§c first, "
                            + "or name one: §f/pvpbot path point create <name>");
                    return;
                }

                switch (sub) {
                    case "create", "add" -> {
                        Location l = sender.getLocation();

                        Location point = new Location(l.getWorld(),
                                l.getBlockX() + 0.5, l.getBlockY(), l.getBlockZ() + 0.5,
                                l.getYaw(), l.getPitch());

                        if (!route.points.isEmpty()
                                && route.points.get(0).getWorld() != null
                                && !route.points.get(0).getWorld().equals(point.getWorld())) {
                            sender.sendMessage("§cThat path is in another world.");
                            return;
                        }

                        route.points.add(point);
                        routes.save();
                        sender.sendMessage("§aPoint §f" + route.size() + "§a added to §e"
                                + route.name + "§a at §7" + point.getBlockX() + ", "
                                + point.getBlockY() + ", " + point.getBlockZ()
                                + " §8facing " + Math.round(point.getYaw()) + "°");
                    }
                    case "remove", "undo" -> {
                        if (route.points.isEmpty()) {
                            sender.sendMessage("§7That path has no points.");
                            return;
                        }
                        route.points.remove(route.size() - 1);
                        routes.save();
                        sender.sendMessage("§eRemoved the last point. §7" + route.size() + " left.");
                    }
                    case "clear" -> {
                        route.points.clear();
                        routes.save();
                        sender.sendMessage("§eCleared every point on §f" + route.name + "§e.");
                    }
                    default -> sender.sendMessage("§cUse: point create | remove | clear");
                }
            }

            case "list" -> {
                var names = routes.names();
                if (names.isEmpty()) {
                    sender.sendMessage("§7No paths yet. §f/pvpbot path create <name>");
                    return;
                }
                sender.sendMessage("§6§lPaths (" + names.size() + "):");
                for (String n : names) {
                    var r = routes.get(n);
                    sender.sendMessage("  §7- §e" + n + " §8(" + r.size() + " points"
                            + (r.loop ? ", loops" : ", one way") + ")");
                }
            }

            case "info" -> {
                var route = args.length >= 3 ? routes.get(args[2])
                                             : routes.selectedFor(sender.getUniqueId());
                if (route == null) {
                    sender.sendMessage("§cNo such path.");
                    return;
                }
                sender.sendMessage("§6§l" + route.name + " §7(" + route.size() + " points, "
                        + (route.loop ? "loops" : "one way") + ")");
                for (int i = 0; i < route.size() && i < 12; i++) {
                    Location l = route.points.get(i);
                    sender.sendMessage("  §8" + (i + 1) + ". §7"
                            + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ());
                }
                if (route.size() > 12) {
                    sender.sendMessage("  §8...and " + (route.size() - 12) + " more");
                }
            }

            case "loop" -> {
                var route = args.length >= 3 ? routes.get(args[2])
                                             : routes.selectedFor(sender.getUniqueId());
                if (route == null) {
                    sender.sendMessage("§cNo such path.");
                    return;
                }
                route.loop = !route.loop;
                routes.save();
                sender.sendMessage("§e" + route.name + "§7 now "
                        + (route.loop ? "§aloops forever" : "§7stops at the last point") + "§7.");
            }

            case "delete" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot path delete <name>");
                    return;
                }
                if (!routes.delete(args[2])) {
                    sender.sendMessage("§cNo such path.");
                    return;
                }
                routes.save();
                sender.sendMessage("§eDeleted §f" + args[2] + "§e.");
            }

            case "walk", "sprint" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot path walk <bot|all|faction> [walk|sprint] [path]");
                    return;
                }

                boolean sprint = action.equals("sprint");
                String routeName = null;
                for (int i = 3; i < args.length; i++) {
                    String a = args[i].toLowerCase();
                    if (routes.get(args[i]) != null) routeName = args[i];
                    else if (a.equals("sprint")) sprint = true;
                    else if (a.equals("walk")) sprint = false;
                    else routeName = args[i];
                }

                var route = routeName != null ? routes.get(routeName)
                                              : routes.selectedFor(sender.getUniqueId());
                if (route == null) {
                    sender.sendMessage("§cNo path selected — name one: "
                            + "§f/pvpbot path walk <bot> walk <path>");
                    return;
                }
                if (route.size() < 2) {
                    sender.sendMessage("§cThat path has " + route.size()
                            + " point(s). Add at least two.");
                    return;
                }

                var crew = resolveBots(args[2], botManager);
                if (crew.isEmpty()) {
                    sender.sendMessage("§cNo bots matched §e" + args[2] + "§c.");
                    return;
                }
                for (PvPBot b : crew) {
                    b.getAI().getContext().patrolController.start(route, sprint);
                }
                sender.sendMessage("§a" + crew.size() + " bot(s) now "
                        + (sprint ? "sprinting" : "walking") + " §e" + route.name + "§a.");
                sender.sendMessage("§7They ignore targets while patrolling. "
                        + "§f/pvpbot path stop " + args[2] + "§7 to release them.");
            }

            case "stop" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot path stop <bot|all|faction>");
                    return;
                }
                var crew = resolveBots(args[2], botManager);
                int stopped = 0;
                for (PvPBot b : crew) {
                    var pc = b.getAI().getContext().patrolController;
                    if (pc.isActive()) {
                        pc.stop();
                        stopped++;
                    }
                }
                sender.sendMessage("§eStopped " + stopped + " bot(s) patrolling.");
            }

            case "status" -> {
                boolean any = false;
                for (PvPBot b : botManager.getBots().values()) {
                    var pc = b.getAI().getContext().patrolController;
                    if (!pc.isActive()) continue;
                    any = true;
                    sender.sendMessage("  §7" + b.getName() + ": §f" + pc.status());
                }
                if (!any) sender.sendMessage("§7No bots are patrolling.");
            }

            default -> {
                sender.sendMessage("§6§lPaths §7— hand-placed waypoint routes");
                sender.sendMessage("§e /pvpbot path create <name>");
                sender.sendMessage("§e /pvpbot path point create §8(also: remove, clear)");
                sender.sendMessage("§e /pvpbot path walk <bot|all|faction> [walk|sprint] [path]");
                sender.sendMessage("§e /pvpbot path stop <bot|all|faction>");
                sender.sendMessage("§e /pvpbot path list|info|loop|delete|status");
            }
        }
    }

    private java.util.List<PvPBot> resolveBots(String who, BotManager botManager) {
        java.util.List<PvPBot> out = new java.util.ArrayList<>();
        if (who.equalsIgnoreCase("all")) {
            out.addAll(botManager.getBots().values());
            return out;
        }
        var faction = botManager.getFactionBots(who);
        if (!faction.isEmpty()) return faction;

        for (PvPBot b : botManager.getBots().values()) {
            if (org.bukkit.ChatColor.stripColor(b.getName()).equalsIgnoreCase(who)) {
                out.add(b);
                return out;
            }
        }
        return out;
    }

    private void handleSchematicCommand(Player sender, String[] args) {
        var mgr = plugin.getSchematicManager();
        if (mgr == null) {
            sender.sendMessage("§cSchematic support is not loaded.");
            return;
        }

        String action = args.length >= 2 ? args[1].toLowerCase() : "list";

        switch (action) {
            case "list" -> {
                var names = mgr.list();
                if (names.isEmpty()) {
                    sender.sendMessage("§7No schematics found.");
                    sender.sendMessage("§7Drop Sponge §f.schem§7 files in §f"
                            + mgr.getFolder().getPath());
                    return;
                }
                sender.sendMessage("§6§lSchematics (" + names.size() + "):");
                for (String n : names) sender.sendMessage("  §7- §e" + n);
            }

            case "reload" -> {
                mgr.reload();
                sender.sendMessage("§aSchematic cache cleared — files will be re-read on next use.");
            }

            case "debug" -> {
                var jobs = plugin.getBuildJobs();
                if (jobs.isEmpty()) {
                    sender.sendMessage("§7No build is running.");
                    return;
                }
                for (var e : jobs.entrySet()) {
                    var job = e.getValue();
                    sender.sendMessage("§6" + e.getKey() + " §7building §e" + job.schematicName
                            + " §7" + job.completed() + "/" + job.total()
                            + (job.isSupportRelaxed() ? " §8(support relaxed)" : ""));
                    for (PvPBot b : plugin.getBotManager().getFactionBots(e.getKey())) {
                        sender.sendMessage("  §7" + b.getName() + ": §f"
                                + b.getAI().getContext().buildController.debugLine());
                    }
                }
            }

            case "clear" -> {
                com.pvpbot.schem.SchematicPreview.clear(sender);
                sender.sendMessage("§7Preview cleared.");
            }

            case "info" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot schematic info <name>");
                    return;
                }
                var s = mgr.get(args[2]);
                if (s == null) {
                    sender.sendMessage("§cCan't load that: §7" + mgr.describeFailure(args[2]));
                    return;
                }
                sender.sendMessage("§6§l" + s.name);
                sender.sendMessage("  §7size: §f" + com.pvpbot.schem.SchematicPreview.describeSize(s)
                        + " §8(" + s.volume() + " cells)");
                var mats = s.materials();
                int totalBlocks = mats.values().stream().mapToInt(Integer::intValue).sum();
                sender.sendMessage("  §7blocks to place: §f" + totalBlocks
                        + " §8across " + mats.size() + " material(s)");
                mats.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(8)
                        .forEach(e -> sender.sendMessage("    §8- §7"
                                + e.getKey().name().toLowerCase() + " §fx" + e.getValue()));
                if (mats.size() > 8) sender.sendMessage("    §8...and " + (mats.size() - 8) + " more");
                if (s.unknownStates > 0) {
                    sender.sendMessage("  §e" + s.unknownStates + " palette entries didn't parse");
                }
            }

            case "preview" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot schematic preview <name> [seconds]");
                    return;
                }
                var s = mgr.get(args[2]);
                if (s == null) {
                    sender.sendMessage("§cCan't load that: §7" + mgr.describeFailure(args[2]));
                    return;
                }
                if (com.pvpbot.schem.SchematicPreview.tooBig(s)) {
                    sender.sendMessage("§cThat's too big to preview ("
                            + s.volume() + " cells) — it would flood your client.");
                    return;
                }
                int seconds = 45;
                if (args.length >= 4) {
                    try {
                        seconds = Math.max(5, Math.min(300, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                int[] origin = com.pvpbot.schem.SchematicPreview.originFor(sender, s);
                int shown = com.pvpbot.schem.SchematicPreview.show(
                        plugin, sender, s, origin[0], origin[1], origin[2], seconds);
                sender.sendMessage("§aPreviewing §e" + s.name + "§a — §f" + shown
                        + "§a blocks, " + seconds + "s.");
                sender.sendMessage("§7Only you can see it, and nothing is placed. "
                        + "§f/pvpbot schematic clear§7 removes it early.");
            }

            default -> {
                sender.sendMessage("§cUnknown action. Try:");
                sender.sendMessage("§e /pvpbot schematic list§7 | §einfo <name>§7 | §epreview <name> [secs]§7 | §eclear§7 | §ereload§7 | §edebug");
            }
        }
    }

    private void handleFactionCommand(Player sender, String[] args, BotManager botManager) {
        if (args.length < 2) {
            sendFactionUsage(sender, botManager);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> {
                var names = botManager.getFactionNames();
                if (names.isEmpty()) {
                    sender.sendMessage("§7No factions yet. §f/pvpbot faction add <name> <bot>§7 creates one automatically.");
                    return;
                }
                sender.sendMessage("§6§lFactions (" + names.size() + "):");
                for (String name : names.stream().sorted().toList()) {
                    int total = botManager.getFactionMembers(name).size();
                    int liveBots = botManager.getFactionBots(name).size();
                    sender.sendMessage("  §7- §e" + name + " §8(" + total + " members, " + liveBots + " live bots)");
                }
            }
            case "info", "members" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /pvpbot faction info <name>"); return; }
                String name = args[2];
                if (!botManager.factionExists(name)) { unknownFaction(sender, botManager, name); return; }
                var members = botManager.getFactionMembers(name);
                sender.sendMessage("§6§lFaction §e" + name.toLowerCase() + " §6§l(" + members.size() + " members):");
                for (UUID uuid : members) {
                    PvPBot bot = botManager.getBots().get(uuid);
                    if (bot != null) {
                        sender.sendMessage("  §7- §f" + org.bukkit.ChatColor.stripColor(bot.getName()) + " §8[bot]");
                    } else {
                        Player p = Bukkit.getPlayer(uuid);
                        sender.sendMessage("  §7- §f" + (p != null ? p.getName() : uuid.toString().substring(0, 8) + "…")
                                + " §8[" + (p != null ? "player" : "offline") + "]");
                    }
                }
            }
            case "create" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /pvpbot faction create <name>"); return; }
                botManager.createFaction(args[2]);
                sender.sendMessage("§aCreated faction: §e" + args[2].toLowerCase());
            }
            case "remove", "delete", "disband" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /pvpbot faction remove <name>"); return; }
                if (!botManager.factionExists(args[2])) { unknownFaction(sender, botManager, args[2]); return; }
                botManager.removeFaction(args[2]);
                sender.sendMessage("§cRemoved faction: §e" + args[2].toLowerCase());
            }
            case "add" -> {
                if (args.length < 4) { sender.sendMessage("§cUsage: /pvpbot faction add <name> <player|bot>"); return; }
                addSingleMember(sender, botManager, args[2], args[3]);
            }
            case "addall" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /pvpbot faction addall <name>"); return; }
                String factionName = args[2];
                boolean created = false;
                int added = 0;
                for (PvPBot bot : botManager.getBots().values()) {
                    created |= botManager.addPlayerToFaction(factionName, bot.getUUID());
                    added++;
                }
                if (created) sender.sendMessage("§7Faction §e" + factionName.toLowerCase() + "§7 didn't exist yet — created it.");
                sender.sendMessage("§aAdded §e" + added + "§a bots to faction §e" + factionName.toLowerCase());
            }
            case "addnear" -> {
                if (args.length < 4) { sender.sendMessage("§cUsage: /pvpbot faction addnear <radius> <name>"); return; }
                double radius;
                String factionName;
                try {
                    radius = Double.parseDouble(args[2]);
                    factionName = args[3];
                } catch (NumberFormatException e) {
                    try {
                        radius = Double.parseDouble(args[3]);
                        factionName = args[2];
                    } catch (NumberFormatException e2) {
                        sender.sendMessage("§cOne of the two arguments must be a radius number.");
                        return;
                    }
                }
                boolean created = false;
                int count = 0;
                for (PvPBot bot : botManager.getBots().values()) {
                    if (bot.getLocation().getWorld() == sender.getWorld()
                            && bot.getLocation().distance(sender.getLocation()) <= radius) {
                        created |= botManager.addPlayerToFaction(factionName, bot.getUUID());
                        count++;
                    }
                }
                if (created) sender.sendMessage("§7Faction §e" + factionName.toLowerCase() + "§7 didn't exist yet — created it.");
                sender.sendMessage("§aAdded §e" + count + "§a bots within " + radius + " blocks to §e" + factionName.toLowerCase());
            }
            case "leader" -> handleLeaderCommand(sender, args, botManager);
            case "givekit" -> applyKitToFaction(sender, args, botManager);
            case "giverandomkit", "randomkit" -> applyRandomKitsToFaction(sender, args, botManager);
            case "formation" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot faction formation <faction> <shape> [smooth|instant] [spacing]");
                    sender.sendMessage("§7Shapes: §f" + String.join(", ", FormationManager.Shape.names()));
                    return;
                }
                handleFormationCommand(sender, args[2],
                        java.util.Arrays.copyOfRange(args, Math.min(3, args.length), args.length),
                        botManager);
            }
            case "alliance", "ally" -> handleAllianceCommand(sender, args, botManager);
            case "schematic", "schem" -> handleFactionSchematic(sender, args, botManager);
            case "group" -> handleGroupFormation(sender, args, botManager);
            case "armor" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction armor <on|off> <faction>");
                    return;
                }
                String armorAction = args[2].toLowerCase();
                String factionName = args[3];

                if (!botManager.factionExists(factionName)) {
                    unknownFaction(sender, botManager, factionName);
                    return;
                }

                if (armorAction.equals("on")) {
                    int count = botManager.equipFactionArmorDelayed(factionName);
                    if (count == 0) {
                        sender.sendMessage("§7No live bots in §e" + factionName.toLowerCase()
                                + "§7 started equipping armor (no armor in inventory, or already in progress).");
                        return;
                    }
                    sender.sendMessage("§a" + count + "§7 bot(s) of §e" + factionName.toLowerCase()
                            + "§7 are putting on their armor.");
                } else if (armorAction.equals("off")) {
                    int count = botManager.removeFactionArmorDelayed(factionName);
                    if (count == 0) {
                        sender.sendMessage("§7No live bots in §e" + factionName.toLowerCase()
                                + "§7 started removing armor (already unarmored, or already in progress).");
                        return;
                    }
                    sender.sendMessage("§a" + count + "§7 bot(s) of §e" + factionName.toLowerCase()
                            + "§7 are taking off their armor.");
                } else {
                    sender.sendMessage("§cUsage: §f/pvpbot faction armor <on|off> <faction>");
                }
            }
            case "stopattack", "standdown", "holdfire" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction stopattack <faction> <true|false>");
                    sender.sendMessage("§8  true §7— faction won't engage, retaliate, or assist until set back to false.");
                    sender.sendMessage("§8  Good for an intimidation lineup: bots stand with their leader instead of fighting.");
                    return;
                }
                String factionName = args[2];
                if (!botManager.factionExists(factionName)) {
                    unknownFaction(sender, botManager, factionName);
                    return;
                }
                boolean value = parseBool(args[3]);
                botManager.setFactionStopAttack(factionName, value);
                if (value) {
                    sender.sendMessage("§e" + factionName.toLowerCase()
                            + " §7is standing down — they won't attack or retaliate until you turn this off.");
                } else {
                    sender.sendMessage("§a" + factionName.toLowerCase() + " §7is free to fight again.");
                }
            }
            case "moveto" -> {
                if (args.length < 6) {
                    sender.sendMessage("§cUsage: /pvpbot faction moveto <faction> <x> <y> <z>");
                    return;
                }
                handleFactionMoveTo(sender, args[2],
                        java.util.Arrays.copyOfRange(args, 3, args.length), botManager);
            }
            case "attack" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /pvpbot faction attack <faction> <faction>");
                    sender.sendMessage("§8  /pvpbot faction <faction> attack <faction> §7— same thing, either order");
                    sender.sendMessage("§8  /pvpbot faction <faction> attack off §7— call it off");
                    return;
                }
                handleFactionAttack(sender, args[2], args[3], botManager);
            }
            case "minearea" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: /pvpbot faction minearea <faction> [radius] [seconds]");
                    sender.sendMessage("§c   or: /pvpbot faction <faction> minearea [radius] [seconds]");
                    sender.sendMessage("§8  Bots in the faction will mine blocks around their leader.");
                    sender.sendMessage("§8  They break grass with fists, then mine stone with pickaxes.");
                    sender.sendMessage("§8  Each bot has its own path to avoid collisions.");
                    sender.sendMessage("§8  /pvpbot faction minearea <faction> stop §7— end mining");
                    sender.sendMessage("§8  /pvpbot faction <faction> minearea stop §7— end mining");
                    return;
                }
                handleFactionMineArea(sender, args, botManager);
            }
            default -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("formation")) {
                    handleFactionFormation(sender, args, botManager);
                } else if (args.length >= 6 && args[2].equalsIgnoreCase("moveto")) {
                    handleFactionMoveTo(sender, args[1],
                            java.util.Arrays.copyOfRange(args, 3, args.length), botManager);
                } else if (args.length >= 4 && args[2].equalsIgnoreCase("attack")) {
                    handleFactionAttack(sender, args[1], args[3], botManager);
                } else if (args.length >= 3 && botManager.factionExists(action) && args.length >= 4 && args[3].equalsIgnoreCase("minearea")) {
                    String[] newArgs = new String[args.length];
                    newArgs[0] = args[0];
                    newArgs[1] = "minearea";
                    newArgs[2] = action;
                    System.arraycopy(args, 4, newArgs, 3, args.length - 4);
                    handleFactionMineArea(sender, newArgs, botManager);
                } else if (botManager.factionExists(action)) {
                    sender.sendMessage("§cWhat do you want to do with §e" + action + "§c? Try: §f/pvpbot faction " + action + " formation grid");
                } else {
                    sender.sendMessage("§cUnknown action or faction: §e" + action);
                    sendFactionUsage(sender, botManager);
                }
            }
        }
    }

    private void handleAllianceCommand(Player sender, String[] args, BotManager botManager) {
        if (args.length < 3) {
            sender.sendMessage("§6§lAlliances");
            sender.sendMessage("§e /pvpbot faction alliance create <name>");
            sender.sendMessage("§e /pvpbot faction alliance add <name> <faction>");
            sender.sendMessage("§e /pvpbot faction alliance remove <faction>");
            sender.sendMessage("§e /pvpbot faction alliance delete <name>");
            sender.sendMessage("§e /pvpbot faction alliance list");
            sender.sendMessage("§8Allied factions stop attacking each other.");
            return;
        }

        String action = args[2].toLowerCase();

        switch (action) {
            case "create" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction alliance create <name>");
                    return;
                }
                String name = args[3];
                if (!botManager.createAlliance(name)) {
                    sender.sendMessage("§cAn alliance called §e" + name + "§c already exists.");
                    return;
                }
                sender.sendMessage("§aCreated alliance §e" + name + "§a.");
                sender.sendMessage("§7Add factions with §f/pvpbot faction alliance add "
                        + name + " <faction>");
            }

            case "delete", "disband" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction alliance delete <name>");
                    return;
                }
                if (!botManager.deleteAlliance(args[3])) {
                    sender.sendMessage("§cNo alliance called §e" + args[3] + "§c.");
                    return;
                }
                sender.sendMessage("§eDisbanded §f" + args[3]
                        + "§e. Its factions are hostile to each other again.");
            }

            case "add", "join" -> {
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction alliance add <name> <faction>");
                    return;
                }
                String problem = botManager.joinAlliance(args[3], args[4]);
                if (problem != null) {
                    sender.sendMessage("§cCannot do that: " + problem + ".");
                    return;
                }
                sender.sendMessage("§e" + args[4] + " §ajoined alliance §e" + args[3] + "§a.");

                int dropped = clearTargetsBetweenAllies(botManager);
                if (dropped > 0) {
                    sender.sendMessage("§7" + dropped + " bot(s) stood down.");
                }
            }

            case "remove", "leave", "kick" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: §f/pvpbot faction alliance remove <faction>");
                    return;
                }
                if (!botManager.leaveAlliance(args[3])) {
                    sender.sendMessage("§c§e" + args[3] + "§c is not in an alliance.");
                    return;
                }
                sender.sendMessage("§e" + args[3] + " §7left its alliance.");
            }

            case "list" -> {
                var names = botManager.getAllianceNames();
                if (names.isEmpty()) {
                    sender.sendMessage("§7No alliances yet. §f/pvpbot faction alliance create <name>");
                    return;
                }
                sender.sendMessage("§6§lAlliances (" + names.size() + "):");
                for (String name : names.stream().sorted().toList()) {
                    var members = botManager.getAllianceMembers(name);
                    sender.sendMessage("  §7- §e" + name + " §8("
                            + (members.isEmpty() ? "no factions" : String.join(", ", members)) + ")");
                }
            }

            default -> sender.sendMessage("§cUnknown alliance action: §e" + action);
        }
    }

    private int clearTargetsBetweenAllies(BotManager botManager) {
        int n = 0;
        for (PvPBot bot : botManager.getBots().values()) {
            if (bot == null || !bot.isAlive()) continue;
            try {
                var ctx = bot.getAI().getContext();
                if (ctx.target == null) continue;
                if (!botManager.isFriendly(bot.getUUID(), ctx.target.getUniqueId())) continue;

                ctx.target = null;
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private void handleFormationCommand(Player sender, String factionName, String[] tail,
                                        BotManager botManager) {
        if (!botManager.factionExists(factionName)) { unknownFaction(sender, botManager, factionName); return; }

        FormationManager.Shape shape = FormationManager.Shape.GRID;
        boolean instant = true;
        double spacing = FormationManager.DEFAULT_SPACING;
        boolean modeGiven = false;
        boolean followLeader = false;

        for (String token : tail) {
            if (token.equalsIgnoreCase("leader") || token.equalsIgnoreCase("follow")) {
                followLeader = true; continue;
            }
            if (token.equalsIgnoreCase("smooth") || token.equalsIgnoreCase("walk")) {
                instant = false; modeGiven = true; continue;
            }
            if (token.equalsIgnoreCase("instant") || token.equalsIgnoreCase("teleport")
                    || token.equalsIgnoreCase("tp")) {
                instant = true; modeGiven = true; continue;
            }
            Double asSpacing = tryParseDouble(token);
            if (asSpacing != null) {
                if (asSpacing < 0.5 || asSpacing > 16.0) {
                    sender.sendMessage("§cSpacing must be between 0.5 and 16 blocks.");
                    return;
                }
                spacing = asSpacing;
                continue;
            }
            FormationManager.Shape parsed = FormationManager.Shape.parse(token);
            if (parsed == null) {
                sender.sendMessage("§cUnknown shape: §e" + token);
                sender.sendMessage("§7Shapes: §f" + String.join(", ", FormationManager.Shape.names()));
                return;
            }
            shape = parsed;
        }

        List<PvPBot> bots = botManager.getFactionBots(factionName);
        if (bots.isEmpty()) {
            sender.sendMessage("§cFaction §e" + factionName.toLowerCase() + "§c has no live bots to arrange.");
            return;
        }

        if (followLeader) {
            if (botManager.getFactionLeader(factionName) == null) {
                sender.sendMessage("§cFaction §e" + factionName.toLowerCase()
                        + "§c has no leader to follow.");
                sender.sendMessage("§7Set one with §f/pvpbot faction leader add <player|bot> "
                        + factionName.toLowerCase());
                return;
            }
            botManager.setFactionFormation(factionName, shape, spacing);
            sender.sendMessage("§e" + bots.size() + "§a bots of §e" + factionName.toLowerCase()
                    + "§a will hold a §e" + shape.name().toLowerCase()
                    + "§a behind their leader.");
            sender.sendMessage("§7They break formation as soon as any member is attacked.");
            return;
        }

        int moved = FormationManager.arrange(bots, sender.getLocation(), shape, spacing, instant);
        sender.sendMessage("§aArranged §e" + moved + "§a bots of §e" + factionName.toLowerCase()
                + "§a into a §e" + shape.name().toLowerCase() + "§a"
                + (instant ? " §7(instant)" : " §7(marching — they'll walk there)"));
        if (!modeGiven && !instant) return;
        if (!modeGiven) {
            sender.sendMessage("§8Tip: add §7smooth§8 to make them walk into position instead.");
        }
    }

    private void applyRandomKitsToFaction(Player sender, String[] args, BotManager botManager) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /pvpbot faction giverandomkit <faction|player> 20%kit1,30%kit2,50%kit3");
            return;
        }
        String target = args[2];

        StringBuilder raw = new StringBuilder();
        for (int i = 3; i < args.length; i++) raw.append(args[i]);

        WeightedSpec spec;
        try {
            spec = WeightedSpec.parse(raw.toString());
        } catch (WeightedSpec.SpecException e) {
            sender.sendMessage("§c" + e.getMessage());
            sender.sendMessage("§7Example: §f/pvpbot faction giverandomkit red 20%warrior,80%archer");
            return;
        }

        for (WeightedSpec.Entry entry : spec.entries()) {
            if (!plugin.getKitManager().kitExists(entry.value())) {
                sender.sendMessage("§cKit '§e" + entry.value() + "§c' does not exist.");
                return;
            }
        }

        if (botManager.factionExists(target)) {
            List<PvPBot> bots = botManager.getFactionBots(target);
            if (bots.isEmpty()) {
                sender.sendMessage("§cFaction §e" + target.toLowerCase() + "§c has no live bots.");
                return;
            }

            java.util.Map<String, Integer> handed = new java.util.LinkedHashMap<>();
            for (PvPBot bot : bots) {
                String chosen = spec.roll();
                bot.equipKit(chosen);
                handed.merge(chosen, 1, Integer::sum);
            }

            sender.sendMessage("§aRolled kits for §e" + bots.size() + "§a bots of §e"
                    + target.toLowerCase() + "§a:");
            for (var e : handed.entrySet()) {
                sender.sendMessage("  §7- §f" + e.getKey() + " §8x" + e.getValue());
            }
        } else {
            Player targetPlayer = Bukkit.getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                sender.sendMessage("§cNo online player or faction named §e" + target);
                return;
            }
            String chosen = spec.roll();
            equipKitToPlayer(targetPlayer, chosen);
            sender.sendMessage("§aRolled kit '" + chosen + "' for player §e" + targetPlayer.getName() + "§a.");
        }
    }

    private void handleFactionFormation(Player sender, String[] args, BotManager botManager) {
        handleFormationCommand(sender, args[1],
                java.util.Arrays.copyOfRange(args, Math.min(3, args.length), args.length),
                botManager);
    }

    private static final int FACTION_MOVETO_TICKS = 12000;

    private void handleFactionMoveTo(Player sender, String factionName, String[] coordArgs,
                                     BotManager botManager) {
        if (!botManager.factionExists(factionName)) {
            unknownFaction(sender, botManager, factionName);
            return;
        }
        if (coordArgs.length < 3) {
            sender.sendMessage("§cUsage: /pvpbot faction " + factionName + " moveto <x> <y> <z>");
            return;
        }

        Location origin = sender.getLocation();
        double x, y, z;
        try {
            x = parseCoord(coordArgs[0], origin.getX());
            y = parseCoord(coordArgs[1], origin.getY());
            z = parseCoord(coordArgs[2], origin.getZ());
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid coordinates. Usage: /pvpbot faction " + factionName + " moveto <x> <y> <z>");
            return;
        }

        List<PvPBot> bots = botManager.getFactionBots(factionName);
        if (bots.isEmpty()) {
            sender.sendMessage("§cFaction §e" + factionName.toLowerCase() + "§c has no live bots to move.");
            return;
        }

        Location anchor = new Location(sender.getWorld(), x, y, z, origin.getYaw(), 0f);
        List<Location> slots = FormationManager.compute(anchor, FormationManager.Shape.SKIRMISH,
                bots.size(), FormationManager.DEFAULT_SPACING);

        int moved = 0;
        for (int i = 0; i < bots.size() && i < slots.size(); i++) {
            PvPBot bot = bots.get(i);
            if (bot == null || !bot.isAlive()) continue;
            bot.orderToFormationSlot(slots.get(i), FACTION_MOVETO_TICKS);
            moved++;
        }

        sender.sendMessage("§aSending §e" + moved + "§a bot(s) of §e" + factionName.toLowerCase()
                + "§a to §f" + (int) x + " " + (int) y + " " + (int) z + "§a, spread out on the way.");
        sender.sendMessage("§7They'll path around obstacles - including mazes - on their own.");
    }

    private void handleFactionAttack(Player sender, String factionAName, String factionBName,
                                      BotManager botManager) {
        if (!botManager.factionExists(factionAName)) {
            unknownFaction(sender, botManager, factionAName);
            return;
        }

        List<PvPBot> attackers = botManager.getFactionBots(factionAName);
        if (attackers.isEmpty()) {
            sender.sendMessage("§cFaction §e" + factionAName.toLowerCase() + "§c has no live bots to send.");
            return;
        }

        String targetWord = factionBName.toLowerCase();
        if (targetWord.equals("off") || targetWord.equals("none") || targetWord.equals("stop")
                || targetWord.equals("cancel") || targetWord.equals("clear")) {
            int cleared = 0;
            for (PvPBot bot : attackers) {
                if (bot.getAI().getContext().forcedTarget != null) {
                    bot.setForcedTarget(null);
                    cleared++;
                }
            }
            sender.sendMessage("§e" + factionAName.toLowerCase()
                    + "§7 called off its attack (§e" + cleared + "§7 bot(s) released back to their own judgement).");
            return;
        }

        if (!botManager.factionExists(factionBName)) {
            unknownFaction(sender, botManager, factionBName);
            return;
        }
        if (factionAName.trim().equalsIgnoreCase(factionBName.trim())) {
            sender.sendMessage("§cA faction can't attack itself.");
            return;
        }

        List<PvPBot> victims = botManager.getFactionBots(factionBName);
        if (victims.isEmpty()) {
            sender.sendMessage("§cFaction §e" + factionBName.toLowerCase() + "§c has no live bots to attack.");
            return;
        }

        int ordered = 0;
        for (PvPBot attacker : attackers) {
            Player attackerPlayer = attacker.getBukkitPlayer();
            if (attackerPlayer == null) continue;

            PvPBot nearest = null;
            double bestSq = Double.MAX_VALUE;
            for (PvPBot victim : victims) {
                Player victimPlayer = victim.getBukkitPlayer();
                if (victimPlayer == null || victimPlayer.getWorld() != attackerPlayer.getWorld()) continue;
                double sq = victimPlayer.getLocation().distanceSquared(attackerPlayer.getLocation());
                if (sq < bestSq) {
                    bestSq = sq;
                    nearest = victim;
                }
            }

            if (nearest == null) continue;
            attacker.setForcedTarget(nearest.getBukkitPlayer());
            ordered++;
        }

        if (ordered == 0) {
            sender.sendMessage("§cCouldn't find any §e" + factionBName.toLowerCase()
                    + "§c bots in the same world as §e" + factionAName.toLowerCase() + "§c's bots.");
            return;
        }

        sender.sendMessage("§a" + factionAName.toLowerCase() + " §ais attacking §e" + factionBName.toLowerCase()
                + "§a — §e" + ordered + "§a of §e" + attackers.size() + "§a bot(s) sent after their nearest target.");
        sender.sendMessage("§8Ignores passive mode and guard leash until their target dies or you cancel with"
                + " §f/pvpbot faction " + factionAName.toLowerCase() + " attack off");
    }

    private void handleFactionMineArea(Player sender, String[] args, BotManager botManager) {
        String factionName = args[2];
        
        if (!botManager.factionExists(factionName)) {
            unknownFaction(sender, botManager, factionName);
            return;
        }

        if (args.length >= 4 && args[3].equalsIgnoreCase("stop")) {
            com.pvpbot.mine.AreaMiningJob.stop(factionName);
            int stopped = 0;
            for (PvPBot bot : botManager.getFactionBots(factionName)) {
                if (bot.getAI().getContext().areaMiningController != null 
                        && bot.getAI().getContext().areaMiningController.isActive()) {
                    bot.getAI().getContext().areaMiningController.abort();
                    stopped++;
                }
            }
            sender.sendMessage("§eStopped area mining for " + stopped + " bot(s) in §e" + factionName.toLowerCase() + "§e.");
            return;
        }

        UUID leader = botManager.getFactionLeader(factionName);
        org.bukkit.entity.Player leaderPlayer;
        
        if (leader != null) {
            PvPBot leaderBot = botManager.getBots().get(leader);
            
            if (leaderBot != null) {
                // Leader is a bot
                if (!leaderBot.isAlive()) {
                    leaderPlayer = sender;
                } else {
                    leaderPlayer = leaderBot.getBukkitPlayer();
                    if (leaderPlayer == null) {
                        leaderPlayer = sender;
                    }
                }
            } else {
                // Leader is a real player
                leaderPlayer = org.bukkit.Bukkit.getPlayer(leader);
                if (leaderPlayer == null || !leaderPlayer.isOnline()) {
                    leaderPlayer = sender;
                }
            }
        } else {
            // No leader set, use sender
            leaderPlayer = sender;
        }

        double radius = 20.0;
        if (args.length >= 4) {
            try {
                radius = Double.parseDouble(args[3]);
                if (radius < 5.0 || radius > 100.0) {
                    sender.sendMessage("§cRadius must be between 5 and 100 blocks.");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid radius: §e" + args[3]);
                return;
            }
        }

        int seconds = 300;
        if (args.length >= 5) {
            try {
                seconds = Integer.parseInt(args[4]);
                if (seconds < 10 || seconds > 7200) {
                    sender.sendMessage("§cTime must be between 10 and 7200 seconds.");
                    return;
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid time: §e" + args[4]);
                return;
            }
        }

        List<PvPBot> factionBots = botManager.getFactionBots(factionName);
        List<PvPBot> eligible = new ArrayList<>();
        int noPick = 0;
        
        for (PvPBot bot : factionBots) {
            if (bot.getUUID().equals(leader)) continue;
            if (!bot.isAlive()) continue;
            
            Player bp = bot.getBukkitPlayer();
            if (bp == null || bp.getWorld() != leaderPlayer.getWorld()) continue;
            
            if (bot.getAI().getContext().miningController.hasPickaxe()) {
                eligible.add(bot);
            } else {
                noPick++;
            }
        }

        if (eligible.isEmpty()) {
            sender.sendMessage("§cNo eligible bots in faction §e" + factionName.toLowerCase() + "§c have pickaxes.");
            return;
        }

        com.pvpbot.mine.AreaMiningJob job = com.pvpbot.mine.AreaMiningJob.start(
                factionName, leaderPlayer.getWorld(), leaderPlayer.getLocation(), 
                radius, seconds, sender.getUniqueId());

        int joined = 0;
        for (PvPBot bot : eligible) {
            String error = bot.getAI().getContext().areaMiningController.join(job, eligible.size());
            if (error == null) {
                joined++;
            } else {
                sender.sendMessage("§cBot §e" + bot.getName() + "§c couldn't join: §e" + error);
            }
        }

        if (joined == 0) {
            com.pvpbot.mine.AreaMiningJob.stop(factionName);
            sender.sendMessage("§cNo bots could start mining.");
            return;
        }

        sender.sendMessage("§a" + joined + " bot(s) from §e" + factionName.toLowerCase() + "§a are now mining around their leader.");
        sender.sendMessage("§7Radius: §f" + radius + "§7 blocks, Time: §f" + seconds + "§7s.");
        if (noPick > 0) {
            sender.sendMessage("§8  " + noPick + " skipped — no pickaxe.");
        }
    }

    private void handleGolemCommand(Player player, String[] args, BotManager manager) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /pvpbot golem <bot> fight §7| §estop");
            return;
        }

        PvPBot golemBot = manager.getBotByName(args[1]);
        if (golemBot == null) {
            player.sendMessage("§cBot not found!");
            return;
        }

        Player gp = golemBot.getBukkitPlayer();
        if (gp == null) {
            player.sendMessage("§cThat bot is not currently in the world.");
            return;
        }

        var gfc = golemBot.getAI().getContext().golemFightController;
        String action = args[2].toLowerCase();

        switch (action) {
            case "fight" -> {
                String err = gfc.start(gp);
                if (err != null) {
                    player.sendMessage("§c" + err);
                    return;
                }
                player.sendMessage("§a" + golemBot.getName()
                        + "§a is spawning an iron golem and pillaring up §e3§a blocks to fight it.");
                player.sendMessage("§7/pvpbot golem " + golemBot.getName() + " stop §7to call it off early.");
            }
            case "stop" -> {
                if (!gfc.isActive()) {
                    player.sendMessage("§7" + golemBot.getName() + " §7isn't golem-fighting.");
                    return;
                }
                gfc.stop();
                player.sendMessage("§eStopped " + golemBot.getName() + "'s golem fight.");
            }
            default -> player.sendMessage("§cUsage: /pvpbot golem <bot> fight §7| §estop");
        }
    }

    private static final java.util.Set<String> RESERVED_GROUP_NAMES =
            java.util.Set.of("create", "disband", "list", "info", "leader");

    private void handleTopLevelGroupCommand(Player player, String[] args, BotManager manager) {
        if (args.length < 2) {
            sendGroupUsage(player, manager);
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "create" -> {
                if (args.length < 5) {
                    player.sendMessage("§cUsage: /pvpbot group create <faction> <groupname> <amount>");
                    return;
                }
                String faction = args[2];
                String groupName = args[3];

                if (!manager.factionExists(faction)) {
                    unknownFaction(player, manager, faction);
                    return;
                }
                if (RESERVED_GROUP_NAMES.contains(groupName.toLowerCase())) {
                    player.sendMessage("§cGroup name §e" + groupName + "§c is reserved. Pick another name.");
                    return;
                }
                if (manager.groupExists(groupName)) {
                    player.sendMessage("§cA group called §e" + groupName
                            + "§c already exists. Disband it first: §f/pvpbot group disband " + groupName);
                    return;
                }

                int amount;
                try {
                    amount = Integer.parseInt(args[4]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid amount. Usage: /pvpbot group create <faction> <groupname> <amount>");
                    return;
                }
                if (amount < 1) {
                    player.sendMessage("§cAmount must be at least 1.");
                    return;
                }

                List<PvPBot> pulled = manager.createGroup(groupName, faction, amount);
                if (pulled == null) {
                    player.sendMessage("§cA group called §e" + groupName + "§c already exists.");
                    return;
                }
                if (pulled.isEmpty()) {
                    manager.disbandGroup(groupName);
                    player.sendMessage("§cNo ungrouped live bots found in faction §e" + faction.toLowerCase() + "§c.");
                    return;
                }

                player.sendMessage("§aFormed group §e" + groupName + "§a with §e" + pulled.size()
                        + "§a bot(s) pulled from §e" + faction.toLowerCase() + "§a.");
                if (pulled.size() < amount) {
                    player.sendMessage("§7(asked for " + amount + ", but only that many ungrouped bots were available)");
                }
                player.sendMessage("§7Give it a leader: §f/pvpbot group " + groupName + " leader add <player>");
            }

            case "disband" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /pvpbot group disband <groupname>");
                    return;
                }
                String groupName = args[2];
                if (!manager.disbandGroup(groupName)) {
                    player.sendMessage("§cNo group called §e" + groupName + "§c.");
                    return;
                }
                player.sendMessage("§eDisbanded group §f" + groupName
                        + "§e. Its bots go back to following the faction leader.");
            }

            case "list" -> {
                var names = args.length >= 3
                        ? manager.getGroupNamesForFaction(args[2])
                        : manager.getGroupNames();
                if (names.isEmpty()) {
                    player.sendMessage("§7No groups yet. §f/pvpbot group create <faction> <groupname> <amount>");
                    return;
                }
                player.sendMessage("§6§lGroups (" + names.size() + "):");
                for (String name : names.stream().sorted().toList()) {
                    BotManager.BotGroup g = manager.getGroup(name);
                    if (g == null) continue;
                    player.sendMessage("  §7- §e" + name + " §8(" + g.getFaction() + ", "
                            + g.getMembers().size() + " bots, "
                            + (g.getLeader() != null ? "led by " + describeMember(manager, g.getLeader()) : "no leader")
                            + ")");
                }
            }

            case "info" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /pvpbot group info <groupname>");
                    return;
                }
                BotManager.BotGroup g = manager.getGroup(args[2]);
                if (g == null) {
                    player.sendMessage("§cNo group called §e" + args[2] + "§c.");
                    return;
                }
                player.sendMessage("§6§lGroup " + g.getName() + " §7(faction " + g.getFaction() + ")");
                player.sendMessage("§7Leader: §f"
                        + (g.getLeader() != null ? describeMember(manager, g.getLeader()) : "none"));
                player.sendMessage("§7Members (" + g.getMembers().size() + "):");
                for (UUID uuid : g.getMembers()) {
                    player.sendMessage("  §7- §f" + describeMember(manager, uuid));
                }
            }

            default -> {
                if (manager.groupExists(action) && args.length >= 3 && args[2].equalsIgnoreCase("leader")) {
                    handleGroupLeaderCommand(player, action, args, manager);
                } else if (manager.groupExists(action)) {
                    player.sendMessage("§cWhat do you want to do with group §e" + action
                            + "§c? Try: §f/pvpbot group " + action + " leader add <player>");
                } else {
                    player.sendMessage("§cUnknown group action or group: §e" + action);
                    sendGroupUsage(player, manager);
                }
            }
        }
    }

    private void handleGroupLeaderCommand(Player player, String groupName, String[] args, BotManager manager) {
        BotManager.BotGroup group = manager.getGroup(groupName);
        String action = args.length >= 4 ? args[3].toLowerCase() : "";

        switch (action) {
            case "add", "set" -> {
                if (args.length < 5) {
                    player.sendMessage("§cUsage: /pvpbot group " + groupName + " leader add <player>");
                    return;
                }
                UUID targetUUID = resolveMemberUUID(manager, args[4]);
                if (targetUUID == null) {
                    player.sendMessage("§cPlayer/Bot not found: §e" + args[4]);
                    return;
                }

                UUID previous = group.getLeader();
                manager.setGroupLeader(groupName, targetUUID);

                if (previous != null && !previous.equals(targetUUID)) {
                    player.sendMessage("§7Replaced previous leader §f" + describeMember(manager, previous) + "§7.");
                }
                player.sendMessage("§a" + args[4] + "§a now leads group §e" + groupName
                        + "§a — §e" + manager.getGroupBots(groupName).size()
                        + "§a bot(s) will escort and defend them.");
            }

            case "remove", "clear", "delete" -> {
                if (group.getLeader() == null) {
                    player.sendMessage("§7Group §e" + groupName + "§7 has no leader.");
                    return;
                }
                manager.clearGroupLeader(groupName);
                player.sendMessage("§cCleared the leader of group §e" + groupName
                        + "§c — its bots go back to following the faction leader (if any).");
            }

            default -> player.sendMessage("§cUsage: /pvpbot group " + groupName + " leader <add|remove> <player>");
        }
    }

    private void sendGroupUsage(Player player, BotManager manager) {
        player.sendMessage("§6§lGroup commands:");
        player.sendMessage("§e /pvpbot group create <faction> <groupname> <amount> §7— pulls ungrouped bots into a squad");
        player.sendMessage("§e /pvpbot group disband <groupname>");
        player.sendMessage("§e /pvpbot group <groupname> leader add/remove <player>");
        player.sendMessage("§e /pvpbot group list [faction] §7| §einfo <groupname>");
        player.sendMessage("§8A group's leader overrides the faction leader for its own members only.");
        var names = manager.getGroupNames();
        if (!names.isEmpty()) {
            player.sendMessage("§7Existing groups: §f" + String.join(", ", names.stream().sorted().toList()));
        }
    }

    private void handleGroupFormation(Player sender, String[] args, BotManager botManager) {
        GroupProvider provider = Bukkit.getServicesManager().load(GroupProvider.class);
        if (provider == null) {
            sender.sendMessage("§cGroup support requires the §epvpbot-groups§c plugin, which is not installed.");
            return;
        }
        if (args.length < 3) { sender.sendMessage("§cUsage: /pvpbot faction group <group> formation grid [spacing]"); return; }
        String groupName = args[2];
        if (!provider.groupExists(groupName)) {
            sender.sendMessage("§cGroup not found: §e" + groupName);
            return;
        }
        if (args.length < 5 || !args[3].equalsIgnoreCase("formation") || !args[4].equalsIgnoreCase("grid")) {
            sender.sendMessage("§cUsage: /pvpbot faction group " + groupName + " formation grid [spacing]");
            return;
        }
        double spacing = parseSpacing(sender, args, 5);
        if (spacing < 0) return;

        List<PvPBot> bots = new ArrayList<>();
        for (UUID uuid : provider.getGroupMembers(groupName)) {
            PvPBot bot = botManager.getBots().get(uuid);
            if (bot != null && bot.isAlive()) bots.add(bot);
        }
        if (bots.isEmpty()) {
            sender.sendMessage("§cGroup §e" + groupName + "§c has no live bots to arrange.");
            return;
        }
        int moved = FormationManager.arrangeGrid(bots, sender.getLocation(), spacing);
        sender.sendMessage("§aArranged §e" + moved + "§a bots of group §e" + groupName + "§a into a grid.");
    }

    private double parseSpacing(Player sender, String[] args, int index) {
        if (args.length <= index) return FormationManager.DEFAULT_SPACING;
        try {
            double spacing = Double.parseDouble(args[index]);
            if (spacing < 0.5 || spacing > 16.0) {
                sender.sendMessage("§cSpacing must be between 0.5 and 16 blocks.");
                return -1;
            }
            return spacing;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cSpacing must be a number, e.g. 2.5");
            return -1;
        }
    }

    private void unknownFaction(Player sender, BotManager botManager, String name) {
        var names = botManager.getFactionNames();
        sender.sendMessage("§cFaction §e" + name + "§c does not exist."
                + (names.isEmpty() ? "" : " §7Existing: §f" + String.join(", ", names.stream().sorted().toList())));
    }

    private void sendFactionUsage(Player sender, BotManager botManager) {
        sender.sendMessage("§6§lFaction commands:");
        sender.sendMessage("§e /pvpbot faction list §7— show all factions");
        sender.sendMessage("§e /pvpbot faction info <name> §7— show members");
        sender.sendMessage("§e /pvpbot faction add <name> <bot|player> §7— auto-creates the faction");
        sender.sendMessage("§e /pvpbot faction addall <name> §7| §eaddnear <radius> <name>");
        sender.sendMessage("§e /pvpbot faction leader add <player|bot> [faction] §7— escort + defend");
        sender.sendMessage("§e /pvpbot faction leader remove <faction> §7| §eleader list");
        sender.sendMessage("§e /pvpbot faction formation <name> <shape> [smooth|instant|leader follow]");
        sender.sendMessage("§7   shapes: §f" + String.join(", ", FormationManager.Shape.names()));
        sender.sendMessage("§e /pvpbot faction giverandomkit <faction|player> 20%kit1,80%kit2");
        sender.sendMessage("§e /pvpbot faction givekit <faction|player> <kit> §7| §eremove <name>");
        sender.sendMessage("§e /pvpbot faction armor <on|off> <name> §7— equip/remove armor");
        sender.sendMessage("§e /pvpbot faction alliance create <name> §7— allied factions stop fighting");
        sender.sendMessage("§e /pvpbot faction stopattack <name> <true|false> §7— stand down for intimidation shots");
        sender.sendMessage("§e /pvpbot faction formation <name> <shape> [smooth|instant]");
        sender.sendMessage("§e /pvpbot faction <name> moveto <x> <y> <z> §7— march there spread out, pathing around obstacles");
        sender.sendMessage("§e /pvpbot faction <name> attack <name> §7— sends every bot after its nearest enemy in that faction");
        sender.sendMessage("§8   /pvpbot faction <name> attack off §7— call it off");
        var names = botManager.getFactionNames();
        if (!names.isEmpty()) {
            sender.sendMessage("§7Your factions: §f" + String.join(", ", names.stream().sorted().toList()));
        }
    }

    private void handleLeaderCommand(Player sender, String[] args, BotManager botManager) {
        String action = args.length >= 3 ? args[2].toLowerCase() : "list";

        switch (action) {
            case "list" -> {
                var leaders = botManager.getFactionLeaders();
                if (leaders.isEmpty()) {
                    sender.sendMessage("§7No faction leaders yet. §f/pvpbot faction leader add <player|bot>");
                    return;
                }
                sender.sendMessage("§6§lFaction leaders (" + leaders.size() + "):");
                for (var entry : leaders.entrySet()) {
                    sender.sendMessage("  §7- §e" + entry.getKey() + "§7 led by §f"
                            + describeMember(botManager, entry.getValue())
                            + " §8(" + botManager.getFactionBots(entry.getKey()).size() + " bots following)");
                }
            }

            case "add", "set" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /pvpbot faction leader add <player|bot> [faction]");
                    return;
                }

                String targetName = args[3];
                String factionName = args.length >= 5 ? args[4] : null;
                UUID targetUUID = resolveMemberUUID(botManager, targetName);
                if (targetUUID == null && factionName != null) {
                    UUID swapped = resolveMemberUUID(botManager, factionName);
                    if (swapped != null) {
                        targetUUID = swapped;
                        String tmp = targetName;
                        targetName = factionName;
                        factionName = tmp;
                    }
                }
                if (targetUUID == null) {
                    sender.sendMessage("§cPlayer/Bot not found: §e" + targetName);
                    return;
                }

                if (factionName == null) {
                    factionName = botManager.getPlayerFaction(targetUUID);
                    if (factionName == null) {
                        sender.sendMessage("§c" + targetName + " isn't in a faction yet — name one: "
                                + "§f/pvpbot faction leader add " + targetName + " <faction>");
                        return;
                    }
                }

                UUID previous = botManager.getFactionLeader(factionName);
                boolean created = botManager.setFactionLeader(factionName, targetUUID);

                if (created) sender.sendMessage("§7Faction §e" + factionName.toLowerCase()
                        + "§7 didn't exist yet — created it.");
                if (previous != null && !previous.equals(targetUUID)) {
                    sender.sendMessage("§7Replaced previous leader §f"
                            + describeMember(botManager, previous) + "§7.");
                }
                sender.sendMessage("§a" + targetName + "§a now leads §e" + factionName.toLowerCase()
                        + "§a — §e" + botManager.getFactionBots(factionName).size()
                        + "§a bot(s) will escort them and defend them.");
            }

            case "remove", "clear", "delete" -> {
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /pvpbot faction leader remove <faction|player|bot>");
                    return;
                }
                String name = args[3];
                String factionName = botManager.factionExists(name) ? name : null;
                if (factionName == null) {
                    UUID uuid = resolveMemberUUID(botManager, name);
                    if (uuid != null) factionName = botManager.getPlayerFaction(uuid);
                }
                if (factionName == null) {
                    sender.sendMessage("§cNo faction or member found for: §e" + name);
                    return;
                }
                if (botManager.getFactionLeader(factionName) == null) {
                    sender.sendMessage("§7Faction §e" + factionName.toLowerCase() + "§7 has no leader.");
                    return;
                }
                botManager.clearFactionLeader(factionName);
                sender.sendMessage("§cCleared the leader of §e" + factionName.toLowerCase()
                        + "§c — its bots go back to holding position.");
            }

            default -> sender.sendMessage("§cUsage: /pvpbot faction leader <add|remove|list> ...");
        }
    }

    private UUID resolveMemberUUID(BotManager botManager, String name) {
        Player online = Bukkit.getPlayer(name);
        if (online != null) return online.getUniqueId();
        PvPBot bot = botManager.getBotByName(name);
        return bot != null ? bot.getUUID() : null;
    }

    private String describeMember(BotManager botManager, UUID uuid) {
        PvPBot bot = botManager.getBots().get(uuid);
        if (bot != null) return org.bukkit.ChatColor.stripColor(bot.getName()) + " §8[bot]";
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName() + " §8[player]";
        return uuid.toString().substring(0, 8) + "… §8[offline]";
    }

    private void addSingleMember(Player sender, BotManager botManager, String faction, String target) {
        UUID targetUUID = null;
        Player targetPlayer = Bukkit.getPlayer(target);
        if (targetPlayer != null) targetUUID = targetPlayer.getUniqueId();
        else {
            PvPBot bot = botManager.getBotByName(target);
            if (bot != null) targetUUID = bot.getUUID();
        }
        if (targetUUID == null) { sender.sendMessage("§cPlayer/Bot not found: §e" + target); return; }
        boolean created = botManager.addPlayerToFaction(faction, targetUUID);
        if (created) sender.sendMessage("§7Faction §e" + faction.toLowerCase() + "§7 didn't exist yet — created it.");
        sender.sendMessage("§aAdded §e" + target + "§a to §e" + faction.toLowerCase());
    }

    private void applyKitToFaction(Player sender, String[] args, BotManager botManager) {
        if (args.length < 4) { sender.sendMessage("§cUsage: /pvpbot faction givekit <factionName|playerName> <kitName>"); return; }
        String target = args[2];
        String kitName = args[3].toLowerCase();
        if (!plugin.getKitManager().kitExists(kitName)) {
            sender.sendMessage("§cKit '§e" + kitName + "§c' does not exist.");
            return;
        }

        if (botManager.factionExists(target)) {
            int equipped = 0;
            for (UUID memberUUID : botManager.getFactionMembers(target)) {
                PvPBot bot = botManager.getBots().get(memberUUID);
                if (bot != null && bot.getBukkitPlayer() != null) {
                    bot.equipKit(kitName);
                    equipped++;
                }
            }
            sender.sendMessage("§aEquipped kit '" + kitName + "' to " + equipped + " members of faction " + target.toLowerCase() + ".");
        } else {
            Player targetPlayer = Bukkit.getPlayerExact(target);
            if (targetPlayer == null || !targetPlayer.isOnline()) {
                sender.sendMessage("§cNo online player or faction named §e" + target);
                return;
            }
            equipKitToPlayer(targetPlayer, kitName);
            sender.sendMessage("§aEquipped kit '" + kitName + "' to player §e" + targetPlayer.getName() + "§a.");
        }
    }

    private void equipKitToPlayer(Player player, String kitName) {
        KitManager kitManager = plugin.getKitManager();
        ItemStack[] contents = kitManager.getKitContents(kitName);
        ItemStack[] armor = kitManager.getKitArmor(kitName);
        ItemStack offhand = kitManager.getKitOffhand(kitName);

        player.getInventory().clear();
        player.getInventory().setContents(contents);

        player.getInventory().setBoots(armor[0]);
        player.getInventory().setLeggings(armor[1]);
        player.getInventory().setChestplate(armor[2]);
        player.getInventory().setHelmet(armor[3]);
        player.getInventory().setItemInOffHand(offhand);

        player.updateInventory();
    }

    private void handleGiveCommand(Player sender, String[] args, BotManager botManager) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /pvpbot give <botname|@bot> <item> [amount]");
            return;
        }

        String target = args[1];
        String itemName = args[2];
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cInvalid amount.");
                return;
            }
        }

        Material material;
        try {
            material = Material.valueOf(itemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid item: " + itemName);
            return;
        }

        ItemStack item = new ItemStack(material, amount);
        int given = 0;

        if (target.equals("@bot")) {
            for (PvPBot bot : botManager.getBots().values()) {
                if (bot.getBukkitPlayer() != null) {
                    bot.getBukkitPlayer().getInventory().addItem(item);
                    given++;
                }
            }
            sender.sendMessage("§aGave " + amount + "x " + itemName + " to " + given + " bots.");
        } else {
            PvPBot bot = botManager.getBotByName(target);
            if (bot == null) {
                sender.sendMessage("§cBot not found: " + target);
                return;
            }
            if (bot.getBukkitPlayer() != null) {
                bot.getBukkitPlayer().getInventory().addItem(item);
                sender.sendMessage("§aGave " + amount + "x " + itemName + " to " + bot.getName());
            }
        }
    }

    private void handleSetCommand(Player sender, String[] args, BotManager manager) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: §f/pvpbot set <bot|faction|all|global> <setting> <value>");
            sender.sendMessage("§8  /pvpbot set Steve bridging false");
            sender.sendMessage("§8  /pvpbot set red macesmash true §7— every bot in faction 'red'");
            sender.sendMessage("§8  /pvpbot set all reach 3.2");
            sender.sendMessage("§8  /pvpbot set global reach 3.2 §7— the default for FUTURE bots");
            sender.sendMessage("§7Settings: §f" + String.join(", ", BotSettings.optionKeys()));
            return;
        }

        String who = args[1];
        BotSettings.Option option = BotSettings.option(args[2]);
        if (option == null) {
            sender.sendMessage("§cThere is no setting called §e" + args[2] + "§c.");
            sender.sendMessage("§7Settings: §f" + String.join(", ", BotSettings.optionKeys()));
            return;
        }

        StringBuilder rawValue = new StringBuilder(args[3]);
        for (int i = 4; i < args.length; i++) rawValue.append(' ').append(args[i]);
        String value = rawValue.toString();

        List<BotSettings> targets = new ArrayList<>();
        String label;

        if (who.equalsIgnoreCase("global") || who.equalsIgnoreCase("default")) {
            targets.add(manager.getGlobalSettings());
            label = "the global default";
        } else if (who.equalsIgnoreCase("all")) {
            for (PvPBot bot : manager.getBots().values()) {
                targets.add(manager.getBotSettings(bot.getUUID()));
            }
            label = targets.size() + " live bot(s)";
        } else {
            List<PvPBot> crew = resolveBots(who, manager);
            if (crew.isEmpty()) {
                sender.sendMessage("§cNo bot or faction called §e" + who + "§c.");
                return;
            }
            for (PvPBot bot : crew) targets.add(manager.getBotSettings(bot.getUUID()));
            label = crew.size() == 1
                    ? org.bukkit.ChatColor.stripColor(crew.get(0).getName())
                    : crew.size() + " bot(s) in " + who.toLowerCase();
        }

        if (targets.isEmpty()) {
            sender.sendMessage("§cNothing to change.");
            return;
        }

        String error = null;
        for (BotSettings settings : targets) {
            String problem = option.set(settings, value);
            if (problem != null) error = problem;
        }
        if (error != null) {
            sender.sendMessage("§c" + error);
            return;
        }

        sender.sendMessage("§a" + option.label + " §7on §f" + label + "§7 is now §f"
                + option.display(targets.get(0)));
    }

    private void handleKitCommand(Player player, String[] args, BotManager manager, KitManager kitManager) {
        if (args.length < 3) {
            player.sendMessage("§cUsage: /pvpbot kit <create|remove|give> <kitname> [botname]");
            return;
        }

        String action = args[1].toLowerCase();
        String kitName = args[2].toLowerCase();

        if (action.equals("create")) {
            kitManager.saveKit(kitName,
                    player.getInventory().getStorageContents(),
                    player.getInventory().getArmorContents(),
                    player.getInventory().getItemInOffHand());
            player.sendMessage("§aKit '§e" + kitName + "§a' saved successfully!");
        }
        else if (action.equals("remove")) {
            if (kitManager.removeKit(kitName)) {
                player.sendMessage("§eKit '" + kitName + "' removed.");
            } else {
                player.sendMessage("§cKit '" + kitName + "' does not exist.");
            }
        }
        else if (action.equals("give")) {
            if (args.length < 4) {
                player.sendMessage("§cUsage: /pvpbot kit give <kitname> <botname>");
                return;
            }
            if (!kitManager.kitExists(kitName)) {
                player.sendMessage("§cKit '" + kitName + "' does not exist.");
                return;
            }
            PvPBot bot = manager.getBotByName(args[3]);
            if (bot == null) {
                player.sendMessage("§cBot not found!");
                return;
            }
            bot.equipKit(kitName);
            player.sendMessage("§aApplied kit '§e" + kitName + "§a' to " + bot.getName());
        }
    }

    private void applyKit(Player p, String kitName) {
        p.getInventory().clear();
        PvPBot bot = plugin.getBotManager().getBotByName(p.getName());
        if (bot != null) bot.equipKit(kitName);
        p.updateInventory();
    }

    private ItemStack enchant(ItemStack item, Object... enchantsAndLevels) {
        for (int i = 0; i < enchantsAndLevels.length; i += 2) {
            Enchantment ench = (Enchantment) enchantsAndLevels[i];
            int level = (Integer) enchantsAndLevels[i+1];
            item.addUnsafeEnchantment(ench, level);
        }
        return item;
    }

    private static BotDifficulty parseDifficulty(String raw) {
        if (raw == null) return null;
        switch (raw.toUpperCase(java.util.Locale.ROOT)) {
            case "INSANE", "IMPOSSIBLE", "GOD", "MAX" -> { return BotDifficulty.EXPERT; }
            case "MEDIUM", "MID" -> { return BotDifficulty.NORMAL; }
            case "NOOB", "BAD" -> { return BotDifficulty.EASY; }
            default -> { }
        }
        try {
            return BotDifficulty.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private WeightedSpec parseDifficultySpec(Player player, String token) {
        WeightedSpec spec;
        try {
            spec = WeightedSpec.parse(token);
        } catch (WeightedSpec.SpecException e) {
            player.sendMessage("§c" + e.getMessage());
            player.sendMessage("§7Example: §f/pvpbot masspawn 20 50%easy,35%normal,15%insane");
            return null;
        }
        for (WeightedSpec.Entry entry : spec.entries()) {
            if (parseDifficulty(entry.value()) == null) {
                player.sendMessage("§cUnknown difficulty: §e" + entry.value());
                player.sendMessage("§7Valid: §feasy, normal, hard, expert §7(insane = expert)");
                return null;
            }
        }
        return spec;
    }

    private void startDebugWatch(Player player, PvPBot bot, int seconds) {
        final int totalTicks = Math.max(20, Math.min(1200, seconds * 20));
        final String botName = org.bukkit.ChatColor.stripColor(bot.getName());

        final java.io.File logFile =
                new java.io.File(plugin.getDataFolder(), "debug-" + botName + ".log");
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            java.nio.file.Files.write(logFile.toPath(),
                    ("=== watch start " + new java.util.Date() + " ===" + System.lineSeparator()).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        player.sendMessage("§aWatching §f" + botName + "§a for §f" + (totalTicks / 20)
                + "s§a. Changes print here; every sample goes to §f" + logFile.getName());

        new org.bukkit.scheduler.BukkitRunnable() {
            int elapsed = 0;
            String last = null;

            @Override
            public void run() {
                var ai = bot.getAI();
                Player bp = bot.getBukkitPlayer();
                if (elapsed >= totalTicks || !player.isOnline() || ai == null || bp == null) {
                    player.sendMessage("§7Watch finished - full log: §f"
                            + logFile.getAbsolutePath());
                    cancel();
                    return;
                }
                elapsed += 5;

                var ctx = ai.getContext();
                String gate = ctx.lastAttackGate == null ? "-" : ctx.lastAttackGate;
                String mace = ctx.maceController.debugLine();
                String tgt;
                if (ctx.target == null) {
                    tgt = "tgt=NONE";
                } else {
                    tgt = String.format("tgt=%s@%.1f blk=%s axe=%s hand=%s",
                            org.bukkit.ChatColor.stripColor(ctx.target.getName()),
                            bp.getLocation().distance(ctx.target.getLocation()),
                            com.pvpbot.ai.InventoryController
                                    .isBlockingWithShield(ctx.target) ? "Y" : "n",
                            ctx.inventoryController.findBestAxeSlot(bp) >= 0 ? "Y" : "NO",
                            bp.getInventory().getItemInMainHand().getType());
                }
                String state = String.format("hp%.0f%s%s",
                        bp.getHealth(),
                        ctx.fleeing ? " FLEEING" : "",
                        ctx.eating ? " EATING" : "");
                String line = tgt + " | " + state + " | " + gate + " | " + mace;

                try {
                    java.nio.file.Files.write(logFile.toPath(),
                            ("[" + elapsed + "] " + line + System.lineSeparator()).getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ignored) {
                }

                if (!line.equals(last)) {
                    last = line;
                    player.sendMessage("§8[" + (elapsed / 20) + "s] §e" + gate + " §8| §d" + mace);
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void handleDebugCommand(Player player, String[] args, BotManager manager) {
        PvPBot bot = null;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("watch")) {
            bot = manager.getBotByName(args[1]);
            if (bot == null) {
                player.sendMessage("§cNo bot named §e" + args[1]);
                return;
            }
        } else {
            double best = Double.MAX_VALUE;
            for (PvPBot b : manager.getBots().values()) {
                Player bp = b.getBukkitPlayer();
                if (bp == null || bp.getWorld() != player.getWorld()) continue;
                double d = bp.getLocation().distanceSquared(player.getLocation());
                if (d < best) { best = d; bot = b; }
            }
            if (bot == null) {
                player.sendMessage("§cNo bots nearby. Spawn one first.");
                return;
            }
        }

        Player bp = bot.getBukkitPlayer();
        var handle = bot.getHandle();
        var ctx = bot.getAI().getContext();
        if (bp == null || handle == null) {
            player.sendMessage("§cThat bot has no live handle.");
            return;
        }

        for (int i = 1; i < args.length; i++) {
            if (!args[i].equalsIgnoreCase("watch")) continue;
            int seconds = 30;
            if (i + 1 < args.length) {
                try {
                    seconds = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
            startDebugWatch(player, bot, seconds);
            return;
        }

        var delta = handle.getDeltaMovement();
        double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double attrSpeed = handle.getAttributeValue(
                net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);

        player.sendMessage("§6§lPvPBot debug: §e" + org.bukkit.ChatColor.stripColor(bot.getName())
                + " §8[diag-build 4]");

        int fails = ctx.movementController.doTickFailures;
        player.sendMessage(fails > 0
                ? "§c§lPHYSICS NOT RUNNING §7— doTick() failing, " + fails + " consecutive. Check console."
                : "§7physics tick: §aok");

        player.sendMessage(String.format(
                "§7speed field: %s%.4f§7  attr: §f%.4f§7  onGround: %s%s",
                handle.getSpeed() < 0.001 ? "§c" : "§a", handle.getSpeed(),
                attrSpeed,
                handle.onGround() ? "§a" : "§e", handle.onGround()));

        player.sendMessage(String.format(
                "§7vertY: §f%.4f§7  fallDist: §f%.2f§7  noPhysics: %s§7  mode: §f%s",
                delta.y, bp.getFallDistance(),
                handle.noPhysics ? "§cyes" : "no", bp.getGameMode()));

        player.sendMessage(String.format(
                "§7velocity: §f%.3f b/t §8(%.2f b/s)§7  zza: §f%.2f§7 xxa: §f%.2f",
                horiz, horiz * 20.0, handle.zza, handle.xxa));

        player.sendMessage(String.format(
                "§7inputs: fwd §f%.2f§7 strafe §f%.2f§7  sprint: %s§7 sneak: %s§7 usingItem: %s",
                ctx.forwardInput, ctx.strafeInput,
                handle.isSprinting() ? "§ayes" : "§cno",
                handle.isShiftKeyDown() ? "§cyes" : "§7no",
                handle.isUsingItem() ? "§cyes" : "§7no"));

        player.sendMessage(String.format(
                "§7suppressors: kbRide §f%d§7 avoid §f%d§7 smash §f%d§7 jumpBan §f%d§7 swap §f%d",
                ctx.knockbackRideTicks, ctx.avoidTicks, ctx.smashThreatTicks,
                ctx.jumpBanTicks, ctx.offhandSwapTicks));

        player.sendMessage(String.format(
                "§7state: food §f%d§7 eating: %s§7 drink §f%d§7 fleeing: %s§7 target: §f%s",
                bp.getFoodLevel(),
                ctx.eating ? "§cyes" : "§7no",
                ctx.drinkingPotionTimer,
                ctx.fleeing ? "§eyes" : "§7no",
                ctx.target == null ? "none" : ctx.target.getName()));

        player.sendMessage(String.format(
                "§7nav: branch §f%s§7  wallBump §f%d§7  chaseBlocked §f%d§7  avoid §f%d§7/§f%d§7  pathFails §f%d",
                ctx.navBranch, ctx.wallBumpTicks, ctx.chaseBlockedTicks,
                ctx.avoidTicks, ctx.avoidDir, ctx.pathFailures));
        if (ctx.blockToBreak != null && ctx.blockToBreak.getWorld() != null
                && ctx.blockToBreak.getWorld().equals(bp.getWorld())) {
            double d = ctx.blockToBreak.distance(bp.getLocation());
            player.sendMessage(String.format(
                    "§7 §8own cobweb at §f%.0f,%.0f,%.0f§8 (§f%.1f§8 blocks away, breaks in §f%d§8 ticks)",
                    ctx.blockToBreak.getX(), ctx.blockToBreak.getY(), ctx.blockToBreak.getZ(),
                    d, ctx.breakingCobwebTimer));
        }

        {
            org.bukkit.inventory.PlayerInventory inv = bp.getInventory();
            StringBuilder worn = new StringBuilder();
            for (org.bukkit.inventory.ItemStack piece : new org.bukkit.inventory.ItemStack[]{
                    inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()}) {
                if (piece != null && piece.getType() != Material.AIR) {
                    if (worn.length() > 0) worn.append(", ");
                    worn.append(piece.getType().name());
                }
            }
            player.sendMessage("§7worn armor: §f"
                    + (worn.length() == 0 ? "§cNONE" : worn.toString()));

            var armorAttr = handle.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            var toughAttr = handle.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
            double armorTotal = handle.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR);
            double toughTotal = handle.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
            player.sendMessage(String.format(
                    "§7 §8armor (total): §f%.2f§8  base: §f%.2f§8  modifiers: §f%d",
                    armorTotal, armorAttr != null ? armorAttr.getBaseValue() : -1,
                    armorAttr != null ? armorAttr.getModifiers().size() : 0));
            if (armorAttr != null) {
                for (var mod : armorAttr.getModifiers()) {
                    player.sendMessage("§7   §8- §f" + mod);
                }
            }
            player.sendMessage(String.format(
                    "§7 §8toughness (total): §f%.2f§8  base: §f%.2f§8  modifiers: §f%d",
                    toughTotal, toughAttr != null ? toughAttr.getBaseValue() : -1,
                    toughAttr != null ? toughAttr.getModifiers().size() : 0));
        }

        if (ctx.target != null) {
            double delay = handle.getCurrentItemAttackStrengthDelay();
            var speedAttrInstance = handle.getAttribute(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            double atkSpeedAttr = handle.getAttributeValue(
                    net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
            player.sendMessage(String.format(
                    "§7attack: dist §f%.2f§7 reach §f%.2f§7/§f%.2f §7los: %s§7 charge: §f%.2f§7/§f%.2f§7 (%s)",
                    ctx.lastAttackDistance, ctx.lastAttackReachDist, ctx.settings.getReach(),
                    ctx.lastAttackHasLOS ? "§ayes" : "§cno",
                    ctx.lastAttackCharge, ctx.lastAttackThreshold,
                    ctx.lastAttackCharged ? "§acharged" : "§ccharging"));
            player.sendMessage(String.format(
                    "§7 §8attackSpeedAttr (total): §f%.3f§8  currentItemAttackStrengthDelay: §f%.3f ticks",
                    atkSpeedAttr, delay));
            if (speedAttrInstance != null) {
                player.sendMessage(String.format(
                        "§7 §8attackSpeed base: §f%.3f§8  modifiers attached: §f%d",
                        speedAttrInstance.getBaseValue(), speedAttrInstance.getModifiers().size()));
                for (var mod : speedAttrInstance.getModifiers()) {
                    player.sendMessage("§7   §8- §f" + mod);
                }
            } else {
                player.sendMessage("§c  ATTACK_SPEED attribute instance is null on this entity");
            }
            player.sendMessage("§7 §8why it did/didn't swing this tick: "
                    + (ctx.lastAttackGate.equals("ATTACKING") ? "§a" : "§e") + ctx.lastAttackGate);
        }

        player.sendMessage("§7" + ctx.maceController.debugLine());

        for (org.bukkit.potion.PotionEffect e : bp.getActivePotionEffects()) {
            if (e.getType().equals(org.bukkit.potion.PotionEffectType.SLOWNESS)) {
                player.sendMessage("§c slowness " + (e.getAmplifier() + 1)
                        + " active §7— turtle master or an external effect");
            }
        }
    }

    private Double tryParseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean parseBool(String val) {
        return val.equalsIgnoreCase("true") || val.equalsIgnoreCase("on") || val.equalsIgnoreCase("yes") || val.equals("1");
    }

    private void sendUsage(Player player) {
        player.sendMessage("§6§lPvPBot Commands:");
        player.sendMessage("§e /pvpbot spawn [faction] [kit] §7| §espawnrandom [faction] [kit] §8(random mash names) §7| §espawnneutral §8(won't fight)");
        player.sendMessage("§e /pvpbot spawnfrozen [faction] §8(training dummy — won't move or fight, still takes hits)");
        player.sendMessage("§e /pvpbot schematic list|info|preview <name> §8(preview is client-side only)");
        player.sendMessage("§e /pvpbot faction schematic [faction] <name> build|stop|status");
        player.sendMessage("§e /pvpbot path create <name> §7| §epath point create §7| §epath walk <bot> [sprint]");
        player.sendMessage("§e /pvpbot masspawn <count> [faction] [kit] [grid] [spacing] [difficulty] §7| §emasspawnrandom <count> ...");
        player.sendMessage("§e /pvpbot group create <faction> <groupname> <amount> §8(pulls existing bots into their own squad)");
        player.sendMessage("§e /pvpbot group <groupname> leader add/remove <player> §7| §edisband <groupname> §7| §elist §7| §einfo <groupname>");
        player.sendMessage("§8   a group's leader overrides the faction leader for its own members only");
        player.sendMessage("§e /pvpbot cinematic spawn <bots> §7| §estop §8(simulates players joining at world spawn, for recording)");
        player.sendMessage("§e /pvpbot cinematic circle <amount> <faction> <kit> §8(invisible bots ring you, armor stashed not worn)");
        player.sendMessage("§e /pvpbot golem <bot> fight §7| §estop §8(spawns an iron golem, pillars up 3 blocks, then hits it for practice)");
        player.sendMessage("§e /pvpbot portals <random|normal> <portals> <bots> [faction] [delay] [kit <name>] [r<radius>] [h<height>]");
        player.sendMessage("§7   floating lit nether portals; bots walk out of them. §f/pvpbot portals random 10 25 test 0.5s kit diamond");
        player.sendMessage("§7   difficulty can be a spread: §f50%easy,35%normal,15%insane");
        player.sendMessage("§e /pvpbot remove, removeall, settings, list, difficulty, kit, give");
        player.sendMessage("§e /pvpbot set <bot|faction|all|global> <setting> <value> §8(changes LIVE bots; the GUI only sets defaults for new ones)");
        player.sendMessage("§e /pvpbot faction list|info|add|addall|addnear|givekit|remove");
        player.sendMessage("§e /pvpbot faction leader add <player|bot> [faction]");
        player.sendMessage("§e /pvpbot deliver <bot> <x> <y> <z> §8(collect a shulker from the faction leader and carry it there)");
        player.sendMessage("§e /pvpbot mine <bot> <block> <x1 y1 z1> <x2 y2 z2> <seconds> §8(faction crew digs tunnels for it, then walks home)");
        player.sendMessage("§e /pvpbot faction formation <name> <shape> [smooth|instant]");
    }

    private static List<PvPBot> crewFor(BotManager mgr, PvPBot lead, String faction) {
        List<PvPBot> crew = new ArrayList<>();
        if (faction == null) {
            crew.add(lead);
            return crew;
        }
        for (PvPBot b : mgr.getFactionBots(faction)) {
            if (b != null && b.isAlive()) crew.add(b);
        }
        if (crew.isEmpty()) crew.add(lead);
        return crew;
    }

    private static double parseCoord(String token, double origin) {
        if (token == null || token.isEmpty()) throw new NumberFormatException("empty");
        if (token.charAt(0) == '~') {
            String rest = token.substring(1);
            if (rest.isEmpty()) return origin;
            return origin + Double.parseDouble(rest);
        }
        return Double.parseDouble(token);
    }

    private static boolean isMassSpawn(String cmd) {
        return cmd.equals("masspawn")
                || cmd.equals("massspawn")
                || cmd.equals("masspawnrandom")
                || cmd.equals("masspawnneutral")
                || cmd.equals("masspawnfrozen");
    }

    private List<String> massSpawnCompletions(BotManager mgr, String[] args) {
        List<String> out = new ArrayList<>();
        boolean hasGrid = false;
        boolean hasFaction = false;
        boolean hasSpacing = false;
        boolean hasKit = false;

        boolean hasDifficulty = false;
        for (int i = 2; i < args.length - 1; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("grid") || token.equalsIgnoreCase("formation")) hasGrid = true;
            else if (WeightedSpec.looksLikeSpec(token) || parseDifficulty(token) != null) hasDifficulty = true;
            else if (tryParseDouble(token) != null) hasSpacing = true;
            else if (plugin.getKitManager() != null && plugin.getKitManager().kitExists(token)) hasKit = true;
            else hasFaction = true;
        }

        if (!hasGrid) out.add("grid");
        if (!hasDifficulty) {
            out.addAll(List.of("50%easy,35%normal,15%insane", "easy", "normal", "hard", "expert"));
        }
        if (!hasFaction) {
            var names = mgr.getFactionNames();
            if (names.isEmpty()) out.addAll(List.of("red", "blue"));
            else out.addAll(names);
        }
        if (!hasKit) out.addAll(kitNames());
        if (hasGrid && !hasSpacing) out.addAll(List.of("2", "3", "4"));
        return out;
    }

    private List<String> coordCommandCompletions(CommandSender sender, String[] args) {
        if (args.length < 3) return null;

        String baseCmd = args[0].toLowerCase();
        List<String> out = new ArrayList<>();

        switch (baseCmd) {
            case "mine" -> {
                if (args.length == 3) {
                    out.addAll(List.of("ancient_debris", "diamond_ore",
                            "deepslate_diamond_ore", "gold_ore", "iron_ore",
                            "emerald_ore", "netherite_scrap", "stop", "status", "debug"));
                } else if (args.length >= 4 && args.length <= 9) {
                    addCoordHint(out, sender, args.length - 4);
                } else if (args.length == 10) {
                    out.addAll(List.of("60", "120", "300", "600"));
                }
            }
            case "farm" -> {
                if (args.length == 3) {
                    out.addAll(List.of("stop", "status"));
                    addCoordHint(out, sender, 0);
                } else if (args.length <= 8) {
                    addCoordHint(out, sender, args.length - 3);
                } else if (args.length == 9) {
                    out.addAll(List.of("300", "600", "1200"));
                }
            }
            case "deliver" -> {
                if (args.length > 5) return null;
                if (args.length == 3) out.addAll(List.of("off", "status"));
                addCoordHint(out, sender, args.length - 3);
            }
            case "checkchest" -> {
                if (args.length > 5) return null;
                addCoordHint(out, sender, args.length - 3);
            }
            case "minearea" -> {
                if (args.length > 5) return null;
                if (args.length == 3) out.addAll(List.of("stop"));
                if (args.length == 4) out.addAll(List.of("10", "20", "30", "50"));
                if (args.length == 5) out.addAll(List.of("60", "300", "600", "1200"));
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private void addCoordHint(List<String> out, CommandSender sender, int axisIndex) {
        out.add("~");
        if (!(sender instanceof Player p)) return;
        Location l = p.getLocation();
        double v = switch (Math.floorMod(axisIndex, 3)) {
            case 0 -> l.getX();
            case 1 -> l.getY();
            default -> l.getZ();
        };
        out.add(String.valueOf((int) v));
    }

    private List<String> portalTailCompletions() {
        List<String> out = new ArrayList<>(List.of(
                "0", "0.25s", "0.5s", "1s", "2s", "5t",
                "kit", "none",
                "r50", "r100", "r150", "r250",
                "h3", "h6", "h12", "h20"));
        out.addAll(kitNames());
        return out;
    }

    private List<String> portalCompletions(BotManager manager, String[] args) {
        List<String> out = new ArrayList<>();

        switch (args.length) {
            case 2 -> out.addAll(List.of("random", "normal", "stop", "close"));
            case 3 -> {
                if (isPortalNameMode(args[1])) {
                    out.addAll(List.of("4", "8", "10", "16"));
                }
            }
            case 4 -> {
                if (isPortalNameMode(args[1])) {
                    out.addAll(List.of("10", "25", "50", "100"));
                }
            }
            default -> {
                if (!isPortalNameMode(args[1])) break;

                if (args.length == 5) {
                    out.addAll(manager.getFactionNames());
                    if (out.isEmpty()) out.addAll(List.of("red", "blue"));
                    out.add("none");
                    out.addAll(portalTailCompletions());
                } else if (args[args.length - 2].equalsIgnoreCase("kit")) {
                    out.addAll(kitNames());
                } else {
                    out.addAll(portalTailCompletions());
                }
            }
        }

        String partial = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(partial));
        return out;
    }

    private static boolean isPortalNameMode(String value) {
        return value.equalsIgnoreCase("random") || value.equalsIgnoreCase("normal");
    }

    private List<String> kitNames() {
        List<String> out = new ArrayList<>();
        if (plugin.getKitManager() == null) return out;
        ConfigurationSection sec = plugin.getKitManager().getKitsConfig()
                .getConfigurationSection("kits");
        if (sec != null) out.addAll(sec.getKeys(false));
        return out;
    }

    public static int cancelPortalWaves() {
        return PortalWave.cancelAll();
    }

    public static java.util.List<PortalStructure> standingPortals() {
        return PortalWave.standing();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        BotManager mgr = plugin.getBotManager();

        if (args.length > 1 && args[0].equalsIgnoreCase("portals")) {
            return portalCompletions(mgr, args);
        }

        List<String> coordCompletions = coordCommandCompletions(sender, args);
        if (coordCompletions != null) {
            String coordPartial = args[args.length - 1].toLowerCase();
            coordCompletions.removeIf(s -> !s.toLowerCase().startsWith(coordPartial));
            return coordCompletions;
        }

        if (args.length == 1) {
            completions.addAll(List.of("schematic", "path", "portals",
                    "spawn", "spawnrandom", "spawnneutral", "spawnfrozen",
                    "masspawn", "massspawn", "masspawnrandom",
                    "masspawnneutral", "masspawnfrozen",
                    "remove", "removeall", "settings", "list", "difficulty",
                    "kit", "faction", "group", "cinematic", "golem", "give", "set", "debug", "performance",
                    "guard", "attack", "deliver", "mine", "farm", "checkchest"));
        }

        else if (args.length == 2) {
            String baseCmd = args[0].toLowerCase();
            switch (baseCmd) {
                case "settings" -> { }
                case "faction" -> {
                    completions.addAll(List.of("list", "info", "create", "remove", "add", "addall", "addnear",
                            "givekit", "giverandomkit", "formation", "group", "leader", "alliance", "stopattack",
                            "armor", "moveto", "attack", "minearea"));
                    completions.addAll(mgr.getFactionNames());
                }
                case "kit" -> completions.addAll(List.of("create", "remove", "give"));
                case "group" -> {
                    completions.addAll(List.of("create", "disband", "list", "info"));
                    completions.addAll(mgr.getGroupNames());
                }
                case "cinematic" -> completions.addAll(List.of("spawn", "circle", "stop"));
                case "set" -> {
                    completions.addAll(List.of("all", "global"));
                    completions.addAll(mgr.getFactionNames());
                    for (PvPBot bot : mgr.getBots().values()) {
                        completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                    }
                }
                case "remove", "difficulty", "guard", "deliver", "mine", "farm", "checkchest", "golem" -> {
                    for (PvPBot bot : mgr.getBots().values()) {
                        completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                    }
                }
                case "performance" -> completions.addAll(List.of("on", "off", "reset"));

                case "schematic" ->
                        completions.addAll(List.of("list", "info", "preview", "clear", "reload", "debug"));
                case "path" -> completions.addAll(List.of("create", "point", "walk", "stop",
                        "list", "info", "loop", "delete", "status"));
                case "attack" -> {
                    completions.add("off");
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        completions.add(org.bukkit.ChatColor.stripColor(online.getName()));
                    }
                }
                case "masspawn", "masspawnrandom", "masspawnneutral", "masspawnfrozen" ->
                        completions.addAll(List.of("5", "10", "25", "50"));
                case "spawn", "spawnrandom", "spawnneutral", "spawnfrozen" -> {
                    completions.addAll(mgr.getFactionNames());
                    if (completions.isEmpty()) completions.addAll(List.of("red", "blue"));
                }
            }
        }

        else if (args.length == 3) {
            String baseCmd = args[0].toLowerCase();
            String secondArg = args[1].toLowerCase();

            if (baseCmd.equals("attack")) {
                for (PvPBot bot : mgr.getBots().values()) {
                    completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                }
            }

            if (baseCmd.equals("faction") && secondArg.equals("armor")) {
                completions.addAll(List.of("on", "off"));
            }

            if (baseCmd.equals("guard")) {
                completions.addAll(List.of("here", "leader", "off", "8", "16", "32"));
            }

            if (baseCmd.equals("set")) {
                completions.addAll(BotSettings.optionKeys());
            }

            if (baseCmd.equals("debug")) {
                completions.add("watch");
            }

            if (baseCmd.equals("path")) {
                if (secondArg.equals("point")) {
                    completions.addAll(List.of("create", "remove", "clear"));
                } else if (secondArg.equals("walk") || secondArg.equals("stop")) {
                    completions.add("all");
                    completions.addAll(mgr.getFactionNames());
                    for (PvPBot bot : mgr.getBots().values()) {
                        completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                    }
                } else if (plugin.getRouteManager() != null) {
                    completions.addAll(plugin.getRouteManager().names());
                }
            }

            if (baseCmd.equals("schematic")
                    && (secondArg.equals("info") || secondArg.equals("preview"))
                    && plugin.getSchematicManager() != null) {
                completions.addAll(plugin.getSchematicManager().list());
            }

            if (baseCmd.equals("faction")
                    && (secondArg.equals("schematic") || secondArg.equals("schem"))) {
                completions.addAll(mgr.getFactionNames());
                if (plugin.getSchematicManager() != null) {
                    completions.addAll(plugin.getSchematicManager().list());
                }
            }

            if (baseCmd.equals("difficulty")) {
                completions.addAll(List.of("Easy", "Normal", "Hard", "Expert"));
            }

            else if (baseCmd.equals("kit")) {
                completions.addAll(List.of("diamond", "anarchy"));
                ConfigurationSection kitSection = plugin.getKitManager().getKitsConfig().getConfigurationSection("kits");
                if (kitSection != null) {
                    completions.addAll(kitSection.getKeys(false));
                }
            }

            else if (baseCmd.equals("faction")) {
                if (baseCmd.equals("faction")
                        && (secondArg.equals("formation") || secondArg.equals("giverandomkit")
                        || secondArg.equals("randomkit") || secondArg.equals("moveto")
                        || secondArg.equals("attack"))) {
                    completions.addAll(mgr.getFactionNames());
                }
                switch (secondArg) {
                    case "leader" -> completions.addAll(List.of("add", "remove", "list"));
                    case "addnear" -> completions.addAll(List.of("5", "10", "20"));
                    case "info", "members", "remove", "delete", "disband", "add", "addall", "givekit",
                            "stopattack", "standdown", "holdfire" ->
                            completions.addAll(mgr.getFactionNames());
                    case "group" -> {
                        GroupProvider provider = Bukkit.getServicesManager().load(GroupProvider.class);
                        if (provider != null) completions.addAll(provider.getGroupNames());
                    }
                    default -> {
                        if (mgr.getFactionNames().contains(secondArg)) {
                            completions.add("formation");
                            completions.add("moveto");
                            completions.add("attack");
                        }
                    }
                }
            }

            else if (baseCmd.equals("group")) {
                switch (secondArg) {
                    case "create", "list" -> completions.addAll(mgr.getFactionNames());
                    case "disband", "info" -> completions.addAll(mgr.getGroupNames());
                    default -> {
                        if (mgr.groupExists(secondArg)) completions.add("leader");
                    }
                }
            }

            else if (baseCmd.equals("cinematic") && (secondArg.equals("spawn") || secondArg.equals("circle"))) {
                completions.addAll(List.of("10", "20", "50"));
            }

            else if (baseCmd.equals("golem")) {
                completions.addAll(List.of("fight", "stop"));
            }

            else if (isMassSpawn(baseCmd)) {
                completions.addAll(massSpawnCompletions(mgr, args));
            }
        }

        else if (args.length == 4) {
            String baseCmd = args[0].toLowerCase();
            String secondArg = args[1].toLowerCase();

            if (baseCmd.equals("faction")) {
                if (secondArg.equals("add")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        completions.add(p.getName());
                    }
                    for (PvPBot bot : mgr.getBots().values()) {
                        completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                    }
                }

                else if (secondArg.equals("givekit")) {
                    completions.addAll(List.of("diamond", "anarchy"));
                    ConfigurationSection kitSection = plugin.getKitManager().getKitsConfig().getConfigurationSection("kits");
                    if (kitSection != null) {
                        completions.addAll(kitSection.getKeys(false));
                    }
                }

                else if (secondArg.equals("addnear")) {
                    completions.addAll(mgr.getFactionNames());
                }

                else if (secondArg.equals("armor")) {
                    if (args[2].equalsIgnoreCase("off")) {
                        completions.addAll(mgr.getFactionNames());
                    }
                }

                else if (secondArg.equals("group")) {
                    completions.add("formation");
                }

                else if (secondArg.equals("formation")) {
                    completions.addAll(FormationManager.Shape.names());
                }

                else if (secondArg.equals("stopattack") || secondArg.equals("standdown")
                        || secondArg.equals("holdfire")) {
                    completions.addAll(List.of("true", "false"));
                }

                else if (secondArg.equals("leader")) {
                    String leaderAction = args[2].toLowerCase();
                    if (leaderAction.equals("add") || leaderAction.equals("set")) {
                        for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
                        for (PvPBot bot : mgr.getBots().values()) {
                            completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                        }
                    } else if (leaderAction.equals("remove") || leaderAction.equals("clear")
                            || leaderAction.equals("delete")) {
                        completions.addAll(mgr.getFactionLeaders().keySet());
                    }
                }

                else if (args[2].equalsIgnoreCase("formation")) {
                    completions.add("grid");
                }

                else if (secondArg.equals("moveto") || args[2].equalsIgnoreCase("moveto")) {
                    completions.add("~");
                }

                else if (secondArg.equals("attack") || args[2].equalsIgnoreCase("attack")) {
                    completions.add("off");
                    completions.addAll(mgr.getFactionNames());
                }
            }

            else if (baseCmd.equals("group") && mgr.groupExists(secondArg)
                    && args[2].equalsIgnoreCase("leader")) {
                completions.addAll(List.of("add", "remove"));
            }

            else if (baseCmd.equals("set")) {
                BotSettings.Option option = BotSettings.option(args[2]);
                if (option != null) completions.addAll(option.suggestions);
            }

            else if (baseCmd.equals("kit") && secondArg.equals("give")) {
                for (PvPBot bot : mgr.getBots().values()) {
                    completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
                }
            }

            else if (baseCmd.equals("cinematic") && secondArg.equals("circle")) {
                completions.addAll(mgr.getFactionNames());
            }

            else if (isMassSpawn(baseCmd)) {
                completions.addAll(massSpawnCompletions(mgr, args));
            }
        }

        else if (args.length == 5
                && args[0].equalsIgnoreCase("cinematic")
                && args[1].equalsIgnoreCase("circle")) {
            completions.addAll(List.of("diamond", "anarchy"));
            ConfigurationSection kitSection = plugin.getKitManager().getKitsConfig().getConfigurationSection("kits");
            if (kitSection != null) {
                completions.addAll(kitSection.getKeys(false));
            }
        }

        else if (args.length == 5
                && args[0].equalsIgnoreCase("faction")
                && args[1].equalsIgnoreCase("group")
                && args[3].equalsIgnoreCase("formation")) {
            completions.add("grid");
        }
        else if (args.length == 5 && isMassSpawn(args[0].toLowerCase())) {
            completions.addAll(massSpawnCompletions(mgr, args));
        }

        else if (args.length >= 5
                && args[0].equalsIgnoreCase("faction")
                && args[1].equalsIgnoreCase("formation")) {
            completions.addAll(List.of("smooth", "instant", "leader", "follow"));
            completions.addAll(List.of("2", "3", "4"));
        }

        else if (args.length == 5
                && args[0].equalsIgnoreCase("faction")
                && args[1].equalsIgnoreCase("leader")
                && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("set"))) {
            completions.addAll(mgr.getFactionNames());
        }

        else if (args.length == 5
                && args[0].equalsIgnoreCase("group")
                && args[1].equalsIgnoreCase("create")) {
            completions.addAll(List.of("5", "10", "20"));
        }

        else if (args.length == 5
                && args[0].equalsIgnoreCase("group")
                && mgr.groupExists(args[1])
                && args[2].equalsIgnoreCase("leader")
                && (args[3].equalsIgnoreCase("add") || args[3].equalsIgnoreCase("set"))) {
            for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
            for (PvPBot bot : mgr.getBots().values()) {
                completions.add(org.bukkit.ChatColor.stripColor(bot.getName()));
            }
        }

        else if ((args.length == 5 || args.length == 6)
                && args[0].equalsIgnoreCase("faction")
                && (args[1].equalsIgnoreCase("moveto") || args[2].equalsIgnoreCase("moveto"))) {
            completions.add("~");
        }

        String partial = args[args.length - 1].toLowerCase();
        completions.removeIf(s -> !s.toLowerCase().startsWith(partial));
        return completions;
    }
}

