package com.pvpbot.ai;

import com.pvpbot.PvPBotPlugin;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class CombatController {
    private final BotAIContext context;

    private static final int SHIELD_DISABLE_TICKS = 100;

    private static final int SHIELD_MEMORY_TICKS = 80;

    private static final int STUN_SLAM_PHASE_DURATION = 20;

    public enum HitType {
        CRITICAL,
        SWEEP,
        KNOCKBACK,
        NORMAL
    }

    public CombatController(BotAIContext context) {
        this.context = context;
    }

    private static final int MACE_FULL_CHARGE_TICKS = 34;

    private static final int BREACH_SWAP_COOLDOWN = 60;
    private static final double BREACH_SWAP_CHANCE = 0.45;

    private net.minecraft.world.item.component.AttackRange heldAttackRange() {
        try {
            ServerPlayer handle = context.bot.getHandle();
            if (handle == null) return null;
            return handle.getMainHandItem()
                    .get(net.minecraft.core.component.DataComponents.ATTACK_RANGE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double attackMaxRangeOf(org.bukkit.inventory.ItemStack it) {
        if (it == null) return 0.0;
        try {
            var range = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(it)
                    .get(net.minecraft.core.component.DataComponents.ATTACK_RANGE);
            return range == null ? 0.0 : range.maxRange();
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private void disableTargetShield(ServerPlayer nmsTarget,
                                     net.minecraft.world.item.ItemStack preCaptured) {
        try {
            net.minecraft.world.item.ItemStack blocking = preCaptured;
            if (blocking == null || blocking.isEmpty()) {
                blocking = nmsTarget.getItemBlockingWith();
            }
            if (blocking == null || blocking.isEmpty()) {
                blocking = nmsTarget.getUseItem();
            }
            if (blocking != null && !blocking.isEmpty()) {
                nmsTarget.getCooldowns().addCooldown(blocking, SHIELD_DISABLE_TICKS);
                nmsTarget.stopUsingItem();
                boolean stuck = context.target.hasCooldown(
                        org.bukkit.craftbukkit.inventory.CraftItemStack
                                .asBukkitCopy(blocking).getType());
                context.lastShieldBreak = stuck ? "nms-ok" : "nms-NOSTICK";
                return;
            }
            context.lastShieldBreak = "no-blocking-item";
        } catch (Throwable t) {
            context.lastShieldBreak = "nms-threw";
        }
        try {
            context.target.setCooldown(Material.SHIELD, SHIELD_DISABLE_TICKS);
            context.lastShieldBreak += "/bukkit-"
                    + (context.target.hasCooldown(Material.SHIELD) ? "ok" : "NOSTICK");
        } catch (Throwable ignored) {
            context.lastShieldBreak += "/bukkit-threw";
        }
    }

    public double effectiveReach() {
        double configured = context.settings.getReach();
        var range = heldAttackRange();
        double base = range == null ? configured : Math.max(configured, range.maxRange());
        return Math.max(base, context.swingReachOverride);
    }

    public double minimumAttackRange() {
        var range = heldAttackRange();
        return range == null ? 0.0 : range.minRange();
    }

    private static final double LUNGE_MIN_GAP = 4.0;
    private static final double LUNGE_MAX_GAP = 12.0;
    private static final int LUNGE_SWAP_COOLDOWN = 50;

    private static final int LUNGE_CHAIN_LENGTH = 2;

    private static final int LUNGE_RETRY_GAP = 6;

    private static final int LUNGE_CHAIN_GAP = 12;

    boolean tryLungeSwap(Player botPlayer, double distance) {
        if (!context.settings.isLungeSwap()) return false;
        boolean chaining = context.lungeChainRemaining > 0;

        if (distance < LUNGE_MIN_GAP || distance > LUNGE_MAX_GAP
                || context.fleeing || context.eating || context.drinkingPotionTimer > 0) {
            if (chaining) {
                context.lungeChainRemaining = 0;
                context.lungeSwapCooldown = LUNGE_SWAP_COOLDOWN;
            }
            return false;
        }
        if (!chaining && context.lungeSwapCooldown > 0) return false;
        if (context.lungeRetryTicks > 0) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || context.target == null) return false;

        try {
            if (botPlayer.isGliding() || handle.isFallFlying()) return false;
        } catch (Throwable ignored) {
        }

        int spearSlot = context.inventoryController.findLungeSpearSlot(botPlayer);
        if (spearSlot < 0 || spearSlot > 8) return false;

        if (!chaining) {
            context.lungeChainRemaining = LUNGE_CHAIN_LENGTH;
            if (handle.onGround()) {
                context.movementController.requestJump();
                return true;
            }
        }

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();
        botPlayer.getInventory().setHeldItemSlot(spearSlot);
        context.packetBroadcaster.broadcastEquipment();

        Location eye = context.target.getEyeLocation();
        context.movementController.lookAt(eye, 0.0);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        boolean lunged = false;
        try {
            net.minecraft.world.item.ItemStack nmsStack =
                    handle.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!handle.cannotAttackWithItem(nmsStack, 5)) {
                var piercing = nmsStack.get(
                        net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
                if (piercing != null) {
                    piercing.attack(handle, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                    lunged = true;
                }
            }
        } catch (Throwable ignored) {
        }

        if (!lunged) {
            botPlayer.getInventory().setHeldItemSlot(previousSlot);
            context.packetBroadcaster.broadcastEquipment();
            context.lungeRetryTicks = LUNGE_RETRY_GAP;
            if (context.lungeChainRemaining > 0) context.lungeChainRemaining--;
            if (context.lungeChainRemaining <= 0) {
                context.lungeSwapCooldown = LUNGE_SWAP_COOLDOWN;
            } else if (!chaining) {
                context.lungeSwapCooldown = 10;
            }
            return false;
        }

        context.packetBroadcaster.broadcastAnimation(handle, 0);
        context.ticksSinceSwing = 0;

        botPlayer.getInventory().setHeldItemSlot(previousSlot);
        context.packetBroadcaster.broadcastEquipment();

        context.lungeRetryTicks = LUNGE_CHAIN_GAP;
        if (context.lungeChainRemaining > 0) context.lungeChainRemaining--;
        if (context.lungeChainRemaining <= 0) {
            context.lungeSwapCooldown = LUNGE_SWAP_COOLDOWN;
        }
        return true;
    }

    public boolean tryLungeAway(Player botPlayer, float awayYaw) {
        if (!context.settings.isLungeSwap()) return false;
        if (context.lungeSwapCooldown > 0 || context.lungeRetryTicks > 0) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        int spearSlot = context.inventoryController.findLungeSpearSlot(botPlayer);
        if (spearSlot < 0 || spearSlot > 8) return false;

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();
        botPlayer.getInventory().setHeldItemSlot(spearSlot);
        context.packetBroadcaster.broadcastEquipment();

        context.requestLook(awayYaw, 0.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        boolean lunged = false;
        try {
            net.minecraft.world.item.ItemStack nmsStack =
                    handle.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!handle.cannotAttackWithItem(nmsStack, 5)) {
                var piercing = nmsStack.get(
                        net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
                if (piercing != null) {
                    piercing.attack(handle, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                    lunged = true;
                }
            }
        } catch (Throwable ignored) {
        }

        botPlayer.getInventory().setHeldItemSlot(previousSlot);
        context.packetBroadcaster.broadcastEquipment();

        if (!lunged) {
            context.lungeRetryTicks = LUNGE_RETRY_GAP;
            return false;
        }
        context.lungeSwapCooldown = LUNGE_SWAP_COOLDOWN;
        context.ticksSinceSwing = 0;
        return true;
    }

    public boolean tryGapCloser(Player botPlayer, double distance) {
        if (context.target == null) return false;
        if (context.fleeing || context.eating || context.drinkingPotionTimer > 0) return false;

        boolean hasSpear = context.inventoryController.findLungeSpearSlot(botPlayer) >= 0;

        if (reservesGapForMace(botPlayer, distance)) return false;

        if (hasSpear && distance <= LUNGE_MAX_GAP
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.6
                && tryLungeSwap(botPlayer, distance)) {
            return true;
        }

        if (hasElytraKit(botPlayer) && distance >= 12.0 && distance <= 60.0
                && context.elytraApproachCooldown <= 0
                && startElytraApproach(botPlayer)) {
            return true;
        }

        return false;
    }

    private static final int ELYTRA_APPROACH_COOLDOWN = 160;
    private static final int ELYTRA_APPROACH_MAX_TICKS = 120;
    // Floor between rockets when the rocket entity couldn't be tracked.
    private static final int ELYTRA_APPROACH_BURN = 22;

    private boolean startElytraApproach(Player botPlayer) {
        if (!context.inventoryController.equipElytraForMace(botPlayer)) return false;
        int rocket = context.inventoryController.findItemSlot(botPlayer, Material.FIREWORK_ROCKET);
        if (rocket < 0 || rocket > 8) {
            context.inventoryController.unequipElytraForMace(botPlayer);
            return false;
        }

        // Jump; the glide opens on the next jump press in mid-air.
        context.movementController.requestJump();

        context.elytraApproachTicks = ELYTRA_APPROACH_MAX_TICKS;
        context.elytraApproachBurn = 0;
        context.elytraApproachCooldown = ELYTRA_APPROACH_COOLDOWN;
        elytraApproachRocket = null;
        return true;
    }

    // The rocket pulling us in, while it burns.
    private org.bukkit.entity.Projectile elytraApproachRocket = null;

    public boolean driveElytraApproach(Player botPlayer) {
        if (context.elytraApproachTicks <= 0) return false;
        context.elytraApproachTicks--;

        Player target = context.target;
        ServerPlayer handle = context.bot.getHandle();
        if (target == null || handle == null || context.fleeing) {
            endElytraApproach(botPlayer);
            return false;
        }

        double gap = botPlayer.getLocation().distance(target.getLocation());
        if (gap <= 5.0) {
            endElytraApproach(botPlayer);
            return false;
        }

        if (!handle.isFallFlying()) {
            if (handle.onGround()) {
                // Never got off the ground (or landed): one more hop, then give up.
                if (ELYTRA_APPROACH_MAX_TICKS - context.elytraApproachTicks > 12) {
                    endElytraApproach(botPlayer);
                    return false;
                }
                context.movementController.requestJump();
                return true;
            }
            if (!VanillaUse.startGlide(handle)) {
                endElytraApproach(botPlayer);
                return false;
            }
        }

        org.bukkit.util.Vector toTarget = target.getLocation().toVector()
                .add(new org.bukkit.util.Vector(0, 1.0, 0))
                .subtract(botPlayer.getEyeLocation().toVector());
        if (toTarget.lengthSquared() < 0.01) return true;
        toTarget.normalize();

        double flat = Math.hypot(toTarget.getX(), toTarget.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(toTarget.getY(), Math.max(0.05, flat)));
        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, false);

        if (elytraApproachRocket != null && !elytraApproachRocket.isValid()) elytraApproachRocket = null;
        if (context.elytraApproachBurn > 0) context.elytraApproachBurn--;
        if (elytraApproachRocket != null || context.elytraApproachBurn > 0) return true;

        // A real rocket: it pulls along our look for its whole flight.
        int rocket = context.inventoryController.findItemSlot(botPlayer, Material.FIREWORK_ROCKET);
        if (rocket < 0 || rocket > 8) {
            endElytraApproach(botPlayer);
            return false;
        }
        VanillaUse.Result r = VanillaUse.useFromHotbar(context, botPlayer, rocket, yaw, pitch);
        if (r.used()) {
            elytraApproachRocket = r.projectile();
            context.elytraApproachBurn = ELYTRA_APPROACH_BURN;
        }
        return true;
    }

    // Swapping the elytra off is how a player ends a glide.
    private void endElytraApproach(Player botPlayer) {
        context.elytraApproachTicks = 0;
        context.elytraApproachBurn = 0;
        elytraApproachRocket = null;
        context.inventoryController.unequipElytraForMace(botPlayer);
    }

    private boolean hasElytraKit(Player botPlayer) {
        return context.settings.isElytraMacing()
                && context.inventoryController.findElytraSlot(botPlayer) != -1
                && context.inventoryController.findItemSlot(
                        botPlayer, Material.FIREWORK_ROCKET) >= 0;
    }

    public boolean reservesGapForMace(Player botPlayer, double distance) {
        return hasElytraKit(botPlayer)
                && context.settings.isMaceSmash()
                && context.maceController.findBestMaceSlot(botPlayer) >= 0
                && distance >= 10.0 && distance <= 40.0
                && context.maceWindupCooldown <= 0;
    }

    public static boolean isCombatWeaponItem(org.bukkit.inventory.ItemStack it) {
        if (it == null) return false;
        String n = it.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")
                || it.getType() == Material.MACE || it.getType() == Material.TRIDENT;
    }

    public int selectWeaponSlot(Player botPlayer) {
        boolean targetBlocking = context.target != null && isValidTarget(context.target)
                && InventoryController.isBlockingWithShield(context.target);

        if (context.maceController.shouldHoldMace(botPlayer)) {
            int mace = context.maceController.findBestMaceSlot(botPlayer);
            if (mace != -1) return mace;
        }

        if (context.maceStunSlamPhase > 0) {
            int phaseWeapon = context.maceStunSlamPhase >= 2
                    ? context.maceController.findBestMaceSlot(botPlayer)
                    : context.inventoryController.findBestAxeSlot(botPlayer);
            if (phaseWeapon != -1) return phaseWeapon;
        }

        if (context.maceHoldAfterAttack > 0 && !targetBlocking
                && context.bot.getHandle() != null && !context.bot.getHandle().onGround()) {
            int mace = context.maceController.findBestMaceSlot(botPlayer);
            if (mace != -1) return mace;
        }

        if (context.target != null && isValidTarget(context.target)
                && InventoryController.holdsShield(context.target)) {
            context.targetShieldMemory = SHIELD_MEMORY_TICKS;
        }
        boolean shieldCarrier = context.target != null && isValidTarget(context.target)
                && context.targetShieldMemory > 0;

        boolean stunSlamWants = shieldCarrier
                && context.maceStunSlamCooldown <= 0
                && context.settings.isMaceSmash()
                && context.maceController.findBestMaceSlot(botPlayer) >= 0;

        if ((targetBlocking || stunSlamWants)
                && (context.axeStunCooldown <= 0 || stunSlamWants)) {
            int axe = context.inventoryController.findBestAxeSlot(botPlayer);
            if (axe != -1) return axe;
        }

        int sword = context.inventoryController.findBestSwordSlot(botPlayer);
        if (sword != -1) return sword;

        return context.inventoryController.findBestWeaponSlot(botPlayer);
    }

    private void resetCrit() {
        context.critPhase = BotAIContext.CritPhase.IDLE;
        context.critPhaseTicks = 0;
        context.critFallTicks = 0;
    }

    private void prepareOffensiveAction(Player botPlayer) {
        if (botPlayer == null) return;

        context.shieldPredictTicks = 0;
        context.shieldHoldTicks = 0;
        context.shieldFlickerTicks = 0;
        context.inventoryController.releaseShield(botPlayer);
    }

    private float panicChargePenalty(Player botPlayer) {
        return botPlayer.getHealth() <= context.settings.getFleeHealthThreshold() * 1.5
                ? 0.08f : 0.0f;
    }

    private void rollAttackChargeThreshold() {
        double slop = switch (context.settings.getDifficulty()) {
            case EASY -> 0.20;
            case NORMAL -> 0.12;
            case HARD -> 0.08;
            case EXPERT -> 0.04;
        };
        context.attackChargeThreshold = (float) (1.0
                - java.util.concurrent.ThreadLocalRandom.current().nextDouble() * slop);
    }

    public double reachDistance(ServerPlayer handle, Player target) {
        net.minecraft.world.entity.Entity nms =
                ((org.bukkit.craftbukkit.entity.CraftPlayer) target).getHandle();
        return Math.sqrt(nms.getBoundingBox().distanceToSqr(handle.getEyePosition()));
    }

    private boolean isChargedEnough(ServerPlayer handle, Player botPlayer) {
        float threshold = Math.min(0.85f,
                context.attackChargeThreshold + panicChargePenalty(botPlayer));
        float chargeAmount = handle.getAttackStrengthScale(0.0f);
        float variance = 0.07f * (float) java.util.concurrent.ThreadLocalRandom.current().nextDouble();
        return chargeAmount >= (threshold - variance);
    }

    private boolean shouldAttemptCritical(ServerPlayer handle, double distance,
                                         double reachDistance) {
        if (!context.settings.isCriticals() || context.settings.isNormalHits()) return false;
        if (context.criticalRetryTicks > 0 || context.lastLandedAttackTick < 0) return false;
        if (!handle.onGround() || context.jumpCooldown > 0) return false;

        double reach = effectiveReach();
        if (distance > reach - 0.35 || reachDistance > reach - 0.20) return false;

        double chance = switch (context.settings.getDifficulty()) {
            case EASY -> 0.10;
            case NORMAL -> 0.15;
            case HARD -> 0.20;
            case EXPERT -> 0.25;
        };
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble() < chance;
    }

    private boolean continueCriticalAttempt(Player botPlayer, ServerPlayer handle) {
        if (context.critPhase == BotAIContext.CritPhase.IDLE) return false;

        if (++context.critPhaseTicks > 12) {
            resetCrit();
            context.criticalRetryTicks = 24;
            return false;
        }

        switch (context.critPhase) {
            case JUMPING -> {
                if (!handle.onGround()) {
                    context.critPhase = BotAIContext.CritPhase.ASCENDING;
                    context.critPhaseTicks = 0;
                    context.critFallTicks = 0;
                }
                return true;
            }
            case ASCENDING -> {
                if (!handle.onGround()) {
                    if (handle.getDeltaMovement().y < 0
                            && ++context.critFallTicks >= context.settings.getCriticalFallTicks()) {
                        boolean attacked = performAttack(botPlayer, true);
                        resetCrit();
                        context.criticalRetryTicks = 24
                                + java.util.concurrent.ThreadLocalRandom.current().nextInt(17);
                        return attacked;
                    }
                    return true;
                }

                resetCrit();
                context.criticalRetryTicks = 18;
                return false;
            }
            case IDLE -> {
                return false;
            }
        }
        return false;
    }

    public void handleAttack(Player botPlayer, double distance) {
        context.lastAttackDistance = distance;

        if (!isValidTarget(context.target)) {
            context.maceController.abortSequences(botPlayer);
            context.lastAttackGate = "invalid target";
            resetCrit();
            return;
        }
        if (context.fleeing) { context.lastAttackGate = "fleeing"; resetCrit(); return; }
        if (context.eating || context.drinkingPotionTimer > 0) { context.lastAttackGate = "eating/drinking"; resetCrit(); return; }
        // Blocking an incoming mace smash beats any swing we could land now:
        // attacking means lowering the shield, and the smash still connects.
        if (context.inventoryController.smashThreatBlocking(botPlayer)) {
            context.lastAttackGate = "shield up (mace smash incoming)";
            resetCrit();
            return;
        }

        if (context.maceWindupTicks > 0 || context.maceWindCharge != null) {
            prepareOffensiveAction(botPlayer);
            context.maceController.handleMaceWindup(botPlayer, distance);
            context.maceController.handleMaceAttack(botPlayer, distance);
            if (context.maceWindupTicks > 0 || context.maceWindCharge != null) {
                context.lastAttackGate = "mace windup in progress";
                return;
            }
        }

        if (context.maceStunSlamPhase > 0) {
            prepareOffensiveAction(botPlayer);
            context.maceController.handleStunSlam(botPlayer);
            context.lastAttackGate = "mace stun slam in progress";
            return;
        }

        Material inHand = botPlayer.getInventory().getItemInMainHand().getType();
        if (inHand.isEdible() || inHand == Material.POTION) { context.lastAttackGate = "holding food/potion"; resetCrit(); return; }
        if (!isCombatWeapon(botPlayer.getInventory().getItemInMainHand())) {
            int weapon = context.inventoryController.findBestWeaponSlot(botPlayer);
            if (weapon >= 0 && weapon <= 8
                    && botPlayer.getInventory().getHeldItemSlot() != weapon) {
                botPlayer.getInventory().setHeldItemSlot(weapon);
                context.packetBroadcaster.broadcastEquipment();
            }
            context.lastAttackGate = "no combat weapon in hand (" + inHand + ")";
            resetCrit();
            return;
        }
        if (InventoryController.isMace(botPlayer.getInventory().getItemInMainHand())
                && !context.maceController.shouldHoldMace(botPlayer)
                && context.maceStunSlamPhase == 0) {
            int sword = context.inventoryController.findBestSwordSlot(botPlayer);
            if (sword >= 0 && sword <= 8) {
                botPlayer.getInventory().setHeldItemSlot(sword);
                context.packetBroadcaster.broadcastEquipment();
                context.lastAttackGate = "swapping mace -> sword";
                resetCrit();
                return;
            }
        }

        // The axe is for breaking shields (and the stun slam): once the target
        // isn't blocking any more, back to the sword - otherwise one shield
        // break left the bot axe-mained for the rest of the fight.
        if (InventoryController.isAxe(botPlayer.getInventory().getItemInMainHand())
                && context.maceStunSlamPhase == 0
                && !InventoryController.isBlockingWithShield(context.target)) {
            int sword = context.inventoryController.findBestSwordSlot(botPlayer);
            if (sword >= 0 && sword <= 8 && botPlayer.getInventory().getHeldItemSlot() != sword) {
                botPlayer.getInventory().setHeldItemSlot(sword);
                context.packetBroadcaster.broadcastEquipment();
                context.lastAttackGate = "swapping axe -> sword";
                resetCrit();
                return;
            }
        }

        if (isBotStuckInCobweb(botPlayer)) { context.lastAttackGate = "stuck in cobweb"; resetCrit(); return; }
        if (context.bridging) { context.lastAttackGate = "bridging"; resetCrit(); return; }

        if (context.knockbackReactionTicks > 0) {
            context.knockbackReactionTicks--;
            if (context.critPhase != BotAIContext.CritPhase.IDLE) {
                context.lastAttackGate = "knockback reaction delay";
                resetCrit();
                return;
            }
        }

        if (context.maceController.shouldAttemptStunSlam(botPlayer, distance)) {
            prepareOffensiveAction(botPlayer);
            context.maceController.beginStunSlam(botPlayer);
            context.lastAttackGate = "starting mace stun slam";
            return;
        }

        if (context.maceController.shouldUseMace(botPlayer, distance)) {
            prepareOffensiveAction(botPlayer);
            context.maceController.handleMaceWindup(botPlayer, distance);
            context.lastAttackGate = "starting mace windup";
            return;
        }

        ServerPlayer handle = context.bot.getHandle();

        double reachDist = reachDistance(handle, context.target);
        double reachVariance = (java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 0.5) * 0.1;
        double adjustedReach = reachDist + reachVariance;
        context.lastAttackReachDist = reachDist;

        boolean hasLOS = hasLineOfSight(botPlayer, context.target);
        context.lastAttackHasLOS = hasLOS;
        if (!hasLOS) {
            context.lastAttackGate = "no line of sight";
            resetCrit();
            return;
        }

        boolean charged = isChargedEnough(handle, botPlayer);
        context.lastAttackCharge = handle.getAttackStrengthScale(0.0f);
        context.lastAttackThreshold = context.attackChargeThreshold;
        context.lastAttackCharged = charged;
        if (adjustedReach > effectiveReach() && tryLungeSwap(botPlayer, distance)) {
            context.lastAttackGate = "LUNGE SWAP";
            resetCrit();
            return;
        }

        double minRange = minimumAttackRange();
        if (minRange > 0.0 && reachDist < minRange) {
            context.lastAttackGate = String.format(
                    "too close for weapon (need >= %.2f, have %.2f)", minRange, reachDist);
            return;
        }
        if (adjustedReach > effectiveReach()) {
            context.lastAttackGate = String.format(
                    "out of reach (need <= %.2f, have %.2f)", effectiveReach(), adjustedReach);
            if (context.critPhase != BotAIContext.CritPhase.IDLE) {
                resetCrit();
                context.criticalRetryTicks = Math.max(context.criticalRetryTicks, 12);
            }
            return;
        }
        if (!charged) {
            context.lastAttackGate = String.format(
                    "charging weapon (%.2f / %.2f)", context.lastAttackCharge, context.lastAttackThreshold);
            return;
        }

        boolean stunSlamAvailable = context.settings.isMaceSmash()
                && context.maceStunSlamCooldown <= 0
                && context.maceStunSlamPhase == 0
                && context.maceController.findBestMaceSlot(botPlayer) >= 0
                && context.inventoryController.findBestAxeSlot(botPlayer) >= 0;

        if (context.target != null && context.settings.isShieldBreak()
                && InventoryController.isBlockingWithShield(context.target)) {
            int axeSlot = context.inventoryController.findBestAxeSlot(botPlayer);
            if (axeSlot >= 0 && axeSlot <= 8) {
                double breakChance = Math.min(1.0,
                        Math.max(context.settings.getShieldBreakChance(), 0.65)
                                + Math.min(0.25, context.shieldHitTicks * 0.05));
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < breakChance) {
                    botPlayer.getInventory().setHeldItemSlot(axeSlot);
                    context.packetBroadcaster.broadcastEquipment();
                    boolean broke = performAttack(botPlayer, false);
                    if (broke) {
                        context.shieldHitTicks = 0;
                        resetCrit();

                        return;
                    }
                }
            }
            context.shieldHitTicks++;
        } else {
            context.shieldHitTicks = 0;
        }

        if (context.settings.isBreachSwap()
                && context.breachSwapCooldown <= 0
                && context.ticksSinceSwing >= MACE_FULL_CHARGE_TICKS
                && InventoryController.isSword(botPlayer.getInventory().getItemInMainHand())
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < BREACH_SWAP_CHANCE) {
            int maceSlot = context.maceController.findBestMaceSlot(botPlayer);
            int swordSlot = botPlayer.getInventory().getHeldItemSlot();
            if (maceSlot >= 0 && maceSlot <= 8) {
                botPlayer.getInventory().setHeldItemSlot(maceSlot);
                context.packetBroadcaster.broadcastEquipment();

                if (performAttack(botPlayer, false)) {
                    context.breachSwapCooldown = BREACH_SWAP_COOLDOWN;
                    context.lastAttackGate = "BREACH SWAP";
                    resetCrit();
                    return;
                }

                botPlayer.getInventory().setHeldItemSlot(swordSlot);
                context.packetBroadcaster.broadcastEquipment();
                context.breachSwapCooldown = 10;
            }
        }

        if (stunSlamAvailable && context.maceController.targetIsBlocking()
                && handle.getAttackStrengthScale(0.0f) < 0.85f) {
            context.lastAttackGate = "holding axe charge for stun slam";
            return;
        }

        if (handle.isUsingItem()) {
            boolean canPunishNow = context.target != null
                    && handle.getAttackStrengthScale(0.0f) >= 0.85f
                    && reachDistance(handle, context.target) <= effectiveReach();

            if (context.smashThreatTicks > 0 && !canPunishNow) {
                context.lastAttackGate = "holding item (smash threat)";
                return;
            }

            if (context.shieldPredictTicks > 0 || context.shieldHoldTicks > 0) {
                if (reachDist > effectiveReach() - 0.15) {
                    context.lastAttackGate = "holding item (shield predict)";
                    return;
                }
                prepareOffensiveAction(botPlayer);
            }
        }

        Location botLoc = botPlayer.getLocation();
        Location tLoc = context.target.getLocation();
        float angleToTarget = (float) Math.toDegrees(
                Math.atan2(-(tLoc.getX() - botLoc.getX()), tLoc.getZ() - botLoc.getZ()));
        float angleDiff = Math.abs(wrapDegrees(angleToTarget - handle.getYRot()));
        if (angleDiff > 90f) {
            context.lastAttackGate = String.format("not facing target (%.0f° off)", angleDiff);
            return;
        }

        if (context.techniqueController.shouldCritDeflect(context.target, distance)
                || context.techniqueController.shouldPunishCrit(botPlayer, distance)
                || context.techniqueController.shouldHitSelect(distance)) {
            ServerPlayer h = context.bot.getHandle();
            boolean readyEnough = h.getAttackStrengthScale(0.0f) >= 0.85f;
            if (readyEnough && performAttack(botPlayer, false)) {
                resetCrit();
                return;
            }
        }

        if (context.critPhase != BotAIContext.CritPhase.IDLE) {
            if (continueCriticalAttempt(botPlayer, handle)) return;
        } else if (shouldAttemptCritical(handle, distance, reachDist)
                && context.movementController.requestJump()) {
            prepareOffensiveAction(botPlayer);
            context.critPhase = BotAIContext.CritPhase.JUMPING;
            context.critPhaseTicks = 0;
            return;
        }

        double whiffChance = switch (context.settings.getDifficulty()) {
            case EASY -> 0.04;
            case NORMAL -> 0.025;
            case HARD -> 0.015;
            case EXPERT -> 0.005;
        };
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < whiffChance) {
            context.lastAttackGate = "whiffed (rng)";
            return;
        }

        context.lastAttackGate = performAttack(botPlayer, false) ? "ATTACKING" : "performAttack() rejected swing";
    }

    public boolean performAttack(Player botPlayer, boolean isCrit) {
        return performAttack(botPlayer, isCrit ? HitType.CRITICAL : HitType.NORMAL);
    }

    public boolean performAttack(Player botPlayer, HitType hitType) {
        if (!isValidTarget(context.target)) return false;
        if (context.fleeing) return false;
        if (isBotStuckInCobweb(botPlayer)) return false;

        try {
            ServerPlayer handle = context.bot.getHandle();
            if (handle == null) return false;

            ItemStack weapon = botPlayer.getInventory().getItemInMainHand();
            if (!isCombatWeapon(weapon)) return false;

            if (!hasLineOfSight(botPlayer, context.target)) return false;

            context.inventoryController.applyToolAttributes(botPlayer);

            if (context.lastAttackTick == context.tickCounter) {
                context.lastAttackGate = "performAttack: already swung this tick";
                return false;
            }
            float minimumCharge = InventoryController.isMace(weapon) ? 0.50f
                    : InventoryController.isAxe(weapon) ? 0.70f : 0.80f;
            if (!context.ignoreSwingCharge
                    && handle.getAttackStrengthScale(0.0f) < minimumCharge) {
                context.lastAttackGate = String.format(
                        "performAttack: under minimum charge (%.2f / %.2f)",
                        handle.getAttackStrengthScale(0.0f), minimumCharge);
                return false;
            }

            if (reachDistance(handle, context.target) > effectiveReach() + 0.35) {
                context.lastAttackGate = "performAttack: out of reach";
                return false;
            }

            if (context.settings.isHitCoordination()
                    && !PvPBotPlugin.getInstance().getAttackCoordinator()
                    .mayAttack(context.target.getUniqueId(), context.bot.getUUID())) {
                context.lastAttackGate = "performAttack: hit coordination denied (another bot has the booking)";
                context.attackDeniedTicks++;
                return false;
            }
            context.attackDeniedTicks = 0;
            prepareOffensiveAction(botPlayer);

            Location te = context.target.getEyeLocation();
            double dx = te.getX() - handle.getX();
            double dy = te.getY() - (handle.getY() + handle.getEyeHeight());
            double dz = te.getZ() - handle.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);

            float wantYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float wantPitch = (float) Math.toDegrees(-Math.atan2(dy, dist));

            context.aimDriftChangeTimer++;
            if (context.aimDriftChangeTimer > java.util.concurrent.ThreadLocalRandom.current().nextInt(8, 16)) {
                context.aimDriftAmount = (java.util.concurrent.ThreadLocalRandom.current().nextDouble() - 0.5) * 8.0;
                context.aimDriftChangeTimer = 0;
            }

            Player botPlayer2 = context.bot.getBukkitPlayer();
            if (botPlayer2 != null) {
                double healthPercent = botPlayer2.getHealth() / botPlayer2.getMaxHealth();
                int nearbyEnemyCount = Math.max(1, (int)Math.ceil((25.0 - healthPercent * 15.0) / 5.0));
                context.panicAimingLevel = Math.max(0.0, Math.min(1.0, (1.0 - healthPercent) * 0.8 + (nearbyEnemyCount * 0.1)));
            }

            float maxSnap = 35.0f;
            float yawDiff   = wrapDegrees(wantYaw   - handle.getYRot());
            float pitchDiff = wrapDegrees(wantPitch - handle.getXRot());

            float smoothFactor = 0.6f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.35f;
            smoothFactor *= (1.0f - (float)context.panicAimingLevel * 0.2f);
            float limitedYawDiff = Math.max(-maxSnap, Math.min(maxSnap, yawDiff)) * smoothFactor + (float)context.aimDriftAmount;
            float limitedPitchDiff = Math.max(-maxSnap, Math.min(maxSnap, pitchDiff)) * smoothFactor;

            context.requestLook(
                    handle.getYRot() + limitedYawDiff,
                    handle.getXRot() + limitedPitchDiff,
                    BotAIContext.LOOK_COMBAT + 1, false);
            context.movementController.flushLook(handle);
            context.packetBroadcaster.broadcastRotation(handle);

            if (dist > 1.25) {
                float facingYaw = (float) Math.toDegrees(Math.atan2(
                        -(te.getX() - handle.getX()), te.getZ() - handle.getZ()));
                if (Math.abs(wrapDegrees(facingYaw - handle.getYRot())) > 80.0f) return false;
            }

            if (handle.isUsingItem()) {
                handle.stopUsingItem();
                context.packetBroadcaster.broadcastEntityData();
            }

            int attackJitter = java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
            if (attackJitter > 0) {
                context.attackCooldown += attackJitter;
            }

            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);

            context.lastAttackTick = context.tickCounter;

            if (context.settings.getMissChance() > 0
                    && java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    < context.settings.getMissChance()) {
                handle.resetAttackStrengthTicker();
                rollAttackChargeThreshold();
                context.comboCount = 0;

                return true;
            }

            if (hitType == HitType.CRITICAL) handle.setSprinting(false);

            boolean axeVsShield = context.target != null && InventoryController.isAxe(weapon)
                    && context.target.isHandRaised()
                    && InventoryController.holdsShield(context.target);

            ServerPlayer nmsTarget =
                    ((org.bukkit.craftbukkit.entity.CraftPlayer) context.target).getHandle();

            int hurtBefore = nmsTarget.hurtTime;
            float healthBefore = nmsTarget.getHealth();
            float absorptionBefore = nmsTarget.getAbsorptionAmount();
            boolean targetWasBlocking = InventoryController.isBlockingWithShield(context.target);
            net.minecraft.world.item.ItemStack blockingStack = null;
            if (targetWasBlocking) {
                try {
                    blockingStack = nmsTarget.getItemBlockingWith();
                    if (blockingStack == null || blockingStack.isEmpty()) {
                        blockingStack = nmsTarget.getUseItem();
                    }
                } catch (Throwable ignored) {
                }
            }

            applyHitTypeEffects(handle, nmsTarget, hitType);

            handle.attack(nmsTarget);

            boolean landed = nmsTarget.hurtTime > hurtBefore
                    || nmsTarget.getHealth() < healthBefore;

            // Only a shield that actually stopped the axe gets disabled (as in
            // vanilla) - a hit around it, from the side or behind, doesn't.
            // (A blocked hit still starts the hurt timer, so go by damage.)
            boolean tookDamage = nmsTarget.getHealth() < healthBefore
                    || nmsTarget.getAbsorptionAmount() < absorptionBefore;
            if (targetWasBlocking && !tookDamage && InventoryController.isAxe(weapon)) {
                disableTargetShield(nmsTarget, blockingStack);
            }

            handle.resetAttackStrengthTicker();
            context.ticksSinceSwing = 0;
            context.attackCooldown = context.inventoryController.attackCooldownFor(weapon);

            if (landed) {
                context.lastLandedAttackTick = context.tickCounter;
                context.comboCount++;
                PvPBotPlugin.getInstance().getAttackCoordinator()
                        .recordHit(context.target.getUniqueId(), context.bot.getUUID());

                context.techniqueController.onHitLanded(dist);

                context.targetingController.notifyFactionAttack(context.target);
            } else {
                context.comboCount = 0;
            }

            if (axeVsShield) context.axeStunCooldown = 100;

            context.wtapPending = true;

            rollAttackChargeThreshold();
            return true;
        } catch (Exception e) {
            PvPBotPlugin.getInstance().getLogger().warning("[PvPBot] Attack failed: " + e.getMessage());
            context.target = null;
            return false;
        }
    }

    private void applyHitTypeEffects(ServerPlayer attacker, ServerPlayer target, HitType hitType) {
    }

    public void handleCobwebOffense(Player botPlayer, double distance) {
        if (!isValidTarget(context.target)) return;
        if (context.cobwebCooldown > 0 || context.attackCooldown > 0 || distance > 3.0 || distance < 1.0) return;
        Block targetLegs = context.target.getLocation().getBlock();
        Block targetHead = context.target.getLocation().add(0, 1, 0).getBlock();
        if (targetLegs.getType() == Material.COBWEB || targetHead.getType() == Material.COBWEB) return;

        Location placeLoc = context.target.getLocation();
        Block placeBlock = placeLoc.getBlock();
        if (placeBlock.getType() != Material.AIR) { context.cobwebCooldown = 10; return; }

        boolean supported =
                placeBlock.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()
                        || placeBlock.getRelative(org.bukkit.block.BlockFace.NORTH).getType().isSolid()
                        || placeBlock.getRelative(org.bukkit.block.BlockFace.SOUTH).getType().isSolid()
                        || placeBlock.getRelative(org.bukkit.block.BlockFace.EAST).getType().isSolid()
                        || placeBlock.getRelative(org.bukkit.block.BlockFace.WEST).getType().isSolid();
        if (!supported) { context.cobwebCooldown = 5; return; }

        int webSlot = context.inventoryController.findItemSlot(botPlayer, Material.COBWEB);
        if (webSlot < 0 || webSlot > 8) return;

        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < 0.35) {
            context.cobwebCooldown = 20 + java.util.concurrent.ThreadLocalRandom.current().nextInt(21);
            return;
        }

        // A real right click on the floor / wall next to their feet (a web
        // has no collision, so vanilla lets it go in around them).
        if (!VanillaWorld.place(context, botPlayer, webSlot, placeBlock, null, null)) {
            context.cobwebCooldown = 10;
            return;
        }

        botPlayer.getInventory().setHeldItemSlot(context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();

        context.cobwebCooldown = 140 + java.util.concurrent.ThreadLocalRandom.current().nextInt(81);
        context.breakingCobwebTimer = 60;
        context.blockToBreak = placeLoc;
    }

    private void steerOutOfWeb(Player botPlayer, Location loc) {
        net.minecraft.server.level.ServerPlayer h = context.bot.getHandle();
        if (h == null) return;

        org.bukkit.World w = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        double bestDx = 0;
        double bestDz = 0;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                int x = bx + dx;
                int z = bz + dz;

                Material atFeet = w.getBlockAt(x, by, z).getType();
                Material atHead = w.getBlockAt(x, by + 1, z).getType();
                if (atFeet == Material.COBWEB || atHead == Material.COBWEB) continue;
                if (atFeet.isSolid() || atHead.isSolid()) continue;
                if (!w.getBlockAt(x, by - 1, z).getType().isSolid()) continue;

                double d = dx * dx + dz * dz;
                if (d < bestDistSq) {
                    bestDistSq = d;
                    bestDx = dx;
                    bestDz = dz;
                }
            }
        }

        if (bestDistSq == Double.MAX_VALUE) {
            double yaw = Math.toRadians(h.getYRot());
            bestDx = Math.sin(yaw);
            bestDz = -Math.cos(yaw);
        }

        context.movementController.worldDirToInputs(h, bestDx, bestDz);

        if (h.onGround()) context.movementController.requestJump();
    }

    public boolean handleCobwebDefense(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();
        if (feet.getType() != Material.COBWEB && head.getType() != Material.COBWEB) {
            if (context.cobwebBreaking != null) {
                clearWebStage();
                context.cobwebBreaking = null;
            }
            context.cobwebDefenseBreakTicks = 0;
            return false;
        }

        if (!context.settings.isWebEscape()) return false;

        steerOutOfWeb(botPlayer, loc);

        int waterSlot = context.inventoryController.findItemSlot(botPlayer, Material.WATER_BUCKET);
        if (waterSlot >= 0 && waterSlot <= 8 && context.waterScoopTimer <= 0) {
            // Bucket the web, as players do: the click lands on the web's
            // outline, the water goes in on top of it and washes the web
            // away as it flows down.
            Block web = feet.getType() == Material.COBWEB ? feet : head;
            if (VanillaWorld.useBucketOn(context, botPlayer, waterSlot, web)) {
                Block source = web.getRelative(0, 1, 0);
                context.placedWaterLoc = source.getType() == Material.WATER
                        ? source.getLocation().clone() : web.getLocation().clone();
                context.waterScoopTimer = 10;
                return true;
            }
        }

        // Cut our way out with the best thing we carry: shears are fastest,
        // then a sword, and with neither, bare hands (slow, but never stuck
        // waiting for a sword we don't have). The web cracks visibly as it
        // goes, like a player breaking it.
        Block breakLoc = feet.getType() == Material.COBWEB ? feet : head;
        if (!breakLoc.equals(context.cobwebBreaking)) {
            clearWebStage();
            context.cobwebBreaking = breakLoc;
            context.cobwebDefenseBreakTicks = 0;
            int shears = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.SHEARS);
            int sword = shears >= 0 ? -1 : context.inventoryController.findBestSwordSlot(botPlayer);
            int slot = shears >= 0 ? shears : sword;
            if (slot >= 0 && slot <= 8) {
                botPlayer.getInventory().setHeldItemSlot(slot);
                context.packetBroadcaster.broadcastEquipment();
            }
            // Vanilla's own mining time for what we're holding (shears or a
            // sword are quick; bare hands take ages - as they do for you).
            int ticks = VanillaWorld.breakTicks(context, breakLoc);
            context.cobwebBreakTarget = ticks > 0 ? ticks : 400;
        }

        lookAtBlock(botPlayer, breakLoc);
        if (context.cobwebDefenseBreakTicks % 4 == 0) {
            context.bot.getHandle().swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(context.bot.getHandle(), 0);
        }

        context.cobwebDefenseBreakTicks++;
        int stage = Math.min(9, context.cobwebDefenseBreakTicks * 10 / Math.max(1, context.cobwebBreakTarget));
        if (stage != context.cobwebStage) {
            context.cobwebStage = stage;
            sendWebStage(breakLoc, stage);
        }
        if (context.cobwebDefenseBreakTicks >= context.cobwebBreakTarget) {
            clearWebStage();
            // Broken for real: string with a sword, the web with shears.
            VanillaWorld.breakBlock(context, breakLoc);
            context.cobwebDefenseBreakTicks = 0;
            context.cobwebBreaking = null;
        }
        return true;
    }

    private void sendWebStage(Block b, int stage) {
        try {
            net.minecraft.server.level.ServerPlayer h = context.bot.getHandle();
            if (h == null || b == null) return;
            context.packetBroadcaster.sendPacketToAll(
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            h.getId(), new net.minecraft.core.BlockPos(b.getX(), b.getY(), b.getZ()), stage));
        } catch (Throwable ignored) {
        }
    }

    private void clearWebStage() {
        if (context.cobwebBreaking != null && context.cobwebStage >= 0) {
            sendWebStage(context.cobwebBreaking, -1);
        }
        context.cobwebStage = -1;
    }

    public void handleWaterScoop(Player botPlayer) {
        if (context.waterScoopTimer <= 0) return;
        context.waterScoopTimer--;
        if (context.waterScoopTimer > 0) return;

        if (context.placedWaterLoc != null) {
            Block waterBlock = context.placedWaterLoc.getBlock();
            if (waterBlock.getType() == Material.WATER) {
                // Scoop it back up with the empty bucket, a real right click
                // on the source.
                int bucket = context.inventoryController.findItemSlot(botPlayer, Material.BUCKET);
                if (bucket >= 0 && bucket <= 8) {
                    VanillaWorld.useBucketOn(context, botPlayer, bucket, waterBlock);
                }
            }
            context.placedWaterLoc = null;
        }
    }

    private boolean isValidTarget(Player p) {
        return TargetFilter.isEngageable(p, context.bot.getBukkitPlayer());
    }

    private boolean isCombatWeapon(ItemStack item) {
        return InventoryController.isSword(item)
                || InventoryController.isAxe(item)
                || InventoryController.isMace(item)
                || (item != null && item.getType() == Material.TRIDENT);
    }

    private boolean isBotStuckInCobweb(Player botPlayer) {
        if (botPlayer == null) return false;
        Location loc = botPlayer.getLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null) return false;

        double[] offsets = {-0.29, 0.0, 0.29};
        int baseY = (int) Math.floor(loc.getY());
        for (double ox : offsets) {
            for (double oz : offsets) {
                int x = (int) Math.floor(loc.getX() + ox);
                int z = (int) Math.floor(loc.getZ() + oz);
                if (world.getBlockAt(x, baseY, z).getType() == Material.COBWEB
                        || world.getBlockAt(x, baseY + 1, z).getType() == Material.COBWEB) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasLineOfSight(Player from, Player to) {
        if (from == null || to == null) return false;

        Location fromLoc = from.getEyeLocation();
        Location toLoc = to.getEyeLocation();

        if (fromLoc.getWorld() == null || !fromLoc.getWorld().equals(toLoc.getWorld())) return false;
        if (!from.hasLineOfSight(to)) return false;

        return !webBetween(fromLoc, toLoc, from, to);
    }

    private boolean webBetween(Location fromLoc, Location toLoc, Player from, Player to) {
        org.bukkit.World w = fromLoc.getWorld();
        if (w == null) return false;

        Location fromBase = from.getLocation();
        Location toBase = to.getLocation();
        if (!w.equals(fromBase.getWorld()) || !w.equals(toBase.getWorld())) return true;

        double[] heights = {0.25, 0.9, 1.45};
        for (double height : heights) {
            if (webOnSegment(w,
                    fromBase.getX(), fromBase.getY() + height, fromBase.getZ(),
                    toBase.getX(), toBase.getY() + height, toBase.getZ(),
                    from, to)) {
                return true;
            }
        }
        return false;
    }

    private boolean webOnSegment(org.bukkit.World world,
                                 double startX, double startY, double startZ,
                                 double endX, double endY, double endZ,
                                 Player from, Player to) {
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.1) return false;

        int steps = Math.max(1, (int) Math.ceil(distance / 0.12));
        for (int i = 1; i < steps; i++) {
            double fraction = (double) i / steps;
            int x = (int) Math.floor(startX + dx * fraction);
            int y = (int) Math.floor(startY + dy * fraction);
            int z = (int) Math.floor(startZ + dz * fraction);

            if (isPlayerColumn(x, y, z, from) || isPlayerColumn(x, y, z, to)) continue;
            if (world.getBlockAt(x, y, z).getType() == Material.COBWEB) return true;
        }
        return false;
    }

    private boolean isPlayerColumn(int x, int y, int z, Player player) {
        Location loc = player.getLocation();
        if (x != loc.getBlockX() || z != loc.getBlockZ()) return false;

        int feetY = (int) Math.floor(loc.getY());
        return y >= feetY - 1 && y <= feetY + 2;
    }

    private float wrapDegrees(float angle) {
        angle %= 360.0f;
        return (angle >= 180.0f) ? angle - 360.0f : (angle < -180.0f ? angle + 360.0f : angle);
    }

    private void lookAtBlock(Player botPlayer, Block target) {
        Location botLoc = botPlayer.getEyeLocation();
        Location targetLoc = target.getLocation().clone();
        targetLoc.add(0.5, 0.5, 0.5);

        double dx = targetLoc.getX() - botLoc.getX();
        double dy = targetLoc.getY() - botLoc.getY();
        double dz = targetLoc.getZ() - botLoc.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist < 0.1) return;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));

        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(context.bot.getHandle());
        context.packetBroadcaster.broadcastRotation(context.bot.getHandle());
    }
}
