package com.pvpbot.ai;

import com.pvpbot.PvPBotPlugin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RestockController {
    public enum Phase { IDLE, PLACE, OPEN, LOOT, EQUIP_PICK, MINE, FINISH }

    public record Need(Material material, int low, int desired) {
    }

    public static boolean enabled = true;

    public static double safeRadius = 20.0;

    public static int attemptCooldown = 300;

    public static int windChargeCraftThreshold = 16;

    public static final Map<Material, Need> DEFAULT_NEEDS = new LinkedHashMap<>();

    static {
        def(Material.TOTEM_OF_UNDYING, 2, 6);
        def(Material.WIND_CHARGE, 8, 64);
        def(Material.EXPERIENCE_BOTTLE, 8, 64);
        def(Material.ENCHANTED_GOLDEN_APPLE, 2, 16);
        def(Material.GOLDEN_APPLE, 4, 32);
        def(Material.ENDER_PEARL, 2, 16);
    }

    private static void def(Material m, int low, int desired) {
        DEFAULT_NEEDS.put(m, new Need(m, low, desired));
    }

    public static List<Need> needs = new ArrayList<>(DEFAULT_NEEDS.values());

    private final BotAIContext context;

    private Phase phase = Phase.IDLE;
    private int timer = 0;
    private int cooldown = 0;

    private Location shulkerLoc = null;
    private Material shulkerType = null;

    private ItemStack[] contentsBackup = null;

    private int previousHeldSlot = -1;
    private int breakTicksTotal = 0;
    private int breakTicksElapsed = 0;
    private int lastDestroyStage = -1;

    private int lootIndex = 0;
    private int craftCooldown = 0;
    private int needScanCooldown = 0;

    private static final int NEED_SCAN_INTERVAL = 40;

    private ItemStack heldBox = null;

    public RestockController(BotAIContext context) {
        this.context = context;
    }

    public boolean isBusy() {
        return phase != Phase.IDLE;
    }

    public Phase getPhase() {
        return phase;
    }

    public boolean handleRestock(Player botPlayer) {
        if (!context.settings.isRestocking()) {
            if (isBusy()) abort();
            return false;
        }
        if (cooldown > 0) cooldown--;
        if (craftCooldown > 0) craftCooldown--;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || botPlayer == null || botPlayer.isDead()) {
            reclaim(botPlayer);
            return false;
        }

        if (phase != Phase.IDLE && !safeToContinue(botPlayer)) {
            reclaim(botPlayer);
            cooldown = attemptCooldown;
            return false;
        }

        if (phase == Phase.IDLE) {
            if (tryCraftWindCharges(botPlayer)) return true;

            if (!enabled || cooldown > 0) return false;

            if (needScanCooldown > 0) { needScanCooldown--; return false; }
            needScanCooldown = NEED_SCAN_INTERVAL;

            if (!handle.onGround() || context.bridging) return false;

            if (context.deliveryController != null && context.deliveryController.isActive()) {
                return false;
            }
            if (context.miningController != null && context.miningController.isActive()) {
                return false;
            }
            if (findUnmetNeeds(botPlayer).isEmpty()) return false;

            if (!safeToStart(botPlayer)) return false;
            if (!beginTrip(botPlayer)) return false;
        }

        return runPhase(botPlayer, handle);
    }

    private static int windChargesPerRod = -1;

    private static int windChargesPerRod() {
        if (windChargesPerRod > 0) return windChargesPerRod;

        int result = 4;
        try {
            for (Recipe r : Bukkit.getServer().getRecipesFor(new ItemStack(Material.WIND_CHARGE))) {
                if (!(r instanceof ShapelessRecipe shapeless)) continue;
                List<ItemStack> ingredients = shapeless.getIngredientList();
                if (ingredients.isEmpty()) continue;

                boolean rodsOnly = true;
                for (ItemStack ing : ingredients) {
                    if (ing == null || ing.getType() != Material.BREEZE_ROD) {
                        rodsOnly = false;
                        break;
                    }
                }
                if (rodsOnly) {
                    result = Math.max(1, r.getResult().getAmount());
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        windChargesPerRod = result;
        return result;
    }

    private boolean tryCraftWindCharges(Player p) {
        if (craftCooldown > 0) return false;

        craftCooldown = 20;

        if (countOf(p, Material.WIND_CHARGE) >= windChargeCraftThreshold) return false;

        int rodSlot = findSlotOf(p, Material.BREEZE_ROD);
        if (rodSlot < 0) return false;

        if (context.target != null) {
            double d = distanceToTarget(p);
            if (d < 6.0) return false;
        }

        PlayerInventory inv = p.getInventory();
        ItemStack rods = inv.getItem(rodSlot);
        if (rods == null || rods.getAmount() <= 0) return false;

        int per = windChargesPerRod();

        rods.setAmount(rods.getAmount() - 1);
        if (rods.getAmount() <= 0) inv.setItem(rodSlot, null);

        Map<Integer, ItemStack> leftover = inv.addItem(new ItemStack(Material.WIND_CHARGE, per));

        for (ItemStack l : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), l);
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle != null) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }
        try {
            p.getWorld().playSound(p.getLocation(),
                    org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
        } catch (Throwable ignored) {
        }

        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private boolean beginTrip(Player botPlayer) {
        List<Need> unmet = findUnmetNeeds(botPlayer);
        if (unmet.isEmpty()) return false;

        int shulkerSlot = findShulkerSatisfying(botPlayer, unmet);
        if (shulkerSlot < 0) {
            cooldown = attemptCooldown;
            return false;
        }

        Block spot = findPlacementSpot(botPlayer);
        if (spot == null) {
            cooldown = 60;
            return false;
        }

        PlayerInventory inv = botPlayer.getInventory();
        ItemStack box = inv.getItem(shulkerSlot);
        if (box == null) return false;

        contentsBackup = readContents(box);

        if (contentsBackup == null) {
            cooldown = attemptCooldown;
            return false;
        }
        shulkerType = box.getType();

        ItemStack single = box.clone();
        single.setAmount(1);
        if (box.getAmount() > 1) {
            box.setAmount(box.getAmount() - 1);
        } else {
            inv.setItem(shulkerSlot, null);
        }
        heldBox = single;

        shulkerLoc = spot.getLocation();
        previousHeldSlot = inv.getHeldItemSlot();
        phase = Phase.PLACE;
        timer = 6;
        lootIndex = 0;
        lastDestroyStage = -1;
        return true;
    }

    private boolean runPhase(Player botPlayer, ServerPlayer handle) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        handle.setSprinting(false);

        if (shulkerLoc != null) {
            context.movementController.lookAt(
                    shulkerLoc.clone().add(0.5, 0.5, 0.5), 0.0);
        }

        switch (phase) {
            case PLACE -> {
                if (timer-- > 0) return true;
                if (!placeBox(botPlayer)) {
                    reclaim(botPlayer);
                    cooldown = attemptCooldown;
                    return false;
                }
                phase = Phase.OPEN;
                timer = 10;
            }

            case OPEN -> {
                if (timer-- > 0) return true;
                phase = Phase.LOOT;
                timer = 3;
            }

            case LOOT -> {
                if (timer-- > 0) return true;
                timer = 3;
                if (!lootOneItem(botPlayer)) {
                    phase = Phase.EQUIP_PICK;
                }
            }

            case EQUIP_PICK -> {
                equipPickaxe(botPlayer);
                breakTicksTotal = computeBreakTicks(botPlayer);
                breakTicksElapsed = 0;
                lastDestroyStage = -1;
                phase = Phase.MINE;
            }

            case MINE -> {
                Block b = shulkerLoc.getBlock();
                if (!isShulker(b.getType())) {
                    heldBox = null;
                    phase = Phase.FINISH;
                    return true;
                }

                if (breakTicksElapsed % 4 == 0) {
                    handle.swing(InteractionHand.MAIN_HAND, true);
                    context.packetBroadcaster.broadcastAnimation(handle, 0);
                }

                breakTicksElapsed++;
                sendDestroyStage(b, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));

                if (breakTicksElapsed >= breakTicksTotal) {
                    breakBox(botPlayer, b);
                    phase = Phase.FINISH;
                }
            }

            case FINISH -> {
                clearDestroyStage();
                if (heldBox != null) giveOrDrop(botPlayer, heldBox);
                heldBox = null;

                if (previousHeldSlot >= 0 && previousHeldSlot <= 8) {
                    botPlayer.getInventory().setHeldItemSlot(previousHeldSlot);
                } else {
                    botPlayer.getInventory().setHeldItemSlot(
                            context.inventoryController.findBestWeaponSlot(botPlayer));
                }
                context.packetBroadcaster.broadcastEquipment();

                reset();
                cooldown = attemptCooldown;
                return false;
            }

            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean placeBox(Player botPlayer) {
        if (shulkerLoc == null || heldBox == null) return false;
        Block block = shulkerLoc.getBlock();
        if (!block.getType().isAir()) return false;

        Block support = block.getRelative(org.bukkit.block.BlockFace.DOWN);
        BlockState replaced = block.getState();

        block.setType(shulkerType, false);

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        block, replaced, support, heldBox.clone(), botPlayer, true,
                        org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        ShulkerIO.write(block, contentsBackup);

        try {
            block.getWorld().playSound(shulkerLoc, org.bukkit.Sound.BLOCK_SHULKER_BOX_OPEN, 0.8f, 1.0f);
        } catch (Throwable ignored) {
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle != null) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }
        return true;
    }

    private boolean lootOneItem(Player botPlayer) {
        Block block = shulkerLoc == null ? null : shulkerLoc.getBlock();
        if (block == null || !isShulker(block.getType())) return false;

        Inventory boxInv;
        try {
            BlockState st = block.getState();
            if (!(st instanceof ShulkerBox sb)) return false;
            boxInv = sb.getInventory();
        } catch (Throwable t) {
            return false;
        }

        List<Need> wanted = findBelowDesired(botPlayer);
        if (wanted.isEmpty() || lootIndex >= 64) return false;
        lootIndex++;

        for (Need need : wanted) {
            int have = countOf(botPlayer, need.material());
            int want = need.desired() - have;
            if (want <= 0) continue;

            int cap = Math.max(1, need.material().getMaxStackSize());
            if (cap > 1) want = Math.min(want, cap);

            for (int i = 0; i < boxInv.getSize(); i++) {
                ItemStack in = boxInv.getItem(i);
                if (in == null || in.getType() != need.material()) continue;

                int take = Math.min(want, in.getAmount());
                if (take <= 0) continue;

                ItemStack moved = in.clone();
                moved.setAmount(take);

                Map<Integer, ItemStack> leftover = botPlayer.getInventory().addItem(moved);
                int actuallyTaken = take;
                for (ItemStack l : leftover.values()) actuallyTaken -= l.getAmount();
                if (actuallyTaken <= 0) return false;

                in.setAmount(in.getAmount() - actuallyTaken);
                boxInv.setItem(i, in.getAmount() > 0 ? in : null);

                try {
                    block.getWorld().playSound(shulkerLoc,
                            org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
                } catch (Throwable ignored) {
                }
                return true;
            }
        }
        return false;
    }

    private void breakBox(Player botPlayer, Block block) {
        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        event.setDropItems(false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            heldBox = null;
            return;
        }

        ItemStack[] finalContents = contentsBackup;

        ItemStack[] fromBlock = ShulkerIO.read(block);
        if (fromBlock != null) finalContents = fromBlock;

        ItemStack rebuilt = new ItemStack(shulkerType, 1);
        try {
            if (rebuilt.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof ShulkerBox sb) {
                sb.getInventory().setContents(finalContents);
                bsm.setBlockState(sb);
                rebuilt.setItemMeta(bsm);
            }
        } catch (Throwable ignored) {
        }

        Material broken = block.getType();
        block.setType(Material.AIR, false);
        try {
            block.getWorld().playEffect(shulkerLoc, Effect.STEP_SOUND, broken);
        } catch (Throwable ignored) {
        }

        heldBox = rebuilt;
        clearDestroyStage();
    }

    private void reclaim(Player botPlayer) {
        if (phase == Phase.IDLE && heldBox == null) return;

        clearDestroyStage();

        if (shulkerLoc != null) {
            Block block = shulkerLoc.getBlock();
            if (isShulker(block.getType())) {
                ItemStack[] finalContents = contentsBackup;
                try {
                    BlockState st = block.getState();
                    if (st instanceof ShulkerBox sb) finalContents = sb.getInventory().getContents();
                } catch (Throwable ignored) {
                }

                ItemStack rebuilt = new ItemStack(block.getType(), 1);
                try {
                    if (rebuilt.getItemMeta() instanceof BlockStateMeta bsm
                            && bsm.getBlockState() instanceof ShulkerBox sb) {
                        sb.getInventory().setContents(finalContents);
                        bsm.setBlockState(sb);
                        rebuilt.setItemMeta(bsm);
                    }
                } catch (Throwable ignored) {
                }

                block.setType(Material.AIR, false);
                heldBox = rebuilt;
            }
        }

        if (heldBox != null && botPlayer != null) {
            giveOrDrop(botPlayer, heldBox);
        } else if (heldBox != null && shulkerLoc != null && shulkerLoc.getWorld() != null) {
            shulkerLoc.getWorld().dropItemNaturally(shulkerLoc, heldBox);
        }

        if (botPlayer != null && previousHeldSlot >= 0 && previousHeldSlot <= 8) {
            botPlayer.getInventory().setHeldItemSlot(previousHeldSlot);
            context.packetBroadcaster.broadcastEquipment();
        }

        reset();
    }

    public void abort() {
        reclaim(context.bot.getBukkitPlayer());
    }

    private void reset() {
        phase = Phase.IDLE;
        timer = 0;
        shulkerLoc = null;
        shulkerType = null;
        contentsBackup = null;
        heldBox = null;
        previousHeldSlot = -1;
        breakTicksTotal = 0;
        breakTicksElapsed = 0;
        lastDestroyStage = -1;
        lootIndex = 0;
    }

    private void giveOrDrop(Player botPlayer, ItemStack stack) {
        Map<Integer, ItemStack> leftover = botPlayer.getInventory().addItem(stack);
        for (ItemStack l : leftover.values()) {
            botPlayer.getWorld().dropItemNaturally(botPlayer.getLocation(), l);
        }
    }

    private int computeBreakTicks(Player botPlayer) {
        final double hardness = 2.0;
        double speed = 1.0;

        ItemStack hand = botPlayer.getInventory().getItemInMainHand();
        if (hand != null) {
            String n = hand.getType().name();
            if (n.endsWith("_PICKAXE")) {
                if (n.startsWith("WOODEN")) speed = 2.0;
                else if (n.startsWith("STONE")) speed = 4.0;
                else if (n.startsWith("IRON")) speed = 6.0;
                else if (n.startsWith("DIAMOND")) speed = 8.0;
                else if (n.startsWith("NETHERITE")) speed = 9.0;
                else if (n.startsWith("GOLDEN")) speed = 12.0;
            }
            try {
                int eff = hand.getEnchantmentLevel(Enchantment.EFFICIENCY);
                if (eff > 0 && speed > 1.0) speed += eff * eff + 1;
            } catch (Throwable ignored) {
            }
        }

        try {
            var haste = botPlayer.getPotionEffect(PotionEffectType.HASTE);
            if (haste != null) speed *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
        } catch (Throwable ignored) {
        }

        int ticks = (int) Math.ceil(30.0 * hardness / Math.max(0.01, speed));
        return Math.max(2, Math.min(ticks, 200));
    }

    private void equipPickaxe(Player botPlayer) {
        int slot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType().name().endsWith("_PICKAXE"));
        if (slot >= 0 && slot <= 8) {
            botPlayer.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
        }
    }

    private void sendDestroyStage(Block block, int stage) {
        stage = Math.max(0, Math.min(9, stage));
        if (stage == lastDestroyStage) return;
        lastDestroyStage = stage;
        sendDestroyPacket(block.getX(), block.getY(), block.getZ(), stage);
    }

    private void clearDestroyStage() {
        if (lastDestroyStage < 0 || shulkerLoc == null) return;
        sendDestroyPacket(shulkerLoc.getBlockX(), shulkerLoc.getBlockY(), shulkerLoc.getBlockZ(), -1);
        lastDestroyStage = -1;
    }

    private void sendDestroyPacket(int x, int y, int z, int stage) {
        try {
            ServerPlayer handle = context.bot.getHandle();
            if (handle == null) return;
            context.packetBroadcaster.sendPacketToAll(
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            handle.getId(), new net.minecraft.core.BlockPos(x, y, z), stage));
        } catch (Throwable ignored) {
        }
    }

    private int[] counts = new int[0];

    private void censusInventory(Player p) {
        int n = needs.size();
        if (counts.length < n) counts = new int[n];
        for (int i = 0; i < n; i++) counts[i] = 0;

        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null) continue;
            Material m = it.getType();
            for (int i = 0; i < n; i++) {
                if (needs.get(i).material() == m) { counts[i] += it.getAmount(); break; }
            }
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null) {
            Material m = off.getType();
            for (int i = 0; i < n; i++) {
                if (needs.get(i).material() == m) { counts[i] += off.getAmount(); break; }
            }
        }
    }

    private List<Need> findUnmetNeeds(Player botPlayer) {
        censusInventory(botPlayer);
        List<Need> out = new ArrayList<>(2);
        for (int i = 0; i < needs.size(); i++) {
            if (counts[i] < needs.get(i).low()) out.add(needs.get(i));
        }
        return out;
    }

    private List<Need> findBelowDesired(Player botPlayer) {
        censusInventory(botPlayer);
        List<Need> out = new ArrayList<>(4);
        for (int i = 0; i < needs.size(); i++) {
            if (counts[i] < needs.get(i).desired()) out.add(needs.get(i));
        }
        return out;
    }

    private int findShulkerSatisfying(Player botPlayer, List<Need> unmet) {
        PlayerInventory inv = botPlayer.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !isShulker(it.getType())) continue;

            if (ItemTags.isCargo(it)) continue;

            ItemStack[] contents = readContents(it);
            if (contents == null) continue;

            for (ItemStack in : contents) {
                if (in == null) continue;
                for (Need n : unmet) {
                    if (in.getType() == n.material()) return i;
                }
            }
        }
        return -1;
    }

    private static ItemStack[] readContents(ItemStack shulkerItem) {
        try {
            if (shulkerItem.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof ShulkerBox sb) {
                ItemStack[] src = sb.getInventory().getContents();
                ItemStack[] copy = new ItemStack[src.length];
                for (int i = 0; i < src.length; i++) {
                    copy[i] = src[i] == null ? null : src[i].clone();
                }
                return copy;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Block findPlacementSpot(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        org.bukkit.World w = loc.getWorld();
        if (w == null) return null;

        int fy = loc.getBlockY();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

        for (int[] o : offsets) {
            int x = loc.getBlockX() + o[0];
            int z = loc.getBlockZ() + o[1];

            Block at = w.getBlockAt(x, fy, z);
            Block below = w.getBlockAt(x, fy - 1, z);
            Block above = w.getBlockAt(x, fy + 1, z);

            if (!at.getType().isAir()) continue;
            if (!above.getType().isAir()) continue;
            if (!below.getType().isSolid()) continue;

            return at;
        }
        return null;
    }

    private boolean safeToStart(Player botPlayer) {
        if (context.fleeing || context.eating || context.drinkingPotionTimer > 0) return false;
        if (context.splashPotionTimer > 0 || context.holeEscapeTicks > 0) return false;
        if (context.cartThreat != null) return false;
        if (context.selfHurtTicks > 0) return false;
        return !anyHostileWithin(botPlayer, safeRadius);
    }

    private boolean safeToContinue(Player botPlayer) {
        if (context.selfHurtTicks > 0) return false;
        if (context.cartThreat != null) return false;

        return !anyHostileWithin(botPlayer, safeRadius * 0.5);
    }

    private boolean anyHostileWithin(Player botPlayer, double radius) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        double bx = handle.getX(), by = handle.getY(), bz = handle.getZ();
        double r2 = radius * radius;

        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(botPlayer.getWorld());
        var mgr = PvPBotPlugin.getInstance().getBotManager();

        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == botPlayer) continue;

            double dx = view.x[i] - bx, dy = view.y[i] - by, dz = view.z[i] - bz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            if (mgr != null && mgr.isFriendly(context.bot.getUUID(), p.getUniqueId())) continue;

            return true;
        }
        return false;
    }

    private double distanceToTarget(Player botPlayer) {
        if (context.target == null) return Double.MAX_VALUE;
        try {
            return context.target.getLocation().distance(botPlayer.getLocation());
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }

    private static boolean isShulker(Material m) {
        return m != null && m.name().endsWith("SHULKER_BOX");
    }

    private static int countOf(Player p, Material m) {
        int total = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == m) total += it.getAmount();
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == m) total += off.getAmount();
        return total;
    }

    private static int findSlotOf(Player p, Material m) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == m && it.getAmount() > 0) return i;
        }
        return -1;
    }

    public static void loadConfig(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return;

        enabled = section.getBoolean("enabled", true);
        safeRadius = section.getDouble("safe-radius", 20.0);
        attemptCooldown = Math.max(20, section.getInt("cooldown-ticks", 300));
        windChargeCraftThreshold = section.getInt("wind-charge-craft-threshold", 16);

        org.bukkit.configuration.ConfigurationSection items = section.getConfigurationSection("items");
        if (items == null) return;

        List<Need> parsed = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) {
                Bukkit.getLogger().warning("[PvPBot] restock: unknown material '" + key + "'");
                continue;
            }
            int low = items.getInt(key + ".low", 1);
            int desired = items.getInt(key + ".desired", Math.max(low, m.getMaxStackSize()));
            parsed.add(new Need(m, low, Math.max(low, desired)));
        }
        if (!parsed.isEmpty()) needs = parsed;
    }
}
