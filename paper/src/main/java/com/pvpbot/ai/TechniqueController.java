package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class TechniqueController {
    private static final int PCRIT_WINDOW = 12;

    private static final int HITSELECT_WINDOW = 6;

    private static final double BACKSTAB_ANGLE = 70.0;

    private final BotAIContext context;

    private int lastHitTick = -1000;

    private int lastJumpResetTick = -1000;

    private int orbitDir = 1;
    private int orbitTicks = 0;

    private int outspaceTicks = 0;

    public TechniqueController(BotAIContext context) {
        this.context = context;
    }

    public void onDamaged(Player attacker) {
        lastHitTick = context.tickCounter;

        if (!context.settings.isJumpReset()) return;
        if (attacker == null) return;

        ServerPlayer h = context.bot.getHandle();
        if (h == null || !h.onGround()) return;

        if (context.tickCounter - lastJumpResetTick < 8) return;
        lastJumpResetTick = context.tickCounter;

        context.movementController.requestJump();
    }

    public boolean shouldPunishCrit(Player botPlayer, double distance) {
        if (!context.settings.isPunishCrit()) return false;
        if (context.tickCounter - lastHitTick > PCRIT_WINDOW) return false;

        ServerPlayer h = context.bot.getHandle();
        if (h == null || h.onGround()) return false;
        if (h.getDeltaMovement().y >= -0.08) return false;
        if (h.isInWater() || h.onClimbable()) return false;

        return distance <= context.settings.getReach();
    }

    public boolean shouldHitSelect(double distance) {
        if (!context.settings.isHitSelect()) return false;
        if (context.tickCounter - lastHitTick > HITSELECT_WINDOW) return false;
        return distance <= context.settings.getReach();
    }

    public boolean shouldCritDeflect(Player target, double distance) {
        if (!context.settings.isCritDeflect() || target == null) return false;
        if (distance > context.settings.getReach()) return false;

        try {
            if (target.isOnGround()) return false;
            return target.getVelocity().getY() > 0.08;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean targetIsBlocking(Player target) {
        if (target == null) return false;
        try {
            return target.isHandRaised()
                    && (InventoryController.holdsShield(target)
                    || target.getInventory().getItemInMainHand().getType() == Material.SHIELD);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isBehind(Player botPlayer, Player target) {
        if (botPlayer == null || target == null) return false;
        try {
            Location t = target.getLocation();
            double dx = botPlayer.getLocation().getX() - t.getX();
            double dz = botPlayer.getLocation().getZ() - t.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) return false;

            double fx = -Math.sin(Math.toRadians(t.getYaw()));
            double fz = Math.cos(Math.toRadians(t.getYaw()));
            double dot = (dx / len) * fx + (dz / len) * fz;
            double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
            return angle > 180.0 - BACKSTAB_ANGLE;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean applyTechniqueMovement(Player botPlayer, Player target, double distance) {
        ServerPlayer h = context.bot.getHandle();
        if (h == null || target == null) return false;

        if (context.settings.isShieldBackstab()
                && targetIsBlocking(target)
                && !isBehind(botPlayer, target)
                && canOrbitForFootwork(distance, false)) {
            orbitAround(h, botPlayer, target, 1.0f);
            return true;
        }

        if (outspaceTicks > 0) {
            outspaceTicks--;

            double dx = botPlayer.getLocation().getX() - target.getLocation().getX();
            double dz = botPlayer.getLocation().getZ() - target.getLocation().getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                context.movementController.worldDirToInputs(h, dx / len, dz / len, 0.9f);
                return true;
            }
        }

        if (context.settings.isCircleStrafe()
                && canOrbitForFootwork(distance, true)) {
            if (--orbitTicks <= 0) {
                orbitDir = -orbitDir;
                int baseOrbit = 20;
                int randomOrbit = java.util.concurrent.ThreadLocalRandom.current().nextInt(30);
                orbitTicks = baseOrbit + randomOrbit;
            }
            float speedVar = 0.75f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.2f;
            orbitAround(h, botPlayer, target, speedVar);
            return true;
        }

        return false;
    }

    private boolean canOrbitForFootwork(double distance, boolean needsRecentHit) {
        if (context.critPhase != BotAIContext.CritPhase.IDLE) return false;
        if (context.attackCooldown <= 2) return false;

        double reach = context.settings.getReach();
        if (distance < 1.65 || distance > reach - 0.25) return false;

        return !needsRecentHit
                || context.tickCounter - context.lastLandedAttackTick <= 28;
    }

    private void orbitAround(ServerPlayer h, Player botPlayer, Player target, float speed) {
        double dx = botPlayer.getLocation().getX() - target.getLocation().getX();
        double dz = botPlayer.getLocation().getZ() - target.getLocation().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;

        double nx = dx / len;
        double nz = dz / len;

        double idealDistance = Math.max(1.8, context.settings.getReach() - 0.55);
        double radial = len > idealDistance + 0.2 ? -0.28
                : len < idealDistance - 0.2 ? 0.28 : 0.0;
        double tx = -nz * orbitDir + nx * radial;
        double tz = nx * orbitDir + nz * radial;
        double tl = Math.sqrt(tx * tx + tz * tz);
        if (tl < 0.001) return;

        context.movementController.worldDirToInputs(h, tx / tl, tz / tl, speed);
    }

    public void onHitLanded(double distance) {
        if (context.settings.isOutspacing()
                && distance >= context.settings.getReach() - 0.35) {
            outspaceTicks = 7;
        }
    }

    public void reset() {
        outspaceTicks = 0;
        orbitTicks = 0;
        lastHitTick = -1000;
    }

    public String debugLine() {
        return "sinceHit=" + (context.tickCounter - lastHitTick)
                + " outspace=" + outspaceTicks
                + " orbit=" + orbitDir + "/" + orbitTicks;
    }
}
