package com.pvpbot.perf;

import com.pvpbot.BotManager;
import com.pvpbot.PvPBot;
import com.pvpbot.ai.BotAIContext;
import net.minecraft.server.level.ServerPlayer;

public final class BotScheduler {
    public static boolean enabled = true;

    public static double radius = 48.0;

    public static int stride = 4;

    public static int minBots = 48;

    public static int botCount = 0;

    private static int fullTicks = 0;
    private static int lightTicks = 0;

    private BotScheduler() {
    }

    public static void resetCounters() {
        fullTicks = 0;
        lightTicks = 0;
    }

    public static int getFullTicks() {
        return fullTicks;
    }

    public static int getLightTicks() {
        return lightTicks;
    }

    public static boolean wantsFullTick(PvPBot bot, BotManager manager, int tickCounter) {
        if (!enabled || botCount < minBots) {
            fullTicks++;
            return true;
        }

        BotAIContext ctx;
        ServerPlayer handle;
        try {
            ctx = bot.getAI().getContext();
            handle = bot.getHandle();
        } catch (Throwable t) {
            fullTicks++;
            return true;
        }
        if (ctx == null || handle == null) {
            fullTicks++;
            return true;
        }

        if (ctx.forceFullTicks > 0
                || ctx.target != null
                || ctx.fleeing
                || ctx.selfHurtTicks > 0
                || ctx.holeEscapeTicks > 0
                || ctx.regroupTicks > 0
                || ctx.formationSlot != null
                || ctx.splashPotionTimer > 0
                || ctx.drinkingPotionTimer > 0
                || ctx.eating
                || ctx.bridging
                || !ctx.currentPath.isEmpty()
                || (ctx.restockController != null && ctx.restockController.isBusy())

                || (ctx.patrolController != null && ctx.patrolController.isActive())

                || (ctx.deliveryController != null && ctx.deliveryController.isActive())
                || (ctx.miningController != null && ctx.miningController.isActive())
                || (ctx.buildController != null && ctx.buildController.isBusy())
                || (ctx.farmController != null && ctx.farmController.isActive())

                || (ctx.cartController != null && ctx.cartController.isActive())
                || (ctx.tunnelController != null && ctx.tunnelController.isActive())
                || (ctx.golemFightController != null && ctx.golemFightController.isActive())
                || (ctx.lavaStuntController != null && ctx.lavaStuntController.isActive())

                || ctx.guardReturning) {
            fullTicks++;
            return true;
        }

        if (manager != null && manager.getLeaderFor(bot.getUUID()) != null) {
            fullTicks++;
            return true;
        }

        double r2 = radius * radius;
        double d2 = PlayerSnapshot.nearestRealPlayerDistSq(
                handle.getBukkitEntity().getWorld(), handle.getX(), handle.getY(), handle.getZ());
        if (d2 <= r2) {
            fullTicks++;
            return true;
        }

        int phase = Math.floorMod(handle.getId(), Math.max(1, stride));
        if (Math.floorMod(tickCounter, Math.max(1, stride)) == phase) {
            fullTicks++;
            return true;
        }

        lightTicks++;
        return false;
    }
}
