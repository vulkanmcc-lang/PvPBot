package com.pvpbot;

import com.pvpbot.commands.PvPBotCommand;
import com.pvpbot.voice.PvPBotVoiceChat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.awt.*;

public class PvPBotPlugin extends JavaPlugin implements Listener {
    private static PvPBotPlugin instance;

    private BotManager botManager;
    private boolean warnedAboutKicks = false;
    private com.pvpbot.schem.SchematicManager schematicManager;
    private com.pvpbot.route.RouteManager routeManager;
    private BotPersistence botPersistence;

    private final java.util.Map<String, com.pvpbot.schem.BuildJob> buildJobs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private KitManager kitManager;

    private boolean preventFactionFriendlyFire = true;

    private final AttackCoordinator attackCoordinator = new AttackCoordinator();

    private BukkitRunnable autosaveTask;

    private int globalTick = 0;

    private int lastBotErrorTick = -1000;

    private long tickNanos = 0;
    private long tickNanosTotal = 0;
    private long tickSamples = 0;

    public long getLastTickNanos() {
        return tickNanos;
    }

    public double getAverageTickMillis() {
        return tickSamples == 0 ? 0.0 : (tickNanosTotal / (double) tickSamples) / 1_000_000.0;
    }

    public void resetPerfCounters() {
        tickNanosTotal = 0;
        tickSamples = 0;
    }

    public AttackCoordinator getAttackCoordinator() {
        return attackCoordinator;
    }

    @Override
    public void onEnable() {
        instance = this;

        getDataFolder().mkdirs();
        saveDefaultConfig();

        com.pvpbot.ai.PacketBroadcaster.USE_VANILLA_TRACKER =
                getConfig().getBoolean("use-vanilla-entity-tracker", true);

        com.pvpbot.nav.TerrainMemory.setEnabled(
                getConfig().getBoolean("performance.terrain-memory", true));
        com.pvpbot.nav.TerrainMemory.load(new java.io.File(getDataFolder(), "terrain-memory.yml"));

        SkinPool.setHarvestFromPlayers(getConfig().getBoolean("harvest-player-skins", false));
        SkinPool.harvestOnline();
        SkinPool.fetchAsync(this, getConfig().getStringList("skin-sources"));

        preventFactionFriendlyFire = getConfig().getBoolean("prevent-faction-friendly-fire", true);

        botManager = new BotManager(this);
        schematicManager = new com.pvpbot.schem.SchematicManager(this);
        routeManager = new com.pvpbot.route.RouteManager(this);
        PvPBotVoiceChat.register(this);
        kitManager = new KitManager(this);
        botPersistence = new BotPersistence(this);
        botPersistence.loadConfig(getConfig().getConfigurationSection("persistence"));

        botManager.loadFactionsFromConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getPluginManager().registerEvents(
                new com.pvpbot.commands.SettingsGui(), this);

        var cmd = getCommand("pvpbot");
        if (cmd != null) {
            PvPBotCommand commandHandler = new PvPBotCommand(this);
            cmd.setExecutor(commandHandler);
            cmd.setTabCompleter(commandHandler);
        }

        com.pvpbot.perf.BotScheduler.enabled = getConfig().getBoolean("performance.ai-lod", true);
        com.pvpbot.perf.BotScheduler.radius  = getConfig().getDouble("performance.ai-lod-radius", 48.0);
        com.pvpbot.perf.BotScheduler.stride  = Math.max(1, getConfig().getInt("performance.ai-lod-stride", 4));
        com.pvpbot.perf.BotScheduler.minBots = Math.max(0, getConfig().getInt("performance.ai-lod-min-bots", 48));

        int requestCeiling = getConfig().getInt("performance.path-requests-per-tick",
                getConfig().getInt("performance.path-snapshots-per-tick", 12));
        com.pvpbot.perf.PathBudget.maxRequestsPerTick = Math.max(1, requestCeiling);
        com.pvpbot.perf.PathBudget.reserveRequestsPerTick =
                Math.max(0, getConfig().getInt("performance.path-reserve-per-tick", 4));

        com.pvpbot.perf.PathBudget.maxChunksPerTick =
                Math.max(32, getConfig().getInt("performance.path-chunks-per-tick", 256));
        com.pvpbot.nav.NavChunkCache.capturesPerTick =
                Math.max(2, getConfig().getInt("performance.chunk-captures-per-tick", 12));
        com.pvpbot.nav.NavChunkCache.ttlTicks =
                Math.max(20, getConfig().getInt("performance.nav-cache-ttl-ticks", 200));
        com.pvpbot.ai.PathfindingController.searchRadius =
                Math.max(16, getConfig().getInt("performance.path-search-radius", 72));
        com.pvpbot.nav.Pathfinder.maxExpansions =
                Math.max(500, getConfig().getInt("performance.path-max-expansions", 8000));

        com.pvpbot.ai.RestockController.loadConfig(getConfig().getConfigurationSection("restock"));

        com.pvpbot.perf.PlayerSnapshot.rebuild(
                botManager == null ? null : botManager.getBots().keySet());

        new BukkitRunnable() {
            @Override
            public void run() {
                long start = System.nanoTime();

                com.pvpbot.perf.PathBudget.resetTick();
                com.pvpbot.perf.BotScheduler.resetCounters();
                com.pvpbot.nav.NavChunkCache.tick(globalTick);

                com.pvpbot.perf.BotProfiler.resetStack();
                com.pvpbot.perf.PlayerSnapshot.rebuild(
                        botManager == null ? null : botManager.getBots().keySet());

                com.pvpbot.perf.FocusBoard.rebuild(botManager);
                com.pvpbot.perf.BotScheduler.botCount =
                        botManager == null ? 0 : botManager.getBots().size();

                attackCoordinator.tick();

                if (!buildJobs.isEmpty()) {
                    buildJobs.values().removeIf(j -> {
                        j.tick();
                        return j.isFinished();
                    });
                }

                globalTick++;

                if (botManager != null && botManager.getBots() != null) {
                    for (PvPBot bot : botManager.getBots().values()) {
                        if (!bot.isAlive()) continue;

                        boolean full = com.pvpbot.perf.BotScheduler
                                .wantsFullTick(bot, botManager, globalTick);

                        try {
                            bot.tick(full);
                        } catch (Throwable t) {
                            if (globalTick - lastBotErrorTick > 100) {
                                lastBotErrorTick = globalTick;
                                getLogger().log(java.util.logging.Level.WARNING,
                                        "[PvPBot] Bot " + bot.getName()
                                                + " threw during tick; other bots continue.", t);
                            }
                        }
                    }
                }

                tickNanos = System.nanoTime() - start;
                tickNanosTotal += tickNanos;
                tickSamples++;
            }
        }.runTaskTimer(this, 0L, 1L);

        autosaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (botManager != null) {
                    botManager.saveFactionsToConfig();
                }
                if (kitManager != null) {
                    kitManager.saveKits();
                }

                if (botPersistence != null) {
                    botPersistence.save();
                }

                com.pvpbot.nav.TerrainMemory.prune();
                com.pvpbot.nav.TerrainMemory.save(
                        new java.io.File(getDataFolder(), "terrain-memory.yml"));
            }
        };
        autosaveTask.runTaskTimer(this, 20L * 60L * 5L, 20L * 60L * 5L);

        if (botPersistence != null && botPersistence.isEnabled()) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> botPersistence.restore(), botPersistence.restoreDelayTicks());
        }

        getLogger().info("PvPBot Plugin has been enabled!");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);

        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }

        if (botManager != null) {
            boolean persist = botPersistence != null && botPersistence.isEnabled();
            if (persist) botPersistence.save();
            botManager.removeAllBots(!persist);
            botManager.saveFactionsToConfig();
        }

        if (kitManager != null) {
            kitManager.saveKits();
        }

        if (routeManager != null) {
            routeManager.save();
        }

        com.pvpbot.commands.PvPBotCommand.cancelPortalWaves();

        com.pvpbot.mine.MiningJob.stopAll();

        com.pvpbot.commands.SettingsGui.clear();
        com.pvpbot.schem.SchematicPreview.clearAll();
        buildJobs.clear();

        com.pvpbot.ai.PathfindingController.shutdownPool();
        com.pvpbot.nav.NavChunkCache.clear();

        com.pvpbot.nav.TerrainMemory.save(new java.io.File(getDataFolder(), "terrain-memory.yml"));

        getLogger().info("PvPBot Plugin has been disabled!");
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onBotPortal(org.bukkit.event.player.PlayerPortalEvent e) {
        if (botManager == null) return;
        PvPBot bot = botManager.getBots().get(e.getPlayer().getUniqueId());
        if (bot == null) return;
        if (!bot.portalsBlocked()) return;

        e.setCancelled(true);
        try {
            e.getPlayer().setPortalCooldown(bot.remainingPortalGraceTicks());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onBotPortalTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (botManager == null) return;
        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                && cause != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL
                && cause != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_GATEWAY) {
            return;
        }
        PvPBot bot = botManager.getBots().get(e.getPlayer().getUniqueId());
        if (bot == null || !bot.portalsBlocked()) return;

        e.setCancelled(true);
        try {
            e.getPlayer().setPortalCooldown(bot.remainingPortalGraceTicks());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onBotChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        if (botManager == null) return;
        PvPBot bot = botManager.getBots().get(e.getPlayer().getUniqueId());
        if (bot == null) return;
        bot.armPortalGrace();
        try {
            e.getPlayer().setPortalCooldown(bot.remainingPortalGraceTicks());
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalPiglin(org.bukkit.event.entity.CreatureSpawnEvent e) {
        if (e.getSpawnReason() != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NETHER_PORTAL) {
            return;
        }
        for (com.pvpbot.commands.PortalStructure ps : com.pvpbot.commands.PvPBotCommand.standingPortals()) {
            if (ps != null && ps.nearMouth(e.getLocation(), 3.0)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private void noteDisturbance(org.bukkit.entity.Player cause, org.bukkit.Location at) {
        if (botManager == null || cause == null || at == null) return;
        if (at.getWorld() == null) return;

        if (botManager.getBots().containsKey(cause.getUniqueId())) return;

        for (PvPBot bot : botManager.getBots().values()) {
            if (bot == null || !bot.isAlive() || bot.getAI() == null) continue;

            org.bukkit.entity.Player bp = bot.getBukkitPlayer();
            if (bp == null || bp.getWorld() != at.getWorld()) continue;

            if (bp.getLocation().distanceSquared(at) > 24.0 * 24.0) continue;
            if (botManager.isFriendly(bot.getUUID(), cause.getUniqueId())) continue;

            var ctx = bot.getAI().getContext();

            if (ctx.target != null) continue;

            ctx.enemyMemory.noteDisturbance(at, ctx.tickCounter);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisturbanceBreak(org.bukkit.event.block.BlockBreakEvent e) {
        noteDisturbance(e.getPlayer(), e.getBlock().getLocation());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onDisturbancePlace(org.bukkit.event.block.BlockPlaceEvent e) {
        noteDisturbance(e.getPlayer(), e.getBlock().getLocation());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBotJoinReassertChannel(PlayerJoinEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();

        PvPBot bot = PvPBot.getSpawning(id);
        if (bot == null && botManager != null && botManager.getBots() != null) {
            bot = botManager.getBots().get(id);
        }
        if (bot == null) return;
        bot.reassertFakeChannel();

        if (bot.isSilent()) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onBotKickAttempt(org.bukkit.event.player.PlayerKickEvent event) {
        if (botManager == null || botManager.getBots() == null) return;
        PvPBot bot = botManager.getBots().get(event.getPlayer().getUniqueId());
        if (bot == null) return;
        if (bot.isBeingRemoved()) return;
        if (event.getPlayer().hasMetadata("pvpbot_died")) return;

        event.setCancelled(true);

        if (!warnedAboutKicks) {
            warnedAboutKicks = true;
            getLogger().info("[PvPBot] Blocked an external kick against a bot. "
                    + "This is normal with packet plugins (PacketEvents, ProtocolLib, "
                    + "anticheats) that don't recognise the bot's fake connection.");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBotDisconnected(org.bukkit.event.player.PlayerQuitEvent event) {
        if (botManager == null || botManager.getBots() == null) return;
        java.util.UUID id = event.getPlayer().getUniqueId();
        PvPBot bot = botManager.getBots().get(id);
        if (bot == null) return;
        if (bot.isBeingRemoved()) return;

        getLogger().warning("[PvPBot] Bot " + event.getPlayer().getName()
                + " was disconnected by something other than PvPBot.");
        getLogger().warning("[PvPBot] This is usually a packet plugin (ProtocolLib, "
                + "PacketEvents, an anticheat) refusing the bot's fake connection.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (botManager == null || botManager.getBots() == null) return;
        if (botManager.getBots().containsKey(event.getPlayer().getUniqueId())) return;

        SkinPool.harvest(event.getPlayer());

        com.pvpbot.perf.PlayerSnapshot.rebuild(botManager.getBots().keySet());

        for (PvPBot bot : botManager.getBots().values()) {
            if (bot.isAlive()) {
                bot.sendSpawnPacketsTo(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotKnockback(EntityDamageByEntityEvent event) {
        if (botManager == null) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        PvPBot bot = botManager.getBots().get(victim.getUniqueId());
        if (bot == null || !bot.isAlive() || bot.getPacketSink() == null) return;

        Entity damager = event.getDamager();
        Player attacker = null;
        if (damager instanceof Player p) attacker = p;
        else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }

        double strength = 0.4;
        double dirX;
        double dirZ;

        if (attacker != null) {
            dirX = attacker.getLocation().getX() - victim.getLocation().getX();
            dirZ = attacker.getLocation().getZ() - victim.getLocation().getZ();

            int levels = 0;
            try {
                levels = attacker.getInventory().getItemInMainHand()
                        .getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK);
            } catch (Throwable ignored) {
            }
            if (attacker.isSprinting()) levels++;
            strength += levels * 0.5;
        } else {
            dirX = damager.getLocation().getX() - victim.getLocation().getX();
            dirZ = damager.getLocation().getZ() - victim.getLocation().getZ();
        }

        bot.getPacketSink().offerFallback(strength, dirX, dirZ);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRailPlaced(org.bukkit.event.block.BlockPlaceEvent event) {
        if (botManager == null) return;
        if (!com.pvpbot.ai.HazardController.isRail(event.getBlock().getType())) return;

        Location loc = event.getBlock().getLocation();
        for (PvPBot bot : botManager.getBots().values()) {
            if (!bot.isAlive()) continue;
            Player bp = bot.getBukkitPlayer();
            if (bp == null || bp.getWorld() != loc.getWorld()) continue;
            bot.getAI().getContext().hazardController.notifyRailPlaced(loc, event.getPlayer());
        }
    }

    @EventHandler
    public void onBotResurrect(org.bukkit.event.entity.EntityResurrectEvent event) {
        if (botManager == null || event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player p)) return;

        PvPBot bot = botManager.getBots().get(p.getUniqueId());
        if (bot != null && bot.isAlive()) {
            bot.getAI().notifyTotemPop();
        }
    }

    @EventHandler
    public void onBotDeath(EntityDeathEvent event) {
        if (botManager == null) return;
        if (!(event.getEntity() instanceof Player p)) return;

        PvPBot bot = botManager.getBots().get(p.getUniqueId());
        if (bot != null) {
            p.kick();
        }
    }

    @EventHandler
    public void onBotDamaged(EntityDamageByEntityEvent event) {
        if (botManager == null) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        boolean botInvolved = botManager.getBots().containsKey(victim.getUniqueId())
                || botManager.getBots().containsKey(attacker.getUniqueId());

        if (preventFactionFriendlyFire && botInvolved
                && botManager.isFriendly(attacker.getUniqueId(), victim.getUniqueId())) {
            PvPBot confusedAttacker = botManager.getBots().get(attacker.getUniqueId());
            boolean swingingBlind = confusedAttacker != null
                    && confusedAttacker.getAI().getContext().confusedTicks > 0;

            if (!swingingBlind) {
                event.setCancelled(true);
                return;
            }
        }

        PvPBot bot = botManager.getBots().get(victim.getUniqueId());
        if (bot != null && bot.isAlive() && bot.getBukkitPlayer() != null) {
            bot.getAI().notifyDamage(attacker);
        }

        String victimFaction = botManager.getPlayerFaction(victim.getUniqueId());
        if (victimFaction != null && botManager.breakFactionFormation(victimFaction)) {
            for (PvPBot member : botManager.getFactionBots(victimFaction)) {
                member.getAI().getContext().movementController.clearFormationOrder();
            }
        }

        if (botManager.isLeader(victim.getUniqueId())) {
            String faction = botManager.getPlayerFaction(victim.getUniqueId());
            for (PvPBot member : botManager.getFactionBots(faction)) {
                if (member.getUUID().equals(victim.getUniqueId())) continue;
                if (!victim.getUniqueId().equals(botManager.getLeaderFor(member.getUUID()))) continue;
                member.getAI().notifyLeaderAttacked(attacker);
            }
        }

        if (victimFaction != null) {
            java.util.List<PvPBot> mates = botManager.getFactionBots(victimFaction);
            if (!mates.isEmpty()) {
                org.bukkit.World vWorld = victim.getWorld();
                double vx = victim.getX(), vy = victim.getY(), vz = victim.getZ();

                for (PvPBot member : mates) {
                    if (member.getUUID().equals(victim.getUniqueId())) continue;
                    if (!member.isAlive()) continue;

                    BotSettings ms = botManager.getBotSettings(member.getUUID());
                    if (ms == null || !ms.isTeamTarget()) continue;

                    double radius = ms.getTeamTargetRadius();
                    if (radius <= 0.0) continue;

                    Player mp = member.getBukkitPlayer();
                    if (mp == null || mp.getWorld() != vWorld) continue;

                    double dx = mp.getX() - vx, dy = mp.getY() - vy, dz = mp.getZ() - vz;
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;

                    member.getAI().notifyAllyAttacked(attacker);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNavBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        com.pvpbot.nav.NavChunkCache.invalidate(
                event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onNavBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        com.pvpbot.nav.NavChunkCache.invalidate(
                event.getBlock().getWorld(), event.getBlock().getX(), event.getBlock().getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onNavExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        for (org.bukkit.block.Block b : event.blockList()) {
            com.pvpbot.nav.NavChunkCache.invalidate(b.getWorld(), b.getX(), b.getZ());
        }
    }

    public static PvPBotPlugin getInstance() {
        return instance;
    }

    public BotPersistence getBotPersistence() {
        return botPersistence;
    }

    public com.pvpbot.route.RouteManager getRouteManager() {
        return routeManager;
    }

    public com.pvpbot.schem.SchematicManager getSchematicManager() {
        return schematicManager;
    }

    public java.util.Map<String, com.pvpbot.schem.BuildJob> getBuildJobs() {
        return buildJobs;
    }

    public BotManager getBotManager() {
        return botManager;
    }

    public KitManager getKitManager() {
        return kitManager;
    }
}
