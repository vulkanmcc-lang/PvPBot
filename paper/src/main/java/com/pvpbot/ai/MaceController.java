package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

public class MaceController {
    private final BotAIContext context;

    private static final float MIN_SMASH_FALL = 1.6f;

    private static final float SMASH_CHARGE = 0.92f;

    private static final float SALVAGE_CHARGE = 0.55f;

    private static final int ATTEMPT_TIMEOUT = 80;

    private static final int ELYTRA_ATTEMPT_TIMEOUT = 240;

    private static final int CHAIN_TIMEOUT = 70;

    private static final int MAX_CHAIN_HITS = 4;

    private static final int SMASH_COOLDOWN = 100;
    private static final int ABORT_COOLDOWN = 40;
    private static final int STUN_SLAM_COOLDOWN = 80;

    private static final int STUN_SLAM_PHASE_DURATION = 70;

    private static final int STUN_SLAM_FOLLOW_TICKS = 1;

    private static final int STUN_SLAM_MAX_WAIT = 24;

    private static final int STUN_SLAM_MAX_SWINGS = 8;

    private static final int SLAM_STALL_TICKS = 12;

    private static final double STUN_SLAM_CHANCE = 0.85;

    private static final double SETUP_RANGE = 6.0;

    private static final double ELYTRA_MIN_RANGE = 10.0;
    private static final double ELYTRA_MAX_RANGE = 40.0;

    private static final double OPPORTUNITY_CHANCE = 0.12;

    private enum Launch { NONE, HEIGHT, WIND_CHARGE, ELYTRA, PEARL }

    private enum ElytraPhase { NONE, CLIMB, ALIGN, DIVE, FALL }

    private static final double ELYTRA_CLIMB_HEIGHT = 20.0;
    private static final double ELYTRA_RELEASE_RANGE = 6.0;
    private static final int ELYTRA_ALIGN_TICKS = 8;

    private static final int ELYTRA_ROCKET_INTERVAL = 18;

    private static final double PEARL_GRAPPLE_MIN_HEIGHT = 4.0;
    private static final double PEARL_GRAPPLE_MAX_HEIGHT = 30.0;
    private static final double PEARL_GRAPPLE_RANGE = 18.0;
    private static final double PEARL_GRAPPLE_LEAD = 1.5;
    private static final int PEARL_GRAPPLE_COOLDOWN = 120;

    private static final int LAUNCH_GRACE_TICKS = 6;

    private static final int MAX_ROCKETS_PER_RUN = 5;

    private static final double ELYTRA_CLIMB_POWER = 1.8;
    private static final double ELYTRA_DIVE_POWER = 1.6;

    private static final float ELYTRA_CLIMB_PITCH = -70.0f;

    private static final int ROCKET_BURN_TICKS = 14;

    private static final double ROCKET_THRUST = 0.16;

    private static final double ROCKET_MAX_SPEED = 1.7;

    private Launch activeLaunch = Launch.NONE;
    private ElytraPhase elytraPhase = ElytraPhase.NONE;
    private int elytraPhaseTicks = 0;
    private int rocketCooldown = 0;
    private int chainsUsed = 0;
    private int rocketsUsed = 0;
    private String stunGate = "-";
    private String p1Landed = "-";
    private boolean slamLaunched = false;
    private int slamDrivenTick = Integer.MIN_VALUE;
    private int slamWaitTicks = 0;
    private int slamSwingRetries = 0;
    private int rocketBurnTicks = 0;
    private Vector rocketThrust = null;

    public MaceController(BotAIContext context) {
        this.context = context;
    }

    public int findBestMaceSlot(Player p) {
        return context.inventoryController.findBestMaceSlot(p);
    }

    public boolean hasMace(Player botPlayer) {
        return findBestMaceSlot(botPlayer) >= 0;
    }

    public boolean isAttempting() {
        return context.maceWindupTicks > 0;
    }

    public void tick() {
        if (context.maceStunSlamPhase > 0) {
            Player stuck = context.bot.getBukkitPlayer();
            String abort = null;
            if (context.fleeing) abort = "slam aborted: fleeing";
            else if (context.eating || context.drinkingPotionTimer > 0) {
                abort = "slam aborted: consuming";
            } else if (!TargetFilter.isEngageable(context.target, stuck)) {
                abort = "slam aborted: target lost";
            } else if (context.tickCounter - slamDrivenTick > SLAM_STALL_TICKS) {
                abort = "slam aborted: stalled";
            }
            if (abort != null) {
                context.maceStunSlamPhase = 0;
                context.maceStunSlamPhaseTicks = 0;
                context.maceStunSlamCooldown = 20;
                context.maceHoldAfterAttack = 0;
                slamLaunched = false;
                slamWaitTicks = 0;
                slamSwingRetries = 0;
                stunGate = abort;
                if (stuck != null) restoreWeapon(stuck);
                return;
            }
        }

        if (isAttempting() && context.fleeing) {
            Player fleeing = context.bot.getBukkitPlayer();
            if (fleeing != null) {
                stunGate = "aborted: fleeing";
                cancel(fleeing);
            }
            return;
        }

        if (isAttempting()) return;
        if (activeLaunch == Launch.NONE && elytraPhase == ElytraPhase.NONE) return;

        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null) return;

        stunGate = "attempt expired";
        cancel(botPlayer);
    }

    public boolean shouldHoldMace(Player botPlayer) {
        if (!isAttempting()) return false;
        if (findBestMaceSlot(botPlayer) < 0) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;
        if (!handle.onGround()) return true;

        return launchTimeout() - context.maceWindupTicks <= LAUNCH_GRACE_TICKS;
    }

    private int launchTimeout() {
        return activeLaunch == Launch.ELYTRA ? ELYTRA_ATTEMPT_TIMEOUT : ATTEMPT_TIMEOUT;
    }

    private void restoreWeapon(Player botPlayer) {
        int slot = context.inventoryController.findBestWeaponSlot(botPlayer);
        if (slot < 0 || slot > 8) return;
        if (botPlayer.getInventory().getHeldItemSlot() == slot) return;
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
    }

    public String debugLine() {
        return "mace=" + (isAttempting() ? activeLaunch + "/" + context.maceWindupTicks : "idle")
                + " cd=" + context.maceWindupCooldown
                + " slam=" + context.maceStunSlamPhase
                + " chain=" + chainsUsed
                + " stun=" + stunGate
                + (elytraPhase == ElytraPhase.NONE ? "" : " ely=" + elytraPhase);
    }


    public boolean shouldUseMace(Player botPlayer, double distance) {
        if (isAttempting()) return true;

        if (!context.settings.isMaceSmash()) return false;
        if (context.maceWindupCooldown > 0 || context.maceHoldAfterAttack > 0) return false;
        if (context.maceStunSlamPhase > 0) return false;
        if (context.fleeing || context.eating || context.drinkingPotionTimer > 0) return false;
        if (!TargetFilter.isEngageable(context.target, botPlayer)) return false;
        if (findBestMaceSlot(botPlayer) < 0) return false;

        if (targetIsBlocking()) {
            return false;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        if (!handle.onGround()
                && handle.getDeltaMovement().y < 0.0
                && handle.fallDistance > MIN_SMASH_FALL
                && distance <= SETUP_RANGE) {
            return true;
        }

        if (!handle.onGround()) {
            return handle.fallDistance > MIN_SMASH_FALL && distance <= SETUP_RANGE;
        }
        if (chooseLaunch(botPlayer, handle, distance) == Launch.NONE) return false;

        return ThreadLocalRandom.current().nextDouble() < OPPORTUNITY_CHANCE;
    }

    private Launch chooseLaunch(Player botPlayer, ServerPlayer handle, double distance) {
        Player target = context.target;
        if (target == null) return Launch.NONE;

        double drop = botPlayer.getLocation().getY() - target.getLocation().getY();

        if (distance <= SETUP_RANGE && drop >= 2.0) return Launch.HEIGHT;

        if (distance <= SETUP_RANGE
                && context.inventoryController.findWindChargeSlot(botPlayer) >= 0) {
            return Launch.WIND_CHARGE;
        }

        double above = target.getLocation().getY() - botPlayer.getLocation().getY();
        if (above >= PEARL_GRAPPLE_MIN_HEIGHT
                && above <= PEARL_GRAPPLE_MAX_HEIGHT
                && distance <= PEARL_GRAPPLE_RANGE
                && context.pearlCooldown <= 0
                && context.inventoryController.findItemSlot(
                        botPlayer, Material.ENDER_PEARL) >= 0) {
            return Launch.PEARL;
        }

        if (context.settings.isElytraMacing()
                && distance >= ELYTRA_MIN_RANGE && distance <= ELYTRA_MAX_RANGE
                && context.inventoryController.findElytraSlot(botPlayer) != -1
                && context.inventoryController.findItemSlot(botPlayer, Material.FIREWORK_ROCKET) >= 0) {
            return Launch.ELYTRA;
        }

        return Launch.NONE;
    }


    public void handleMaceWindup(Player botPlayer, double distance) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;

        if (!TargetFilter.isEngageable(context.target, botPlayer)) {
            cancel(botPlayer);
            return;
        }

        if (!isAttempting()) {
            beginAttempt(botPlayer, handle, distance);
            return;
        }


        equipMace(botPlayer);
        context.suppressSprint = true;
        handle.setSprinting(false);
        aimAtTarget(handle);
        steerToTarget(handle);

        if (activeLaunch == Launch.ELYTRA) driveElytra(botPlayer, handle, distance);
    }

    private void beginAttempt(Player botPlayer, ServerPlayer handle, double distance) {
        chainsUsed = 0;
        Launch launch = chooseLaunch(botPlayer, handle, distance);

        if (launch == Launch.NONE) {
            if (handle.onGround() || handle.fallDistance <= MIN_SMASH_FALL) {
                cancel(botPlayer);
                return;
            }
        }

        switch (launch) {
            case WIND_CHARGE -> {
                if (!launchWithWindCharge(botPlayer, handle)) {
                    cancel(botPlayer);
                    return;
                }
            }
            case HEIGHT -> {
                steerToTarget(handle);
                context.movementController.requestJump();
            }
            case ELYTRA -> {
                if (!launchWithElytra(botPlayer, handle)) {
                    cancel(botPlayer);
                    return;
                }
            }
            case PEARL -> {
                if (!launchWithPearl(botPlayer, handle)) {
                    cancel(botPlayer);
                    return;
                }
            }
            default -> { }
        }

        activeLaunch = launch;
        if (!equipMace(botPlayer)) {
            cancel(botPlayer);
            return;
        }
        context.maceWindupTicks = launch == Launch.ELYTRA
                ? ELYTRA_ATTEMPT_TIMEOUT : ATTEMPT_TIMEOUT;
    }

    private boolean launchWithWindCharge(Player botPlayer, ServerPlayer handle) {
        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0 || slot > 8) return false;

        context.movementController.requestJump();

        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        context.requestLook(handle.getYRot(), 90.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        try {
            botPlayer.launchProjectile(org.bukkit.entity.WindCharge.class);
        } catch (Throwable t) {
            return false;
        }

        consumeOne(botPlayer, slot);
        return true;
    }

    private boolean launchWithPearl(Player botPlayer, ServerPlayer handle) {
        Player target = context.target;
        if (target == null) return false;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.ENDER_PEARL);
        if (slot < 0 || slot > 8) return false;

        int previousSlot = botPlayer.getInventory().getHeldItemSlot();
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        Location eye = botPlayer.getEyeLocation();
        Location aim = target.getLocation().clone().add(0.0, PEARL_GRAPPLE_LEAD, 0.0);

        double dx = aim.getX() - eye.getX();
        double dy = aim.getY() - eye.getY();
        double dz = aim.getZ() - eye.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.1, flat)));
        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        try {
            botPlayer.launchProjectile(org.bukkit.entity.EnderPearl.class);
        } catch (Throwable t) {
            botPlayer.getInventory().setHeldItemSlot(previousSlot);
            context.packetBroadcaster.broadcastEquipment();
            return false;
        }

        consumeOne(botPlayer, slot);
        context.pearlCooldown = PEARL_GRAPPLE_COOLDOWN;
        return true;
    }

    private boolean launchWithElytra(Player botPlayer, ServerPlayer handle) {
        if (!hasRocket(botPlayer)) return false;
        if (!context.inventoryController.equipElytraForMace(botPlayer)) return false;

        context.movementController.requestJump();
        startGliding(botPlayer);

        elytraPhase = ElytraPhase.CLIMB;
        elytraPhaseTicks = 0;
        rocketCooldown = 0;
        rocketsUsed = 0;
        rocketBurnTicks = 0;
        rocketThrust = null;
        return true;
    }

    private void driveElytra(Player botPlayer, ServerPlayer handle, double distance) {
        Player target = context.target;
        if (target == null) return;

        if (rocketCooldown > 0) rocketCooldown--;
        tickRocketBurn(botPlayer);
        elytraPhaseTicks++;

        double above = handle.getY() - target.getLocation().getY();

        switch (elytraPhase) {
            case CLIMB -> {
                ensureGliding(botPlayer);
                if (above < ELYTRA_CLIMB_HEIGHT) {
                    boostRocket(botPlayer, climbAim(handle), ELYTRA_CLIMB_POWER);
                    return;
                }
                stopGliding(botPlayer, true);
                elytraPhase = ElytraPhase.ALIGN;
                elytraPhaseTicks = 0;
            }
            case ALIGN -> {
                aimAtTarget(handle);
                if (elytraPhaseTicks < ELYTRA_ALIGN_TICKS) return;

                if (!context.inventoryController.equipElytraForMace(botPlayer)) {
                    cancel(botPlayer);
                    return;
                }
                startGliding(botPlayer);
                elytraPhase = ElytraPhase.DIVE;
                elytraPhaseTicks = 0;
            }
            case DIVE -> {
                ensureGliding(botPlayer);
                aimAtTarget(handle);

                if (distance <= ELYTRA_RELEASE_RANGE || above <= 4.0) {
                    stopGliding(botPlayer, true);
                    equipMace(botPlayer);
                    elytraPhase = ElytraPhase.FALL;
                    elytraPhaseTicks = 0;
                    return;
                }

                Vector toTarget = target.getLocation().toVector()
                        .subtract(botPlayer.getLocation().toVector());
                if (toTarget.lengthSquared() > 0.01) {
                    boostRocket(botPlayer, toTarget.normalize(), ELYTRA_DIVE_POWER);
                }
            }
            case FALL -> {
                equipMace(botPlayer);
            }
            default -> { }
        }
    }

    private boolean hasRocket(Player botPlayer) {
        return context.inventoryController.findItemSlot(botPlayer, Material.FIREWORK_ROCKET) >= 0;
    }

    private void boostRocket(Player botPlayer, Vector direction, double power) {
        if (rocketCooldown > 0) return;
        if (rocketsUsed >= MAX_ROCKETS_PER_RUN) return;
        if (direction.lengthSquared() < 1.0E-6) return;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.FIREWORK_ROCKET);
        if (slot < 0) return;

        rocketThrust = direction.clone().normalize().multiply(ROCKET_THRUST * power);
        rocketBurnTicks = ROCKET_BURN_TICKS;

        try {
            botPlayer.getWorld().playSound(botPlayer.getLocation(),
                    org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }

        consumeOne(botPlayer, slot);
        rocketsUsed++;
        rocketCooldown = ELYTRA_ROCKET_INTERVAL;
    }

    private void tickRocketBurn(Player botPlayer) {
        if (rocketBurnTicks <= 0 || rocketThrust == null) {
            rocketThrust = null;
            return;
        }
        rocketBurnTicks--;

        Vector v = botPlayer.getVelocity().add(rocketThrust);
        if (v.lengthSquared() > ROCKET_MAX_SPEED * ROCKET_MAX_SPEED) {
            v.normalize().multiply(ROCKET_MAX_SPEED);
        }
        botPlayer.setVelocity(v);

        if (rocketBurnTicks <= 0) rocketThrust = null;
    }

    private Vector climbAim(ServerPlayer handle) {
        Player target = context.target;
        double dx = 0.0;
        double dz = 0.0;
        if (target != null) {
            dx = target.getLocation().getX() - handle.getX();
            dz = target.getLocation().getZ() - handle.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > 0.001) {
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                context.requestLook(yaw, ELYTRA_CLIMB_PITCH, BotAIContext.LOOK_CRITICAL, false);
                dx /= flat;
                dz /= flat;
            }
        }
        return new Vector(dx * 0.25, 1.0, dz * 0.25);
    }

    private void startGliding(Player botPlayer) {
        try {
            botPlayer.setGliding(true);
        } catch (Throwable ignored) {
        }
    }

    private void ensureGliding(Player botPlayer) {
        if (!context.elytraEquippedForMace) return;
        try {
            if (!botPlayer.isGliding()) botPlayer.setGliding(true);
        } catch (Throwable ignored) {
        }
    }

    private void stopGliding(Player botPlayer, boolean unequip) {
        try {
            botPlayer.setGliding(false);
        } catch (Throwable ignored) {
        }
        if (unequip) context.inventoryController.unequipElytraForMace(botPlayer);
    }


    public void handleMaceAttack(Player botPlayer, double distance) {
        if (!isAttempting()) return;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;

        Player target = context.target;
        if (!TargetFilter.isEngageable(target, botPlayer)) {
            cancel(botPlayer);
            return;
        }

        boolean elytraMidRun = activeLaunch == Launch.ELYTRA
                && elytraPhase != ElytraPhase.NONE
                && elytraPhase != ElytraPhase.FALL;
        if (!elytraMidRun && handle.onGround()
                && context.maceWindupTicks < launchTimeout() - LAUNCH_GRACE_TICKS) {
            cancel(botPlayer);
            return;
        }

        if (handle.isFallFlying()) return;
        if (handle.fallDistance <= MIN_SMASH_FALL) return;
        if (handle.getDeltaMovement().y >= 0.0) return;

        if (targetIsBlocking()
                && context.inventoryController.findBestAxeSlot(botPlayer) >= 0) {
            if (context.combatController.reachDistance(handle, target)
                    <= context.settings.getReach() + 0.35) {
                int axeSlot = context.inventoryController.findBestAxeSlot(botPlayer);
                if (axeSlot >= 0 && axeSlot <= 8) {
                    botPlayer.getInventory().setHeldItemSlot(axeSlot);
                    context.packetBroadcaster.broadcastEquipment();
                    if (handle.isUsingItem()) handle.stopUsingItem();

                    context.ignoreSwingCharge = true;
                    boolean broke;
                    try {
                        broke = context.combatController.performAttack(botPlayer, false);
                    } finally {
                        context.ignoreSwingCharge = false;
                    }
                    stunGate = broke ? "mid-dive shield break" : "mid-dive break refused";
                    equipMace(botPlayer);
                }
            }
            return;
        }

        if (context.combatController.reachDistance(handle, target)
                > context.settings.getReach()) {
            return;
        }

        float charge = handle.getAttackStrengthScale(0.0f);
        boolean lastChance = aboutToLand(botPlayer);
        if (charge < SMASH_CHARGE && !(lastChance && charge >= SALVAGE_CHARGE)) return;

        handle.setSprinting(false);
        context.suppressSprint = true;

        if (!context.combatController.performAttack(botPlayer, false)) return;

        finish(botPlayer);
    }

    private boolean aboutToLand(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        org.bukkit.World world = loc.getWorld();
        if (world == null) return true;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int y = (int) Math.floor(loc.getY());
        for (int dy = 1; dy <= 2; dy++) {
            if (world.getBlockAt(x, y - dy, z).getType().isSolid()) return true;
        }
        return false;
    }


    private void aimAtTarget(ServerPlayer handle) {
        Player target = context.target;
        if (target == null) return;

        Location eye = target.getEyeLocation();
        double dx = eye.getX() - handle.getX();
        double dy = eye.getY() - (handle.getY() + handle.getEyeHeight());
        double dz = eye.getZ() - handle.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.1, flat)));

        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, false);
    }

    private void steerToTarget(ServerPlayer handle) {
        Player target = context.target;
        if (target == null) return;

        Location tl = target.getLocation();

        Vector v = target.getVelocity();
        double airTicks = Math.min(12.0, Math.max(0.0, -handle.getDeltaMovement().y) * 8.0);
        double tx = tl.getX() + v.getX() * airTicks;
        double tz = tl.getZ() + v.getZ() * airTicks;

        double dx = tx - handle.getX();
        double dz = tz - handle.getZ();

        if (dx * dx + dz * dz < 0.04) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return;
        }
        context.movementController.worldDirToInputs(handle, dx, dz, 1.0f);
    }


    public boolean targetIsBlocking() {
        Player target = context.target;
        return InventoryController.isBlockingWithShield(target);
    }

    public boolean shouldAttemptStunSlam(Player botPlayer, double distance) {
        if (!context.settings.isMaceSmash()) { stunGate = "macesmash off"; return false; }
        if (context.maceStunSlamCooldown > 0) {
            stunGate = "cooldown " + context.maceStunSlamCooldown;
            return false;
        }
        if (isAttempting()) { stunGate = "mace run active"; return false; }

        ServerPlayer launchCheck = context.bot.getHandle();
        if (launchCheck == null) return false;

        boolean alreadyFalling = !launchCheck.onGround()
                && launchCheck.getDeltaMovement().y < 0.0;
        if (!alreadyFalling
                && context.inventoryController.findWindChargeSlot(botPlayer) < 0) {
            stunGate = "no wind charge and not falling";
            return false;
        }

        if (context.target == null) { stunGate = "no target"; return false; }
        if (!InventoryController.isBlockingWithShield(context.target)) {
            stunGate = InventoryController.holdsShield(context.target)
                    ? "has shield but not blocking" : "target has no shield";
            return false;
        }

        double swingRange = context.settings.getReach() + 0.35;

        int spearForReach = context.inventoryController.findSpearSlot(botPlayer);
        if (spearForReach >= 0) {
            double spearReach = CombatController.attackMaxRangeOf(
                    botPlayer.getInventory().getItem(spearForReach));
            if (spearReach > 0.0) swingRange = Math.max(swingRange, spearReach + 0.35);
        }

        if (distance > swingRange) {
            stunGate = String.format("too far (%.1f > %.1f)", distance, swingRange);
            return false;
        }

        if (context.inventoryController.findBestAxeSlot(botPlayer) < 0) {
            stunGate = "no axe";
            return false;
        }
        if (findBestMaceSlot(botPlayer) < 0) { stunGate = "no mace"; return false; }


        if (ThreadLocalRandom.current().nextDouble() >= STUN_SLAM_CHANCE) {
            stunGate = "waiting on roll";
            return false;
        }
        stunGate = "GO";
        return true;
    }

    public void beginStunSlam(Player botPlayer) {
        slamLaunched = false;
        slamWaitTicks = 0;
        slamSwingRetries = 0;
        slamDrivenTick = context.tickCounter;
        context.maceStunSlamPhase = 1;
        context.maceStunSlamPhaseTicks = STUN_SLAM_PHASE_DURATION;
        handleStunSlam(botPlayer);
    }

    public void abortSequences(Player botPlayer) {
        if (context.maceStunSlamPhase > 0) {
            context.maceStunSlamPhase = 0;
            context.maceStunSlamPhaseTicks = 0;
            context.maceStunSlamCooldown = 20;
            context.maceHoldAfterAttack = 0;
            slamLaunched = false;
            slamWaitTicks = 0;
            slamSwingRetries = 0;
            stunGate = "target lost";
        }
        if (isAttempting()) cancel(botPlayer);
    }

    public void handleStunSlam(Player botPlayer) {
        slamDrivenTick = context.tickCounter;
        if (!TargetFilter.isEngageable(context.target, botPlayer)) {
            abortSequences(botPlayer);
            restoreWeapon(botPlayer);
            return;
        }

        switch (context.maceStunSlamPhase) {
            case 0 -> startStunSlam(botPlayer);
            case 1 -> breakShieldPhase(botPlayer);
            case 2 -> {
                equipMace(botPlayer);
                if (context.maceStunSlamPhaseTicks > 0) {
                    stunGate = "P2 mace in " + context.maceStunSlamPhaseTicks;
                    return;
                }

                ServerPlayer slamHandle = context.bot.getHandle();
                if (slamHandle != null && context.target != null
                        && slamWaitTicks < STUN_SLAM_MAX_WAIT) {
                    double slamGap = context.combatController.reachDistance(
                            slamHandle, context.target);
                    if (slamGap > context.settings.getReach() + 0.35) {
                        double flat = botPlayer.getLocation()
                                .distance(context.target.getLocation());
                        if (context.combatController.tryLungeSwap(botPlayer, flat)) {
                            slamWaitTicks++;
                            stunGate = String.format("P2 lunging in @%.1f", slamGap);
                            context.maceStunSlamPhaseTicks = STUN_SLAM_FOLLOW_TICKS;
                            return;
                        }
                    }
                }

                ServerPlayer smashHandle = context.bot.getHandle();
                if (smashHandle == null) return;

                boolean canSmash = !smashHandle.onGround()
                        && smashHandle.fallDistance > MIN_SMASH_FALL;

                boolean lastChance = aboutToLand(botPlayer);

                if (!lastChance && slamWaitTicks < STUN_SLAM_MAX_WAIT) {
                    ServerPlayer invulTarget = null;
                    try {
                        invulTarget = ((org.bukkit.craftbukkit.entity.CraftPlayer)
                                context.target).getHandle();
                    } catch (Throwable ignored) {
                    }
                    if (invulTarget != null && invulTarget.invulnerableTime > 10) {
                        slamWaitTicks++;
                        stunGate = "P2 waiting out i-frames ("
                                + invulTarget.invulnerableTime + ")";
                        context.maceStunSlamPhaseTicks = 1;
                        return;
                    }
                }

                if (!canSmash && !smashHandle.onGround()
                        && slamWaitTicks < STUN_SLAM_MAX_WAIT) {
                    slamWaitTicks++;
                    stunGate = String.format("P2 building fall (%.1f)",
                            smashHandle.fallDistance);
                    context.maceStunSlamPhaseTicks = 1;
                    return;
                }

                if (!canSmash && smashHandle.onGround()) {
                    endStunSlam(botPlayer, "P2 grounded, no smash - sword takes over");
                    restoreWeapon(botPlayer);
                    return;
                }

                context.ignoreSwingCharge = true;
                boolean landed;
                try {
                    landed = context.combatController.performAttack(botPlayer, false);
                } finally {
                    context.ignoreSwingCharge = false;
                }
                if (!landed && ++slamSwingRetries < STUN_SLAM_MAX_SWINGS) {
                    stunGate = "P2 retry " + slamSwingRetries + ": " + context.lastAttackGate;
                    context.maceStunSlamPhaseTicks = 1;
                    return;
                }

                endStunSlam(botPlayer,
                        (landed ? "P2 MACE HIT" : "P2 gave up: " + context.lastAttackGate)
                                + " [p1=" + p1Landed + " brk=" + context.lastShieldBreak + "]");
                return;
            }
            default -> { }
        }

        if (context.maceStunSlamPhase == 1 && context.maceStunSlamPhaseTicks <= 0) {
            p1Landed = "TIMEOUT";
            endStunSlam(botPlayer, "P1 TIMED OUT (shield never broken)");
            context.maceStunSlamCooldown = 40;
            restoreWeapon(botPlayer);
        }
    }

    private void endStunSlam(Player botPlayer, String why) {
        context.maceStunSlamPhase = 0;
        context.maceStunSlamPhaseTicks = 0;
        context.maceStunSlamCooldown = STUN_SLAM_COOLDOWN;
        context.maceHoldAfterAttack = 0;
        slamLaunched = false;
        slamWaitTicks = 0;
        slamSwingRetries = 0;
        stunGate = why;
    }

    private void startStunSlam(Player botPlayer) {
        int axeSlot = context.inventoryController.findBestAxeSlot(botPlayer);
        if (axeSlot >= 0 && axeSlot <= 8
                && botPlayer.getInventory().getHeldItemSlot() != axeSlot) {
            botPlayer.getInventory().setHeldItemSlot(axeSlot);
            context.packetBroadcaster.broadcastEquipment();
        }
    }

    private void breakShieldPhase(Player botPlayer) {
        ServerPlayer h = context.bot.getHandle();
        if (h == null) return;

        if (!slamLaunched) {
            if (!h.onGround()) {
                slamLaunched = true;
            } else if (launchWithWindCharge(botPlayer, h)) {
                slamLaunched = true;
                stunGate = "P1 launched";
                context.maceStunSlamPhaseTicks = STUN_SLAM_PHASE_DURATION;

                int axeNow = context.inventoryController.findBestAxeSlot(botPlayer);
                if (axeNow >= 0 && axeNow <= 8) {
                    botPlayer.getInventory().setHeldItemSlot(axeNow);
                    context.packetBroadcaster.broadcastEquipment();
                }
                return;
            } else {
                stunGate = "P1 launch failed";
                context.maceStunSlamPhase = 0;
                context.maceStunSlamCooldown = 20;
                return;
            }
        }

        if (h.getDeltaMovement().y > 0.0) {
            stunGate = "P1 rising, waiting to fall";
            return;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;

        int axeSlot = context.inventoryController.findBestAxeSlot(botPlayer);
        if (axeSlot < 0 || axeSlot > 8) return;
        if (botPlayer.getInventory().getHeldItemSlot() != axeSlot) {
            botPlayer.getInventory().setHeldItemSlot(axeSlot);
            context.packetBroadcaster.broadcastEquipment();
        }


        if (handle.isUsingItem()) {
            handle.stopUsingItem();
            context.eating = false;
            context.packetBroadcaster.broadcastEntityData();
        }

        double gap = context.combatController.reachDistance(handle, context.target);
        if (gap > context.settings.getReach() + 0.35) {
            int spearSlot = context.inventoryController.findSpearSlot(botPlayer);
            double spearReach = spearSlot < 0 ? 0.0 : CombatController.attackMaxRangeOf(
                    botPlayer.getInventory().getItem(spearSlot));
            if (spearReach <= gap) {
                stunGate = String.format("P1 out of reach %.1f (no spear carry)", gap);
                return;
            }
            context.swingReachOverride = spearReach;
            stunGate = String.format("P1 reach swap @%.1f", gap);
        }

        boolean swung;
        context.ignoreSwingCharge = true;
        try {
            swung = context.combatController.performAttack(botPlayer, false);
        } finally {
            context.ignoreSwingCharge = false;
            context.swingReachOverride = 0.0;
        }

        if (!swung) {
            stunGate = "P1 swing refused: " + context.lastAttackGate;
            if (context.maceStunSlamPhaseTicks <= 1) {
                context.maceStunSlamPhase = 0;
                context.maceStunSlamPhaseTicks = 0;
                context.maceStunSlamCooldown = 20;
                stunGate = "P1 gave up (never in reach)";
            }
            return;
        }

        stunGate = "P1 HIT -> P2";
        p1Landed = "hit";
        context.maceStunSlamPhase = 2;
        context.maceStunSlamPhaseTicks = STUN_SLAM_FOLLOW_TICKS;
        equipMace(botPlayer);
    }


    private boolean equipMace(Player botPlayer) {
        int slot = findBestMaceSlot(botPlayer);
        if (slot < 0 || slot > 8) return false;
        if (botPlayer.getInventory().getHeldItemSlot() != slot) {
            botPlayer.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
        }
        return true;
    }

    private void consumeOne(Player botPlayer, int slot) {
        ItemStack item = botPlayer.getInventory().getItem(slot);
        if (item == null || item.getAmount() <= 0) return;
        item.setAmount(item.getAmount() - 1);
        botPlayer.getInventory().setItem(slot, item.getAmount() > 0 ? item : null);
        context.packetBroadcaster.broadcastEquipment();
    }

    private void clearWindCharge() {
        if (context.maceWindCharge != null) {
            try {
                context.maceWindCharge.remove();
            } catch (Throwable ignored) {
            }
        }
        context.maceWindCharge = null;
        context.maceWindChargeTicks = 0;
    }

    private void restoreElytra(Player botPlayer) {
        if (!context.elytraEquippedForMace) return;
        try {
            botPlayer.setGliding(false);
        } catch (Throwable ignored) {
        }
        context.inventoryController.unequipElytraForMace(botPlayer);
    }

    private void finish(Player botPlayer) {
        clearWindCharge();

        if (tryStartChain(botPlayer)) return;

        restoreElytra(botPlayer);
        activeLaunch = Launch.NONE;
        elytraPhase = ElytraPhase.NONE;
        elytraPhaseTicks = 0;
        rocketCooldown = 0;
        rocketsUsed = 0;
        rocketBurnTicks = 0;
        rocketThrust = null;
        chainsUsed = 0;
        restoreWeapon(botPlayer);
        context.maceWindupTicks = 0;
        context.maceWindupDelay = 0;
        context.maceWindupCooldown = SMASH_COOLDOWN;
        context.maceHoldAfterAttack = 10;
    }

    private boolean tryStartChain(Player botPlayer) {
        int windBurst = windBurstLevel(botPlayer);
        if (windBurst <= 0) return false;
        if (!TargetFilter.isEngageable(context.target, botPlayer)) return false;

        int allowed = Math.min(MAX_CHAIN_HITS, 1 + windBurst);
        if (chainsUsed >= allowed) return false;

        chainsUsed++;
        context.maceWindupTicks = CHAIN_TIMEOUT;
        context.maceWindupDelay = 0;
        context.maceHoldAfterAttack = 0;

        double gap = botPlayer.getLocation().distance(context.target.getLocation());
        boolean canFly = context.settings.isElytraMacing()
                && hasRocket(botPlayer)
                && context.inventoryController.findElytraSlot(botPlayer) != -1;

        if (gap > ELYTRA_RELEASE_RANGE && canFly) {
            restoreElytra(botPlayer);
            activeLaunch = Launch.ELYTRA;
            elytraPhase = ElytraPhase.ALIGN;
        } else {
            activeLaunch = Launch.HEIGHT;
            elytraPhase = ElytraPhase.NONE;
        }
        elytraPhaseTicks = 0;
        rocketCooldown = 0;
        rocketsUsed = 0;
        rocketBurnTicks = 0;
        rocketThrust = null;

        equipMace(botPlayer);
        return true;
    }

    private int windBurstLevel(Player botPlayer) {
        int slot = findBestMaceSlot(botPlayer);
        if (slot < 0) return 0;
        return context.inventoryController.readWindburstLevel(
                botPlayer.getInventory().getItem(slot));
    }

    private void cancel(Player botPlayer) {
        clearWindCharge();
        restoreElytra(botPlayer);
        activeLaunch = Launch.NONE;
        elytraPhase = ElytraPhase.NONE;
        elytraPhaseTicks = 0;
        rocketCooldown = 0;
        rocketsUsed = 0;
        rocketBurnTicks = 0;
        rocketThrust = null;
        chainsUsed = 0;
        restoreWeapon(botPlayer);
        context.maceWindupTicks = 0;
        context.maceWindupDelay = 0;
        context.maceWindupCooldown = ABORT_COOLDOWN;
    }
}
