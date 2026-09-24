package com.pvpbot.ai;

import com.pvpbot.PvPBotPlugin;
import com.pvpbot.nav.NavGrid;
import com.pvpbot.nav.Pathfinder;
import com.pvpbot.perf.PathBudget;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.concurrent.*;

// Rewritten in place (backup at backups/PathfindingController.java.bak-*).
// Async A* orchestrator: every caller just calls calculatePathAsync(start,
// end) and later reads context.currentPath/pathNodeIndex/pathComplete once
// it's populated - the whole point of this class is to keep the expensive
// parts (world snapshotting, the actual A* search) off the main thread while
// still applying the result safely back on it. Nothing here was found to be
// buggy on read-through; this is a reorganize-for-clarity pass, same
// algorithm, thresholds and tunables as before.
public class PathfindingController {
    private final BotAIContext context;

    public static int searchRadius = 96;

    public static int verticalPad = 32;

    public static double maxPathDistance = 160.0;

    public PathfindingController(BotAIContext context) {
        this.context = context;
    }

    // =====================================================================
    // Shared background thread pool. One pool for the whole plugin (not per
    // bot) - bounded queue + AbortPolicy so a burst of path requests fails
    // fast (caller backs off and retries) rather than piling up unbounded
    // work.
    // =====================================================================

    private static ExecutorService pool = newPool();

    private static ExecutorService newPool() {
        return new ThreadPoolExecutor(
            1,
            Math.max(1, Runtime.getRuntime().availableProcessors() / 3),
            30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "PvPBot-Path");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }

    private static synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            pool = newPool();
        }
        return pool;
    }

    public static synchronized void shutdownPool() {
        if (pool != null) pool.shutdownNow();
    }

    // =====================================================================
    // Request entry point: validate, size the search window, capture a
    // NavGrid snapshot on the main thread (chunk data isn't safe to touch
    // off-thread), inject avoid/terrain-memory/crowd penalties, then hand the
    // actual search off to the pool.
    // =====================================================================

    public void calculatePathAsync(Location start, Location end) {
        if (start == null || end == null) return;

        if (!context.settings.isPathfinding()) return;
        if (context.pathfindingInProgress || context.pathRecalcCooldown > 0) return;

        World world = start.getWorld();
        if (world == null || end.getWorld() != world) return;

        final int sx = start.getBlockX(), sy = start.getBlockY(), sz = start.getBlockZ();
        final int gx = end.getBlockX(), gy = end.getBlockY(), gz = end.getBlockZ();

        // Only trust the target-tracking staleness check in applyResult()
        // against context.target if this request was actually FOR the
        // current target's location (within half a block) - a path to some
        // other fixed point shouldn't get invalidated just because the bot
        // also happens to have a combat target right now.
        final org.bukkit.entity.Player requestedTarget = context.target != null
                && context.target.getWorld() == world
                && context.target.getLocation().distanceSquared(end) <= 4.0
                ? context.target : null;

        context.mazeMemory.setGoal(gx, gy, gz);
        final boolean mazeMode = context.mazeMemory.isMazeLike(context.tickCounter);

        double ddx = sx - gx, ddy = sy - gy, ddz = sz - gz;
        if (ddx * ddx + ddy * ddy + ddz * ddz > maxPathDistance * maxPathDistance) {
            context.pathRecalcCooldown = 36 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9);
            return;
        }

        // Search window: padded around the straight line between start and
        // goal, widened further the more the bot has recently failed to
        // path (pathFailures) or is actively trying to escape a hole -
        // those situations are exactly when the direct route is blocked and
        // a wider detour search is needed.
        int flatDist = (int) Math.sqrt(ddx * ddx + ddz * ddz);
        int recoveryPad = Math.min(32, context.pathFailures * 6
                + (context.holeEscapeTicks > 0 ? 10 : 0));
        // Mazes wind far away from the straight line, so give the search
        // room to follow corridors that loop out past the start/goal box.
        if (mazeMode) recoveryPad += 24;
        int pad = Math.min(searchRadius + (mazeMode ? 16 : 0), 40 + flatDist / 2 + recoveryPad);
        int verticalRecoveryPad = Math.min(24, context.pathFailures * 3
                + (context.holeEscapeTicks > 0 ? 12 : 0));
        int searchVerticalPad = Math.min(56, verticalPad + verticalRecoveryPad);
        int minX = Math.min(sx, gx) - pad, maxX = Math.max(sx, gx) + pad;
        int minZ = Math.min(sz, gz) - pad, maxZ = Math.max(sz, gz) + pad;
        int minY = Math.min(sy, gy) - searchVerticalPad;
        int maxY = Math.max(sy, gy) + searchVerticalPad;

        int chunksWide = (maxX >> 4) - (minX >> 4) + 1;
        int chunksDeep = (maxZ >> 4) - (minZ >> 4) + 1;

        // Server-wide chunk-snapshot budget for this tick - if we're over
        // it, defer rather than starve other bots' path requests. A bot
        // that's been deferred 3+ times in a row is treated as starved and
        // allowed to jump the budget queue so it isn't permanently starved
        // out by busier bots.
        boolean starved = context.pathDeferrals >= 3;
        if (!PathBudget.tryConsume(chunksWide * chunksDeep, starved)) {
            context.pathDeferrals++;
            context.pathRecalcCooldown = Math.max(context.pathRecalcCooldown,
                    3 + java.util.concurrent.ThreadLocalRandom.current().nextInt(3));
            return;
        }
        context.pathDeferrals = 0;

        final NavGrid grid;
        context.pathfindingInProgress = true;
        com.pvpbot.perf.BotProfiler.start(com.pvpbot.perf.BotProfiler.Section.PATH_SNAPSHOT);
        try {
            grid = NavGrid.capture(world, minX, minZ, maxX, maxZ, minY, maxY);
        } catch (Throwable t) {
            discardRoute();
            context.pathfindingInProgress = false;
            context.pathRecalcCooldown = Math.max(context.pathRecalcCooldown, 20);
            return;
        } finally {
            com.pvpbot.perf.BotProfiler.end();
        }
        if (grid == null) {
            discardRoute();
            context.pathfindingInProgress = false;
            context.pathRecalcCooldown = 18 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5);
            return;
        }

        try {
            injectPenalties(grid, world, start, gx, gz, minX, minY, minZ, maxX, maxY, maxZ);
        } catch (Throwable t) {
            discardRoute();
            context.pathfindingInProgress = false;
            context.pathRecalcCooldown = Math.max(context.pathRecalcCooldown, 8);
            return;
        }

        context.pathRecalcCooldown = 13 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5);

        int recoveryBudget = Math.min(5000, context.pathFailures * 900
                + (context.holeEscapeTicks > 0 ? 1800 : 0));
        final int budget = mazeMode
                ? Math.max(12000, Math.min(Pathfinder.mazeMaxExpansions,
                        6000 + flatDist * 600 + recoveryBudget * 3))
                : Math.max(3600, Math.min(Pathfinder.maxExpansions,
                        2000 + flatDist * 260 + recoveryBudget));

        try {
            CompletableFuture
                    .supplyAsync(() -> Pathfinder.find(grid, sx, sy, sz, gx, gy, gz, budget), pool())
                    .whenComplete((result, err) -> {
                        try {
                            Bukkit.getScheduler().runTask(
                                    PvPBotPlugin.getInstance(), () -> {
                                        try {
                                            applyResult(world, result, err, requestedTarget,
                                                    sx, sy, sz, gx, gy, gz);
                                        } finally {
                                            context.pathfindingInProgress = false;
                                        }
                                    });
                        } catch (Throwable callbackError) {
                            context.pathfindingInProgress = false;
                            context.pathRecalcCooldown = Math.max(
                                    context.pathRecalcCooldown, 8);
                        }
                    });
        } catch (Throwable t) {
            discardRoute();
            context.pathfindingInProgress = false;
            context.pathRecalcCooldown = Math.max(context.pathRecalcCooldown, 8);
        }
    }

    // =====================================================================
    // Penalty injection: nudge the search away from cells other systems
    // have flagged, without forbidding them outright (avoid/terrain-memory/
    // crowd are all soft costs the A* weighs against the direct route).
    // =====================================================================

    private void injectPenalties(NavGrid grid, World world, Location start,
                                 int gx, int gz, int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ) {
        if (!context.navAvoid.isEmpty()) {
            int cap = NavAvoid.capacity();
            int[] ax = new int[cap], ay = new int[cap], az = new int[cap];
            int n = context.navAvoid.snapshot(ax, ay, az);
            grid.setAvoid(ax, ay, az, n, NavAvoid.PENALTY);
        }

        if (com.pvpbot.nav.TerrainMemory.isEnabled() && context.settings.isUseTerrainMemory()) {
            final int MEM_CAP = 64;
            int[] mx = new int[MEM_CAP], my = new int[MEM_CAP], mz = new int[MEM_CAP];
            int mn = com.pvpbot.nav.TerrainMemory.collect(world,
                    minX, minY, minZ, maxX, maxY, maxZ, mx, my, mz, MEM_CAP);
            if (mn > 0) {
                grid.setTerrainMemory(mx, my, mz, mn,
                        com.pvpbot.nav.TerrainMemory.PENALTY);
            }
        }

        if (context.settings.isCrowdAvoidance()) {
            final int CROWD_CAP = 48;
            int[] cx = new int[CROWD_CAP];
            int[] cz = new int[CROWD_CAP];
            int cn = 0;
            try {
                com.pvpbot.perf.PlayerSnapshot.WorldView view =
                        com.pvpbot.perf.PlayerSnapshot.forWorld(world);
                if (view != null) {
                    double selfX = start.getX(), selfZ = start.getZ();
                    for (int i = 0; i < view.count && cn < CROWD_CAP; i++) {
                        int bx = (int) Math.floor(view.x[i]);
                        int bz = (int) Math.floor(view.z[i]);
                        if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) continue;

                        if (Math.abs(view.x[i] - selfX) < 1.0
                                && Math.abs(view.z[i] - selfZ) < 1.0) continue;
                        if (Math.abs(bx - gx) <= 1 && Math.abs(bz - gz) <= 1) continue;
                        cx[cn] = bx;
                        cz[cn] = bz;
                        cn++;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (cn > 0) grid.setCrowd(cx, cz, cn, 6.0);
        }

        grid.setCellCosts(context.mazeMemory.export(world,
                minX, minY, minZ, maxX, maxY, maxZ, context.tickCounter));
    }

    // =====================================================================
    // Result application: validate the request is still fresh (the bot -
    // and, if this path was for the current target, the target too - must
    // not have moved far from where the request was issued) before trusting
    // a result computed a few ticks ago on a background thread.
    // =====================================================================

    private void applyResult(World world, Pathfinder.Result result, Throwable err,
                             org.bukkit.entity.Player requestedTarget,
                             int requestedStartX, int requestedStartY, int requestedStartZ,
                             int requestedX, int requestedY, int requestedZ) {
        org.bukkit.entity.Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null || botPlayer.getWorld() != world) {
            discardRoute();
            context.pathRecalcCooldown = 4;
            return;
        }

        Location botNow = botPlayer.getLocation();
        if (Math.abs(botNow.getBlockX() - requestedStartX) > 3
                || Math.abs(botNow.getBlockY() - requestedStartY) > 2
                || Math.abs(botNow.getBlockZ() - requestedStartZ) > 3) {
            discardRoute();
            context.pathRecalcCooldown = 3
                    + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
            return;
        }

        if (requestedTarget != null) {
            if (context.target != requestedTarget) {
                discardRoute();
                context.pathRecalcCooldown = 3
                        + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
                return;
            }
            Location now = requestedTarget.getLocation();
            if (now.getWorld() != world
                    || Math.abs(now.getBlockX() - requestedX) > 3
                     || Math.abs(now.getBlockY() - requestedY) > 2
                     || Math.abs(now.getBlockZ() - requestedZ) > 3) {
                discardRoute();
                context.pathRecalcCooldown = 3
                        + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
                return;
            }
        }

        if (result != null && err == null) {
            context.mazeMemory.onPathResult(result.complete, result.exhausted);
        }

        if (err != null || result == null || result.steps.isEmpty()) {
            discardRoute();
            // An exhausted search explored everything reachable in the
            // window: count it double so the next attempt widens faster.
            int bump = (result != null && result.exhausted) ? 2 : 1;
            context.pathFailures = Math.min(context.pathFailures + bump, 5);
            int backoff = 12 + context.pathFailures * (10 + java.util.concurrent.ThreadLocalRandom.current().nextInt(5));
            context.pathRecalcCooldown = Math.max(context.pathRecalcCooldown, backoff);
            return;
        }

        context.pathFailures = 0;

        List<Location> path = context.objectPool.getArrayList();
        path.clear();
        for (Pathfinder.Step s : result.steps) {
            path.add(new Location(world, s.x, s.y, s.z));
        }

        if (context.currentPath instanceof ObjectPool.PooledArrayList<?> old) {
            context.objectPool.releaseArrayList(old);
        }
        context.currentPath = path;
        context.pathNodeIndex = 0;
        context.pathComplete = result.complete;
        context.pathNodeStuckTicks = 0;
        context.lastPathNodeIndex = -1;
        context.lastPathNodeDistance = -1.0;
        context.pathRecenterTicks = 0;
        context.pathNodeBumps = 0;
        context.pathBumpNodeIndex = -1;

        if (!result.complete) {
            context.pathRecalcCooldown = Math.min(context.pathRecalcCooldown,
                    22 + java.util.concurrent.ThreadLocalRandom.current().nextInt(7));
        }
    }

    private void discardRoute() {
        if (context.currentPath instanceof ObjectPool.PooledArrayList<?> old) {
            context.objectPool.releaseArrayList(old);
        } else {
            context.currentPath.clear();
        }
        context.currentPath = context.objectPool.getArrayList();
        context.pathNodeIndex = 0;
        context.pathComplete = false;
        context.pathNodeStuckTicks = 0;
        context.lastPathNodeIndex = -1;
        context.lastPathNodeDistance = -1.0;
    }
}
