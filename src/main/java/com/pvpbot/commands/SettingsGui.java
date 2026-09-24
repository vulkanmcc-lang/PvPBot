package com.pvpbot.commands;

import com.pvpbot.BotSettings;
import com.pvpbot.PvPBotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SettingsGui implements Listener {
    private static final int SIZE = 27;
    private static final int TOGGLES_PER_PAGE = 24;
    private static final int SLOT_BACK = 24;
    private static final int SLOT_PREV = 25;
    private static final int SLOT_NEXT = 26;

    private static final int CATEGORY_HOME = -1;
    private static final int CATEGORY_COMBAT = 0;
    private static final int CATEGORY_UTILITY = 1;
    private static final int CATEGORY_GENERAL = 2;

    private static final Map<UUID, Session> OPEN = new HashMap<>();
    private static final Map<UUID, AnvilSession> ANVIL_OPEN = new HashMap<>();
    private static final Map<UUID, PendingInput> PENDING_INPUT = new HashMap<>();

    private static final class Session {
        int page;
        int category;
        Inventory inv;
    }

    private static final class PendingInput {
        BotSettings.Option option;
        int returnPage;
        int returnCategory;
    }

    private static final class AnvilSession {
        BotSettings.Option option;
        Inventory inv;
        int returnPage;
        int returnCategory;
    }

    private enum SettingCategory {
        COMBAT("Combat", Material.DIAMOND_SWORD,
            "strafing", "stapping", "wtapping", "sprint", "bhop", "criticals", "normalhits",
            "shielding", "shieldbreak", "autosword", "hitcoordination", "punishcrit",
            "jumpreset", "hitselect", "critdeflect", "outspacing", "circlestrafe",
            "shieldbackstab", "macesmash", "breachswap", "lungeswap", "elytramacing",
            "reach", "cooldown", "strafespeed", "misschance",
            "shieldbreakchance", "critfallticks", "aimnoise", "rotationspeed",
            "pitchspeed", "aimease", "reactionticks"),
        UTILITY("Utility", Material.WATER_BUCKET,
                "autoeat", "bridging", "speedbridging", "clutching", "restocking", "opendoors", "webescape",
                "terrainmemory", "pathfinding", "crowdavoidance", "tunnelwhenstuck",
                "leaderleash", "bots-use-invis", "cartdefense", "pearling", "cartpvp",
                "xbowcart", "cartcooldown"),
        GENERAL("General", Material.REPEATING_COMMAND_BLOCK,
            "hostile", "wanderwithoutfaction", "noautotargetwhileidle", "assistonly", "threattargeting", "investigate",
            "patrolengage", "patrolengagerange",
            "frozen", "invisibility-confusion", "range", "fleehealth", "returnhealth",
            "fleedistance", "prechaseheal", "prechasehealth", "prechasedistance",
            "difficulty", "teamtarget", "teamtargetradius", "lavaclutchstunts");

        private final String name;
        private final Material icon;
        private final List<String> options;

        SettingCategory(String name, Material icon, String... options) {
            this.name = name;
            this.icon = icon;
            this.options = List.of(options);
        }
    }

    public static void open(Player viewer, int page) {
        Session s = new Session();
        s.page = Math.max(0, page);
        s.category = CATEGORY_HOME;

        s.inv = Bukkit.createInventory(null, SIZE, "§8Bot Settings");

        OPEN.put(viewer.getUniqueId(), s);
        redraw(viewer, s);
        viewer.openInventory(s.inv);
    }

    private static BotSettings settingsFor(Session s) {
        PvPBotPlugin plugin = PvPBotPlugin.getInstance();
        if (plugin == null || plugin.getBotManager() == null) return null;
        return plugin.getBotManager().getGlobalSettings();
    }

    private static void redraw(Player viewer, Session s) {
        BotSettings settings = settingsFor(s);
        s.inv.clear();

        if (settings == null) {
            viewer.sendMessage("§cSettings are unavailable right now.");
            return;
        }

        if (s.category == CATEGORY_HOME) {
            redrawCategories(s);
        } else {
            redrawCategorySettings(s, settings);
        }
    }

    private static void redrawCategories(Session s) {
        s.inv.clear();
        int slot = 0;

        for (SettingCategory cat : SettingCategory.values()) {
            ItemStack item = new ItemStack(cat.icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + cat.name);
                List<String> lore = new ArrayList<>();
                lore.add("§7" + cat.options.size() + " settings");
                lore.add("§7Click to view");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            s.inv.setItem(slot++, item);
        }
    }

    private static void redrawCategorySettings(Session s, BotSettings settings) {
        SettingCategory cat = SettingCategory.values()[s.category];
        List<BotSettings.Option> opts = getCategoryOptions(cat);

        int pages = Math.max(1, (opts.size() + TOGGLES_PER_PAGE - 1) / TOGGLES_PER_PAGE);
        if (s.page >= pages) s.page = pages - 1;

        int start = s.page * TOGGLES_PER_PAGE;

        for (int i = 0; i < TOGGLES_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= opts.size()) break;

            BotSettings.Option o = opts.get(idx);
            boolean on = "true".equalsIgnoreCase(o.raw(settings));

            ItemStack item = getIconForSetting(o.key, on);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName((on ? "§a" : "§c") + o.label);
                List<String> lore = new ArrayList<>();
                lore.add("§8" + o.key);
                lore.add(on ? "§aENABLED" : "§cDISABLED");
                lore.add("§7Click to " + (on ? "disable" : "enable") + ".");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            s.inv.setItem(i, item);
        }

        if (s.page > 0) {
            s.inv.setItem(SLOT_PREV, nav(Material.ARROW, "§ePrevious page",
                    "§7Page " + s.page + " of " + pages));
        }
        if (s.page < pages - 1) {
            s.inv.setItem(SLOT_NEXT, nav(Material.ARROW, "§eNext page",
                    "§7Page " + (s.page + 2) + " of " + pages));
        }

        s.inv.setItem(SLOT_BACK, nav(Material.DARK_OAK_DOOR, "§cBack to Categories", "§7Return to category selection"));
    }

    private static List<BotSettings.Option> getCategoryOptions(SettingCategory cat) {
        List<BotSettings.Option> result = new ArrayList<>();
        for (String key : cat.options) {
            BotSettings.Option opt = BotSettings.option(key);
            if (opt != null) result.add(opt);
        }
        return result;
    }

    private static ItemStack getIconForSetting(String key, boolean enabled) {
        Material icon = switch (key.toLowerCase()) {
            case "strafing", "stapping", "wtapping", "circlestrafe" -> Material.SADDLE;
            case "sprint" -> Material.LEATHER_BOOTS;
            case "bhop" -> Material.SLIME_BALL;
            case "criticals", "punishcrit", "hitselect", "critdeflect" -> Material.FEATHER;
            case "normalhits" -> Material.STICK;
            case "shielding", "shieldbreak", "shieldbackstab" -> Material.SHIELD;
            case "autosword" -> Material.DIAMOND_SWORD;
            case "reach", "cooldown", "strafespeed", "misschance", "shieldbreakchance",
                "critfallticks", "aimnoise", "rotationspeed", "pitchspeed", "aimease",
                "reactionticks" -> Material.REDSTONE;
            case "hitcoordination" -> Material.CHORUS_FRUIT;
            case "macesmash" -> Material.MACE;
            case "breachswap" -> Material.NETHERITE_SWORD;
            case "lungeswap" -> Material.TRIDENT;
            case "elytramacing" -> Material.ELYTRA;
            case "autoeat" -> Material.BREAD;
            case "bridging" -> Material.STONE;
            case "speedbridging" -> Material.GOLDEN_PICKAXE;
            case "clutching" -> Material.WATER_BUCKET;
            case "restocking" -> Material.SHULKER_BOX;
            case "opendoors" -> Material.OAK_DOOR;
            case "webescape" -> Material.COBWEB;
            case "terrainmemory" -> Material.MAP;
            case "pathfinding" -> Material.COMPASS;
            case "crowdavoidance" -> Material.BARRIER;
            case "tunnelwhenstuck" -> Material.DIAMOND_PICKAXE;
            case "leaderleash" -> Material.LEAD;
            case "bots-use-invis" -> Material.SPLASH_POTION;
            case "cartdefense" -> Material.TNT_MINECART;
            case "pearling" -> Material.ENDER_PEARL;
            case "cartpvp", "xbowcart", "cartcooldown" -> Material.MINECART;

            case "hostile" -> Material.IRON_SWORD;
            case "wanderwithoutfaction" -> Material.COMPASS;
            case "noautotargetwhileidle" -> Material.SHIELD;
            case "assistonly" -> Material.IRON_SWORD;
            case "threattargeting" -> Material.REDSTONE_TORCH;
            case "investigate" -> Material.SPYGLASS;
            case "patrolengage", "patrolengagerange" -> Material.LANTERN;
            case "frozen" -> Material.BLUE_ICE;
            case "invisibility-confusion" -> Material.SNOWBALL;
            case "range" -> Material.OBSERVER;
            case "fleehealth", "returnhealth" -> Material.APPLE;
            case "prechaseheal", "prechasehealth", "prechasedistance" -> Material.GOLDEN_APPLE;
            case "fleedistance" -> Material.ENDER_EYE;
            case "lavaclutchstunts" -> Material.MAGMA_CREAM;
            case "difficulty" -> Material.NETHERITE_INGOT;
            case "teamtarget", "teamtargetradius" -> Material.HEART_OF_THE_SEA;

            default -> Material.PAPER;
        };

        ItemStack item = new ItemStack(enabled ? icon : Material.GRAY_DYE);
        return item;
    }

    private static ItemStack nav(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onSettingChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        Player viewer = e.getPlayer();
        PendingInput pending = PENDING_INPUT.remove(viewer.getUniqueId());
        if (pending == null) return;

        e.setCancelled(true);
        String typed = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(e.message()).trim();

        org.bukkit.Bukkit.getScheduler().runTask(
                com.pvpbot.PvPBotPlugin.getInstance(), () -> {
            if (typed.equalsIgnoreCase("cancel")) {
                viewer.sendMessage("§7Cancelled.");
                reopenSettings(viewer, pending.returnCategory, pending.returnPage);
                return;
            }

            BotSettings settings = settingsFor(null);
            if (settings == null) {
                viewer.sendMessage("§cSettings are unavailable right now.");
                return;
            }

            String error = pending.option.set(settings, typed);
            if (error != null) {
                viewer.sendMessage("§c" + error);
                PENDING_INPUT.put(viewer.getUniqueId(), pending);
                viewer.sendMessage("§7Type another value, or §fcancel§7.");
                return;
            }

            viewer.sendMessage("§a" + pending.option.label + ": §f"
                    + pending.option.display(settings));
            try {
                viewer.playSound(viewer.getLocation(),
                        org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
            } catch (Throwable ignored) {
            }
            reopenSettings(viewer, pending.returnCategory, pending.returnPage);
        });
    }

    private static void reopenSettings(Player viewer, int category, int page) {
        Session s2 = new Session();
        s2.category = category;
        s2.page = page;
        s2.inv = Bukkit.createInventory(null, SIZE, "§8Bot Settings");
        OPEN.put(viewer.getUniqueId(), s2);
        redraw(viewer, s2);
        viewer.openInventory(s2.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player viewer)) return;

        AnvilSession anvil = ANVIL_OPEN.get(viewer.getUniqueId());
        if (anvil != null && e.getInventory().equals(anvil.inv)) {
            e.setCancelled(true);
            if (e.getRawSlot() != 2) return;

            String typed = ((AnvilInventory) anvil.inv).getRenameText();
            if (typed == null || typed.isBlank()) {
                viewer.sendMessage("§cType a value into the name field first.");
                return;
            }

            BotSettings settings = settingsFor(null);
            if (settings == null) {
                viewer.sendMessage("§cSettings are unavailable right now.");
                viewer.closeInventory();
                return;
            }

            String error = anvil.option.set(settings, typed.trim());
            if (error != null) {
                viewer.sendMessage("§c" + error);
                return;
            }

            viewer.sendMessage("§a" + anvil.option.label + ": §f" + anvil.option.display(settings));
            try {
                viewer.playSound(viewer.getLocation(),
                        org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
            } catch (Throwable ignored) {
            }

            int returnCategory = anvil.returnCategory;
            int returnPage = anvil.returnPage;
            ANVIL_OPEN.remove(viewer.getUniqueId());
            viewer.closeInventory();

            Session s2 = new Session();
            s2.category = returnCategory;
            s2.page = returnPage;
            s2.inv = Bukkit.createInventory(null, SIZE, "§8Bot Settings");
            OPEN.put(viewer.getUniqueId(), s2);
            redraw(viewer, s2);
            viewer.openInventory(s2.inv);
            return;
        }

        Session s = OPEN.get(viewer.getUniqueId());
        if (s == null) return;

        if (!s.inv.equals(e.getInventory())) return;

        e.setCancelled(true);

        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(s.inv)) return;

        int slot = e.getRawSlot();

        if (s.category == CATEGORY_HOME) {
            if (slot < SettingCategory.values().length) {
                s.category = slot;
                s.page = 0;
                redraw(viewer, s);
            }
            return;
        }

        if (slot == SLOT_BACK) {
            s.category = CATEGORY_HOME;
            s.page = 0;
            redraw(viewer, s);
            return;
        }

        if (slot == SLOT_PREV && s.page > 0) {
            s.page--;
            redraw(viewer, s);
            return;
        }
        if (slot == SLOT_NEXT) {
            s.page++;
            redraw(viewer, s);
            return;
        }
        if (slot < 0 || slot >= TOGGLES_PER_PAGE) return;

        SettingCategory cat = SettingCategory.values()[s.category];
        List<BotSettings.Option> opts = getCategoryOptions(cat);
        int idx = s.page * TOGGLES_PER_PAGE + slot;
        if (idx >= opts.size()) return;

        BotSettings settings = settingsFor(s);
        if (settings == null) {
            viewer.closeInventory();
            return;
        }

        BotSettings.Option o = opts.get(idx);

        if (o.isBoolean()) {
            boolean on = "true".equalsIgnoreCase(o.raw(settings));
            String error = o.set(settings, on ? "false" : "true");
            if (error != null) {
                viewer.sendMessage("§c" + error);
                return;
            }

            try {
                viewer.playSound(viewer.getLocation(),
                        on ? org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS
                           : org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.0f);
            } catch (Throwable ignored) {
            }

            redraw(viewer, s);
            return;
        }

        startAnvilInput(viewer, o, s);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            OPEN.remove(p.getUniqueId());
            ANVIL_OPEN.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        boolean ours = false;
        for (AnvilSession session : ANVIL_OPEN.values()) {
            if (session.inv.equals(e.getInventory())) {
                ours = true;
                break;
            }
        }
        if (!ours) return;

        ItemStack input = e.getInventory().getItem(0);
        if (input != null && input.getType() != Material.AIR) {
            e.setResult(input.clone());
        }
        try {
            e.getInventory().setRepairCost(0);
        } catch (Throwable ignored) {
        }
    }

    private static void startAnvilInput(Player viewer, BotSettings.Option option, Session s) {
        PendingInput pending = new PendingInput();
        pending.option = option;
        pending.returnCategory = s.category;
        pending.returnPage = s.page;
        PENDING_INPUT.put(viewer.getUniqueId(), pending);

        viewer.closeInventory();

        BotSettings settings = settingsFor(null);
        viewer.sendMessage("§6" + option.label + " §7is currently §f"
                + (settings == null ? "?" : option.display(settings)));
        if (!option.suggestions.isEmpty()) {
            viewer.sendMessage("§7Suggestions: §f" + String.join("§7, §f", option.suggestions));
        }
        viewer.sendMessage("§eType the new value in chat§7, or §fcancel§7 to go back.");
    }

    public static void clear() {
       OPEN.clear();
       ANVIL_OPEN.clear();
       PENDING_INPUT.clear();
    }
}
