package com.pvpbot.route;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class RouteManager {
    public static class Route {
        public final String name;
        public final List<Location> points = new ArrayList<>();

        public boolean loop = true;

        Route(String name) {
            this.name = name;
        }

        public int size() {
            return points.size();
        }
    }

    private final Plugin plugin;
    private final File file;
    private final Map<String, Route> routes = new LinkedHashMap<>();

    private final Map<UUID, String> selected = new LinkedHashMap<>();

    public RouteManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "routes.yml");
        load();
    }

    public Route get(String name) {
        return name == null ? null : routes.get(name.toLowerCase(Locale.ROOT));
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Route r : routes.values()) out.add(r.name);
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public Route create(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (routes.containsKey(key)) return null;
        Route r = new Route(name);
        routes.put(key, r);
        return r;
    }

    public boolean delete(String name) {
        return routes.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public void select(UUID editor, String name) {
        selected.put(editor, name.toLowerCase(Locale.ROOT));
    }

    public Route selectedFor(UUID editor) {
        String key = selected.get(editor);
        if (key != null) {
            Route r = routes.get(key);
            if (r != null) return r;
        }

        return routes.size() == 1 ? routes.values().iterator().next() : null;
    }

    public void load() {
        routes.clear();
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("routes");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;

            Route r = new Route(sec.getString("name", key));
            r.loop = sec.getBoolean("loop", true);

            String worldName = sec.getString("world", "");
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                plugin.getLogger().warning("[PvPBot] Route '" + key
                        + "' refers to world '" + worldName + "', which is not loaded. Skipped.");
                continue;
            }

            for (String raw : sec.getStringList("points")) {
                String[] parts = raw.split(",");
                if (parts.length < 3) continue;
                try {
                    Location p = new Location(world,
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]));
                    if (parts.length >= 5) {
                        p.setYaw(Float.parseFloat(parts[3]));
                        p.setPitch(Float.parseFloat(parts[4]));
                    } else {
                        p.setYaw(Float.NaN);
                        p.setPitch(Float.NaN);
                    }
                    r.points.add(p);
                } catch (NumberFormatException ignored) {
                }
            }
            routes.put(key.toLowerCase(Locale.ROOT), r);
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, Route> e : routes.entrySet()) {
            Route r = e.getValue();
            if (r.points.isEmpty()) continue;

            String base = "routes." + e.getKey();
            cfg.set(base + ".name", r.name);
            cfg.set(base + ".loop", r.loop);
            cfg.set(base + ".world", r.points.get(0).getWorld() == null
                    ? "" : r.points.get(0).getWorld().getName());

            List<String> pts = new ArrayList<>();
            for (Location l : r.points) {
                pts.add(l.getX() + "," + l.getY() + "," + l.getZ()
                        + "," + l.getYaw() + "," + l.getPitch());
            }
            cfg.set(base + ".points", pts);
        }

        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[PvPBot] Could not save routes.yml: " + e.getMessage());
        }
    }
}
