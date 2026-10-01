package com.pvpbot.ai;

import com.pvpbot.nav.ScaffoldPlanner;
import com.pvpbot.schem.BuildJob;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

public class BuildController {
    private enum Phase { IDLE, GOTO, CLEAR, PLACE, SCAFFOLD, TEARDOWN }

    private static final double REACH = 4.0;

    private static final double REACH_SQ = REACH * REACH;

    private final BotAIContext context;

    private BuildJob job;
    private BuildJob.Task task;
    private Phase phase = Phase.IDLE;

    private int breakTicksTotal, breakTicksElapsed;
    private int lastDestroyStage = -1;
    private Block breaking;

    private int repathCooldown = 0;
    private int blockedCooldown = 0;

    private int lastGroundX, lastGroundY = Integer.MIN_VALUE, lastGroundZ;

    // ---- scaffold plan (bridge / pillar / walk moves to reach a task) ----
    private java.util.List<ScaffoldPlanner.Step> plan;
    private int planIdx = 0;
    private int planStepTicks = 0;
    private int planAirTicks = 0;
    private int planFailures = 0;
    private int planCooldown = 0;
    private int prevStepX, prevStepY, prevStepZ;
    private int pillarPlaceDelay = 0;

    // ---- teardown of this bot's own scaffold ----
    private BuildJob.Scaffold teardownTarget;
    private int teardownTicks = 0;

    private boolean sneaking = false;

    // Horizontal range inside which a stuck/high/cut-off task gets a
    // scaffold plan instead of more path-finding.
    private static final double SCAFFOLD_RANGE = 14.0;
    private static final int PLAN_PAD = 10;
    private static final int PLAN_BUDGET = 5000;
    private static final int WALK_STEP_TIMEOUT = 60;
    private static final int PLACE_STEP_TIMEOUT = 80;
    private static final int TEARDOWN_BLOCK_TIMEOUT = 160;
    private static final int MAX_PLAN_FAILURES = 3;

    private BuildJob.Task avoidTask;
    private int avoidTicksLeft = 0;

    private int losBlockedTicks = 0;

    private final ArrivalTracker arrival = new ArrivalTracker(0.0, 25);

    private int failedApproaches = 0;
    private int placeDelay = 0;
    private int restockCooldown = 0;

    public BuildController(BotAIContext context) {
        this.context = context;
    }

    public void assign(BuildJob job) {
        this.job = job;
        reset();
    }

    public String debugLine() {
        if (job == null) return "no job";
        int mine = job.scaffoldOf(context.bot.getUUID()).size();
        String scaffoldInfo = " scaffold=" + mine + "/" + job.scaffoldCount()
                + (plan != null ? " plan=" + planIdx + "/" + plan.size()
                + "(" + plan.get(Math.min(planIdx, plan.size() - 1)).kind() + ")" : "")
                + " planFails=" + planFailures;
        if (task == null) {
            return "idle/" + phase
                    + (blockedCooldown > 0 ? " blocked:" + blockedCooldown : "")
                    + (avoidTask != null ? " avoiding-a-task" : "")
                    + scaffoldInfo
                    + " needs:" + job.nextNeededMaterial();
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return "no handle";

        int feetY = feetCellY(handle);
        double dx = task.x + 0.5 - handle.getX();
        double dz = task.z + 0.5 - handle.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = task.y + 0.5 - (handle.getY() + handle.getEyeHeight());
        double distSq = horizSq + dy * dy;

        return phase
                + " task(" + task.x + "," + task.y + "," + task.z + ")"
                + " feetY=" + feetY
                + " up=" + (task.y - feetY)
                + String.format(" d2=%.1f h2=%.1f", distSq, horizSq)
                + (distSq > REACH_SQ ? " WALKING" : " IN-REACH")
                + scaffoldInfo
                + " path=" + (context.currentPath.isEmpty()
                ? "none" : context.pathNodeIndex + "/" + context.currentPath.size())
                + " stuck=" + arrival.stalledFor() + " tries=" + failedApproaches
                + " bump=" + context.wallBumpTicks
                + " grnd=" + (lastGroundY == Integer.MIN_VALUE ? "never" : String.valueOf(lastGroundY));
    }

    public BuildJob currentJob() {
        return job;
    }

    public boolean hasJob() {
        return job != null && !job.isFinished();
    }

    public boolean isBusy() {
        // Still busy while taking our scaffold down after the build is done.
        return (hasJob() && phase != Phase.IDLE)
                || (job != null && phase == Phase.TEARDOWN);
    }

    public void abort() {
        clearDestroyStage();
        if (job != null && task != null) job.release(task);
        // Leaving the job for good (stopped, bot removed, reassigned): pop
        // any scaffold we still own straight out of the world so no
        // pillars/bridges are left behind.
        if (job != null) job.removeScaffoldNow(context.bot.getUUID());
        job = null;
        reset();
    }

    private void reset() {
        if (job != null && task != null) job.release(task);
        task = null;
        phase = Phase.IDLE;
        breaking = null;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        losBlockedByBuild = false;
        plan = null;
        planIdx = 0;
        planFailures = 0;
        teardownTarget = null;
        teardownTicks = 0;
        setSneak(false);
        arrival.cancel();
        failedApproaches = 0;
        avoidTask = null;
        avoidTicksLeft = 0;
        losBlockedTicks = 0;
        clearDestroyStage();
    }

    public boolean handleBuild(Player botPlayer) {
        if (repathCooldown > 0) repathCooldown--;
        if (placeDelay > 0) placeDelay--;
        if (restockCooldown > 0) restockCooldown--;
        if (avoidTicksLeft > 0 && --avoidTicksLeft == 0) avoidTask = null;

        if (planCooldown > 0) planCooldown--;
        if (pillarPlaceDelay > 0) pillarPlaceDelay--;

        if (job == null) return false;
        if (botPlayer == null) { abort(); return false; }
        job.heartbeat(context.bot.getUUID());

        if (job.isCancelled()) {
            abort();
            return false;
        }

        if (context.target != null || context.fleeing) {
            if (task != null) {
                job.release(task);
                task = null;
                clearDestroyStage();
            }
            plan = null;
            phase = Phase.IDLE;
            teardownTarget = null;
            setSneak(false);
            return false;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        // Build done: take our own scaffold down before leaving the job.
        if (job.isFinished()) {
            if (!job.scaffoldOf(context.bot.getUUID()).isEmpty()) {
                if (task != null) { job.release(task); task = null; }
                phase = Phase.TEARDOWN;
                runTeardown(botPlayer, handle);
                return true;
            }
            abort();
            return false;
        }

        if (handle.onGround()) {
            lastGroundX = (int) Math.floor(handle.getX());
            lastGroundY = (int) Math.floor(handle.getY());
            lastGroundZ = (int) Math.floor(handle.getZ());
        }

        if (blockedCooldown > 0) {
            blockedCooldown--;
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return true;
        }

        if (phase == Phase.TEARDOWN) {
            if (runTeardown(botPlayer, handle)) return true;
            phase = Phase.IDLE;
        }

        if (task == null) {
            if (!acquireTask(botPlayer, handle)) {
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                return true;
            }
            // Holding scaffold from an earlier task: keep it only if the new
            // task can be done from right here, otherwise take it down first
            // (it would just be litter, and a reason to get stuck later).
            if (!job.scaffoldOf(context.bot.getUUID()).isEmpty()
                    && !canWorkFromHere(handle, task)) {
                job.release(task);
                task = null;
                plan = null;
                phase = Phase.TEARDOWN;
                runTeardown(botPlayer, handle);
                return true;
            }
        }

        if (plan != null) {
            if (target(task).getBlockData().matches(task.data)) {
                plan = null;
                setSneak(false);
            } else {
                executePlan(botPlayer, handle);
                return true;
            }
        }

        Location botLoc = botPlayer.getLocation();
        Block target = job.world.getBlockAt(task.x, task.y, task.z);

        double dx = task.x + 0.5 - botLoc.getX();
        double dy = task.y + 0.5 - (botLoc.getY() + handle.getEyeHeight());
        double dz = task.z + 0.5 - botLoc.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        if (target.getBlockData().matches(task.data)) {
            job.complete(task);
            task = null;
            phase = Phase.IDLE;
            return true;
        }

        if (distSq > REACH_SQ) {
            phase = Phase.GOTO;
            walkTo(botPlayer, handle, task);
            return true;
        }

        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
        context.movementController.easePitchTo(
                (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));

        if (selfOccupies(handle, task)) {
            phase = Phase.PLACE;
            jumpAndPlace(botPlayer, handle, target);
            return true;
        }

        if (otherEntityOccupies(handle, task)) {
            job.release(task);
            task = null;
            phase = Phase.IDLE;
            blockedCooldown = 20;
            return true;
        }

        Block blocker;
        if (distSq > 6.25) {
            blocker = firstBlockingBetween(handle, task);
        } else {
            blocker = null;
            losBlockedByBuild = false;
        }
        if (losBlockedByBuild) {
            losBlockedTicks++;
            if (losBlockedTicks > 10 && planCooldown <= 0 && tryStartPlan(botPlayer, handle)) {
                return true;
            }
            if (losBlockedTicks > 140) {
                losBlockedTicks = 0;
                repositionOrDrop();
                return true;
            }
            phase = Phase.GOTO;
            walkTo(botPlayer, handle, task, true);
            return true;
        }
        losBlockedTicks = 0;

        if (blocker != null) {
            phase = Phase.CLEAR;
            mine(botPlayer, handle, blocker, false);
            return true;
        }

        if (!target.getType().isAir() && !isReplaceable(target.getType())) {
            phase = Phase.CLEAR;
            mine(botPlayer, handle, target, true);
            return true;
        }

        phase = Phase.PLACE;
        placeBlock(botPlayer, handle, target);
        return true;
    }

    private boolean selfOccupies(ServerPlayer handle, BuildJob.Task t) {
        return boxOverlapsCell(handle.getX(), handle.getY(), handle.getZ(), t);
    }

    private boolean otherEntityOccupies(ServerPlayer handle, BuildJob.Task t) {
        try {
            org.bukkit.util.BoundingBox cell = new org.bukkit.util.BoundingBox(
                    t.x, t.y, t.z, t.x + 1.0, t.y + 1.0, t.z + 1.0);
            for (org.bukkit.entity.Entity e :
                    job.world.getNearbyEntities(cell, e -> e instanceof org.bukkit.entity.LivingEntity)) {
                if (e.getEntityId() == handle.getId()) continue;
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean boxOverlapsCell(double x, double feetY, double z, BuildJob.Task t) {
        final double half = 0.31;
        final double height = 1.8;
        return x + half > t.x && x - half < t.x + 1.0
                && z + half > t.z && z - half < t.z + 1.0
                && feetY + height > t.y && feetY < t.y + 1.0;
    }

    private boolean losBlockedByBuild = false;

    private Block firstBlockingBetween(ServerPlayer handle, BuildJob.Task t) {
        losBlockedByBuild = false;

        double ox = handle.getX();
        double oy = handle.getY() + handle.getEyeHeight();
        double oz = handle.getZ();

        double dx = (t.x + 0.5) - ox, dy = (t.y + 0.5) - oy, dz = (t.z + 0.5) - oz;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return null;
        dx /= len; dy /= len; dz /= len;

        int cx = (int) Math.floor(ox), cy = (int) Math.floor(oy), cz = (int) Math.floor(oz);
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tMaxX = boundary(ox, dx, stepX);
        double tMaxY = boundary(oy, dy, stepY);
        double tMaxZ = boundary(oz, dz, stepZ);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);

        int botCellX = cx, botCellZ = cz;
        int feetCellY = (int) Math.floor(handle.getY());

        for (int guard = 0; guard < 24; guard++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cx += stepX; tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                cy += stepY; tMaxY += tDeltaY;
            } else {
                cz += stepZ; tMaxZ += tDeltaZ;
            }

            if (cx == t.x && cy == t.y && cz == t.z) return null;
            if (Math.min(tMaxX, Math.min(tMaxY, tMaxZ)) > len + 1.0) return null;

            if (cx == botCellX && cz == botCellZ
                    && (cy == feetCellY || cy == feetCellY + 1 || cy == feetCellY - 1)) {
                continue;
            }

            Material m = job.world.getBlockAt(cx, cy, cz).getType();
            if (m.isAir() || isReplaceable(m) || !m.isSolid()) continue;

            if (job.isBuildCell(cx, cy, cz) || job.isScaffoldCell(cx, cy, cz)) {
                losBlockedByBuild = true;
                return null;
            }
            return job.world.getBlockAt(cx, cy, cz);
        }
        return null;
    }

    private static double boundary(double origin, double dir, int step) {
        if (step == 0) return Double.MAX_VALUE;
        double cell = Math.floor(origin);
        double edge = step > 0 ? cell + 1.0 : cell;
        return (edge - origin) / dir;
    }

    private void jumpAndPlace(Player botPlayer, ServerPlayer handle, Block target) {
        if (handle.getY() >= task.y + 0.9) {
            placeBlock(botPlayer, handle, target);
            return;
        }
        if (!handle.onGround()) return;
        // Jumping to place under ourselves needs room above the head. With a
        // low ceiling (or a refused jump) step off the cell instead and place
        // it from beside - no 3-high space needed.
        int feetY = (int) Math.floor(handle.getY() + 1.0e-3);
        boolean lowCeiling = job.world.getBlockAt((int) Math.floor(handle.getX()), feetY + 2,
                (int) Math.floor(handle.getZ())).getType().isSolid();
        if (lowCeiling || !context.movementController.requestJump()) {
            double ax = handle.getX() - (task.x + 0.5), az = handle.getZ() - (task.z + 0.5);
            double al = Math.hypot(ax, az);
            if (al < 0.05) {
                // Dead centre: pick a side that has floor and room.
                int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] sd : sides) {
                    int sx = task.x + sd[0], sz = task.z + sd[1];
                    if (job.world.getBlockAt(sx, feetY - 1, sz).getType().isSolid()
                            && !job.world.getBlockAt(sx, feetY, sz).getType().isSolid()
                            && !job.world.getBlockAt(sx, feetY + 1, sz).getType().isSolid()) {
                        ax = sd[0];
                        az = sd[1];
                        al = 1.0;
                        break;
                    }
                }
            }
            if (al >= 0.05) {
                context.movementController.worldDirToInputs(handle, ax / al, az / al, 0.6f);
            }
        }
    }

    private boolean acquireTask(Player botPlayer, ServerPlayer handle) {
        BuildJob.Task next = job.claim(context.bot.getUUID(), botPlayer,
                handle.getX(), handle.getY(), handle.getZ(), avoidTask);

        if (next != null) {
            task = next;
            phase = Phase.GOTO;

            arrival.cancel();
            failedApproaches = 0;
            return true;
        }

        if (restockCooldown <= 0) {
            restockCooldown = 40;
            Material needed = job.nextNeededMaterial();
            if (needed != null) job.supply(botPlayer, needed, 32);
        }
        return false;
    }

    private void walkTo(Player botPlayer, ServerPlayer handle, BuildJob.Task t) {
        walkTo(botPlayer, handle, t, false);
    }

    private void walkTo(Player botPlayer, ServerPlayer handle, BuildJob.Task t,
                        boolean forcePath) {
        int feetY = feetCellY(handle);
        double horizSq = Math.pow(t.x + 0.5 - handle.getX(), 2)
                + Math.pow(t.z + 0.5 - handle.getZ(), 2);

        Location dest = standingSpotFor(t, feetY);
        Location loc = botPlayer.getLocation();

        // Close to the task but it's up high, there's no floor to stand on
        // next to it, the bot is grinding into something, or walking has
        // already stalled: plan a route that may bridge out and/or pillar up.
        boolean mustClimb = t.y - feetY >= 3;
        boolean aerialSpot = dest.getY() - feetY > 1.5 || !hasFloor(dest);
        boolean grinding = context.wallBumpTicks > 8;
        boolean near = horizSq < SCAFFOLD_RANGE * SCAFFOLD_RANGE;
        if (near && (mustClimb || aerialSpot || grinding || failedApproaches >= 1 || forcePath)
                && planCooldown <= 0 && tryStartPlan(botPlayer, handle)) {
            return;
        }

        double flat = Math.pow(dest.getX() - loc.getX(), 2) + Math.pow(dest.getZ() - loc.getZ(), 2);

        arrival.retarget(dest);
        if (arrival.update(botPlayer) == ArrivalTracker.State.STALLED) {
            arrival.begin(dest);
            forcePath = true;

            if (!context.currentPath.isEmpty()
                    && context.pathNodeIndex < context.currentPath.size()) {
                context.markNavFailure(context.currentPath.get(context.pathNodeIndex));
            }

            context.currentPath.clear();
            context.pathNodeIndex = 0;
            repathCooldown = 0;
            if (++failedApproaches >= 4) {
                failedApproaches = 0;
                repositionOrDrop();
                return;
            }
        }

        boolean blocked = forcePath || context.wallBumpTicks > 6;
        boolean tooFar = flat > 9.0;
        if (blocked || tooFar) {
            if (repathCooldown <= 0
                    && (context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size())) {
                repathCooldown = 20;
                context.pathfindingController.calculatePathAsync(loc, dest);
            }
            if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
                context.movementController.followPath();
                return;
            }
            if (blocked) {
                double bdx = dest.getX() - loc.getX();
                double bdz = dest.getZ() - loc.getZ();
                context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-bdx, bdz)));
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                context.suppressSprint = true;
                return;
            }
        }

        double dx = dest.getX() - loc.getX();
        double dz = dest.getZ() - loc.getZ();
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        context.suppressSprint = true;
    }

    private Location standingSpotFor(BuildJob.Task t, int feetY) {
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        Location best = null;
        double bestD = Double.MAX_VALUE;

        for (int[] s : sides) {
            int sx = t.x + s[0], sz = t.z + s[1];
            for (int dy = 0; dy >= -1; dy--) {
                int sy = t.y + dy;
                Block feet = job.world.getBlockAt(sx, sy, sz);
                Block head = job.world.getBlockAt(sx, sy + 1, sz);
                Block floor = job.world.getBlockAt(sx, sy - 1, sz);
                if (!feet.getType().isAir() && !isReplaceable(feet.getType())) continue;
                if (!head.getType().isAir() && !isReplaceable(head.getType())) continue;
                if (!floor.getType().isSolid()) continue;

                double d = Math.abs(sy - feetY);
                if (d < bestD) {
                    bestD = d;
                    best = new Location(job.world, sx + 0.5, sy, sz + 0.5);
                }
            }
        }
        return best != null ? best : new Location(job.world, t.x + 0.5, t.y, t.z + 0.5);
    }

    private void mine(Player botPlayer, ServerPlayer handle, Block block, boolean isTargetCell) {
        if (!isTargetCell && job.isBuildCell(block.getX(), block.getY(), block.getZ())) {
            repositionOrDrop();
            return;
        }

        int result = breakStep(botPlayer, handle, block);
        if (result == BREAK_CANCELLED) {
            if (isTargetCell) {
                job.complete(task);
                task = null;
                phase = Phase.IDLE;
            } else {
                repositionOrDrop();
            }
        }
    }

    private static final int BREAK_IN_PROGRESS = 0;
    private static final int BREAK_DONE = 1;
    private static final int BREAK_CANCELLED = -1;

    // One tick of mining `block` with the right tool, crack animation and a
    // proper BlockBreakEvent at the end (so protection plugins still apply).
    private int breakStep(Player botPlayer, ServerPlayer handle, Block block) {
        if (breaking == null || !breaking.equals(block)) {
            clearDestroyStage();
            breaking = block;
            equipTool(botPlayer, block);
            breakTicksTotal = computeBreakTicks(botPlayer, block);
            breakTicksElapsed = 0;
        }

        if (breakTicksElapsed % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }

        breakTicksElapsed++;
        sendDestroyStage(block, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));

        if (breakTicksElapsed < breakTicksTotal) return BREAK_IN_PROGRESS;

        clearDestroyStage();
        breaking = null;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return BREAK_CANCELLED;

        block.setType(Material.AIR, true);
        return BREAK_DONE;
    }

    private void repositionOrDrop() {
        clearDestroyStage();
        breaking = null;
        plan = null;
        setSneak(false);
        if (task != null) {
            job.release(task);
            avoidTask = task;
            avoidTicksLeft = 100;
        }
        task = null;
        phase = Phase.IDLE;
        blockedCooldown = 10;
    }

    private void placeBlock(Player botPlayer, ServerPlayer handle, Block target) {
        if (placeDelay > 0) return;

        int slot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType() == task.item);
        if (slot < 0) {
            job.release(task);
            task = null;
            phase = Phase.IDLE;
            return;
        }
        if (slot <= 8) botPlayer.getInventory().setHeldItemSlot(slot);

        Block support = findSupport(target);
        if (support == null && !job.isBaseLayer(task.y) && !job.isSupportRelaxed()) {
            job.release(task);
            task = null;
            phase = Phase.IDLE;
            blockedCooldown = 15;
            return;
        }

        org.bukkit.block.BlockState replaced = target.getState();

        target.setBlockData(task.data, false);

        ItemStack held = botPlayer.getInventory().getItem(slot);
        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        target, replaced,
                        support != null ? support : target,
                        held != null ? held.clone() : new ItemStack(task.item),
                        botPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            replaced.update(true, false);

            job.complete(task);
            task = null;
            phase = Phase.IDLE;
            return;
        }

        consumeOne(botPlayer, slot);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        try {
            job.world.playSound(target.getLocation(),
                    target.getBlockData().getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }

        job.complete(task);
        task = null;
        phase = Phase.IDLE;

        placeDelay = 5;
    }

    // =====================================================================
    // Scaffold planning & execution
    // =====================================================================

    private Block target(BuildJob.Task t) {
        return job.world.getBlockAt(t.x, t.y, t.z);
    }

    private static int feetCellY(ServerPlayer handle) {
        return (int) Math.floor(handle.getY() + 1.0e-3);
    }

    private boolean hasFloor(Location spot) {
        return job.world.getBlockAt(spot.getBlockX(), spot.getBlockY() - 1, spot.getBlockZ())
                .getType().isSolid();
    }

    private boolean canWorkFromHere(ServerPlayer handle, BuildJob.Task t) {
        double ex = handle.getX(), ey = handle.getY() + handle.getEyeHeight(), ez = handle.getZ();
        double dx = t.x + 0.5 - ex, dy = t.y + 0.5 - ey, dz = t.z + 0.5 - ez;
        if (dx * dx + dy * dy + dz * dz > REACH_SQ) return false;
        return ScaffoldPlanner.clearSight(new LiveTerrain(), ex, ey, ez, t.x, t.y, t.z);
    }

    private boolean tryStartPlan(Player botPlayer, ServerPlayer handle) {
        if (task == null || !handle.onGround()) return false;
        planCooldown = 30;

        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = feetCellY(handle);
        LiveTerrain terrain = new LiveTerrain();
        // Standing on a slab/path block puts the feet cell inside it.
        if (!terrain.passable(fx, fy, fz)) fy++;

        java.util.List<ScaffoldPlanner.Step> p = ScaffoldPlanner.plan(terrain,
                fx, fy, fz, task.x, task.y, task.z, REACH, PLAN_PAD, PLAN_BUDGET);
        if (p == null || p.isEmpty()) {
            if (p == null) planFailures++;
            if (planFailures >= MAX_PLAN_FAILURES) {
                planFailures = 0;
                repositionOrDrop();
            }
            return false;
        }

        int blocksNeeded = 0;
        for (ScaffoldPlanner.Step st : p) if (st.placesBlock()) blocksNeeded++;
        if (blocksNeeded > 0 && countScaffold(botPlayer) < blocksNeeded) {
            job.supply(botPlayer, BuildJob.SCAFFOLD, Math.max(32, blocksNeeded));
        }

        plan = p;
        planIdx = 0;
        planStepTicks = 0;
        planAirTicks = 0;
        prevStepX = fx;
        prevStepY = fy;
        prevStepZ = fz;
        phase = Phase.SCAFFOLD;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        executePlan(botPlayer, handle);
        return true;
    }

    private void executePlan(Player botPlayer, ServerPlayer handle) {
        phase = Phase.SCAFFOLD;
        context.suppressSprint = true;
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (planIdx >= plan.size()) {
            finishPlan();
            return;
        }
        ScaffoldPlanner.Step st = plan.get(planIdx);

        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = feetCellY(handle);
        boolean onGround = handle.onGround();

        planStepTicks++;
        planAirTicks = onGround ? 0 : planAirTicks + 1;

        int timeout = st.placesBlock() ? PLACE_STEP_TIMEOUT : WALK_STEP_TIMEOUT;
        if (planStepTicks > timeout) {
            failPlan();
            return;
        }
        if (planAirTicks > 40) {
            // Falling a long way means we left the plan entirely.
            failPlan();
            return;
        }

        if (onGround && fx == st.x() && fz == st.z() && fy == st.y()) {
            prevStepX = st.x();
            prevStepY = st.y();
            prevStepZ = st.z();
            planIdx++;
            planStepTicks = 0;
            if (planIdx >= plan.size()) finishPlan();
            return;
        }

        // Knocked/slid off the plan (not on either end of the current move).
        if (onGround && !nearCell(fx, fy, fz, st.x(), st.y(), st.z())
                && !nearCell(fx, fy, fz, prevStepX, prevStepY, prevStepZ)) {
            failPlan();
            return;
        }

        switch (st.kind()) {
            case WALK -> {
                boolean flat = st.y() == prevStepY;
                setSneak(flat && (job.isScaffoldCell(st.x(), st.y() - 1, st.z())
                        || job.isScaffoldCell(prevStepX, prevStepY - 1, prevStepZ)));
                double dist = steerTo(handle, st.x() + 0.5, st.z() + 0.5, sneaking ? 0.45f : 0.8f);
                if (st.y() > fy && onGround && dist < 1.4) context.movementController.requestJump();
            }
            case BRIDGE -> {
                Block floor = job.world.getBlockAt(st.x(), st.y() - 1, st.z());
                if (!floor.getType().isSolid()) {
                    if (fx != prevStepX || fz != prevStepZ || fy != prevStepY) {
                        setSneak(true);
                        steerTo(handle, prevStepX + 0.5, prevStepZ + 0.5, 0.45f);
                        return;
                    }
                    setSneak(true);
                    lookAtBlock(handle, st.x(), st.y() - 1, st.z());
                    if (!placeScaffold(botPlayer, handle, floor, false, prevStepX, prevStepY, prevStepZ)) {
                        failPlan();
                    }
                    return;
                }
                setSneak(true);
                steerTo(handle, st.x() + 0.5, st.z() + 0.5, 0.45f);
            }
            case PILLAR -> {
                setSneak(false);
                context.movementController.easePitchTo(88f);
                double cx = st.x() + 0.5, cz = st.z() + 0.5;
                double off = Math.hypot(cx - handle.getX(), cz - handle.getZ());
                if (off > 0.22 && onGround) {
                    steerTo(handle, cx, cz, 0.3f);
                    return;
                }
                Block under = job.world.getBlockAt(st.x(), st.y() - 1, st.z());
                if (onGround && fy == st.y() - 1) {
                    if (pillarPlaceDelay <= 0) context.movementController.requestJump();
                    return;
                }
                if (!onGround && handle.getY() >= st.y() - 0.05
                        && fx == st.x() && fz == st.z()
                        && (under.getType().isAir() || isReplaceable(under.getType()))) {
                    if (!placeScaffold(botPlayer, handle, under, true, st.x(), st.y(), st.z())) {
                        failPlan();
                        return;
                    }
                    pillarPlaceDelay = 2;
                }
            }
        }
    }

    private static boolean nearCell(int x, int y, int z, int cx, int cy, int cz) {
        return Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1 && Math.abs(y - cy) <= 1;
    }

    private void finishPlan() {
        plan = null;
        planIdx = 0;
        planFailures = 0;
        phase = Phase.GOTO;
        setSneak(false);
        context.forwardInput = 0f;
        context.strafeInput = 0f;
    }

    private void failPlan() {
        plan = null;
        planIdx = 0;
        setSneak(false);
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        planCooldown = 20;
        if (++planFailures >= MAX_PLAN_FAILURES) {
            planFailures = 0;
            repositionOrDrop();
        } else {
            phase = Phase.GOTO;
        }
    }

    private int countScaffold(Player botPlayer) {
        int n = 0;
        for (ItemStack it : botPlayer.getInventory().getStorageContents()) {
            if (it != null && it.getType() == BuildJob.SCAFFOLD) n += it.getAmount();
        }
        return n;
    }

    // Places one scaffold block (always the job's scaffold material, never a
    // block the schematic needs), fires a real BlockPlaceEvent and records it
    // in the job so it gets torn down later.
    private boolean placeScaffold(Player botPlayer, ServerPlayer handle, Block cell, boolean pillar,
                                  int standX, int standY, int standZ) {
        if (job.isBuildCell(cell.getX(), cell.getY(), cell.getZ())) return false;

        int slot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType() == BuildJob.SCAFFOLD);
        if (slot < 0) {
            job.supply(botPlayer, BuildJob.SCAFFOLD, 32);
            slot = context.inventoryController.ensureInHotbar(
                    botPlayer, it -> it.getType() == BuildJob.SCAFFOLD);
        }
        if (slot < 0 || slot > 8) return false;
        botPlayer.getInventory().setHeldItemSlot(slot);
        ItemStack held = botPlayer.getInventory().getItem(slot);
        if (held == null) return false;

        org.bukkit.block.BlockState replaced = cell.getState();
        Block against = pillar ? cell.getRelative(0, -1, 0)
                : job.world.getBlockAt(standX, standY - 1, standZ);
        cell.setType(BuildJob.SCAFFOLD, true);

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        cell, replaced, against, held.clone(), botPlayer, true,
                        org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            replaced.update(true, false);
            return false;
        }

        consumeOne(botPlayer, slot);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        try {
            job.world.playSound(cell.getLocation(),
                    cell.getBlockData().getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        job.addScaffold(cell.getX(), cell.getY(), cell.getZ(), BuildJob.SCAFFOLD,
                context.bot.getUUID(), pillar, standX, standY, standZ);
        return true;
    }

    // =====================================================================
    // Teardown: undo our own scaffold in reverse order of placement.
    //   - a block under our feet with a safe landing below: dig down through
    //     it (how you take a pillar down),
    //   - otherwise walk back to where we stood when placing it (for a bridge
    //     that's the previous bridge cell, still standing) and mine it.
    // Anything we can't get to in time is popped out directly, so nothing is
    // ever left behind and the bot can't get stuck on its own scaffold.
    // Returns false once there's nothing left to take down.
    // =====================================================================

    private boolean runTeardown(Player botPlayer, ServerPlayer handle) {
        java.util.List<BuildJob.Scaffold> mine = job.scaffoldOf(context.bot.getUUID());
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.suppressSprint = true;
        if (mine.isEmpty()) {
            teardownTarget = null;
            setSneak(false);
            clearDestroyStage();
            breaking = null;
            return false;
        }

        BuildJob.Scaffold sc = mine.get(mine.size() - 1);
        Block block = job.world.getBlockAt(sc.x, sc.y, sc.z);
        if (block.getType() != sc.material) {
            job.removeScaffold(sc);
            return true;
        }
        if (teardownTarget != sc) {
            teardownTarget = sc;
            teardownTicks = 0;
            clearDestroyStage();
            breaking = null;
        }

        // Someone else (another builder, a player) is standing on it: wait
        // for them rather than pull the floor out from under them.
        if (job.someoneStandsOn(sc, handle.getId())) {
            if (++teardownTicks > TEARDOWN_BLOCK_TIMEOUT) {
                sc.abandoned = true; // job clears it once they step off
                teardownTarget = null;
            }
            return true;
        }

        if (++teardownTicks > TEARDOWN_BLOCK_TIMEOUT) {
            giveBack(botPlayer, sc.material);
            if (standingOn(handle, sc)) {
                sc.abandoned = true; // job removes it once nobody is on it
            } else {
                job.removeScaffoldNow(sc);
            }
            teardownTarget = null;
            return true;
        }

        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = feetCellY(handle);

        if (standingOn(handle, sc)) {
            if (safeToDigDown(sc)) {
                setSneak(false);
                lookAtBlock(handle, sc.x, sc.y, sc.z);
                mineScaffold(botPlayer, handle, block, sc);
                return true;
            }
            // Digging would drop us somewhere bad: step back to where we
            // stood when we placed it.
            setSneak(true);
            if (!(fx == sc.standX && fz == sc.standZ)) {
                steerTo(handle, sc.standX + 0.5, sc.standZ + 0.5, 0.4f);
            }
            return true;
        }

        double ex = handle.getX(), ey = handle.getY() + handle.getEyeHeight(), ez = handle.getZ();
        double dx = sc.x + 0.5 - ex, dy = sc.y + 0.5 - ey, dz = sc.z + 0.5 - ez;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= REACH_SQ
                && ScaffoldPlanner.clearSight(new LiveTerrain(), ex, ey, ez, sc.x, sc.y, sc.z)) {
            lookAtBlock(handle, sc.x, sc.y, sc.z);
            mineScaffold(botPlayer, handle, block, sc);
            return true;
        }

        if (distSq > 8.0 * 8.0 || !handle.onGround() && teardownTicks > 40) {
            // Wandered off (or got knocked away): don't trek back across the
            // map for one block.
            giveBack(botPlayer, sc.material);
            job.removeScaffoldNow(sc);
            teardownTarget = null;
            return true;
        }

        // Walk back to the spot we placed it from.
        setSneak(job.isScaffoldCell(fx, fy - 1, fz));
        double d = steerTo(handle, sc.standX + 0.5, sc.standZ + 0.5, sneaking ? 0.45f : 0.7f);
        if (sc.standY > fy && handle.onGround() && d < 1.4) context.movementController.requestJump();
        return true;
    }

    private void mineScaffold(Player botPlayer, ServerPlayer handle, Block block, BuildJob.Scaffold sc) {
        int r = breakStep(botPlayer, handle, block);
        if (r == BREAK_DONE) {
            giveBack(botPlayer, sc.material);
            job.removeScaffold(sc);
            teardownTarget = null;
        } else if (r == BREAK_CANCELLED) {
            // A protection plugin said no - pop it directly instead so the
            // scaffold still doesn't stay.
            job.removeScaffoldNow(sc);
            teardownTarget = null;
        }
    }

    private boolean standingOn(ServerPlayer handle, BuildJob.Scaffold sc) {
        if (!handle.onGround()) return false;
        if (Math.abs(handle.getY() - (sc.y + 1.0)) > 0.1) return false;
        final double half = 0.3;
        return handle.getX() + half > sc.x && handle.getX() - half < sc.x + 1.0
                && handle.getZ() + half > sc.z && handle.getZ() - half < sc.z + 1.0;
    }

    // Removing the block under us is fine if we'd land at most 3 blocks
    // lower on something that isn't lava/fire/etc.
    private boolean safeToDigDown(BuildJob.Scaffold sc) {
        for (int d = 1; d <= 4; d++) {
            Material m = job.world.getBlockAt(sc.x, sc.y - d, sc.z).getType();
            if (m.isSolid()) return d <= 4 && !DANGER.contains(m);
            if (m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE) return false;
            if (m == Material.WATER) return true;
        }
        return false;
    }

    private void giveBack(Player botPlayer, Material m) {
        java.util.Map<Integer, ItemStack> left = botPlayer.getInventory().addItem(new ItemStack(m));
        if (left.isEmpty()) context.packetBroadcaster.broadcastEquipment();
    }

    // =====================================================================
    // Small movement helpers
    // =====================================================================

    // Walk toward (x, z) regardless of where we're looking; returns the
    // horizontal distance left.
    private double steerTo(ServerPlayer handle, double x, double z, float speed) {
        double dx = x - handle.getX(), dz = z - handle.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.12) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return dist;
        }
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
        float mag = (float) Math.min(speed, Math.max(0.15, dist));
        context.movementController.worldDirToInputs(handle, dx / dist, dz / dist, mag);
        context.suppressSprint = true;
        return dist;
    }

    private void lookAtBlock(ServerPlayer handle, int x, int y, int z) {
        double dx = x + 0.5 - handle.getX();
        double dy = y + 0.5 - (handle.getY() + handle.getEyeHeight());
        double dz = z + 0.5 - handle.getZ();
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
        context.movementController.easePitchTo(
                (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void setSneak(boolean on) {
        if (sneaking == on) return;
        sneaking = on;
        try {
            ServerPlayer handle = context.bot.getHandle();
            if (handle != null) {
                handle.setShiftKeyDown(on);
                context.packetBroadcaster.broadcastEntityData();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final java.util.Set<Material> DANGER = java.util.EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE,
            Material.POINTED_DRIPSTONE);

    // Live-world view for the planner, with a per-plan block cache so the
    // search doesn't hammer world lookups.
    private final class LiveTerrain implements ScaffoldPlanner.Terrain {
        private final java.util.Map<Long, Material> cache = new java.util.HashMap<>();
        private final int minH = job.world.getMinHeight();
        private final int maxH = job.world.getMaxHeight();

        private Material at(int x, int y, int z) {
            if (y < minH || y >= maxH) return null;
            long k = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
            Material m = cache.get(k);
            if (m == null) {
                m = job.world.getBlockAt(x, y, z).getType();
                cache.put(k, m);
            }
            return m;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && m.isSolid();
        }

        @Override
        public boolean passable(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && !m.isSolid() && !DANGER.contains(m) && m != Material.COBWEB;
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && (m.isAir() || isReplaceable(m)) && !job.isBuildCell(x, y, z);
        }

        @Override
        public boolean dangerous(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && DANGER.contains(m);
        }

        @Override
        public boolean pendingBuild(int x, int y, int z) {
            if (!job.isBuildCell(x, y, z)) return false;
            Material m = at(x, y, z);
            return m != null && (m.isAir() || isReplaceable(m));
        }
    }

    private Block findSupport(Block target) {
        Block[] around = {
                target.getRelative(0, -1, 0), target.getRelative(0, 1, 0),
                target.getRelative(1, 0, 0), target.getRelative(-1, 0, 0),
                target.getRelative(0, 0, 1), target.getRelative(0, 0, -1)};
        for (Block b : around) {
            if (b != null && !b.getType().isAir()) return b;
        }
        return null;
    }

    private static boolean isReplaceable(Material m) {
        if (m.isAir()) return true;
        return m == Material.WATER || m == Material.LAVA
                || m == Material.SHORT_GRASS || m == Material.TALL_GRASS
                || m == Material.SNOW || m == Material.FERN
                || m == Material.LARGE_FERN || m == Material.SEAGRASS
                || m == Material.DEAD_BUSH || m == Material.VINE;
    }

    private void consumeOne(Player botPlayer, int slot) {
        ItemStack s = botPlayer.getInventory().getItem(slot);
        if (s == null) return;
        int left = s.getAmount() - 1;
        botPlayer.getInventory().setItem(slot, left > 0 ? withAmount(s, left) : null);
        context.packetBroadcaster.broadcastEquipment();
    }

    private static ItemStack withAmount(ItemStack s, int n) {
        s.setAmount(n);
        return s;
    }

    private void equipTool(Player botPlayer, Block block) {
        String needed = preferredToolSuffix(block.getType());
        int slot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType().name().endsWith(needed));
        if (slot >= 0 && slot <= 8) {
            botPlayer.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
        }
    }

    private static String preferredToolSuffix(Material m) {
        String n = m.name();
        if (n.contains("LOG") || n.contains("PLANK") || n.contains("WOOD")
                || n.contains("FENCE") || n.contains("DOOR")) return "_AXE";
        if (n.contains("DIRT") || n.contains("SAND") || n.contains("GRAVEL")
                || n.contains("GRASS_BLOCK") || n.contains("CLAY")) return "_SHOVEL";
        return "_PICKAXE";
    }

    private int computeBreakTicks(Player botPlayer, Block block) {
        double hardness;
        try {
            hardness = block.getType().getHardness();
        } catch (Throwable t) {
            hardness = 1.5;
        }
        if (hardness < 0) return 200;
        if (hardness == 0) return 2;

        double speed = 1.0;
        ItemStack hand = botPlayer.getInventory().getItemInMainHand();
        if (hand != null) {
            String n = hand.getType().name();
            String wanted = preferredToolSuffix(block.getType());
            if (n.endsWith(wanted)) {
                if (n.startsWith("WOODEN")) speed = 2.0;
                else if (n.startsWith("STONE")) speed = 4.0;
                else if (n.startsWith("IRON")) speed = 6.0;
                else if (n.startsWith("DIAMOND")) speed = 8.0;
                else if (n.startsWith("NETHERITE")) speed = 9.0;
                else if (n.startsWith("GOLDEN")) speed = 12.0;
            }
            try {
                int eff = hand.getEnchantmentLevel(Enchantment.EFFICIENCY);
                if (eff > 0 && speed > 1.0) speed += eff * eff + 1;
            } catch (Throwable ignored) {
            }
        }

        try {
            var haste = botPlayer.getPotionEffect(PotionEffectType.HASTE);
            if (haste != null) speed *= 1.0 + 0.2 * (haste.getAmplifier() + 1);
        } catch (Throwable ignored) {
        }

        int ticks = (int) Math.ceil(30.0 * hardness / Math.max(0.01, speed));
        return Math.max(2, Math.min(ticks, 200));
    }

    private void sendDestroyStage(Block block, int stage) {
        stage = Math.max(0, Math.min(9, stage));
        if (stage == lastDestroyStage) return;
        lastDestroyStage = stage;
        sendDestroyPacket(block.getX(), block.getY(), block.getZ(), stage);
    }

    private void clearDestroyStage() {
        if (lastDestroyStage < 0 || breaking == null) {
            lastDestroyStage = -1;
            return;
        }
        sendDestroyPacket(breaking.getX(), breaking.getY(), breaking.getZ(), -1);
        lastDestroyStage = -1;
    }

    private void sendDestroyPacket(int x, int y, int z, int stage) {
        try {
            ServerPlayer handle = context.bot.getHandle();
            if (handle == null) return;
            context.packetBroadcaster.sendPacketToAll(
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            handle.getId(), new net.minecraft.core.BlockPos(x, y, z), stage));
        } catch (Throwable ignored) {
        }
    }
}
