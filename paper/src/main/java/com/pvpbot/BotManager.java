package com.pvpbot;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

public class BotManager {
    private final PvPBotPlugin plugin;
    private final Map<UUID, PvPBot> activeBots = new java.util.concurrent.ConcurrentHashMap<>();

    private final BotSettings globalSettings = new BotSettings();

    private final Map<UUID, BotSettings> botSettingsMap = new java.util.concurrent.ConcurrentHashMap<>();

    public BotManager(PvPBotPlugin plugin) {
        this.plugin = plugin;

        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            boolean changed = false;

            synchronized(activeBots) {
                java.util.Iterator<Map.Entry<UUID, PvPBot>> iterator = activeBots.entrySet().iterator();

                while (iterator.hasNext()) {
                    PvPBot bot = iterator.next().getValue();

                    if (!bot.isAlive() || bot.getBukkitPlayer() == null || bot.getBukkitPlayer().isDead()) {
                        bot.remove();
                        botSettingsMap.remove(bot.getUUID());
                        removeFromAllFactions(bot.getUUID());
                        iterator.remove();
                        changed = true;
                    }
                }
            }

            if (changed) {
                saveFactionsToConfig();
            }
        }, 1L, 1L);
    }

    private final Map<String, Set<UUID>> factions = new HashMap<>();

    private final Map<UUID, String> factionOf = new HashMap<>();

    private final Map<String, Set<String>> alliances = new HashMap<>();
    private final Map<String, String> allianceOf = new HashMap<>();

    private final Map<String, UUID> factionLeaders = new HashMap<>();
    // One bot per faction the leader can address as "commander" by voice.
    private final Map<String, UUID> factionCommanders = new HashMap<>();

    public record FormationOrder(FormationManager.Shape shape, double spacing) {}

    private final Map<String, FormationOrder> factionFormations = new HashMap<>();

    private final Map<String, Boolean> factionStopAttack = new HashMap<>();

    public static final class BotGroup {
        private final String name;
        private final String faction;
        private UUID leader;
        private final Set<UUID> members = new LinkedHashSet<>();

        private BotGroup(String name, String faction) {
            this.name = name;
            this.faction = faction;
        }

        public String getName() { return name; }
        public String getFaction() { return faction; }
        public UUID getLeader() { return leader; }
        public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }
    }

    private final Map<String, BotGroup> groups = new HashMap<>();
    private final Map<UUID, String> groupOf = new HashMap<>();

    private final java.util.Set<UUID> armorTaskInProgress =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void createFaction(String name) {
        factions.putIfAbsent(normalize(name), new HashSet<>());
        saveFactionsToConfig();
    }

    public int equipFactionArmorDelayed(String factionName) {
        String key = normalize(factionName);
        Set<UUID> members = factions.get(key);

        if (members == null) return 0;

        int count = 0;

        for (UUID uuid : new HashSet<>(members)) {
            PvPBot bot = activeBots.get(uuid);

            if (bot == null || !bot.isAlive()) continue;
            if (bot.getBukkitPlayer() == null) continue;
            if (!armorTaskInProgress.add(uuid)) continue;

            scheduleArmorEquip(bot);
            count++;
        }

        return count;
    }

    public int removeFactionArmorDelayed(String factionName) {
        String key = normalize(factionName);
        Set<UUID> members = factions.get(key);

        if (members == null) return 0;

        int count = 0;

        for (UUID uuid : new HashSet<>(members)) {
            PvPBot bot = activeBots.get(uuid);

            if (bot == null || !bot.isAlive()) continue;
            if (bot.getBukkitPlayer() == null) continue;
            if (!armorTaskInProgress.add(uuid)) continue;

            scheduleArmorRemove(bot);
            count++;
        }

        return count;
    }

    // Single-bot versions (voice: "Andy put your armor on").
    public boolean equipArmorDelayed(PvPBot bot) {
        if (bot == null || !bot.isAlive() || bot.getBukkitPlayer() == null) return false;
        if (!armorTaskInProgress.add(bot.getUUID())) return false;
        scheduleArmorEquip(bot);
        return true;
    }

    public boolean removeArmorDelayed(PvPBot bot) {
        if (bot == null || !bot.isAlive() || bot.getBukkitPlayer() == null) return false;
        if (!armorTaskInProgress.add(bot.getUUID())) return false;
        scheduleArmorRemove(bot);
        return true;
    }

    // "put your best / worst armor on": for every slot, wear the strongest (or
    // weakest) piece the bot owns - worn or in its inventory - swapping the
    // current piece back into the inventory. One piece at a time, with the
    // equip sounds, like the plain armor on/off.
    public boolean equipRankedArmorDelayed(PvPBot bot, boolean best) {
        if (bot == null || !bot.isAlive() || bot.getBukkitPlayer() == null) return false;
        org.bukkit.entity.Player player = bot.getBukkitPlayer();
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        java.util.List<ArmorKind> kinds = new java.util.ArrayList<>();
        for (ArmorKind kind : new ArmorKind[]{ArmorKind.HELMET, ArmorKind.CHESTPLATE, ArmorKind.LEGGINGS, ArmorKind.BOOTS}) {
            ItemStack worn = wornPiece(inv, kind);
            // An elytra in the chest slot is flight gear (mace dives) - leave it.
            if (worn != null && worn.getType() == org.bukkit.Material.ELYTRA) continue;
            int pick = pickRankedPiece(inv, kind, best);
            if (pick < 0) continue;
            double pickScore = armorScore(inv.getItem(pick));
            boolean wornUsable = worn != null && !worn.getType().isAir();
            if (wornUsable) {
                double wornScore = armorScore(worn);
                if (best ? pickScore <= wornScore : pickScore >= wornScore) continue;
            }
            kinds.add(kind);
        }
        if (kinds.isEmpty()) return false;
        if (!armorTaskInProgress.add(bot.getUUID())) return false;

        java.util.Collections.shuffle(kinds);
        final int[] index = {0};
        final UUID botUuid = bot.getUUID();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                Player p = bot.getBukkitPlayer();
                if (!bot.isAlive() || p == null || p.isDead() || index[0] >= kinds.size()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }
                ArmorKind kind = kinds.get(index[0]++);
                org.bukkit.inventory.PlayerInventory pinv = p.getInventory();
                int pick = pickRankedPiece(pinv, kind, best);
                ItemStack wornNow = wornPiece(pinv, kind);
                if (pick >= 0 && (wornNow == null || wornNow.getType() != org.bukkit.Material.ELYTRA)) {
                    ItemStack chosen = pinv.getItem(pick).clone();
                    ItemStack worn = wornNow;
                    boolean keepWorn = worn != null && !worn.getType().isAir();
                    double chosenScore = armorScore(chosen);
                    boolean better = !keepWorn
                            || (best ? chosenScore > armorScore(worn) : chosenScore < armorScore(worn));
                    if (better) {
                        ItemStack single = chosen.clone();
                        single.setAmount(1);
                        if (chosen.getAmount() > 1) {
                            chosen.setAmount(chosen.getAmount() - 1);
                            pinv.setItem(pick, chosen);
                        } else {
                            pinv.setItem(pick, keepWorn ? worn.clone() : null);
                            keepWorn = false;
                        }
                        equipToArmorSlot(pinv, kind, single);
                        if (keepWorn) {
                            java.util.Map<Integer, ItemStack> left = pinv.addItem(worn.clone());
                            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
                        }
                        playArmorEquipSound(p, kind, single);
                        bot.broadcastEquipment();
                    }
                }
                if (index[0] < kinds.size()) {
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, this,
                            java.util.concurrent.ThreadLocalRandom.current().nextLong(8L, 26L));
                } else {
                    armorTaskInProgress.remove(botUuid);
                }
            }
        }, java.util.concurrent.ThreadLocalRandom.current().nextLong(5L, 21L));
        return true;
    }

    private static ItemStack wornPiece(org.bukkit.inventory.PlayerInventory inv, ArmorKind kind) {
        return switch (kind) {
            case HELMET -> inv.getHelmet();
            case CHESTPLATE -> inv.getChestplate();
            case LEGGINGS -> inv.getLeggings();
            case BOOTS -> inv.getBoots();
            default -> null;
        };
    }

    // Storage slot of the best (or worst) piece for `kind`; elytras don't
    // count as armor here.
    private int pickRankedPiece(org.bukkit.inventory.PlayerInventory inv, ArmorKind kind, boolean best) {
        int pick = -1;
        double pickScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (it.getType() == org.bukkit.Material.ELYTRA) continue;
            if (!matchesArmorKind(it.getType(), kind)) continue;
            double sc = armorScore(it);
            if (pick < 0 || (best ? sc > pickScore : sc < pickScore)) {
                pick = i;
                pickScore = sc;
            }
        }
        return pick;
    }

    // Rough protection value: material tier first, then Protection-type
    // enchants, then how worn it is.
    static double armorScore(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        String n = it.getType().name();
        double tier;
        if (n.startsWith("NETHERITE")) tier = 7;
        else if (n.startsWith("DIAMOND")) tier = 6;
        else if (n.startsWith("IRON")) tier = 5;
        else if (n.equals("TURTLE_HELMET")) tier = 4.5;
        else if (n.startsWith("CHAINMAIL")) tier = 4;
        else if (n.startsWith("GOLDEN")) tier = 3;
        else if (n.startsWith("COPPER")) tier = 3.5;
        else if (n.startsWith("LEATHER")) tier = 2;
        else tier = 1;
        double score = tier * 10;
        var ench = it.getEnchantments();
        for (var e : ench.entrySet()) {
            String key = e.getKey().getKey().getKey();
            if (key.equals("protection")) score += e.getValue() * 2.0;
            else if (key.contains("protection")) score += e.getValue();
            else if (key.equals("unbreaking") || key.equals("mending")) score += 0.3 * e.getValue();
        }
        if (it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && it.getType().getMaxDurability() > 0) {
            score -= 2.0 * d.getDamage() / it.getType().getMaxDurability();
        }
        return score;
    }

    private void scheduleArmorEquip(PvPBot bot) {
        org.bukkit.entity.Player player = bot.getBukkitPlayer();
        if (player == null) {
            armorTaskInProgress.remove(bot.getUUID());
            return;
        }

        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        java.util.List<ArmorPieceSlot> toEquip = new java.util.ArrayList<>();

        if (inv.getHelmet() == null || inv.getHelmet().getType().isAir()) {
            int idx = findArmorPieceInStorage(inv, ArmorKind.HELMET);
            if (idx != -1) toEquip.add(new ArmorPieceSlot(ArmorKind.HELMET, inv.getItem(idx).clone(), idx));
        }
        if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) {
            int idx = findArmorPieceInStorage(inv, ArmorKind.CHESTPLATE);
            if (idx != -1) toEquip.add(new ArmorPieceSlot(ArmorKind.CHESTPLATE, inv.getItem(idx).clone(), idx));
        }
        if (inv.getLeggings() == null || inv.getLeggings().getType().isAir()) {
            int idx = findArmorPieceInStorage(inv, ArmorKind.LEGGINGS);
            if (idx != -1) toEquip.add(new ArmorPieceSlot(ArmorKind.LEGGINGS, inv.getItem(idx).clone(), idx));
        }
        if (inv.getBoots() == null || inv.getBoots().getType().isAir()) {
            int idx = findArmorPieceInStorage(inv, ArmorKind.BOOTS);
            if (idx != -1) toEquip.add(new ArmorPieceSlot(ArmorKind.BOOTS, inv.getItem(idx).clone(), idx));
        }

        if (toEquip.isEmpty()) {
            armorTaskInProgress.remove(bot.getUUID());
            return;
        }

        java.util.Collections.shuffle(toEquip);

        long initialDelay =
                java.util.concurrent.ThreadLocalRandom.current().nextLong(5L, 21L);

        final int[] index = {0};
        final UUID botUuid = bot.getUUID();

        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!bot.isAlive()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                Player p = bot.getBukkitPlayer();
                if (p == null || p.isDead()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                if (index[0] >= toEquip.size()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                ArmorPieceSlot next = toEquip.get(index[0]++);
                equipArmorPiece(p, next);
                playArmorEquipSound(p, next.kind(), next.item());
                bot.broadcastEquipment();

                if (index[0] < toEquip.size()) {
                    long nextDelay =
                            java.util.concurrent.ThreadLocalRandom.current()
                                    .nextLong(8L, 26L);

                    org.bukkit.Bukkit.getScheduler().runTaskLater(
                            plugin,
                            this,
                            nextDelay
                    );
                } else {
                    armorTaskInProgress.remove(botUuid);
                }
            }
        }, initialDelay);
    }

    private void scheduleArmorRemove(PvPBot bot) {
        org.bukkit.entity.Player player = bot.getBukkitPlayer();
        if (player == null) {
            armorTaskInProgress.remove(bot.getUUID());
            return;
        }

        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        java.util.List<ArmorPieceSlot> toRemove = new java.util.ArrayList<>();

        if (inv.getHelmet() != null && !inv.getHelmet().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.HELMET, inv.getHelmet().clone(), idx));
        }
        if (inv.getChestplate() != null && !inv.getChestplate().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.CHESTPLATE, inv.getChestplate().clone(), idx));
        }
        if (inv.getLeggings() != null && !inv.getLeggings().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.LEGGINGS, inv.getLeggings().clone(), idx));
        }
        if (inv.getBoots() != null && !inv.getBoots().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.BOOTS, inv.getBoots().clone(), idx));
        }

        if (inv.getItemInMainHand() != null && !inv.getItemInMainHand().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.MAINHAND, inv.getItemInMainHand().clone(), idx));
        }
        if (inv.getItemInOffHand() != null && !inv.getItemInOffHand().getType().isAir()) {
            int idx = findEmptyInventorySlot(inv);
            if (idx != -1) toRemove.add(new ArmorPieceSlot(ArmorKind.OFFHAND, inv.getItemInOffHand().clone(), idx));
        }

        if (toRemove.isEmpty()) {
            armorTaskInProgress.remove(bot.getUUID());
            return;
        }

        java.util.Collections.shuffle(toRemove);

        long initialDelay =
                java.util.concurrent.ThreadLocalRandom.current().nextLong(5L, 21L);

        final int[] index = {0};
        final UUID botUuid = bot.getUUID();

        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!bot.isAlive()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                Player p = bot.getBukkitPlayer();
                if (p == null || p.isDead()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                if (index[0] >= toRemove.size()) {
                    armorTaskInProgress.remove(botUuid);
                    return;
                }

                ArmorPieceSlot next = toRemove.get(index[0]++);
                removeArmorPiece(p, next);
                playArmorEquipSound(p, next.kind(), next.item());
                bot.broadcastEquipment();

                if (index[0] < toRemove.size()) {
                    long nextDelay =
                            java.util.concurrent.ThreadLocalRandom.current()
                                    .nextLong(8L, 26L);

                    org.bukkit.Bukkit.getScheduler().runTaskLater(
                            plugin,
                            this,
                            nextDelay
                    );
                } else {
                    armorTaskInProgress.remove(botUuid);
                }
            }
        }, initialDelay);
    }

    private enum ArmorKind { HELMET, CHESTPLATE, LEGGINGS, BOOTS, MAINHAND, OFFHAND }

    private record ArmorPieceSlot(ArmorKind kind, ItemStack item, int sourceSlot) {}

    private int findArmorPieceInStorage(org.bukkit.inventory.PlayerInventory inv, ArmorKind kind) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (matchesArmorKind(it.getType(), kind)) return i;
        }
        return -1;
    }

    private boolean matchesArmorKind(org.bukkit.Material m, ArmorKind kind) {
        String n = m.name();
        return switch (kind) {
            case HELMET -> n.endsWith("_HELMET") || m == org.bukkit.Material.TURTLE_HELMET;
            case CHESTPLATE -> n.endsWith("_CHESTPLATE") || m == org.bukkit.Material.ELYTRA;
            case LEGGINGS -> n.endsWith("_LEGGINGS");
            case BOOTS -> n.endsWith("_BOOTS");
            case MAINHAND, OFFHAND -> false;
        };
    }

    private void equipArmorPiece(org.bukkit.entity.Player player, ArmorPieceSlot slot) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        ItemStack current = inv.getItem(slot.sourceSlot());
        if (current == null || !current.isSimilar(slot.item())) {
            int idx = findArmorPieceInStorage(inv, slot.kind());
            if (idx == -1) return;
            current = inv.getItem(idx);
            inv.setItem(idx, null);
            equipToArmorSlot(inv, slot.kind(), current);
            return;
        }

        int amount = current.getAmount();
        if (amount <= 1) {
            inv.setItem(slot.sourceSlot(), null);
        } else {
            current.setAmount(amount - 1);
        }

        ItemStack single = slot.item().clone();
        single.setAmount(1);
        equipToArmorSlot(inv, slot.kind(), single);
    }

    private void equipToArmorSlot(org.bukkit.inventory.PlayerInventory inv, ArmorKind kind, ItemStack piece) {
        switch (kind) {
            case HELMET -> inv.setHelmet(piece);
            case CHESTPLATE -> inv.setChestplate(piece);
            case LEGGINGS -> inv.setLeggings(piece);
            case BOOTS -> inv.setBoots(piece);
        }
    }

    private void playArmorEquipSound(org.bukkit.entity.Player player, ArmorKind kind, org.bukkit.inventory.ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        org.bukkit.Sound sound = getArmorEquipSound(item.getType());
        player.getWorld().playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private org.bukkit.Sound getArmorEquipSound(org.bukkit.Material material) {
        String name = material.name();
        if (name.contains("LEATHER")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_LEATHER;
        } else if (name.contains("CHAIN")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_CHAIN;
        } else if (name.contains("DIAMOND")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_DIAMOND;
        } else if (name.contains("GOLD") || name.contains("GOLDEN")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_GOLD;
        } else if (name.contains("NETHERITE")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_NETHERITE;
        } else if (name.contains("IRON")) {
            return org.bukkit.Sound.ITEM_ARMOR_EQUIP_IRON;
        }
        return org.bukkit.Sound.ITEM_ARMOR_EQUIP_GENERIC;
    }

    private int findEmptyInventorySlot(org.bukkit.inventory.PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) return i;
        }
        return -1;
    }

    private void removeArmorPiece(org.bukkit.entity.Player player, ArmorPieceSlot slot) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        ItemStack armorPiece = slot.item();
        int targetSlot = slot.sourceSlot();

        ItemStack existing = inv.getItem(targetSlot);
        if (existing != null && !existing.getType().isAir()) {
            targetSlot = findEmptyInventorySlot(inv);
            if (targetSlot == -1) return;
        }

        inv.setItem(targetSlot, armorPiece);
        clearArmorSlot(inv, slot.kind());
    }

    private void clearArmorSlot(org.bukkit.inventory.PlayerInventory inv, ArmorKind kind) {
        switch (kind) {
            case HELMET -> inv.setHelmet(null);
            case CHESTPLATE -> inv.setChestplate(null);
            case LEGGINGS -> inv.setLeggings(null);
            case BOOTS -> inv.setBoots(null);
            case MAINHAND -> inv.setItemInMainHand(null);
            case OFFHAND -> inv.setItemInOffHand(null);
        }
    }

    private boolean sameArmorPiece(
            ItemStack a,
            ItemStack b) {

        if (a == null || b == null) return false;

        return a.isSimilar(b);
    }

    public void removeFaction(String name) {
        String key = normalize(name);
        Set<UUID> members = factions.remove(key);
        if (members != null) members.forEach(factionOf::remove);
        factionLeaders.remove(key);
        factionCommanders.remove(key);
        factionFormations.remove(key);
        factionStopAttack.remove(key);
        saveFactionsToConfig();
    }

    public boolean factionExists(String name) {
        return factions.containsKey(normalize(name));
    }

    public boolean addPlayerToFaction(String factionName, UUID playerUUID) {
        String key = normalize(factionName);

        String previous = factionOf.get(playerUUID);
        if (previous != null) {
            Set<UUID> old = factions.get(previous);
            if (old != null) old.remove(playerUUID);

            if (!previous.equals(key)) {
                factionLeaders.remove(previous, playerUUID);
                factionCommanders.remove(previous, playerUUID);
                removeFromGroup(playerUUID);
            }
        }

        boolean created = !factions.containsKey(key);
        factions.computeIfAbsent(key, k -> new HashSet<>()).add(playerUUID);
        factionOf.put(playerUUID, key);

        saveFactionsToConfig();
        return created;
    }

    public void saveFactionsToConfig() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();

        config.set("factions", null);

        for (String factionName : factions.keySet()) {
            Set<UUID> members = factions.get(factionName);

            List<String> memberStrings = new ArrayList<>();
            for (UUID uuid : members) {
                memberStrings.add(uuid.toString());
            }

            config.set("factions." + factionName + ".members", memberStrings);

            UUID leader = factionLeaders.get(factionName);
            if (leader != null) {
                config.set("factions." + factionName + ".leader", leader.toString());
            }

            UUID commander = factionCommanders.get(factionName);
            if (commander != null) {
                config.set("factions." + factionName + ".commander", commander.toString());
            }

            FormationOrder formation = factionFormations.get(factionName);
            if (formation != null) {
                config.set("factions." + factionName + ".formation.shape", formation.shape().name());
                config.set("factions." + factionName + ".formation.spacing", formation.spacing());
            }

            if (Boolean.TRUE.equals(factionStopAttack.get(factionName))) {
                config.set("factions." + factionName + ".stopattack", true);
            }
        }

        config.set("alliances", null);
        for (Map.Entry<String, Set<String>> e : alliances.entrySet()) {
            config.set("alliances." + e.getKey(), new ArrayList<>(e.getValue()));
        }

        config.set("groups", null);
        for (BotGroup group : groups.values()) {
            List<String> memberStrings = new ArrayList<>();
            for (UUID uuid : group.members) memberStrings.add(uuid.toString());

            config.set("groups." + group.name + ".faction", group.faction);
            config.set("groups." + group.name + ".members", memberStrings);
            if (group.leader != null) {
                config.set("groups." + group.name + ".leader", group.leader.toString());
            }
        }

        plugin.saveConfig();
    }

    public void loadFactionsFromConfig() {
        factions.clear();
        factionOf.clear();
        factionLeaders.clear();
        factionCommanders.clear();
        factionFormations.clear();
        factionStopAttack.clear();
        alliances.clear();
        allianceOf.clear();
        groups.clear();
        groupOf.clear();

        org.bukkit.configuration.ConfigurationSection aSection =
                plugin.getConfig().getConfigurationSection("alliances");
        if (aSection != null) {
            for (String name : aSection.getKeys(false)) {
                String akey = normalize(name);
                Set<String> members = new HashSet<>();
                for (String f : plugin.getConfig().getStringList("alliances." + name)) {
                    String fkey = normalize(f);
                    members.add(fkey);
                    allianceOf.put(fkey, akey);
                }
                alliances.put(akey, members);
            }
        }

        org.bukkit.configuration.ConfigurationSection gSection = plugin.getConfig().getConfigurationSection("groups");
        if (gSection != null) {
            for (String groupName : gSection.getKeys(false)) {
                String gkey = normalize(groupName);
                String factionKey = plugin.getConfig().getString("groups." + groupName + ".faction");
                BotGroup group = new BotGroup(gkey, factionKey != null ? normalize(factionKey) : null);

                for (String s : plugin.getConfig().getStringList("groups." + groupName + ".members")) {
                    try {
                        UUID uuid = UUID.fromString(s);
                        group.members.add(uuid);
                        groupOf.put(uuid, gkey);
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                String leaderStr = plugin.getConfig().getString("groups." + groupName + ".leader");
                if (leaderStr != null) {
                    try {
                        group.leader = UUID.fromString(leaderStr);
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                groups.put(gkey, group);
            }
        }

        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("factions");
        if (section == null) return;

        for (String factionName : section.getKeys(false)) {
            String key = normalize(factionName);

            Set<UUID> members = new HashSet<>();
            List<String> memberStrings = plugin.getConfig().getStringList("factions." + factionName + ".members");
            for (String s : memberStrings) {
                try {
                    UUID uuid = UUID.fromString(s);
                    members.add(uuid);
                    factionOf.put(uuid, key);
                } catch (IllegalArgumentException ignored) {
                }
            }

            factions.put(key, members);

            String leaderStr = plugin.getConfig().getString("factions." + factionName + ".leader");
            if (leaderStr != null) {
                try {
                    factionLeaders.put(key, UUID.fromString(leaderStr));
                } catch (IllegalArgumentException ignored) {
                }
            }

            String commanderStr = plugin.getConfig().getString("factions." + factionName + ".commander");
            if (commanderStr != null) {
                try {
                    factionCommanders.put(key, UUID.fromString(commanderStr));
                } catch (IllegalArgumentException ignored) {
                }
            }

            String shapeStr = plugin.getConfig().getString("factions." + factionName + ".formation.shape");
            double spacing = plugin.getConfig().getDouble("factions." + factionName + ".formation.spacing", 0.0);
            if (shapeStr != null) {
                try {
                    FormationManager.Shape shape = FormationManager.Shape.valueOf(shapeStr);
                    factionFormations.put(key, new FormationOrder(shape, spacing));
                } catch (IllegalArgumentException ignored) {
                }
            }

            if (plugin.getConfig().getBoolean("factions." + factionName + ".stopattack", false)) {
                factionStopAttack.put(key, true);
            }
        }
    }

    public void removeFromAllFactions(UUID uuid) {
        String key = factionOf.remove(uuid);
        if (key != null) {
            Set<UUID> members = factions.get(key);
            if (members != null) members.remove(uuid);
            factionLeaders.remove(key, uuid);
            factionCommanders.remove(key, uuid);
        }
        removeFromGroup(uuid);
    }


    public boolean groupExists(String name) {
        return groups.containsKey(normalize(name));
    }

    public BotGroup getGroup(String name) {
        return groups.get(normalize(name));
    }

    public Set<String> getGroupNames() {
        return new HashSet<>(groups.keySet());
    }

    public List<String> getGroupNamesForFaction(String factionName) {
        String key = normalize(factionName);
        List<String> out = new ArrayList<>();
        for (BotGroup g : groups.values()) {
            if (g.faction != null && g.faction.equals(key)) out.add(g.name);
        }
        return out;
    }

    public String getGroupOf(UUID member) {
        return groupOf.get(member);
    }

    public List<PvPBot> createGroup(String groupName, String factionName, int amount) {
        String key = normalize(groupName);
        if (groups.containsKey(key)) return null;

        String factionKey = normalize(factionName);
        BotGroup group = new BotGroup(key, factionKey);

        List<PvPBot> pulled = new ArrayList<>();
        for (UUID uuid : getFactionMembers(factionKey)) {
            if (pulled.size() >= amount) break;
            if (groupOf.containsKey(uuid)) continue;

            PvPBot bot = activeBots.get(uuid);
            if (bot == null || !bot.isAlive()) continue;

            group.members.add(uuid);
            groupOf.put(uuid, key);
            pulled.add(bot);
        }

        groups.put(key, group);
        saveFactionsToConfig();
        return pulled;
    }

    public boolean disbandGroup(String groupName) {
        BotGroup group = groups.remove(normalize(groupName));
        if (group == null) return false;

        for (UUID uuid : group.members) {
            groupOf.remove(uuid, group.name);
        }
        saveFactionsToConfig();
        return true;
    }

    public boolean setGroupLeader(String groupName, UUID leader) {
        BotGroup group = groups.get(normalize(groupName));
        if (group == null) return false;
        if (group.faction != null) addPlayerToFaction(group.faction, leader);
        group.leader = leader;
        saveFactionsToConfig();
        return true;
    }

    public boolean clearGroupLeader(String groupName) {
        BotGroup group = groups.get(normalize(groupName));
        if (group == null) return false;
        group.leader = null;
        saveFactionsToConfig();
        return true;
    }

    public List<PvPBot> getGroupBots(String groupName) {
        BotGroup group = groups.get(normalize(groupName));
        if (group == null) return List.of();

        List<PvPBot> out = new ArrayList<>();
        for (UUID uuid : group.members) {
            PvPBot bot = activeBots.get(uuid);
            if (bot != null && bot.isAlive()) out.add(bot);
        }
        return out;
    }

    private void removeFromGroup(UUID uuid) {
        String groupKey = groupOf.remove(uuid);
        if (groupKey == null) return;
        BotGroup group = groups.get(groupKey);
        if (group != null) group.members.remove(uuid);
    }

    public boolean setFactionLeader(String factionName, UUID uuid) {
        boolean created = addPlayerToFaction(factionName, uuid);
        factionLeaders.put(normalize(factionName), uuid);
        saveFactionsToConfig();
        return created;
    }

    public void clearFactionLeader(String factionName) {
        factionLeaders.remove(normalize(factionName));
        saveFactionsToConfig();
    }

    public UUID getFactionLeader(String factionName) {
        return factionLeaders.get(normalize(factionName));
    }

    // The commander must be a member of the faction (joins it if not).
    public void setFactionCommander(String factionName, UUID bot) {
        String key = normalize(factionName);
        if (!key.equals(factionOf.get(bot))) addPlayerToFaction(factionName, bot);
        factionCommanders.put(key, bot);
        saveFactionsToConfig();
    }

    public boolean clearFactionCommander(String factionName) {
        boolean had = factionCommanders.remove(normalize(factionName)) != null;
        if (had) saveFactionsToConfig();
        return had;
    }

    public UUID getFactionCommander(String factionName) {
        return factionCommanders.get(normalize(factionName));
    }

    public boolean isCommander(UUID uuid) {
        String f = factionOf.get(uuid);
        return f != null && uuid.equals(factionCommanders.get(f));
    }

    public UUID getLeaderFor(UUID member) {
        String groupKey = groupOf.get(member);
        if (groupKey != null) {
            BotGroup group = groups.get(groupKey);
            if (group != null && group.leader != null) return group.leader;
        }
        String faction = factionOf.get(member);
        return faction == null ? null : factionLeaders.get(faction);
    }

    public boolean isLeader(UUID uuid) {
        if (uuid == null) return false;
        if (uuid.equals(getLeaderFor(uuid))) return true;

        for (BotGroup group : groups.values()) {
            if (uuid.equals(group.leader)) return true;
        }
        return false;
    }

    public void setFactionFormation(String faction, FormationManager.Shape shape, double spacing) {
        factionFormations.put(normalize(faction), new FormationOrder(shape, spacing));
        saveFactionsToConfig();
    }

    public FormationOrder getFactionFormation(String faction) {
        return faction == null ? null : factionFormations.get(normalize(faction));
    }

    public boolean breakFactionFormation(String faction) {
        boolean removed = faction != null && factionFormations.remove(normalize(faction)) != null;
        if (removed) {
            saveFactionsToConfig();
        }
        return removed;
    }

    public void setFactionStopAttack(String faction, boolean stop) {
        String key = normalize(faction);
        if (stop) {
            factionStopAttack.put(key, true);
        } else {
            factionStopAttack.remove(key);
        }
        saveFactionsToConfig();
    }

    public boolean isFactionStopAttack(String faction) {
        return faction != null && factionStopAttack.getOrDefault(normalize(faction), false);
    }

    public List<PvPBot> getFactionBotsOrdered(String factionName) {
        List<PvPBot> bots = getFactionBots(factionName);
        bots.sort(java.util.Comparator.comparing(b -> b.getUUID().toString()));
        return bots;
    }

    public Map<String, UUID> getFactionLeaders() {
        return new HashMap<>(factionLeaders);
    }

    public Set<String> getFactionNames() {
        return new HashSet<>(factions.keySet());
    }

    public List<PvPBot> getFactionBots(String factionName) {
        List<PvPBot> bots = new ArrayList<>();
        for (UUID uuid : getFactionMembers(factionName)) {
            PvPBot bot = activeBots.get(uuid);
            if (bot != null && bot.isAlive()) bots.add(bot);
        }
        return bots;
    }

    public String getPlayerFaction(UUID playerUUID) {
        return factionOf.get(playerUUID);
    }

    public Set<UUID> getFactionMembers(String factionName) {
        return factions.getOrDefault(normalize(factionName), Collections.emptySet());
    }

    public boolean isFriendly(UUID a, UUID b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String fa = factionOf.get(a);
        if (fa == null) return false;
        String fb = factionOf.get(b);
        if (fb == null) return false;
        if (fa.equals(fb)) return true;

        String aa = allianceOf.get(fa);
        return aa != null && aa.equals(allianceOf.get(fb));
    }

    public boolean createAlliance(String name) {
        String key = normalize(name);
        if (key.isEmpty() || alliances.containsKey(key)) return false;
        alliances.put(key, new HashSet<>());
        saveFactionsToConfig();
        return true;
    }

    public boolean allianceExists(String name) {
        return alliances.containsKey(normalize(name));
    }

    public boolean deleteAlliance(String name) {
        String key = normalize(name);
        Set<String> members = alliances.remove(key);
        if (members == null) return false;
        members.forEach(allianceOf::remove);
        saveFactionsToConfig();
        return true;
    }

    public String joinAlliance(String allianceName, String factionName) {
        String akey = normalize(allianceName);
        String fkey = normalize(factionName);
        if (!alliances.containsKey(akey)) return "no alliance called " + allianceName;
        if (!factions.containsKey(fkey)) return "no faction called " + factionName;

        String previous = allianceOf.get(fkey);
        if (akey.equals(previous)) return factionName + " is already in " + allianceName;
        if (previous != null) {
            Set<String> old = alliances.get(previous);
            if (old != null) old.remove(fkey);
        }

        alliances.get(akey).add(fkey);
        allianceOf.put(fkey, akey);
        saveFactionsToConfig();
        return null;
    }

    public boolean leaveAlliance(String factionName) {
        String fkey = normalize(factionName);
        String akey = allianceOf.remove(fkey);
        if (akey == null) return false;
        Set<String> members = alliances.get(akey);
        if (members != null) members.remove(fkey);
        saveFactionsToConfig();
        return true;
    }

    public Set<String> getAllianceNames() {
        return new HashSet<>(alliances.keySet());
    }

    public Set<String> getAllianceMembers(String name) {
        Set<String> m = alliances.get(normalize(name));
        return m == null ? new HashSet<>() : new HashSet<>(m);
    }

    public String getAllianceOf(String factionName) {
        return allianceOf.get(normalize(factionName));
    }

    private static String normalize(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    public PvPBot spawnBot(Location location) {
        return spawnBot(location, null);
    }

    public PvPBot spawnBot(Location location, String faction) {
        return spawnBot(location, faction, NameGenerator.NameStyle.CLEAN);
    }

    public PvPBot spawnBot(Location location, String faction, NameGenerator.NameStyle nameStyle) {
        BotSettings botSettings = new BotSettings(globalSettings);

        PvPBot bot = new PvPBot(location, plugin, botSettings, nameStyle);

        activeBots.put(bot.getUUID(), bot);
        botSettingsMap.put(bot.getUUID(), botSettings);

        if (faction != null && !faction.isBlank()) {
            addPlayerToFaction(faction, bot.getUUID());
        }

        return bot;
    }

    public PvPBot restoreBot(Location location, String faction,
                             String name, UUID uuid, BotSettings settings) {
        return restoreBot(location, faction, name, uuid, settings, NameGenerator.NameStyle.CLEAN);
    }

    public PvPBot restoreBot(Location location, String faction, String name, UUID uuid,
                             BotSettings settings, NameGenerator.NameStyle nameStyle) {
        BotSettings botSettings = settings != null ? settings : new BotSettings(globalSettings);

        PvPBot bot = new PvPBot(location, plugin, botSettings,
                nameStyle == null ? NameGenerator.NameStyle.CLEAN : nameStyle, name, uuid);

        activeBots.put(bot.getUUID(), bot);
        botSettingsMap.put(bot.getUUID(), botSettings);

        if (faction != null && !faction.isBlank()) {
            addPlayerToFaction(faction, bot.getUUID());
        }

        return bot;
    }

    public List<PvPBot> massSpawn(Location location, int count) {
        return massSpawn(location, count, null);
    }

    public List<PvPBot> massSpawn(Location location, int count, String faction) {
        return massSpawn(location, count, faction, NameGenerator.NameStyle.CLEAN);
    }

    public List<PvPBot> massSpawn(Location location, int count, String faction,
                                  NameGenerator.NameStyle nameStyle) {
        List<PvPBot> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location offsetLoc = location.clone().add((Math.random() - 0.5) * 2, 0, (Math.random() - 0.5) * 2);
            spawned.add(spawnBot(offsetLoc, faction, nameStyle));
        }
        return spawned;
    }

    public List<PvPBot> massSpawnGrid(Location anchor, int count, double spacing) {
        return massSpawnGrid(anchor, count, spacing, null);
    }

    public List<PvPBot> massSpawnGrid(Location anchor, int count, double spacing, String faction) {
        return massSpawnGrid(anchor, count, spacing, faction, NameGenerator.NameStyle.CLEAN);
    }

    public List<PvPBot> massSpawnGrid(Location anchor, int count, double spacing, String faction,
                                      NameGenerator.NameStyle nameStyle) {
        List<Location> slots = FormationManager.computeGrid(anchor, count, spacing);
        List<PvPBot> spawned = new ArrayList<>(slots.size());
        for (Location slot : slots) {
            spawned.add(spawnBot(slot, faction, nameStyle));
        }
        return spawned;
    }

    public void removeBot(UUID uuid) {
        PvPBot bot = activeBots.remove(uuid);
        if (bot != null) {
            bot.remove();
            botSettingsMap.remove(uuid);
            removeFromAllFactions(uuid);
            saveFactionsToConfig();
        }
    }

    public int removeAllBots() {
        return removeAllBots(true);
    }

    public int removeAllBots(boolean forget) {
        int count = activeBots.size();

        for (PvPBot bot : new ArrayList<>(activeBots.values())) {
            bot.remove(forget);
            if (forget) removeFromAllFactions(bot.getUUID());
        }

        activeBots.clear();
        if (forget) {
            botSettingsMap.clear();
            saveFactionsToConfig();
        }
        return count;
    }

    public void cleanUpAllBots() {
        removeAllBots();
    }

    public PvPBot getBotByName(String name) {
        for (PvPBot bot : activeBots.values()) {
            if (org.bukkit.ChatColor.stripColor(bot.getName()).equalsIgnoreCase(name)) {
                return bot;
            }
        }
        return null;
    }

    public Map<UUID, PvPBot> getBots() {
        return activeBots;
    }

    public BotSettings getGlobalSettings() {
        return globalSettings;
    }

    public BotSettings getBotSettings(UUID uuid) {
        return botSettingsMap.getOrDefault(uuid, globalSettings);
    }
}
