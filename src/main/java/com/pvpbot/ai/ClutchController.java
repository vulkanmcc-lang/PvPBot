package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ClutchController {
    private static final double SAFE_FALL = 3.5;

    private static final int MAX_SCAN_DOWN = 48;

    private static final int CLUTCH_COOLDOWN = 30;

    private static final int HOP_COOLDOWN = 60;

    private final BotAIContext context;

    private Location placedWater = null;
    private int waterLingerTicks = 0;

    private int clutchCooldown = 0;
    private int hopCooldown = 0;

    public ClutchController(BotAIContext context) {
        this.context = context;
    }

    public void tick(Player botPlayer, ServerPlayer handle) {
        if (!context.settings.isClutching()) {
            abort(botPlayer);
            return;
        }
        if (clutchCooldown > 0) clutchCooldown--;
        if (hopCooldown > 0) hopCooldown--;
        if (botPlayer == null || handle == null) return;

        reclaimWater(botPlayer, handle);

        if (handleFall(botPlayer, handle)) return;
        handleWindHop(botPlayer, handle);
    }

    public void abort(Player botPlayer) {
        if (placedWater != null) {
            removeWater(botPlayer, false);
        }
    }

    private boolean handleFall(Player botPlayer, ServerPlayer handle) {
        if (handle.onGround() || handle.isInWater() || handle.onClimbable()) return false;

        Vec3 vel = handle.getDeltaMovement();
        if (vel.y > -0.4) return false;

        Location loc = botPlayer.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;

        double drop = distanceToFloor(w, loc);
        if (drop < 0) return false;

        double projected = handle.fallDistance + drop;
        if (projected < SAFE_FALL) return false;

        double lead = Math.abs(vel.y) * 2.0 + 1.0;
        if (drop > lead) return false;
        if (clutchCooldown > 0) return false;

        int floorY = (int) Math.floor(loc.getY() - drop);

        if (tryWaterClutch(botPlayer, handle, w, loc, floorY)) return true;
        return tryWindClutch(botPlayer, handle);
    }

    private boolean tryWaterClutch(Player botPlayer, ServerPlayer handle,
                                   World w, Location loc, int floorY) {
        if (w.getEnvironment() == World.Environment.NETHER) return false;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.WATER_BUCKET);
        if (slot < 0) return false;

        Block target = w.getBlockAt(loc.getBlockX(), floorY + 1, loc.getBlockZ());
        if (!target.getType().isAir()) return false;

        Block support = w.getBlockAt(loc.getBlockX(), floorY, loc.getBlockZ());
        if (support.getType().isAir()) return false;

        if (slot <= 8) botPlayer.getInventory().setHeldItemSlot(slot);
        aimStraightDown(handle);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        org.bukkit.block.BlockState replaced = target.getState();
        target.setType(Material.WATER, true);

        ItemStack bucket = botPlayer.getInventory().getItem(slot);
        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        target, replaced, support,
                        bucket != null ? bucket.clone() : new ItemStack(Material.WATER_BUCKET),
                        botPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            replaced.update(true, false);
            clutchCooldown = CLUTCH_COOLDOWN;

            return tryWindClutch(botPlayer, handle);
        }

        botPlayer.getInventory().setItem(slot, new ItemStack(Material.BUCKET));
        placedWater = target.getLocation();
        waterLingerTicks = 0;
        clutchCooldown = CLUTCH_COOLDOWN;
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private boolean tryWindClutch(Player botPlayer, ServerPlayer handle) {
        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0) return false;
        if (slot <= 8) botPlayer.getInventory().setHeldItemSlot(slot);

        aimStraightDown(handle);
        if (!launchWindCharge(botPlayer, slot, handle)) return false;

        clutchCooldown = CLUTCH_COOLDOWN;
        return true;
    }

    private void handleWindHop(Player botPlayer, ServerPlayer handle) {
        if (hopCooldown > 0) return;
        if (!handle.onGround()) return;
        if (context.bridging || context.inWater) return;

        if (context.wallBumpTicks < 25) return;

        if (context.target == null && !context.isGuarding() && context.currentPath.isEmpty()) return;

        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0) return;
        if (slot <= 8) botPlayer.getInventory().setHeldItemSlot(slot);

        aimStraightDown(handle);
        if (!launchWindCharge(botPlayer, slot, handle)) return;

        context.forwardInput = 1.0f;
        context.wallBumpTicks = 0;
        context.avoidTicks = 0;
        hopCooldown = HOP_COOLDOWN;
    }

    private boolean launchWindCharge(Player botPlayer, int slot, ServerPlayer handle) {
        ItemStack charge = botPlayer.getInventory().getItem(slot);
        if (charge == null || charge.getType() != Material.WIND_CHARGE) return false;

        try {
            botPlayer.launchProjectile(org.bukkit.entity.WindCharge.class);
        } catch (Throwable t) {
            return false;
        }

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        charge.setAmount(charge.getAmount() - 1);
        botPlayer.getInventory().setItem(slot, charge.getAmount() > 0 ? charge : null);
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private void aimStraightDown(ServerPlayer handle) {
        context.requestLook(handle.getYRot(), 90.0f, BotAIContext.LOOK_CRITICAL, true);
        context.movementController.flushLook(handle);
        context.packetBroadcaster.broadcastRotation(handle);
    }

    private double distanceToFloor(World w, Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int startY = (int) Math.floor(loc.getY());
        int min = Math.max(w.getMinHeight(), startY - MAX_SCAN_DOWN);

        for (int y = startY - 1; y >= min; y--) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.WATER) return -1;
            if (m.isSolid()) return loc.getY() - (y + 1);
        }
        return -1;
    }

    private void reclaimWater(Player botPlayer, ServerPlayer handle) {
        if (placedWater == null) return;

        waterLingerTicks++;

        boolean settled = handle.onGround() || handle.isInWater();
        if (waterLingerTicks < 6) return;
        if (!settled && waterLingerTicks < 100) return;

        removeWater(botPlayer, true);
    }

    private void removeWater(Player botPlayer, boolean refillBucket) {
        Location at = placedWater;
        placedWater = null;
        waterLingerTicks = 0;
        if (at == null || at.getWorld() == null) return;

        Block b = at.getWorld().getBlockAt(at);
        if (b.getType() != Material.WATER) return;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(b, botPlayer);
        event.setDropItems(false);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        b.setType(Material.AIR, true);

        if (refillBucket && botPlayer != null) {
            int bucketSlot = context.inventoryController.findItemSlot(botPlayer, Material.BUCKET);
            if (bucketSlot >= 0) {
                botPlayer.getInventory().setItem(bucketSlot, new ItemStack(Material.WATER_BUCKET));
            }
        }
    }
}
