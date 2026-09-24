package com.pvpbot;

import com.pvpbot.ai.BotAIContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BotPersistence {
    private static final int FORMAT = 1;

    private final PvPBotPlugin plugin;
    private final File file;

    private boolean enabled = true;

    private int restoreDelay = 40;

    public BotPersistence(PvPBotPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bots.yml");
    }

    public void loadConfig(ConfigurationSection sec) {
        if (sec == null) return;
        enabled = sec.getBoolean("enabled", true);
        restoreDelay = Math.max(0, sec.getInt("restore-delay-ticks", 40));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int restoreDelayTicks() {
        return restoreDelay;
    }

    public void save() {
        if (!enabled) return;

        BotManager manager = plugin.getBotManager();
        if (manager == null) return;

        YamlConfiguration yml = new YamlConfiguration();
        yml.set("format", FORMAT);
        yml.set("saved-at", System.currentTimeMillis());

        List<Map.Entry<UUID, PvPBot>> snapshot;
        synchronized (manager.getBots()) {
            snapshot = new ArrayList<>(manager.getBots().entrySet());
        }

        int written = 0;
        for (Map.Entry<UUID, PvPBot> e : snapshot) {
            PvPBot bot = e.getValue();
            if (bot == null || !bot.isAlive()) continue;

            org.bukkit.entity.Player p = bot.getBukkitPlayer();
            if (p == null) continue;

            Location loc = p.getLocation();
            if (loc.getWorld() == null) continue;

            String path = "bots." + e.getKey();
            ConfigurationSection sec = yml.createSection(path);

            sec.set("name", bot.getName());
            sec.set("world", loc.getWorld().getName());
            sec.set("x", loc.getX());
            sec.set("y", loc.getY());
            sec.set("z", loc.getZ());
            sec.set("yaw", loc.getYaw());
            sec.set("pitch", loc.getPitch());

            String faction = manager.getPlayerFaction(e.getKey());
            if (faction != null) sec.set("faction", faction);

            BotSettings settings = manager.getBotSettings(e.getKey());
            if (settings != null) settings.saveTo(sec.createSection("settings"));

            saveOrders(bot, sec);
            written++;
        }

        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create the plugin folder for bots.yml");
                return;
            }
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to save bots.yml: " + ex.getMessage());
            return;
        }

        if (written == 0 && file.exists()) {
            plugin.getLogger().fine("bots.yml saved with no bots.");
        }
    }

    private void saveOrders(PvPBot bot, ConfigurationSection sec) {
        BotAI ai = bot.getAI();
        if (ai == null) return;
        BotAIContext ctx = ai.getContext();
        if (ctx == null) return;

        if (ctx.patrolController != null && ctx.patrolController.isActive()) {
            ConfigurationSection ps = sec.createSection("patrol");
            ps.set("route", ctx.patrolController.routeName());
            ps.set("index", ctx.patrolController.currentIndex());
            ps.set("sprint", ctx.patrolController.isSprinting());
        }

        if (ctx.guardAnchor != null && ctx.guardAnchor.getWorld() != null) {
            ConfigurationSection gs = sec.createSection("guard");
            gs.set("mode", ctx.guardMode.name());
            gs.set("world", ctx.guardAnchor.getWorld().getName());
            gs.set("x", ctx.guardAnchor.getX());
            gs.set("y", ctx.guardAnchor.getY());
            gs.set("z", ctx.guardAnchor.getZ());
            gs.set("radius", ctx.guardRadius);
            gs.set("facing", ctx.guardFacing);
        }
    }

    public int restore() {
        if (!enabled || !file.exists()) return 0;

        BotManager manager = plugin.getBotManager();
        if (manager == null) return 0;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("bots");
        if (root == null) return 0;

        int restored = 0;
        int skipped = 0;

        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                skipped++;
                continue;
            }

            if (Bukkit.getPlayer(uuid) != null) {
                skipped++;
                continue;
            }

            String worldName = sec.getString("world");
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("Skipping bot " + sec.getString("name", key)
                        + ": world '" + worldName + "' is not loaded.");
                skipped++;
                continue;
            }

            Location loc = new Location(world,
                    sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                    (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));

            BotSettings settings = new BotSettings(manager.getGlobalSettings());
            settings.loadFrom(sec.getConfigurationSection("settings"));

            PvPBot bot;
            try {
                bot = manager.restoreBot(loc, sec.getString("faction"),
                        sec.getString("name"), uuid, settings);
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to restore bot "
                        + sec.getString("name", key) + ": " + t);
                skipped++;
                continue;
            }

            restoreOrders(bot, sec);
            restored++;
        }

        if (restored > 0 || skipped > 0) {
            plugin.getLogger().info("Restored " + restored + " bot(s)"
                    + (skipped > 0 ? ", skipped " + skipped : "") + ".");
        }
        return restored;
    }

    private void restoreOrders(PvPBot bot, ConfigurationSection sec) {
        BotAI ai = bot.getAI();
        if (ai == null) return;

        ConfigurationSection gs = sec.getConfigurationSection("guard");
        if (gs != null) {
            World gw = Bukkit.getWorld(gs.getString("world", ""));
            if (gw != null) {
                Location anchor = new Location(gw,
                        gs.getDouble("x"), gs.getDouble("y"), gs.getDouble("z"));
                BotAIContext.GuardMode mode;
                try {
                    mode = BotAIContext.GuardMode.valueOf(
                            gs.getString("mode", "POST").toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    mode = BotAIContext.GuardMode.POST;
                }
                bot.setGuardPost(anchor, gs.getDouble("radius", 16.0),
                        (float) gs.getDouble("facing"), mode);
            }
        }

        ConfigurationSection ps = sec.getConfigurationSection("patrol");
        if (ps != null && plugin.getRouteManager() != null) {
            String routeName = ps.getString("route");
            com.pvpbot.route.RouteManager.Route route =
                    routeName == null ? null : plugin.getRouteManager().get(routeName);
            if (route != null && route.size() > 0) {
                ai.getContext().patrolController.resumeAt(
                        route, ps.getBoolean("sprint", false), ps.getInt("index", 0));
            } else if (routeName != null) {
                plugin.getLogger().warning("Bot " + bot.getName()
                        + " was patrolling route '" + routeName + "', which no longer exists.");
            }
        }
    }

    public void clear() {
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete bots.yml");
        }
    }
}
