package com.pvpbot.ai;

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
    private enum Phase { IDLE, GOTO, CLEAR, PLACE }

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
    private int pillarDelay = 0;

    private int pillarBaseY = Integer.MIN_VALUE;
    private int pillarBaseX, pillarBaseZ;

    private int lastGroundX, lastGroundY = Integer.MIN_VALUE, lastGroundZ;

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
        if (task == null) {
            return "idle/" + phase
                    + (blockedCooldown > 0 ? " blocked:" + blockedCooldown : "")
                    + (avoidTask != null ? " avoiding-a-task" : "")
                    + " needs:" + job.nextNeededMaterial();
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return "no handle";

        int feetY = (int) Math.floor(handle.getY());
        double dx = task.x + 0.5 - handle.getX();
        double dz = task.z + 0.5 - handle.getZ();
        double horizSq = dx * dx + dz * dz;
        double dy = task.y + 0.5 - (handle.getY() + handle.getEyeHeight());
        double distSq = horizSq + dy * dy;

        boolean walking = distSq > REACH_SQ;
        boolean mustClimb = task.y - feetY >= 3;
        Location dest = standingSpotFor(task, feetY);
        boolean aerial = dest.getY() - feetY > 1.5;
        boolean grinding = context.wallBumpTicks > 8;
        boolean climbs = mustClimb && horizSq < 36.0 && (horizSq < 9.0 || aerial || grinding);

        return phase
                + " task(" + task.x + "," + task.y + "," + task.z + ")"
                + " feetY=" + feetY
                + " up=" + (task.y - feetY)
                + String.format(" d2=%.1f h2=%.1f", distSq, horizSq)
                + (walking ? " WALKING" : " IN-REACH")
                + " climb[must=" + mustClimb + " aerial=" + aerial
                + " grind=" + grinding + "]=" + climbs
                + (climbs ? " base=" + pillarBaseY + " onGround=" + handle.onGround() : "")
                + " path=" + (context.currentPath.isEmpty()
                ? "none" : context.pathNodeIndex + "/" + context.currentPath.size())
                + " stuck=" + arrival.stalledFor() + " tries=" + failedApproaches
                + " bump=" + context.wallBumpTicks
                + " grnd=" + (lastGroundY == Integer.MIN_VALUE ? "never" : String.valueOf(lastGroundY));
    }

    public boolean hasJob() {
        return job != null && !job.isFinished();
    }

    public boolean isBusy() {
        return hasJob() && phase != Phase.IDLE;
    }

    public void abort() {
        clearDestroyStage();
        if (job != null && task != null) job.release(task);
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
        pillarBaseY = Integer.MIN_VALUE;
        losBlockedByBuild = false;
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

        if (job == null) return false;
        if (job.isFinished()) {
            abort();
            return false;
        }
        if (botPlayer == null) { abort(); return false; }

        if (context.target != null || context.fleeing) {
            if (task != null) {
                job.release(task);
                task = null;
                phase = Phase.IDLE;
                clearDestroyStage();
            }
            return false;
        }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

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

        if (task == null) {
            if (!acquireTask(botPlayer, handle)) {
                context.forwardInput = 0f;
                context.strafeInput = 0f;
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

            if (job.isBuildCell(cx, cy, cz)) {
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
        if (handle.onGround()) {
            context.movementController.requestJump();
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
        int feetY = (int) Math.floor(handle.getY());
        double horizSq = Math.pow(t.x + 0.5 - handle.getX(), 2)
                + Math.pow(t.z + 0.5 - handle.getZ(), 2);

        Location dest = standingSpotFor(t, feetY);
        Location loc = botPlayer.getLocation();

        boolean mustClimb = t.y - feetY >= 3;
        boolean aerialSpot = dest.getY() - feetY > 1.5;
        boolean grinding = context.wallBumpTicks > 8;
        if (mustClimb && horizSq < 36.0 && (horizSq < 9.0 || aerialSpot || grinding)) {
            phase = Phase.PLACE;
            pillarUp(botPlayer, handle);
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

    private void pillarUp(Player botPlayer, ServerPlayer handle) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.movementController.easePitchTo(85f);

        if (pillarDelay > 0) {
            pillarDelay--;
            return;
        }

        if (lastGroundY == Integer.MIN_VALUE) return;

        if (handle.onGround()) {
            pillarBaseX = lastGroundX;
            pillarBaseY = lastGroundY;
            pillarBaseZ = lastGroundZ;
            context.movementController.requestJump();
            return;
        }

        if (pillarBaseY == Integer.MIN_VALUE) {
            pillarBaseX = lastGroundX;
            pillarBaseY = lastGroundY;
            pillarBaseZ = lastGroundZ;
        }

        if (handle.getY() < pillarBaseY + 1.0) return;

        if ((int) Math.floor(handle.getX()) != pillarBaseX
                || (int) Math.floor(handle.getZ()) != pillarBaseZ) {
            return;
        }

        Block under = job.world.getBlockAt(pillarBaseX, pillarBaseY, pillarBaseZ);
        if (!under.getType().isAir() && !isReplaceable(under.getType())) return;

        int slot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType() == com.pvpbot.schem.BuildJob.SCAFFOLD);
        if (slot < 0) slot = context.inventoryController.findBlockSlot(botPlayer);
        if (slot < 0) return;
        if (slot <= 8) botPlayer.getInventory().setHeldItemSlot(slot);

        ItemStack held = botPlayer.getInventory().getItem(slot);
        if (held == null) return;
        Material m = held.getType();

        org.bukkit.block.BlockState replaced = under.getState();
        under.setType(m, true);

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        under, replaced, under.getRelative(0, -1, 0),
                        held.clone(), botPlayer, true,
                        org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            replaced.update(true, false);

            if (task != null) job.release(task);
            task = null;
            phase = Phase.IDLE;
            blockedCooldown = 40;
            pillarBaseY = Integer.MIN_VALUE;
            return;
        }

        consumeOne(botPlayer, slot);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        pillarBaseY = Integer.MIN_VALUE;
        pillarDelay = 2;
    }

    private void mine(Player botPlayer, ServerPlayer handle, Block block, boolean isTargetCell) {
        if (!isTargetCell && job.isBuildCell(block.getX(), block.getY(), block.getZ())) {
            repositionOrDrop();
            return;
        }

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

        if (breakTicksElapsed < breakTicksTotal) return;

        clearDestroyStage();
        breaking = null;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (isTargetCell) {
                job.complete(task);
                task = null;
                phase = Phase.IDLE;
            } else {
                repositionOrDrop();
            }
            return;
        }

        block.setType(Material.AIR, false);
    }

    private void repositionOrDrop() {
        clearDestroyStage();
        breaking = null;
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
