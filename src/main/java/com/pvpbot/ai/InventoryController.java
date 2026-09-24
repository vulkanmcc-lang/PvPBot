package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionType;
import java.util.function.Predicate;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.PotionMeta;

public class InventoryController {
    private final BotAIContext context;

    public static final int SLOT_SWORD   = 0;
    public static final int SLOT_AXE     = 1;
    public static final int SLOT_BLOCKS  = 2;
    public static final int SLOT_FOOD    = 3;
    public static final int SLOT_POTION  = 4;
    public static final int SLOT_UTILITY = 5;

    private static final int[] SCRATCH_SLOTS = {8, 7, 6};

    private int hotbarOrganizeCooldown = 0;

    public InventoryController(BotAIContext context) {
        this.context = context;
    }

    private static final int MAX_CHARGE_HOLD_TICKS = 20;
    private static final float CHARGE_WORTH_KEEPING = 0.8f;

    private int chargeHoldTicks = 0;

    private boolean wouldWasteCharge(Player p) {
        Player t = context.target;
        if (t == null || !isValidTarget(t)) return false;
        if (t.getWorld() != p.getWorld()) return false;

        if (holdsShield(t)) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;
        if (handle.getAttackStrengthScale(0.0f) < CHARGE_WORTH_KEEPING) return false;

        return p.getLocation().distance(t.getLocation()) <= context.settings.getReach() + 1.5;
    }

    public void handleInventorySwitching(Player p) {
        if (context.eating || context.drinkingPotionTimer > 0) return;

        if (context.maceStunSlamPhase > 0 || context.maceWindupTicks > 0) {
            context.weaponSwitchPendingSlot = -1;
            context.weaponSwitchDelayTicks = 0;
            return;
        }

        boolean holdForCharge = wouldWasteCharge(p) && chargeHoldTicks < MAX_CHARGE_HOLD_TICKS;
        if (holdForCharge) {
            chargeHoldTicks++;
        } else {
            chargeHoldTicks = 0;
        }

        if (context.weaponSwitchDelayTicks > 0) {
            if (holdForCharge) return;

            context.weaponSwitchDelayTicks--;
            if (context.weaponSwitchDelayTicks == 0 && context.weaponSwitchPendingSlot >= 0) {
                int desired = context.weaponSwitchPendingSlot;
                if (desired >= 0 && desired <= 8 && p.getInventory().getHeldItemSlot() != desired) {
                    p.getInventory().setHeldItemSlot(desired);
                    context.packetBroadcaster.broadcastEquipment();
                }
                context.weaponSwitchPendingSlot = -1;
            }
            return;
        }

        if (context.weaponSwitchCooldown > 0) return;
        if (holdForCharge) return;

        int desired = context.combatController.selectWeaponSlot(p);
        if (desired < 0 || desired > 8) return;

        if (p.getInventory().getHeldItemSlot() != desired
                && isAxe(p.getInventory().getItem(desired))
                && context.maceStunSlamCooldown <= 0
                && context.targetShieldMemory > 0
                && context.settings.isMaceSmash()) {
            p.getInventory().setHeldItemSlot(desired);
            context.packetBroadcaster.broadcastEquipment();
            context.weaponSwitchPendingSlot = -1;
            context.weaponSwitchDelayTicks = 0;
            return;
        }

        if (p.getInventory().getHeldItemSlot() != desired) {
            context.weaponSwitchPendingSlot = desired;
            context.weaponSwitchDelayTicks = 5 + java.util.concurrent.ThreadLocalRandom.current().nextInt(11);
            context.weaponSwitchCooldown = context.weaponSwitchDelayTicks + 2;
        }
    }

    public int ensureInHotbar(Player p, Predicate<ItemStack> match) {
        PlayerInventory inv = p.getInventory();

        int bestIdx = -1;
        double bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            if (!match.test(it)) continue;
            double score = itemScore(it);
            if (score > bestScore) { bestScore = score; bestIdx = i; }
        }

        if (bestIdx == -1) return -1;
        if (bestIdx < 9) return bestIdx;

        int dest = findDisplaceableHotbarSlot(p);
        if (dest < 0) return -1;
        ItemStack incoming  = inv.getItem(bestIdx).clone();
        ItemStack displaced = inv.getItem(dest) == null ? null : inv.getItem(dest).clone();

        inv.setItem(dest, incoming);
        inv.setItem(bestIdx, displaced);
        context.packetBroadcaster.broadcastEquipment();
        return dest;
    }

    public void organizeHotbar(Player p) {
        if (hotbarOrganizeCooldown > 0) { hotbarOrganizeCooldown--; return; }

        if (context.maceStunSlamPhase > 0 || context.maceWindupTicks > 0) return;

        hotbarOrganizeCooldown = 40;

        PlayerInventory inv = p.getInventory();
        int held = p.getInventory().getHeldItemSlot();
        stageBest(inv, held, SLOT_SWORD,   InventoryController::isSword);
        stageBest(inv, held, SLOT_AXE,     InventoryController::isAxe);
        stageBest(inv, held, SLOT_BLOCKS,  InventoryController::isPlaceableBlock);
        stageBest(inv, held, SLOT_FOOD,    it -> it.getType().isEdible());
        stageBest(inv, held, SLOT_POTION,  InventoryController::isHealingPotion);
        stageBest(inv, held, SLOT_UTILITY, it -> it.getType() == Material.COBWEB
                || it.getType() == Material.WATER_BUCKET
                || it.getType() == Material.WIND_CHARGE);
    }

    private void stageBest(PlayerInventory inv, int heldSlot, int targetSlot,
                           Predicate<ItemStack> match) {
        if (targetSlot == heldSlot) return;

        int bestIdx = -1;
        double bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            if (!match.test(it)) continue;
            double score = itemScore(it);
            if (score > bestScore) { bestScore = score; bestIdx = i; }
        }
        if (bestIdx == -1 || bestIdx == targetSlot || bestIdx == heldSlot) return;

        ItemStack current = inv.getItem(targetSlot);
        if (current != null && current.getType() != Material.AIR
                && match.test(current) && itemScore(current) >= bestScore) return;

        ItemStack incoming  = inv.getItem(bestIdx).clone();
        ItemStack displaced = (current == null) ? null : current.clone();
        inv.setItem(targetSlot, incoming);
        inv.setItem(bestIdx, displaced);
        context.packetBroadcaster.broadcastEquipment();
    }

    public static boolean isSword(ItemStack it) {
        return it != null && it.getType().name().endsWith("_SWORD");
    }

    public static boolean isAxe(ItemStack it) {
        return it != null && it.getType().name().endsWith("_AXE");
    }

    public static boolean isPlaceableBlock(ItemStack it) {
        if (it == null) return false;
        Material m = it.getType();
        if (!m.isBlock() || !m.isSolid()) return false;
        if (m == Material.COBWEB || m == Material.TNT) return false;
        if (BAD_BUILD_BLOCKS.contains(m)) return false;

        String n = m.name();

        if (n.endsWith("_FENCE") || n.endsWith("_WALL") || n.endsWith("_FENCE_GATE")) return false;
        if (n.endsWith("_PANE") || n.endsWith("_BARS")) return false;

        if (n.endsWith("_SHULKER_BOX")) return false;

        return !FALLING_BLOCKS.contains(m);
    }

    private static final java.util.EnumSet<Material> BAD_BUILD_BLOCKS =
            java.util.EnumSet.of(
                    Material.ENDER_CHEST, Material.CHEST, Material.TRAPPED_CHEST,
                    Material.BARREL, Material.BEACON, Material.CONDUIT,
                    Material.SPAWNER, Material.RESPAWN_ANCHOR, Material.LODESTONE,
                    Material.CRAFTING_TABLE, Material.FURNACE, Material.HOPPER,
                    Material.DISPENSER, Material.DROPPER, Material.OBSERVER,
                    Material.PISTON, Material.STICKY_PISTON, Material.NOTE_BLOCK,
                    Material.SLIME_BLOCK, Material.HONEY_BLOCK, Material.SOUL_SAND,
                    Material.MAGMA_BLOCK, Material.ICE, Material.BLUE_ICE,
                    Material.PACKED_ICE, Material.SCAFFOLDING);

    private static final java.util.EnumSet<Material> FALLING_BLOCKS =
            java.util.EnumSet.of(
                    Material.SAND, Material.RED_SAND, Material.GRAVEL,
                    Material.WHITE_CONCRETE_POWDER, Material.ORANGE_CONCRETE_POWDER,
                    Material.MAGENTA_CONCRETE_POWDER, Material.LIGHT_BLUE_CONCRETE_POWDER,
                    Material.YELLOW_CONCRETE_POWDER, Material.LIME_CONCRETE_POWDER,
                    Material.PINK_CONCRETE_POWDER, Material.GRAY_CONCRETE_POWDER,
                    Material.LIGHT_GRAY_CONCRETE_POWDER, Material.CYAN_CONCRETE_POWDER,
                    Material.PURPLE_CONCRETE_POWDER, Material.BLUE_CONCRETE_POWDER,
                    Material.BROWN_CONCRETE_POWDER, Material.GREEN_CONCRETE_POWDER,
                    Material.RED_CONCRETE_POWDER, Material.BLACK_CONCRETE_POWDER,
                    Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
                    Material.DRAGON_EGG, Material.POINTED_DRIPSTONE);

    private static boolean isHealingPotion(ItemStack it) {
        return potionHasEffect(it, Material.POTION, org.bukkit.potion.PotionEffectType.REGENERATION)
                || potionHasEffect(it, Material.POTION, org.bukkit.potion.PotionEffectType.INSTANT_HEALTH);
    }

    public static boolean potionHasEffect(ItemStack it, Material container,
                                          org.bukkit.potion.PotionEffectType effect) {
        if (it == null || it.getType() != container) return false;
        if (!(it.getItemMeta() instanceof PotionMeta meta)) return false;

        PotionType base = meta.getBasePotionType();
        if (base != null) {
            for (org.bukkit.potion.PotionEffect e : base.getPotionEffects()) {
                if (e.getType().equals(effect)) return true;
            }
        }
        for (org.bukkit.potion.PotionEffect e : meta.getCustomEffects()) {
            if (e.getType().equals(effect)) return true;
        }
        return false;
    }

    private static boolean isPotionOfType(ItemStack it, Material container, PotionType type) {
        if (it == null || it.getType() != container) return false;
        if (!(it.getItemMeta() instanceof PotionMeta meta)) return false;
        return meta.getBasePotionType() == type;
    }

    private double itemScore(ItemStack it) {
        double dps = calculateWeaponDamage(it);
        if (dps > 1.0) return dps;
        return 1.0 + Math.min(it.getAmount(), 64) / 100.0;
    }

    public int findBestSwordSlot(Player p) {
        return ensureInHotbar(p, InventoryController::isSword);
    }

    public int findBestAxeSlot(Player p) {
        return ensureInHotbar(p, InventoryController::isAxe);
    }

    public int findBestMaceSlot(Player p) {
        return ensureInHotbar(p, InventoryController::isMace);
    }

    public static boolean shieldDisabled(Player p) {
        if (p == null) return false;
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack off = inv.getItemInOffHand();
            ItemStack main = inv.getItemInMainHand();
            if (canBlockAttacks(off) && p.hasCooldown(off.getType())) return true;
            if (canBlockAttacks(main) && p.hasCooldown(main.getType())) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean canBlockAttacks(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        try {
            if (org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(it)
                    .has(net.minecraft.core.component.DataComponents.BLOCKS_ATTACKS)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return it.getType() == Material.SHIELD;
    }

    public static boolean holdsShield(Player p) {
        if (p == null) return false;
        PlayerInventory inv = p.getInventory();
        return canBlockAttacks(inv.getItemInMainHand())
                || canBlockAttacks(inv.getItemInOffHand());
    }

    public static boolean isBlockingWithShield(Player p) {
        if (p == null) return false;
        try {
            return ((org.bukkit.craftbukkit.entity.CraftPlayer) p)
                    .getHandle().getItemBlockingWith() != null;
        } catch (Throwable ignored) {
            return p.isHandRaised() && holdsShield(p);
        }
    }

    public static boolean isMace(ItemStack it) {
        return it != null && it.getType() == Material.MACE;
    }

    public static boolean carriesMace(Player p) {
        if (p == null) return false;
        try {
            PlayerInventory inv = p.getInventory();
            if (isMace(inv.getItemInMainHand())) return true;
            if (isMace(inv.getItemInOffHand())) return true;
            for (int i = 0; i < 36; i++) {
                if (isMace(inv.getItem(i))) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public int attackCooldownFor(ItemStack weapon) {
        double speed = 4.0;
        if (isSword(weapon)) speed = 1.6;
        else if (isAxe(weapon)) speed = 1.0;
        else if (isMace(weapon)) speed = 0.6;
        else if (weapon != null && weapon.getType() == Material.TRIDENT) speed = 1.1;

        int weaponTicks = (int) Math.ceil(20.0 / speed);
        return Math.max(context.settings.getAttackCooldownTicks(), weaponTicks);
    }

    public void releaseShield(Player botPlayer) {
        if (botPlayer == null) return;
        if (context.eating || context.drinkingPotionTimer > 0) return;
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || !handle.isUsingItem()) return;
        handle.stopUsingItem();
        context.packetBroadcaster.broadcastEntityData();
    }

    public void manageShieldBlock(Player botPlayer) {
        context.inventoryController.organizeHotbar(botPlayer);
        net.minecraft.server.level.ServerPlayer handle = context.bot.getHandle();

        int smashTicks = detectSmashThreat(botPlayer);
        context.smashThreatTicks = smashTicks;

        boolean consuming = context.eating || context.drinkingPotionTimer > 0;

        if (botPlayer.hasCooldown(Material.SHIELD)) {
            if (!consuming && handle.isUsingItem()) {
                handle.stopUsingItem();
                context.packetBroadcaster.broadcastEntityData();
            }
            context.shieldStunnedTicks = 10;
            return;
        }

        boolean shieldOnCooldown = shieldDisabled(botPlayer);
        boolean hasShield = holdsShield(botPlayer) && !shieldOnCooldown;
        if (!hasShield) {
            if (!consuming && handle.isUsingItem()) handle.stopUsingItem();
            return;
        }

        boolean threatBlock = context.settings.isShielding()
                && !consuming
                && context.smashThreatTicks > 0;

        boolean canBlock = context.settings.isShielding()
                && isValidTarget(context.target)
                && !consuming
                && !context.fleeing
                && context.knockbackRideTicks <= 0;

        Player t = context.target;
        boolean hasValidTarget = isValidTarget(t);

        double dist = 0;
        boolean closing = false;
        boolean inMelee = false;
        boolean willAttack = false;

        if (hasValidTarget) {
            Location botLoc = botPlayer.getLocation();
            dist = botLoc.distance(t.getLocation());
            if (context.enemyPredictCooldown > 0) context.enemyPredictCooldown--;
            closing = dist < context.lastEnemyDist;
            context.lastEnemyDist = dist;

            double horizDist = Math.hypot(t.getLocation().getX() - botLoc.getX(),
                    t.getLocation().getZ() - botLoc.getZ());
            inMelee = horizDist <= context.settings.getReach() + 1.5;

            float charge = handle.getAttackStrengthScale(0.0f);
            willAttack = inMelee && charge >= 0.80f;
        }

        if (!canBlock) {
            if (threatBlock && !willAttack) {
                if (!handle.isUsingItem()) {
                    handle.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
                    context.packetBroadcaster.broadcastEntityData();
                }
                return;
            }
            if (!consuming && handle.isUsingItem()) {
                handle.stopUsingItem();
                context.packetBroadcaster.broadcastEntityData();
            }
            return;
        }

        if (inMelee && t.isSprinting() && closing && context.enemyPredictCooldown <= 0) {
            context.shieldPredictTicks = 4;
            context.shieldHoldTicks = 3;
            context.enemyPredictCooldown = 15
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(10);
        }
        if (context.shieldPredictTicks > 0) context.shieldPredictTicks--;
        if (context.shieldHoldTicks > 0) context.shieldHoldTicks--;
        if (context.shieldFlickerTicks > 0) context.shieldFlickerTicks--;

        boolean holdShield = context.smashThreatTicks > 0
                || context.shieldPredictTicks > 0
                || context.shieldHoldTicks > 0;

        if (willAttack && context.smashThreatTicks <= 0) {
            holdShield = false;
        }



        if (holdShield && !handle.isUsingItem()) {
            handle.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
            context.packetBroadcaster.broadcastEntityData();
        } else if (!holdShield && !consuming && handle.isUsingItem()) {
            handle.stopUsingItem();
            context.packetBroadcaster.broadcastEntityData();
        }
    }

    private int detectSmashThreat(Player botPlayer) {
        net.minecraft.server.level.ServerPlayer selfHandle = context.bot.getHandle();
        if (selfHandle == null) return 0;

        double botX = selfHandle.getX();
        double botY = selfHandle.getY();
        double botZ = selfHandle.getZ();
        double eyeY = botY + botPlayer.getEyeHeight();

        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(botPlayer.getWorld());

        int worst = 0;
        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == botPlayer) continue;

            double px = view.x[i], py = view.y[i], pz = view.z[i];

            double dxa = px - botX, dza = pz - botZ;
            if (dxa * dxa + dza * dza > 5.5 * 5.5) continue;
            if (Math.abs(py - botY) > 14.0) continue;
            if (isFriendly(p)) continue;

            if (p.isOnGround()) continue;

            try {
                if (!botPlayer.hasLineOfSight(p)) continue;
            } catch (Throwable ignored) {
                continue;
            }

            double above = py - botY;
            double horiz = Math.sqrt(dxa * dxa + dza * dza);
            boolean maceInHand = isMace(p.getInventory().getItemInMainHand());

            int ticks;
            if (maceInHand && above > 1.0 && horiz < 6.0) {
                ticks = 16;
            } else if (above > 2.5 && horiz < 6.0 && carriesMace(p)) {
                ticks = 12;
            } else if (above > 1.0 && horiz < 2.5) {
                ticks = 6;
            } else {
                continue;
            }

            if (ticks > worst) worst = ticks;
        }
        return worst;
    }

    private boolean isFriendly(Player p) {
        return com.pvpbot.PvPBotPlugin.getInstance().getBotManager()
                .isFriendly(context.bot.getUUID(), p.getUniqueId());
    }

    public void manageOffhand(Player p) {
        if (context.eating || context.drinkingPotionTimer > 0) return;
        if (p.isInvisible() && !hasArmorEquipped(p)) return;

        Material current = p.getInventory().getItemInOffHand().getType();

        boolean wantTotem = context.totemRecoveryTicks > 0
                || p.getHealth() <= 6.0
                || context.fleeing;
        Material desired = wantTotem ? Material.TOTEM_OF_UNDYING : Material.SHIELD;

        if (context.offhandSwapTicks > 0) {
            if (--context.offhandSwapTicks > 0) return;

            Material target = context.pendingOffhand != null ? context.pendingOffhand : desired;
            context.pendingOffhand = null;
            boolean done = executeOffhandSwap(p, target);

            if (!done && target == Material.TOTEM_OF_UNDYING) {
                done = executeOffhandSwap(p, Material.SHIELD);
            }
            context.inventoryPauseTimer = done ? 20 : 40;
            return;
        }

        if (current == desired) { context.inventoryPauseTimer = 0; return; }

        Material available = null;
        if (findOffhandSlot(p, desired) >= 0) {
            available = desired;
        } else if (desired == Material.TOTEM_OF_UNDYING
                && findOffhandSlot(p, Material.SHIELD) >= 0) {
            available = Material.SHIELD;
        }

        if (available == null) {
            context.pendingOffhand = null;
            context.offhandSwapTicks = 0;
            context.inventoryPauseTimer = 60;
            return;
        }
        if (available == current) { context.inventoryPauseTimer = 0; return; }

        boolean urgent = current == Material.AIR
                || (wantTotem && current != Material.TOTEM_OF_UNDYING);

        if (!urgent && context.inventoryPauseTimer > 0) { context.inventoryPauseTimer--; return; }

        context.pendingOffhand = available;
        context.offhandSwapTicks = OFFHAND_SWAP_WINDUP_TICKS;
    }

    private static boolean hasArmorEquipped(Player p) {
        for (ItemStack armor : p.getInventory().getArmorContents()) {
            if (armor != null && armor.getType() != Material.AIR) return true;
        }
        return false;
    }

    private int findOffhandSlot(Player p, Material desired) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == desired) return i;
        }
        return -1;
    }

    private static final int OFFHAND_SWAP_WINDUP_TICKS = 8;

    public boolean isSwappingOffhand() {
        return context.offhandSwapTicks > 0;
    }

    private boolean executeOffhandSwap(Player p, Material desired) {
        PlayerInventory inv = p.getInventory();
        ItemStack offhand = inv.getItemInOffHand();

        int slot = findOffhandSlot(p, desired);
        if (slot == -1) return false;

        ItemStack desiredItem = inv.getItem(slot).clone();
        inv.setItem(slot, offhand);
        inv.setItemInOffHand(desiredItem);
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private static volatile boolean warnedMissingAttackDamageAttr = false;
    private static volatile boolean warnedMissingAttackSpeedAttr = false;

    public void applyToolAttributes(Player botPlayer) {
        net.minecraft.server.level.ServerPlayer handle = context.bot.getHandle();

        AttributeInstance dmgAttr = handle.getAttribute(Attributes.ATTACK_DAMAGE);
        if (dmgAttr != null) {
            if (dmgAttr.getBaseValue() != 1.0D) dmgAttr.setBaseValue(1.0D);
        } else if (!warnedMissingAttackDamageAttr) {
            warnedMissingAttackDamageAttr = true;
            com.pvpbot.PvPBotPlugin.getInstance().getLogger().warning(
                    "[PvPBot] handle.getAttribute(Attributes.ATTACK_DAMAGE) returned null for "
                            + context.bot.getName() + " - this bot's ATTACK_DAMAGE can never be set. "
                            + "(logged once)");
        }

        AttributeInstance speedAttr = handle.getAttribute(Attributes.ATTACK_SPEED);
        if (speedAttr != null) {
            if (speedAttr.getBaseValue() != 4.0D) speedAttr.setBaseValue(4.0D);
        } else if (!warnedMissingAttackSpeedAttr) {
            warnedMissingAttackSpeedAttr = true;
            com.pvpbot.PvPBotPlugin.getInstance().getLogger().warning(
                    "[PvPBot] handle.getAttribute(Attributes.ATTACK_SPEED) returned null for "
                            + context.bot.getName() + " - this bot's ATTACK_SPEED can never be set, "
                            + "so it can never charge an attack. (logged once)");
        }
    }


    public int findBestWeaponSlot(Player p) {
        int sword = findBestSwordSlot(p);
        if (sword != -1) return sword;

        int axe = findBestAxeSlot(p);
        if (axe != -1) return axe;

        PlayerInventory inv = p.getInventory();
        int bestSlot = -1;
        double bestDps = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            double dps = calculateWeaponDamage(it);
            if (dps > bestDps) { bestDps = dps; bestSlot = i; }
        }
        return bestSlot;
    }

    public int findBestWeaponSlotCached(Player p) {
        long hash = calculateInventoryHash(p);
        if (hash != context.lastInventoryHash) {
            context.lastInventoryHash = hash;
            context.cachedBestWeapon = findBestWeaponSlot(p);
        }
        return context.cachedBestWeapon;
    }

    private double calculateWeaponDamage(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return 0;
        String name = item.getType().name();

        double dmg;
        double speed;

        if (name.endsWith("_SWORD")) {
            speed = 1.6;
            if (name.startsWith("NETHERITE")) dmg = 8.0;
            else if (name.startsWith("DIAMOND")) dmg = 7.0;
            else if (name.startsWith("IRON")) dmg = 6.0;
            else if (name.startsWith("STONE")) dmg = 5.0;
            else dmg = 4.0;
        } else if (name.endsWith("_AXE")) {
            speed = 1.0;
            if (name.startsWith("NETHERITE")) dmg = 10.0;
            else if (name.startsWith("DIAMOND") || name.startsWith("IRON")
                    || name.startsWith("STONE")) dmg = 9.0;
            else dmg = 7.0;
        } else if (name.equals("TRIDENT")) {
            speed = 1.1; dmg = 9.0;
        } else if (name.endsWith("_PICKAXE")) {
            speed = 1.2; dmg = 3.0;
        } else if (name.endsWith("_SHOVEL")) {
            speed = 1.0; dmg = 3.0;
        } else if (name.endsWith("_HOE")) {
            speed = 1.0; dmg = 1.0;
        } else if (name.equals("SHEARS")) {
            speed = 1.0; dmg = 1.0;
        } else if (name.equals("MACE")) {
            speed = 1.0; dmg = 9.0;
        } else if (isSpear(item)) {
            speed = 1.0; dmg = 6.0;
        } else {
            return 0.0;
        }

        int sharp = item.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) dmg += 0.5 * sharp + 0.5;

        return dmg * speed;
    }

    private long calculateInventoryHash(Player p) {
        long hash = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null) {
                hash = hash * 31 + item.getType().name().hashCode();
            }
        }
        return hash;
    }

    public int findFoodSlot(Player p) {
        return ensureInHotbar(p, it -> it.getType().isEdible());
    }

    // Read-only check (no hotbar shuffling): does the bot carry anything it
    // could heal with — food or a drinkable regen/instant-health potion.
    public boolean hasHealingSupply(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (it.getType().isEdible() || isHealingPotion(it)) return true;
        }
        ItemStack off = inv.getItemInOffHand();
        return off != null && (off.getType().isEdible() || isHealingPotion(off));
    }

    public int findGoldenAppleSlot(Player p) {
        int enchanted = ensureInHotbar(p, it -> it.getType() == Material.ENCHANTED_GOLDEN_APPLE);
        if (enchanted >= 0) return enchanted;
        return ensureInHotbar(p, it -> it.getType() == Material.GOLDEN_APPLE);
    }

    public int findBlockSlot(Player p) {
        return ensureInHotbar(p, InventoryController::isPlaceableBlock);
    }

    public int findItemSlot(Player p, Material m) {
        return ensureInHotbar(p, it -> it.getType() == m);
    }

    public int findWindChargeSlot(Player p) {
        return ensureInHotbar(p, it -> it.getType() == Material.WIND_CHARGE);
    }

    public int findElytraSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack chest = inv.getChestplate();
        if (chest != null && chest.getType() == Material.ELYTRA) return -2;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == Material.ELYTRA) return i;
        }
        return -1;
    }

    public boolean equipElytraForMace(Player p) {
        if (context.elytraEquippedForMace) return true;
        PlayerInventory inv = p.getInventory();
        int slot = findElytraSlot(p);
        if (slot == -1) return false;
        if (slot == -2) return true;

        ItemStack prevChest = inv.getChestplate();
        context.elytraChestBackup = (prevChest == null) ? null : prevChest.clone();
        context.elytraChestSlot = (slot >= 0) ? slot : -1;

        ItemStack ely = inv.getItem(slot);
        inv.setChestplate(ely);
        inv.setItem(slot, context.elytraChestBackup);
        context.elytraEquippedForMace = true;
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    public void tickElytraRestore(Player p) {
        if (!context.elytraEquippedForMace) {
            context.elytraIdleTicks = 0;
            return;
        }

        ServerPlayer handle = context.bot.getHandle();
        boolean airborneWork = handle == null
                || !handle.onGround()
                || handle.isFallFlying()
                || context.maceWindupTicks > 0
                || context.maceStunSlamPhase > 0;

        boolean gliding;
        try {
            gliding = p.isGliding();
        } catch (Throwable ignored) {
            gliding = false;
        }

        if (airborneWork || gliding) {
            context.elytraIdleTicks = 0;
            return;
        }

        if (++context.elytraIdleTicks < ELYTRA_RESTORE_GRACE) return;

        context.elytraIdleTicks = 0;
        unequipElytraForMace(p);
    }

    private static final int ELYTRA_RESTORE_GRACE = 4;

    public void unequipElytraForMace(Player p) {
        if (!context.elytraEquippedForMace) return;
        PlayerInventory inv = p.getInventory();
        ItemStack current = inv.getChestplate();

        inv.setChestplate(context.elytraChestBackup);

        if (current != null && current.getType() == Material.ELYTRA) {
            if (context.elytraChestSlot >= 0) {
                inv.setItem(context.elytraChestSlot, current);
            } else {
                int dest = findDisplaceableHotbarSlot(p);
                if (dest >= 0) inv.setItem(dest, current);
            }
        }

        context.elytraChestBackup = null;
        context.elytraChestSlot = -1;
        context.elytraEquippedForMace = false;
        context.packetBroadcaster.broadcastEquipment();
    }

    public static boolean isSpear(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        try {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(it)
                    .has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public int findSpearSlot(Player p) {
        return ensureInHotbar(p, InventoryController::isSpear);
    }

    public int findLungeSpearSlot(Player p) {
        return ensureInHotbar(p, it -> isSpear(it) && readLungeLevel(it) > 0);
    }

    public int readLungeLevel(ItemStack it) {
        if (it == null) return 0;
        try {
            for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e
                    : it.getEnchantments().entrySet()) {
                if (e.getKey() == null) continue;
                String key = String.valueOf(e.getKey().getKey()).toLowerCase();
                if (key.contains("lunge")) return e.getValue();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public int readWindburstLevel(ItemStack it) {
        if (it == null) return 0;

        try {
            org.bukkit.enchantments.Enchantment ench1 = org.bukkit.enchantments.Enchantment.getByName("WIND_BURST");
            if (ench1 != null) return it.getEnchantmentLevel(ench1);
            org.bukkit.enchantments.Enchantment ench2 = org.bukkit.enchantments.Enchantment.getByName("WINDBURST");
            if (ench2 != null) return it.getEnchantmentLevel(ench2);
        } catch (Throwable ignored) { }

        try {
            java.util.Map<org.bukkit.enchantments.Enchantment, Integer> enchMap = it.getEnchantments();
            for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e : enchMap.entrySet()) {
                org.bukkit.enchantments.Enchantment ench = e.getKey();
                if (ench == null) continue;
                int lvl = e.getValue();
                String key = "";
                try {
                    var ns = ench.getKey();
                    if (ns != null) key = ns.getKey().toLowerCase(java.util.Locale.ROOT);
                } catch (Throwable ex) {
                    key = "";
                }
                if (key.contains("wind") || key.contains("burst")) return lvl;
            }
        } catch (Throwable ignored) { }

        if (!it.hasItemMeta()) return 0;
        org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
        if (meta == null) return 0;
        java.util.List<String> lore = meta.hasLore() ? meta.getLore() : null;
        if (lore == null) return 0;
        for (String line : lore) {
            if (line == null) continue;
            String plain = org.bukkit.ChatColor.stripColor(line).toLowerCase(java.util.Locale.ROOT);
            if (plain.contains("windburst") || plain.contains("wind burst") || plain.contains("wind-burst")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(line);
                if (m.find()) {
                    try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { }
                }
                return 1;
            }
        }

        return 0;
    }

    public int findPotionSlot(Player p, PotionType type) {
        return ensureInHotbar(p, it -> isPotionOfType(it, Material.POTION, type));
    }

    public int findSplashPotionSlot(Player p, PotionType type) {
        return ensureInHotbar(p, it -> isPotionOfType(it, Material.SPLASH_POTION, type));
    }

    public int findDrinkableSlotByEffect(Player p, org.bukkit.potion.PotionEffectType effect) {
        return ensureInHotbar(p, it -> potionHasEffect(it, Material.POTION, effect));
    }

    public int findSplashSlotByEffect(Player p, org.bukkit.potion.PotionEffectType effect) {
        return ensureInHotbar(p, it -> potionHasEffect(it, Material.SPLASH_POTION, effect));
    }

    public int findEmptyHotbarSlot(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) return i;
        }
        return -1;
    }

    public int findDisplaceableHotbarSlot(Player p) {
        PlayerInventory inv = p.getInventory();

        int held = inv.getHeldItemSlot();

        for (int i : SCRATCH_SLOTS) {
            if (i == held) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) return i;
        }
        for (int i : SCRATCH_SLOTS) {
            if (i == held) continue;
            ItemStack it = inv.getItem(i);
            if (it == null) return i;
            if (!isSword(it) && !isAxe(it) && !isMace(it)
                    && it.getType() != Material.TRIDENT && !it.getType().isEdible()
                    && it.getType() != Material.TOTEM_OF_UNDYING) return i;
        }
        return -1;
    }

    private boolean isValidTarget(Player p) {
        return TargetFilter.isEngageable(p, context.bot.getBukkitPlayer());
    }
}
