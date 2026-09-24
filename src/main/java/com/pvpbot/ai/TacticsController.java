package com.pvpbot.ai;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public class TacticsController {
    public enum Mode { FIGHT, CHASE, PATH }

    private static final double FIGHT_RANGE_HYSTERESIS = 1.0;

    private static final double FIGHT_RANGE_PAD = 2.0;

    private static final double FAR_PATH_DISTANCE = 14.0;

    private static final double PATH_STALE_DISTANCE = 3.0;

    private static final int NODE_TIMEOUT_TICKS = 24;

    private final BotAIContext context;

    public TacticsController(BotAIContext context) {
        this.context = context;
    }

    public Mode decide(Player botPlayer, double distance) {
        if (context.pathBanTicks > 0) context.pathBanTicks--;
        checkProgress(botPlayer, distance);

        Player target = context.target;
        Location botLoc = botPlayer.getLocation();
        if (target == null) return Mode.CHASE;
        Location tLoc = target.getLocation();
        double vertGap = tLoc.getY() - botLoc.getY();
        boolean los = context.combatController.hasLineOfSight(botPlayer, target);
        double fightRange = context.combatController.effectiveReach() + FIGHT_RANGE_PAD;

        boolean midCrossing = context.bridging && !context.bridgeCrossed;

        boolean walkable = distance <= context.combatController.effectiveReach()
                || context.movementController.canWalkStraightTo(tLoc);

        boolean fightGates = los && vertGap <= 3.0 && !midCrossing && walkable;
        double exitRange = fightRange + FIGHT_RANGE_HYSTERESIS;
        boolean withinFight = context.inFightRange
                ? distance <= exitRange
                : distance <= fightRange;

        if (fightGates && withinFight) {
            context.inFightRange = true;
            if (!context.currentPath.isEmpty()) {
                context.currentPath.clear();
                context.pathNodeIndex = 0;
            }
            if (context.bridging) {
                context.bridging = false;
                context.bot.getHandle().setShiftKeyDown(false);
            }
            resetNodeWatch();
            return Mode.FIGHT;
        }

        context.inFightRange = false;

        if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
            boolean stale = pathIsStale(tLoc);
            boolean timedOut = nodeTimedOut();

            if (stale || timedOut) {
                if (timedOut && !stale) {
                    context.markNavFailure(context.currentPath.get(context.pathNodeIndex));
                }

                context.currentPath.clear();
                context.pathNodeIndex = 0;
                resetNodeWatch();

                context.pathBanTicks = stale ? 0 : 15;
                return Mode.CHASE;
            }
            return Mode.PATH;
        }

        boolean wantPath = context.pathBanTicks <= 0
                && (distance > FAR_PATH_DISTANCE
                || ((!los || !walkable) && distance > fightRange)
                || context.chaseBlockedTicks > 2
                || (vertGap > 3.0 && distance > 2.5));
        if (wantPath && context.pathRecalcCooldown <= 0) {
            context.pathfindingController.calculatePathAsync(botLoc, tLoc);
        }
        return Mode.CHASE;
    }

    private boolean pathIsStale(Location targetLoc) {
        Location last = context.currentPath.get(context.currentPath.size() - 1);
        if (last.getWorld() != targetLoc.getWorld()) return true;
        double dx = (last.getX() + 0.5) - targetLoc.getX();
        double dz = (last.getZ() + 0.5) - targetLoc.getZ();

        int remaining = Math.max(0, context.currentPath.size() - context.pathNodeIndex);
        double tolerance = Math.min(14.0, PATH_STALE_DISTANCE + remaining * 0.6);
        return (dx * dx + dz * dz) > tolerance * tolerance;
    }

    private boolean nodeTimedOut() {
        if (context.pathNodeIndex != context.lastPathNodeIndex) {
            context.lastPathNodeIndex = context.pathNodeIndex;
            context.pathNodeStuckTicks = 0;
            context.lastPathNodeDistance = -1.0;
            return false;
        }

        Location node = context.currentPath.get(context.pathNodeIndex);
        Location bot = context.bot.getBukkitPlayer().getLocation();
        double dx = node.getX() + 0.5 - bot.getX();
        double dz = node.getZ() + 0.5 - bot.getZ();
        double distance = dx * dx + dz * dz;
        if (context.lastPathNodeDistance < 0.0
                || distance < context.lastPathNodeDistance - 0.01) {
            context.pathNodeStuckTicks = 0;
        } else {
            context.pathNodeStuckTicks++;
        }
        context.lastPathNodeDistance = distance;
        return context.pathNodeStuckTicks > NODE_TIMEOUT_TICKS;
    }

    private void checkProgress(Player botPlayer, double distance) {
        if (context.target == null) return;
        if (++context.progressCheckTicks < 20) return;
        context.progressCheckTicks = 0;

        double previous = context.lastProgressDistance;
        context.lastProgressDistance = distance;
        if (previous < 0) return;

        if (distance <= context.combatController.effectiveReach() + FIGHT_RANGE_PAD) return;

        if (previous - distance < 0.5) {
            context.jumpBanTicks = 20;
            context.pathBanTicks = 0;
            if (context.pathRecalcCooldown <= 0) {
                markObstruction(botPlayer);

                context.currentPath.clear();
                context.pathNodeIndex = 0;
                resetNodeWatch();
                context.pathfindingController.calculatePathAsync(
                        botPlayer.getLocation(), context.target.getLocation());
            }
        }
    }

    private void markObstruction(Player botPlayer) {
        try {
            if (!botPlayer.isOnGround()) return;

            if (context.pathNodeIndex < context.currentPath.size()
                    && !context.currentPath.isEmpty()) {
                context.markNavFailure(context.currentPath.get(context.pathNodeIndex));
                return;
            }

            Player target = context.target;
            if (target == null) return;
            Location bot = botPlayer.getLocation();
            Location tl = target.getLocation();
            double dx = tl.getX() - bot.getX();
            double dz = tl.getZ() - bot.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.5) return;

            int ax = (int) Math.floor(bot.getX() + (dx / len) * 2.0);
            int az = (int) Math.floor(bot.getZ() + (dz / len) * 2.0);
            context.markNavFailure(ax, bot.getBlockY(), az);
        } catch (Throwable ignored) {
        }
    }

    private void resetNodeWatch() {
        context.pathNodeStuckTicks = 0;
        context.lastPathNodeIndex = -1;
        context.lastPathNodeDistance = -1.0;
    }
}
