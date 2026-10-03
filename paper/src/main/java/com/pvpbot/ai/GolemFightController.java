package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GolemFightController {
    private enum Phase { IDLE, PILLAR, FIGHT }

    private static final int PILLAR_HEIGHT = 3;

    private final BotAIContext context;
    private Phase phase = Phase.IDLE;
    private int blocksPlaced = 0;
    private double pillarStepBaseY;
    private int placeCooldown = 0;
    private int attackCooldown = 0;
    private UUID golemId;

    public GolemFightController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public String status() {
        return switch (phase) {
            case IDLE -> "idle";
            case PILLAR -> "pillaring (" + blocksPlaced + "/" + PILLAR_HEIGHT + ")";
            case FIGHT -> "fighting golem";
        };
    }

    public String start(Player botPlayer) {
        if (isActive()) return "Already golem-fighting.";
        if (botPlayer == null) return "Bot has no live handle.";
        if (context.inventoryController.findBlockSlot(botPlayer) < 0) {
            return "No placeable blocks to pillar with - give it some (e.g. cobblestone) first.";
        }

        World w = botPlayer.getWorld();
        Location botLoc = botPlayer.getLocation();
        Vector dir = botLoc.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-6) dir = new Vector(1, 0, 0);
        dir.normalize();
        Location spot = botLoc.clone().add(dir.multiply(2.5));

        IronGolem golem;
        try {
            golem = w.spawn(spot, IronGolem.class, g -> {
                g.setPlayerCreated(false);
                g.setRemoveWhenFarAway(false);
            });
        } catch (Throwable t) {
            return "Could not spawn the golem here.";
        }

        golemId = golem.getUniqueId();
        phase = Phase.PILLAR;
        blocksPlaced = 0;
        pillarStepBaseY = botLoc.getY();
        placeCooldown = 0;
        attackCooldown = 0;
        return null;
    }

    public void stop() {
        phase = Phase.IDLE;
        blocksPlaced = 0;
        golemId = null;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
    }

    public boolean handleGolemFight(Player botPlayer) {
        if (phase == Phase.IDLE) return false;
        if (botPlayer == null) {
            stop();
            return false;
        }
        if (context.target != null || context.fleeing) return false;

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) {
            stop();
            return false;
        }

        Entity e = golemId != null ? Bukkit.getEntity(golemId) : null;
        if (!(e instanceof IronGolem golem) || golem.isDead()) {
            stop();
            return false;
        }

        if (placeCooldown > 0) placeCooldown--;
        if (attackCooldown > 0) attackCooldown--;

        if (phase == Phase.PILLAR) {
            tickPillar(botPlayer, handle);
        } else {
            tickFight(botPlayer, handle, golem);
        }
        return true;
    }

    private void tickPillar(Player botPlayer, ServerPlayer handle) {
        World w = botPlayer.getWorld();
        Location loc = botPlayer.getLocation();
        int feetY = context.movementController.feetBlockY(loc, handle);
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        context.forwardInput = 0f;
        context.strafeInput = 0f;
        handle.setShiftKeyDown(false);

        context.movementController.lookAtPlacement(handle,
                w.getBlockAt(bx, feetY - 2, bz), w.getBlockAt(bx, feetY - 1, bz));

        if (placeCooldown > 0) return;

        Block below = w.getBlockAt(bx, feetY - 1, bz);
        if (!below.getType().isAir()) {
            if (handle.onGround()) {
                pillarStepBaseY = loc.getY();
                context.movementController.requestJump();
            }
            return;
        }

        if (loc.getY() - pillarStepBaseY < 0.95) return;
        if (handle.getDeltaMovement().y > 0.08) return;

        int slot = context.inventoryController.findBlockSlot(botPlayer);
        if (slot < 0) {
            stop();
            return;
        }

        Block support = w.getBlockAt(bx, feetY - 2, bz);
        if (context.movementController.placeFromSlot(botPlayer, slot, below, support)) {
            placeCooldown = 6;
            blocksPlaced++;
            if (blocksPlaced >= PILLAR_HEIGHT) {
                phase = Phase.FIGHT;
            }
        }
    }

    private void tickFight(Player botPlayer, ServerPlayer handle, IronGolem golem) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        context.movementController.lookAt(golem.getEyeLocation(), 0.0);

        double dist = botPlayer.getLocation().distance(golem.getLocation());
        if (dist > context.settings.getReach() + 0.5) return;
        if (attackCooldown > 0) return;

        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);
        if (handle.isUsingItem()) handle.stopUsingItem();

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        net.minecraft.world.entity.Entity nmsGolem = ((CraftEntity) golem).getHandle();
        handle.attack(nmsGolem);

        attackCooldown = 10 + ThreadLocalRandom.current().nextInt(6);
    }
}
