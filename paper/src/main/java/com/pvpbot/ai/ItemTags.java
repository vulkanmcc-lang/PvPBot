package com.pvpbot.ai;

import com.pvpbot.PvPBotPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class ItemTags {
    private static NamespacedKey cargoKey;

    private ItemTags() {
    }

    private static NamespacedKey cargoKey() {
        if (cargoKey == null) {
            try {
                cargoKey = new NamespacedKey(PvPBotPlugin.getInstance(), "cargo");
            } catch (Throwable t) {
                return null;
            }
        }
        return cargoKey;
    }

    public static ItemStack markCargo(ItemStack stack) {
        if (stack == null) return null;
        NamespacedKey key = cargoKey();
        if (key == null) return stack;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

            stack.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
        return stack;
    }

    public static boolean isCargo(ItemStack stack) {
        if (stack == null) return false;
        NamespacedKey key = cargoKey();
        if (key == null) return false;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return false;
            return meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static ItemStack clearCargo(ItemStack stack) {
        if (stack == null) return null;
        NamespacedKey key = cargoKey();
        if (key == null) return stack;
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return stack;
            meta.getPersistentDataContainer().remove(key);
            stack.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
        return stack;
    }
}
