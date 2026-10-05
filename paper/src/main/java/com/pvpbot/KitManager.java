package com.pvpbot;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KitManager {
    private final PvPBotPlugin plugin;
    private final File kitsFile;
    private FileConfiguration kitsConfig;

    public KitManager(PvPBotPlugin plugin) {
        this.plugin = plugin;
        this.kitsFile = new File(plugin.getDataFolder(), "kits.yml");

        if (!kitsFile.exists()) {
            plugin.saveResource("kits.yml", false);
        }

        this.kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    public FileConfiguration getKitsConfig() {
        return kitsConfig;
    }

    public void saveKits() {
        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save kits.yml");
            e.printStackTrace();
        }
    }

    public void reloadKits() {
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    private void loadKitsConfig() {
        if (!kitsFile.exists()) {
            try {
                kitsFile.getParentFile().mkdirs();
                kitsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("[KitManager] Could not create kits.yml: " + e.getMessage());
            }
        }
        kitsConfig = YamlConfiguration.loadConfiguration(kitsFile);
    }

    private void saveKitsConfig() {
        try {
            kitsConfig.save(kitsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[KitManager] Could not save kits.yml: " + e.getMessage());
        }
    }

    public void saveKit(String name, ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
        String key = "kits." + name.toLowerCase();

        List<Map<String, Object>> serializedContents = new ArrayList<>();
        for (ItemStack item : contents) {
            serializedContents.add(item != null && item.getType() != Material.AIR ? item.serialize() : null);
        }

        List<Map<String, Object>> serializedArmor = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ItemStack piece = (i < armor.length) ? armor[i] : null;
            serializedArmor.add(piece != null && piece.getType() != Material.AIR ? piece.serialize() : null);
        }

        kitsConfig.set(key + ".contents", serializedContents);
        kitsConfig.set(key + ".armor", serializedArmor);
        kitsConfig.set(key + ".offhand",
                (offhand != null && offhand.getType() != Material.AIR) ? offhand.serialize() : null);

        saveKitsConfig();
    }

    public ItemStack getKitOffhand(String name) {
        Object obj = kitsConfig.get("kits." + name.toLowerCase() + ".offhand");
        return deserializeOne(obj, name, "offhand", 0);
    }

    public ItemStack[] getKitContents(String name) {
        List<?> list = kitsConfig.getList("kits." + name.toLowerCase() + ".contents");
        if (list == null) {
            plugin.getLogger().warning("[KitManager] No contents found for kit '" + name + "'");
            return new ItemStack[36];
        }

        ItemStack[] result = new ItemStack[36];
        for (int i = 0; i < Math.min(list.size(), 36); i++) {
            result[i] = deserializeOne(list.get(i), name, "contents", i);
        }
        return result;
    }

    public ItemStack[] getKitArmor(String name) {
        List<?> list = kitsConfig.getList("kits." + name.toLowerCase() + ".armor");
        if (list == null) {
            plugin.getLogger().warning("[KitManager] No armor found for kit '" + name + "'");
            return new ItemStack[4];
        }

        ItemStack[] result = new ItemStack[4];
        for (int i = 0; i < Math.min(list.size(), 4); i++) {
            result[i] = deserializeOne(list.get(i), name, "armor", i);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ItemStack deserializeOne(Object obj, String kitName, String section, int index) {
        if (obj == null) return null;

        if (obj instanceof ItemStack is) {
            return is;
        }

        if (obj instanceof Map<?, ?> map) {
            try {
                return ItemStack.deserialize((Map<String, Object>) map);
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] Failed to deserialize " + section
                        + "[" + index + "] in kit '" + kitName + "': " + e.getMessage());
                return null;
            }
        }

        if (obj instanceof ConfigurationSection cs) {
            try {
                return ItemStack.deserialize((Map<String, Object>) cs.getValues(false));
            } catch (Exception e) {
                plugin.getLogger().warning("[KitManager] Failed to deserialize " + section
                        + "[" + index + "] in kit '" + kitName + "': " + e.getMessage());
                return null;
            }
        }

        plugin.getLogger().warning("[KitManager] Unknown type at " + section
                + "[" + index + "] in kit '" + kitName + "': " + obj.getClass().getName());
        return null;
    }

    public boolean removeKit(String name) {
        String key = "kits." + name.toLowerCase();
        if (kitsConfig.contains(key)) {
            kitsConfig.set(key, null);
            saveKitsConfig();
            return true;
        }
        return false;
    }

    public boolean kitExists(String name) {
        return kitsConfig.contains("kits." + name.toLowerCase());
    }

    public void reload() {
        loadKitsConfig();
    }
}
