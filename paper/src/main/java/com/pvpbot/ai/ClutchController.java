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

        int water = tryWaterClutch(botPlayer, handle, w, loc, floorY);
        if (water > 0) return true;
        // The floor isn't in bucket reach yet: keep falling toward it while
        // there's still another tick before we hit it.
        if (water == 0 && drop > Math.abs(vel.y) + 0.5) return true;
        return tryWindClutch(botPlayer, handle);
    }

    // 1 = water's down, 0 = not in reach of the floor yet, -1 = can't.
    private int tryWaterClutch(Player botPlayer, ServerPlayer handle,
                               World w, Location loc, int floorY) {
        if (w.getEnvironment() == World.Environment.NETHER) return -1;

        int slot = context.inventoryController.findItemSlot(botPlayer, Material.WATER_BUCKET);
        if (slot < 0 || slot > 8) return -1;

        Block target = w.getBlockAt(loc.getBlockX(), floorY + 1, loc.getBlockZ());
        if (!target.getType().isAir()) return -1;

        Block support = w.getBlockAt(loc.getBlockX(), floorY, loc.getBlockZ());
        if (support.getType().isAir()) return -1;

        // The MLG: look down at the floor and right click the bucket - only
        // once the floor is within reach, like for a player.
        if (!VanillaWorld.inReach(handle, support, org.bukkit.block.BlockFace.UP)) return 0;
        if (!VanillaWorld.pourInto(context, botPlayer, slot, target)) {
            clutchCooldown = CLUTCH_COOLDOWN;
            return -1;
        }

        placedWater = target.getLocation();
        waterLingerTicks = 0;
        clutchCooldown = CLUTCH_COOLDOWN;
        return 1;
    }

    private boolean tryWindClutch(Player botPlayer, ServerPlayer handle) {
        int slot = context.inventoryController.findWindChargeSlot(botPlayer);
        if (slot < 0 || slot > 8) return false;

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
        if (slot < 0 || slot > 8) return;

        if (!launchWindCharge(botPlayer, slot, handle)) return;

        context.forwardInput = 1.0f;
        context.wallBumpTicks = 0;
        context.avoidTicks = 0;
        hopCooldown = HOP_COOLDOWN;
    }

    private boolean launchWindCharge(Player botPlayer, int slot, ServerPlayer handle) {
        ItemStack charge = botPlayer.getInventory().getItem(slot);
        if (charge == null || charge.getType() != Material.WIND_CHARGE) return false;

        // Straight down, as a real right click (vanilla speed, spread and
        // the half-second wind charge cooldown).
        return VanillaUse.useFromHotbar(context, botPlayer, slot, handle.getYRot(), 90.0f).used();
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

    // Scoop the clutch water back up with the empty bucket (a real right
    // click on the source, in reach). Out of reach, or told not to, it just
    // stays where it is - as a player's would.
    private void removeWater(Player botPlayer, boolean refillBucket) {
        Location at = placedWater;
        placedWater = null;
        waterLingerTicks = 0;
        if (!refillBucket || at == null || at.getWorld() == null || botPlayer == null) return;

        Block b = at.getWorld().getBlockAt(at);
        if (b.getType() != Material.WATER) return;

        int bucketSlot = context.inventoryController.findItemSlot(botPlayer, Material.BUCKET);
        if (bucketSlot < 0 || bucketSlot > 8) return;
        VanillaWorld.useBucketOn(context, botPlayer, bucketSlot, b);
    }
}
