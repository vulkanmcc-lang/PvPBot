package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;

// Item use the way a real client does it: a right click goes through the
// server's own use-item handling (ServerPlayerGameMode.useItem), so every
// throwable, rocket, bow and potion behaves exactly like a player's - vanilla
// launch speed and spread, the thrower's momentum carried into the throw,
// item cooldowns (pearls 1s, wind charges 0.5s), stack use, sounds, stats,
// events. Bots used to spawn these by hand, which got speeds wrong (splash
// potions flew 3x too fast), skipped cooldowns entirely (pearl and wind
// charge spam) and faked rocket boosts with velocity pushes.
public final class VanillaUse implements Listener {

    public static final Result FAILED = new Result(false, null);

    public record Result(boolean used, Projectile projectile) {}

    // The projectile a use spawned, caught from the launch event fired while
    // useItem runs (it's synchronous, on the main thread).
    private static ServerPlayer capturing;
    private static Projectile captured;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (capturing == null || captured != null) return;
        if (event.getEntity().getShooter() instanceof org.bukkit.craftbukkit.entity.CraftPlayer shooter
                && shooter.getHandle() == capturing) {
            captured = event.getEntity();
        }
    }

    private VanillaUse() {}

    private static final VanillaUse LISTENER = new VanillaUse();

    public static Listener listener() {
        return LISTENER;
    }

    // Right click with whatever is in `hand`.
    public static Result use(ServerPlayer h, InteractionHand hand) {
        if (h == null) return FAILED;
        ItemStack stack = h.getItemInHand(hand);
        if (stack.isEmpty()) return FAILED;
        if (h.getCooldowns().isOnCooldown(stack)) return FAILED;

        ServerPlayer outer = capturing;
        Projectile outerCaptured = captured;
        capturing = h;
        captured = null;
        InteractionResult result;
        Projectile spawned;
        try {
            result = h.gameMode.useItem(h, h.level(), stack, hand);
        } catch (Throwable t) {
            return FAILED;
        } finally {
            spawned = captured;
            capturing = outer;
            captured = outerCaptured;
        }
        return result != null && result.consumesAction() ? new Result(true, spawned) : FAILED;
    }

    // Is the item in this hotbar slot cooling down (pearl/wind charge/etc.)?
    public static boolean coolingDown(Player bp, int slot) {
        if (bp == null || slot < 0 || slot > 8) return false;
        try {
            ServerPlayer h = ((org.bukkit.craftbukkit.entity.CraftPlayer) bp).getHandle();
            ItemStack stack = h.getInventory().getItem(slot);
            return !stack.isEmpty() && h.getCooldowns().isOnCooldown(stack);
        } catch (Throwable t) {
            return false;
        }
    }

    // Pick the hotbar slot, face (yaw, pitch) exactly, right click. Items
    // outside the hotbar can't be used - a player can't either without
    // opening their inventory first.
    public static Result useFromHotbar(BotAIContext ctx, Player bp, int slot, float yaw, float pitch) {
        if (ctx == null || bp == null || slot < 0 || slot > 8) return FAILED;
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return FAILED;
        if (coolingDown(bp, slot)) return FAILED;

        if (bp.getInventory().getHeldItemSlot() != slot) {
            bp.getInventory().setHeldItemSlot(slot);
            ctx.packetBroadcaster.broadcastEquipment();
        }
        face(ctx, h, yaw, pitch);

        Result r = use(h, InteractionHand.MAIN_HAND);
        if (r.used()) {
            h.swing(InteractionHand.MAIN_HAND, true);
            ctx.packetBroadcaster.broadcastAnimation(h, 0);
            ctx.packetBroadcaster.broadcastEquipment();
        }
        return r;
    }

    // Same, keeping the bot's current facing.
    public static Result useFromHotbar(BotAIContext ctx, Player bp, int slot) {
        ServerPlayer h = ctx == null ? null : ctx.bot.getHandle();
        if (h == null) return FAILED;
        return useFromHotbar(ctx, bp, slot, h.getYRot(), h.getXRot());
    }

    // Exact rotation right now (the throw reads it this tick), and a snap
    // request so this tick's look flush doesn't ease it back off target.
    public static void face(BotAIContext ctx, ServerPlayer h, float yaw, float pitch) {
        float p = Math.max(-90f, Math.min(90f, pitch));
        h.setYRot(yaw);
        h.setXRot(p);
        h.setYHeadRot(yaw);
        h.yRotO = yaw;
        h.xRotO = p;
        ctx.requestLook(yaw, p, BotAIContext.LOOK_CRITICAL + 5, true);
        ctx.packetBroadcaster.broadcastRotation(h);
    }

    // Start an elytra glide the way pressing jump in mid-air does: only off
    // the ground, out of water, with a working elytra on.
    public static boolean startGlide(ServerPlayer h) {
        if (h == null) return false;
        if (h.isFallFlying()) return true;
        if (h.onGround()) return false;
        try {
            return h.tryToStartFallFlying();
        } catch (Throwable t) {
            return false;
        }
    }
}
