package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public class LavaStuntController {
    private enum Phase { IDLE, COMMITTED }

    private static final int SCAN_INTERVAL = 20;
    private static final double TRIGGER_CHANCE = 1.0 / 300.0;
    private static final double SUCCESS_CHANCE = 0.7;
    private static final int STUNT_COOLDOWN = 20 * 25;
    private static final int GIVE_UP_TICKS = 100;

    private final BotAIContext context;
    private Phase phase = Phase.IDLE;
    private boolean willSucceed;
    private boolean placed;
    private int scanCooldown = 0;
    private int stuntCooldown = 0;
    private int giveUpTicks = 0;
    private Location lavaAbove;

    public LavaStuntController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public boolean handleLavaStunt(Player botPlayer) {
        if (!context.settings.isLavaClutchStunts()) {
            if (phase != Phase.IDLE) finish();
            return false;
        }
        if (stuntCooldown > 0) stuntCooldown--;
        if (scanCooldown > 0) scanCooldown--;

        if (phase == Phase.IDLE) {
            if (context.target != null || context.fleeing) return false;
            if (!context.hasNoActiveOrders()) return false;
            if (stuntCooldown > 0 || scanCooldown > 0) return false;
            scanCooldown = SCAN_INTERVAL;

            Location spot = findNearbyLava(botPlayer);
            if (spot == null) return false;
            if (ThreadLocalRandom.current().nextDouble() >= TRIGGER_CHANCE) return false;

            lavaAbove = spot;
            willSucceed = ThreadLocalRandom.current().nextDouble() < SUCCESS_CHANCE
                    && context.inventoryController.findBlockSlot(botPlayer) >= 0;
            placed = false;
            giveUpTicks = GIVE_UP_TICKS;
            phase = Phase.COMMITTED;
        }

        return tickCommitted(botPlayer);
    }

    private Location findNearbyLava(Player botPlayer) {
        World w = botPlayer.getWorld();
        Location loc = botPlayer.getLocation();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -2; dy <= 0; dy++) {
                    Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (b.getType() != Material.LAVA) continue;
                    Block above = w.getBlockAt(bx + dx, by + dy + 1, bz + dz);
                    if (above.getType().isAir()) {
                        return above.getLocation().add(0.5, 0, 0.5);
                    }
                }
            }
        }
        return null;
    }

    private boolean tickCommitted(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null || lavaAbove == null || --giveUpTicks <= 0) {
            finish();
            return false;
        }
        if (handle.isInLava() || (placed && handle.onGround())) {
            finish();
            return false;
        }

        double dx = lavaAbove.getX() - handle.getX();
        double dz = lavaAbove.getZ() - handle.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);

        context.movementController.lookAt(lavaAbove, 0.0);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;

        if (handle.onGround() && context.jumpCooldown <= 0 && flat < 2.5) {
            context.movementController.requestJump();
        }

        boolean overLava = flat < 1.0 && !handle.onGround();
        if (overLava && willSucceed && !placed) {
            World w = botPlayer.getWorld();
            Location loc = botPlayer.getLocation();
            Block below = w.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
            if (below.getType() == Material.LAVA) {
                int slot = context.inventoryController.findBlockSlot(botPlayer);
                if (slot >= 0 && placeOverLava(botPlayer, handle, below, slot)) {
                    placed = true;
                }
            }
        }

        return true;
    }

    private boolean placeOverLava(Player botPlayer, ServerPlayer handle, Block target, int slot) {
        ItemStack item = botPlayer.getInventory().getItem(slot);
        if (item == null || item.getAmount() <= 0) return false;
        if (!InventoryController.isPlaceableBlock(item)) return false;

        Material mat = item.getType();
        org.bukkit.block.BlockState replaced = target.getState();
        target.setType(mat, true);

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        target, replaced, target, item.clone(),
                        botPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        item.setAmount(item.getAmount() - 1);
        botPlayer.getInventory().setItem(slot, item.getAmount() > 0 ? item : null);
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private void finish() {
        phase = Phase.IDLE;
        lavaAbove = null;
        stuntCooldown = STUNT_COOLDOWN + ThreadLocalRandom.current().nextInt(200);
    }
}
