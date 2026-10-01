package com.pvpbot.schem;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Names a schematic can be ordered by voice ("everyone build me a castle
// here"): /pvpbot schematic mark <schematic> <voice name>. Kept in the
// plugin config under voice-builds.<name>: <schematic>.
public final class VoiceBuilds {
    private static final String PATH = "voice-builds";

    private final Plugin plugin;
    private final Map<String, String> byName = new LinkedHashMap<>();

    public VoiceBuilds(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    public static String normalize(String voiceName) {
        return voiceName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
    }

    public void load() {
        byName.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection(PATH);
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            String schem = sec.getString(k);
            if (schem != null && !schem.isBlank()) byName.put(normalize(k.replace('_', ' ')), schem);
        }
    }

    private void save() {
        plugin.getConfig().set(PATH, null);
        for (Map.Entry<String, String> e : byName.entrySet()) {
            plugin.getConfig().set(PATH + "." + e.getKey().replace(' ', '_'), e.getValue());
        }
        plugin.saveConfig();
    }

    public void mark(String voiceName, String schematic) {
        byName.put(normalize(voiceName), schematic);
        save();
    }

    public boolean unmark(String voiceName) {
        boolean had = byName.remove(normalize(voiceName)) != null;
        if (had) save();
        return had;
    }

    public String schematicFor(String voiceName) {
        return voiceName == null ? null : byName.get(normalize(voiceName));
    }

    public Map<String, String> all() {
        return new LinkedHashMap<>(byName);
    }
}
