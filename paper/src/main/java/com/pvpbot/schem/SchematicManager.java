package com.pvpbot.schem;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SchematicManager {
    private final Plugin plugin;
    private final File folder;
    private final Map<String, Schematic> cache = new ConcurrentHashMap<>();

    public SchematicManager(Plugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "schematics");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create " + folder.getPath());
        }
        writeReadme();
    }

    public File getFolder() {
        return folder;
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        File[] files = folder.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!f.isFile()) continue;
            if (!n.endsWith(".schem") && !n.endsWith(".schematic")) continue;
            out.add(f.getName().substring(0, f.getName().lastIndexOf('.')));
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    public Schematic get(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);

        Schematic cached = cache.get(key);
        if (cached != null) return cached;

        File file = resolve(name);
        if (file == null) return null;

        try {
            Schematic s = Schematic.load(file);
            cache.put(key, s);
            return s;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to load schematic '" + name + "': " + t.getMessage());
            return null;
        }
    }

    public String describeFailure(String name) {
        File file = resolve(name);
        if (file == null) return "no file called " + name + ".schem in " + folder.getName() + "/";
        try {
            Schematic.load(file);
            return null;
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    public void reload() {
        cache.clear();
    }

    private File resolve(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return null;

        for (String ext : new String[]{".schem", ".schematic"}) {
            File f = new File(folder, name + ext);
            if (f.isFile()) return f;
        }

        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                String base = f.getName();
                int dot = base.lastIndexOf('.');
                if (dot > 0) base = base.substring(0, dot);
                if (base.equalsIgnoreCase(name)) return f;
            }
        }
        return null;
    }

    private void writeReadme() {
        File readme = new File(folder, "README.txt");
        if (readme.exists()) return;
        try (java.io.PrintWriter w = new java.io.PrintWriter(readme, java.nio.charset.StandardCharsets.UTF_8)) {
            w.println("Drop Sponge schematic files (.schem) in this folder.");
            w.println();
            w.println("These are what WorldEdit's //copy + //schem save produces by default,");
            w.println("and what most schematic sites distribute. Version 2 is the target;");
            w.println("version 3 files also load.");
            w.println();
            w.println("The older MCEdit .schematic format (numeric block IDs) is NOT supported --");
            w.println("those IDs stopped meaning anything in 1.13.");
            w.println();
            w.println("  /pvpbot schematic list");
            w.println("  /pvpbot schematic info <name>");
            w.println("  /pvpbot schematic preview <name>");
            w.println("  /pvpbot faction schematic <name> build");
        } catch (Throwable ignored) {
        }
    }
}
