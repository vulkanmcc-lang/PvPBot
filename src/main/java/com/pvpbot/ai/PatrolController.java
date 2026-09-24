package com.pvpbot.ai;

import com.pvpbot.route.RouteManager;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class PatrolController {
    private static final double ARRIVE_SQ = 1.8 * 1.8;

    private static final int STUCK_TICKS = 30;

    private static final int ABANDON_TICKS = 140;

    private final BotAIContext context;

    private RouteManager.Route route;
    private boolean sprint;
    private int index;

    private final ArrivalTracker arrival = new ArrivalTracker(Math.sqrt(ARRIVE_SQ), ABANDON_TICKS);
    private int repathCooldown = 0;
    private boolean usingPathfinder = false;

    private boolean looking = false;
    private int lookTicks = 0;

    private static final double VIEW_HALF_ANGLE = 70.0;

    private static final int SPOT_INTERVAL = 5;

    private static final int STAND_DOWN_TICKS = 8;

    private static final int ENGAGE_MAX_TICKS = 1200;

    private boolean engaging = false;
    private int engageTicks = 0;
    private int clearTicks = 0;
    private int spotCooldown = 0;

    private Location engageAnchor;

    private boolean forceRoute = false;

    public PatrolController(BotAIContext context) {
        this.context = context;
    }

    public void start(RouteManager.Route route, boolean sprint) {
        this.route = route;
        this.sprint = sprint;
        this.index = nearestPointIndex();
        engaging = false;
        engageTicks = 0;
        clearTicks = 0;
        engageAnchor = null;
        spotCooldown = 0;
        resetProgress();
    }

    public void stop() {
        route = null;
        engaging = false;
        engageTicks = 0;
        clearTicks = 0;
        engageAnchor = null;
        forceRoute = false;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        usingPathfinder = false;
    }

    public boolean isActive() {
        return route != null && route.size() > 0;
    }

    public String routeName() {
        return route == null ? null : route.name;
    }

    public int currentIndex() { return index; }

    public boolean isSprinting() { return sprint; }

    public void resumeAt(RouteManager.Route route, boolean sprint, int index) {
        start(route, sprint);
        if (route != null && index >= 0 && index < route.size()) {
            this.index = index;
            resetProgress();

            forceRoute = true;
        }
    }

    public String status() {
        if (!isActive()) return "idle";
        if (engaging) {
            Player t = context.target;
            return route.name + " point " + (index + 1) + "/" + route.size()
                    + " \u00a7cENGAGING\u00a7r " + (t == null ? "(standing down)" : t.getName())
                    + " " + (engageTicks / 20) + "s";
        }
        return route.name + " point " + (index + 1) + "/" + route.size()
                + (sprint ? " sprinting" : " walking")
                + (looking ? " (facing)" : usingPathfinder ? " (routing)" : "")
                + (forceRoute ? " (returning)" : "")
                + (arrival.stalledFor() > 0 ? " stuck:" + arrival.stalledFor() : "");
    }

    public boolean handlePatrol(Player botPlayer) {
        if (repathCooldown > 0) repathCooldown--;
        if (spotCooldown > 0) spotCooldown--;
        if (!isActive()) return false;
        if (botPlayer == null) { stop(); return false; }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { stop(); return false; }

        if (engaging) {
            if (!engagementOver(botPlayer)) {
                engageTicks++;
                return false;
            }
            endEngagement();
        } else if (spotCooldown <= 0) {
            spotCooldown = SPOT_INTERVAL;
            Player foe = spotEnemy(botPlayer, handle);
            if (foe != null) {
                beginEngagement(botPlayer, foe);
                return false;
            }
        }

        context.target = null;
        context.fleeing = false;

        Location dest = route.points.get(index);
        if (dest.getWorld() != null && !dest.getWorld().equals(botPlayer.getWorld())) {
            return false;
        }

        Location loc = botPlayer.getLocation();
        double dx = dest.getX() - loc.getX();
        double dz = dest.getZ() - loc.getZ();
        double horizSq = dx * dx + dz * dz;

        if (looking || (horizSq < ARRIVE_SQ && Math.abs(dest.getY() - loc.getY()) < 3.0)) {
            if (!looking) {
                looking = true;
                lookTicks = 0;
            }

            if (!Float.isNaN(dest.getYaw()) && lookAtPoint(dest)) return true;
            looking = false;
            advance();
            return true;
        }

        arrival.retarget(dest);
        if (arrival.update(botPlayer) == ArrivalTracker.State.STALLED) {
            advance();
            return true;
        }

        context.suppressSprint = !sprint;

        if (arrival.stalledFor() < STUCK_TICKS && !forceRoute) {
            usingPathfinder = false;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            walkStraight(botPlayer, dest, dx, dz);
            return true;
        }

        usingPathfinder = true;
        if (repathCooldown <= 0
                && (context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size())) {
            repathCooldown = 20;
            context.pathfindingController.calculatePathAsync(loc, dest);
        }
        if (!context.currentPath.isEmpty()
                && context.pathNodeIndex < context.currentPath.size()) {
            context.movementController.followPath();
            return true;
        }

        forceRoute = false;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        return true;
    }

    private Player spotEnemy(Player botPlayer, ServerPlayer handle) {
        if (!context.settings.isPatrolEngage()) return null;

        if (!context.settings.isHostile()) return null;

        com.pvpbot.BotManager factionMgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
        if (factionMgr != null) {
            String faction = factionMgr.getPlayerFaction(context.bot.getUUID());
            if (faction != null && factionMgr.isFactionStopAttack(faction)) return null;
        }

        double range = context.settings.getPatrolEngageRange();
        if (range <= 0.0) return null;
        double rangeSq = range * range;

        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(botPlayer.getWorld());

        double bx = handle.getX(), by = handle.getY(), bz = handle.getZ();
        double yawRad = Math.toRadians(handle.getYRot());

        double faceX = -Math.sin(yawRad), faceZ = Math.cos(yawRad);
        double cosLimit = Math.cos(Math.toRadians(VIEW_HALF_ANGLE));

        com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();

        Player best = null;
        double bestSq = Double.MAX_VALUE;

        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == botPlayer) continue;

            double dx = view.x[i] - bx;
            double dz = view.z[i] - bz;
            double d2 = dx * dx + dz * dz;
            if (d2 > rangeSq || d2 >= bestSq) continue;
            if (Math.abs(view.y[i] - by) > 8.0) continue;
            if (mgr != null && mgr.isFriendly(context.bot.getUUID(), p.getUniqueId())) continue;
            if (!TargetFilter.isEngageable(p, botPlayer)) continue;

            double len = Math.sqrt(d2);
            if (len > 0.01) {
                double dot = (dx / len) * faceX + (dz / len) * faceZ;

                if (dot < cosLimit && len > 3.0) continue;
            }

            if (!context.combatController.hasLineOfSight(botPlayer, p)) continue;

            bestSq = d2;
            best = p;
        }
        return best;
    }

    private void beginEngagement(Player botPlayer, Player foe) {
        engaging = true;
        engageTicks = 0;
        clearTicks = 0;
        engageAnchor = botPlayer.getLocation().clone();

        context.currentPath.clear();
        context.pathNodeIndex = 0;
        usingPathfinder = false;
        context.suppressSprint = false;

        context.target = foe;
        context.findTargetCooldown = 0;
        context.forceFullTicks = Math.max(context.forceFullTicks, 60);
    }

    private boolean engagementOver(Player botPlayer) {
        if (!context.settings.isPatrolEngage()) return true;
        if (engageTicks > ENGAGE_MAX_TICKS) return true;

        if (engageAnchor != null
                && engageAnchor.getWorld() != null
                && engageAnchor.getWorld().equals(botPlayer.getWorld())) {
            double dx = botPlayer.getLocation().getX() - engageAnchor.getX();
            double dz = botPlayer.getLocation().getZ() - engageAnchor.getZ();
            double leash = leashDistance();
            if (dx * dx + dz * dz > leash * leash) return true;
        }

        Player t = context.target;
        boolean fighting = t != null && TargetFilter.isEngageable(t, botPlayer);

        if (fighting || context.fleeing || anyHostileNear(botPlayer, leashDistance())) {
            clearTicks = 0;
            return false;
        }

        return ++clearTicks >= STAND_DOWN_TICKS;
    }

    private boolean anyHostileNear(Player botPlayer, double radius) {
        com.pvpbot.perf.PlayerSnapshot.WorldView view =
                com.pvpbot.perf.PlayerSnapshot.forWorld(botPlayer.getWorld());
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        double bx = handle.getX(), by = handle.getY(), bz = handle.getZ();
        double rSq = radius * radius;
        com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();

        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == botPlayer) continue;
            double dx = view.x[i] - bx, dz = view.z[i] - bz;
            if (dx * dx + dz * dz > rSq) continue;
            if (Math.abs(view.y[i] - by) > 10.0) continue;

            if (mgr != null && mgr.isFriendly(context.bot.getUUID(), p.getUniqueId())) continue;
            if (!TargetFilter.isEngageable(p, botPlayer)) continue;
            return true;
        }
        return false;
    }

    private double leashDistance() {
        double range = context.settings.getPatrolEngageRange();
        return range + Math.min(Math.max(range * 0.5, 6.0), 16.0);
    }

    private void endEngagement() {
        engaging = false;
        engageTicks = 0;
        clearTicks = 0;
        engageAnchor = null;
        context.target = null;
        context.fleeing = false;
        resetProgress();

        forceRoute = true;
    }

    private void walkStraight(Player botPlayer, Location dest, double dx, double dz) {
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        context.movementController.easeYawTo(desiredYaw);

        context.movementController.easePitchTo(0f);

        ServerPlayer handle = context.bot.getHandle();
        float err = handle == null ? 0f : Math.abs(wrapDegrees(desiredYaw - handle.getYRot()));

        if (err > 50f) {
            context.forwardInput = 0f;
        } else if (err > 20f) {
            context.forwardInput = 0.35f;
        } else {
            context.forwardInput = 1.0f;
        }
        context.strafeInput = 0f;

        if (err > 20f) context.suppressSprint = true;
    }

    private static float wrapDegrees(float angle) {
        angle %= 360.0f;
        if (angle >= 180.0f) return angle - 360.0f;
        if (angle < -180.0f) return angle + 360.0f;
        return angle;
    }

    private boolean lookAtPoint(Location dest) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return false;

        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.movementController.easeYawTo(dest.getYaw());
        context.movementController.easePitchTo(dest.getPitch());

        lookTicks++;
        float yawErr = Math.abs(wrapDegrees(dest.getYaw() - handle.getYRot()));
        float pitchErr = Math.abs(dest.getPitch() - handle.getXRot());

        return lookTicks < 30 && (yawErr > 4f || pitchErr > 4f);
    }

    private void advance() {
        resetProgress();
        index++;
        if (index >= route.size()) {
            if (route.loop) {
                index = 0;
            } else {
                stop();
            }
        }
    }

    private void resetProgress() {
        forceRoute = false;
        looking = false;
        lookTicks = 0;
        arrival.cancel();
        repathCooldown = 0;
        usingPathfinder = false;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private int nearestPointIndex() {
        Player p = context.bot.getBukkitPlayer();
        if (p == null || route == null || route.size() == 0) return 0;

        Location loc = p.getLocation();
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < route.size(); i++) {
            Location pt = route.points.get(i);
            if (pt.getWorld() == null || !pt.getWorld().equals(loc.getWorld())) continue;
            double d = pt.distanceSquared(loc);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}
