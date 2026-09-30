package com.pvpbot.ai;

import com.pvpbot.nav.ScaffoldPlanner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

// "everyone pillar to Steve": get to a player who's somewhere walking can't
// reach - up a tower, across a gap, on a floating island - by walking,
// sneak-bridging and pillaring up with whatever blocks the bot carries,
// re-planning as they move. Once within striking distance it hands the
// player to normal combat as a forced target.
//
// Planning is ScaffoldPlanner's A* over walk / bridge / pillar moves (fewest
// blocks that work); this class executes one plan step at a time, watches
// for falls and knockback, and re-plans when the target moves on.
public class ReachController {
    private static final double ARRIVE_DISTANCE = 3.2;
    private static final double PLAN_RANGE = 24.0;
    private static final int PLAN_PAD = 8;
    private static final int PLAN_BUDGET = 6000;
    private static final int MAX_PLAN_FAILURES = 5;
    private static final int MAX_ACTIVE_TICKS = 20 * 120;
    private static final int WALK_STEP_TIMEOUT = 60;
    private static final int PLACE_STEP_TIMEOUT = 80;
    private static final double REPLAN_MOVE = 2.5;
    private static final int GIVE_BLOCKS = 64;

    // Cells other bots have planned to build in or stand on, so several bots
    // told to climb to the same player (or build up side by side) spread
    // over different columns instead of fighting over one block.
    private static final java.util.Map<Long, UUID> RESERVED = new java.util.HashMap<>();

    private final BotAIContext context;

    private UUID targetId;
    private World targetWorld;

    // "build up": straight pillar on an assigned column.
    private boolean towerMode;
    private int towerX, towerZ, towerTopY;

    private List<ScaffoldPlanner.Step> plan;
    private int planIdx;
    private int stepTicks;
    private int airTicks;
    private int prevX, prevY, prevZ;
    private int planTX, planTY, planTZ;
    private int planCooldown;
    private int failures;
    private int placeDelay;
    private int activeTicks;
    private int repathCooldown;
    private boolean sneaking;
    private boolean gaveBlocks;

    public ReachController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return targetId != null || towerMode;
    }

    // "everyone build up": walk to column (x, z) and pillar `height` blocks.
    public boolean startTower(int x, int z, int height) {
        stop();
        towerMode = true;
        towerX = x;
        towerZ = z;
        Player self = context.bot.getBukkitPlayer();
        int baseY = self != null ? self.getLocation().getBlockY() : 64;
        towerTopY = baseY + height;
        context.movementController.clearFormationOrder();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        reserveColumn(x, z, baseY, towerTopY + 1);
        gaveBlocks = false;
        if (self != null && countBlocks(self) < height + 4) {
            self.getInventory().addItem(new ItemStack(Material.COBBLESTONE, GIVE_BLOCKS));
            context.packetBroadcaster.broadcastEquipment();
            gaveBlocks = true;
        }
        return gaveBlocks;
    }

    private void reserveColumn(int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) RESERVED.put(key(x, y, z), context.bot.getUUID());
    }

    private void releaseReservations() {
        UUID me = context.bot.getUUID();
        RESERVED.values().removeIf(me::equals);
    }

    private boolean reservedByOther(int x, int y, int z) {
        UUID who = RESERVED.get(key(x, y, z));
        return who != null && !who.equals(context.bot.getUUID());
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    public String status() {
        if (towerMode) return "building up at " + towerX + "," + towerZ + " to y=" + towerTopY
                + (plan != null ? " step " + planIdx + "/" + plan.size() : "");
        if (targetId == null) return "idle";
        return "reaching " + targetId.toString().substring(0, 8)
                + (plan != null ? " step " + planIdx + "/" + plan.size() : " no plan")
                + " fails=" + failures;
    }

    // Returns true if blocks had to be handed out for the climb.
    public boolean start(Player target) {
        stop();
        targetId = target.getUniqueId();
        targetWorld = target.getWorld();
        context.releaseStandDown();
        context.movementController.clearFormationOrder();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        Player self = context.bot.getBukkitPlayer();
        gaveBlocks = false;
        if (self != null && countBlocks(self) < 16) {
            self.getInventory().addItem(new ItemStack(Material.COBBLESTONE, GIVE_BLOCKS));
            context.packetBroadcaster.broadcastEquipment();
            gaveBlocks = true;
        }
        return gaveBlocks;
    }

    public void stop() {
        targetId = null;
        targetWorld = null;
        towerMode = false;
        releaseReservations();
        plan = null;
        planIdx = 0;
        failures = 0;
        activeTicks = 0;
        planCooldown = 0;
        setSneak(false);
    }

    public boolean handleReach(Player botPlayer) {
        if (towerMode) return handleTower(botPlayer);
        if (targetId == null) return false;
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null) { stop(); return false; }

        Player target = org.bukkit.Bukkit.getPlayer(targetId);
        if (target == null) {
            var bot = com.pvpbot.PvPBotPlugin.getInstance().getBotManager().getBots().get(targetId);
            target = bot != null && bot.isAlive() ? bot.getBukkitPlayer() : null;
        }
        if (target == null || target.isDead() || target.getWorld() != botPlayer.getWorld()) {
            stop();
            return false;
        }
        if (context.fleeing) return false;

        activeTicks++;
        if (planCooldown > 0) planCooldown--;
        if (placeDelay > 0) placeDelay--;
        if (repathCooldown > 0) repathCooldown--;
        context.suppressSprint = true;

        Location eye = botPlayer.getEyeLocation();
        Location tEye = target.getEyeLocation();
        boolean close = eye.distanceSquared(tEye) <= ARRIVE_DISTANCE * ARRIVE_DISTANCE
                || botPlayer.getLocation().distanceSquared(target.getLocation()) <= 2.2 * 2.2;
        if ((close && botPlayer.hasLineOfSight(target)) || activeTicks > MAX_ACTIVE_TICKS) {
            handOver(target);
            return false;
        }

        double horiz = Math.hypot(target.getLocation().getX() - botPlayer.getLocation().getX(),
                target.getLocation().getZ() - botPlayer.getLocation().getZ());

        // Far away: plain path-finding gets us into range first.
        if (horiz > PLAN_RANGE) {
            plan = null;
            walkTowards(botPlayer, target.getLocation());
            return true;
        }

        int tx = target.getLocation().getBlockX();
        int ty = (int) Math.floor(target.getLocation().getY() + 1.0e-3);
        int tz = target.getLocation().getBlockZ();
        boolean targetMoved = plan != null
                && (Math.abs(tx - planTX) + Math.abs(tz - planTZ) > REPLAN_MOVE || Math.abs(ty - planTY) > 1);

        if ((plan == null || targetMoved) && planCooldown <= 0 && handle.onGround()) {
            if (!replan(botPlayer, handle, tx, ty, tz)) {
                if (++failures >= MAX_PLAN_FAILURES) {
                    handOver(target);
                    return false;
                }
                planCooldown = 30;
            }
        }

        if (plan == null) {
            walkTowards(botPlayer, target.getLocation());
            return true;
        }
        executeStep(botPlayer, handle);
        return true;
    }

    private boolean handleTower(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null) { stop(); return false; }
        if (context.fleeing) return false;
        if (placeDelay > 0) placeDelay--;
        if (repathCooldown > 0) repathCooldown--;
        if (++activeTicks > MAX_ACTIVE_TICKS) { stop(); return false; }
        context.suppressSprint = true;

        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = (int) Math.floor(handle.getY() + 1.0e-3);

        if (fy >= towerTopY && handle.onGround()) {
            // Up top: stay put on the pillar.
            stop();
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return false;
        }

        if (plan == null) {
            if (fx != towerX || fz != towerZ) {
                Location col = new Location(botPlayer.getWorld(), towerX + 0.5, fy, towerZ + 0.5);
                if (botPlayer.getLocation().distanceSquared(col) < 6 * 6) {
                    double d = steerTo(handle, towerX + 0.5, towerZ + 0.5, 0.6f);
                    if (handle.horizontalCollision && handle.onGround() && d > 0.3) {
                        context.movementController.requestJump();
                    }
                } else {
                    walkTowards(botPlayer, col);
                }
                return true;
            }
            if (!handle.onGround()) return true;
            java.util.List<ScaffoldPlanner.Step> steps = new java.util.ArrayList<>();
            for (int y = fy + 1; y <= towerTopY; y++) {
                steps.add(new ScaffoldPlanner.Step(ScaffoldPlanner.Kind.PILLAR, towerX, y, towerZ));
            }
            plan = steps;
            planIdx = 0;
            stepTicks = 0;
            airTicks = 0;
            prevX = fx;
            prevY = fy;
            prevZ = fz;
        }
        executeStep(botPlayer, handle);
        if (plan == null && failures >= MAX_PLAN_FAILURES) stop(); // ceiling / no blocks
        return true;
    }

    private void handOver(Player target) {
        stop();
        context.forcedTarget = target;
        context.target = target;
    }

    private boolean replan(Player botPlayer, ServerPlayer handle, int tx, int ty, int tz) {
        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = (int) Math.floor(handle.getY() + 1.0e-3);
        Terrain terrain = new Terrain(botPlayer.getWorld(), tx, ty, tz, this::reservedByOther);
        if (!terrain.passable(fx, fy, fz)) fy++;

        List<ScaffoldPlanner.Step> p = ScaffoldPlanner.plan(terrain, fx, fy, fz, tx, ty, tz,
                ARRIVE_DISTANCE, PLAN_PAD, PLAN_BUDGET);
        if (p == null) return false;
        releaseReservations();
        for (ScaffoldPlanner.Step st : p) {
            RESERVED.put(key(st.x(), st.y(), st.z()), context.bot.getUUID());
            if (st.placesBlock()) RESERVED.put(key(st.x(), st.y() - 1, st.z()), context.bot.getUUID());
        }
        plan = p;
        planIdx = 0;
        stepTicks = 0;
        airTicks = 0;
        prevX = fx;
        prevY = fy;
        prevZ = fz;
        planTX = tx;
        planTY = ty;
        planTZ = tz;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        return true;
    }

    // One tick of the current plan step (same moves as the schematic
    // builder: walk, sneak-bridge, jump-and-place pillar).
    private void executeStep(Player botPlayer, ServerPlayer handle) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        if (planIdx >= plan.size()) {
            plan = null;
            setSneak(false);
            return;
        }
        ScaffoldPlanner.Step st = plan.get(planIdx);
        int fx = (int) Math.floor(handle.getX());
        int fz = (int) Math.floor(handle.getZ());
        int fy = (int) Math.floor(handle.getY() + 1.0e-3);
        boolean onGround = handle.onGround();

        stepTicks++;
        airTicks = onGround ? 0 : airTicks + 1;
        int timeout = st.placesBlock() ? PLACE_STEP_TIMEOUT : WALK_STEP_TIMEOUT;
        if (stepTicks > timeout || airTicks > 40) {
            failStep();
            return;
        }

        if (onGround && fx == st.x() && fz == st.z() && fy == st.y()) {
            prevX = st.x();
            prevY = st.y();
            prevZ = st.z();
            planIdx++;
            stepTicks = 0;
            if (planIdx >= plan.size()) {
                plan = null;
                setSneak(false);
            }
            return;
        }
        if (onGround && !near(fx, fy, fz, st.x(), st.y(), st.z()) && !near(fx, fy, fz, prevX, prevY, prevZ)) {
            failStep(); // knocked off the route
            return;
        }

        World w = botPlayer.getWorld();
        switch (st.kind()) {
            case WALK -> {
                setSneak(st.y() == prevY && !solidBelowWide(w, st.x(), st.y(), st.z()));
                double d = steerTo(handle, st.x() + 0.5, st.z() + 0.5, sneaking ? 0.45f : 0.8f);
                if (st.y() > fy && onGround && d < 1.4) context.movementController.requestJump();
            }
            case BRIDGE -> {
                Block floor = w.getBlockAt(st.x(), st.y() - 1, st.z());
                setSneak(true);
                if (!floor.getType().isSolid()) {
                    if (fx != prevX || fz != prevZ || fy != prevY) {
                        steerTo(handle, prevX + 0.5, prevZ + 0.5, 0.45f);
                        return;
                    }
                    lookAtBlock(handle, st.x(), st.y() - 1, st.z());
                    if (!place(botPlayer, handle, floor, w.getBlockAt(prevX, prevY - 1, prevZ))) failStep();
                    return;
                }
                steerTo(handle, st.x() + 0.5, st.z() + 0.5, 0.45f);
            }
            case PILLAR -> {
                setSneak(false);
                context.movementController.easePitchTo(88f);
                double cx = st.x() + 0.5, cz = st.z() + 0.5;
                if (Math.hypot(cx - handle.getX(), cz - handle.getZ()) > 0.22 && onGround) {
                    steerTo(handle, cx, cz, 0.3f);
                    return;
                }
                Block under = w.getBlockAt(st.x(), st.y() - 1, st.z());
                if (onGround && fy == st.y() - 1) {
                    if (placeDelay <= 0) context.movementController.requestJump();
                    return;
                }
                if (!onGround && handle.getY() >= st.y() - 0.05 && fx == st.x() && fz == st.z()
                        && (under.getType().isAir() || !under.getType().isSolid())) {
                    if (!place(botPlayer, handle, under, under.getRelative(0, -1, 0))) {
                        failStep();
                        return;
                    }
                    placeDelay = 2;
                }
            }
        }
    }

    private void failStep() {
        plan = null;
        setSneak(false);
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        planCooldown = 10;
        failures++;
    }

    private boolean place(Player botPlayer, ServerPlayer handle, Block cell, Block against) {
        int slot = context.inventoryController.findBlockSlot(botPlayer);
        if (slot < 0 || slot > 8) {
            botPlayer.getInventory().addItem(new ItemStack(Material.COBBLESTONE, GIVE_BLOCKS));
            slot = context.inventoryController.findBlockSlot(botPlayer);
            if (slot < 0 || slot > 8) return false;
        }
        botPlayer.getInventory().setHeldItemSlot(slot);
        ItemStack held = botPlayer.getInventory().getItem(slot);
        if (held == null || !held.getType().isBlock()) return false;

        org.bukkit.block.BlockState replaced = cell.getState();
        cell.setType(held.getType(), true);
        org.bukkit.event.block.BlockPlaceEvent ev = new org.bukkit.event.block.BlockPlaceEvent(
                cell, replaced, against, held.clone(), botPlayer, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            replaced.update(true, false);
            return false;
        }
        if (held.getAmount() <= 1) botPlayer.getInventory().setItem(slot, null);
        else held.setAmount(held.getAmount() - 1);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        context.packetBroadcaster.broadcastEquipment();
        try {
            cell.getWorld().playSound(cell.getLocation(),
                    cell.getBlockData().getSoundGroup().getPlaceSound(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private int countBlocks(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType().isBlock() && it.getType().isSolid()
                    && !it.getType().hasGravity()) {
                n += it.getAmount();
            }
        }
        return n;
    }

    private static boolean near(int x, int y, int z, int cx, int cy, int cz) {
        return Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1 && Math.abs(y - cy) <= 1;
    }

    // Narrow footing (a pillar top, a bridge) - sneak so we don't slip off.
    private static boolean solidBelowWide(World w, int x, int y, int z) {
        int solid = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (w.getBlockAt(x + dx, y - 1, z + dz).getType().isSolid()) solid++;
            }
        }
        return solid >= 6;
    }

    private void walkTowards(Player botPlayer, Location dest) {
        setSneak(false);
        MovementController mc = context.movementController;
        if (context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size()) {
            if (repathCooldown <= 0) {
                repathCooldown = 20;
                context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), dest);
            }
            Location me = botPlayer.getLocation();
            double dx = dest.getX() - me.getX(), dz = dest.getZ() - me.getZ();
            double len = Math.hypot(dx, dz);
            if (len > 0.5) {
                mc.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.worldDirToInputs(context.bot.getHandle(), dx / len, dz / len, 1.0f);
            }
        } else {
            mc.followPath();
        }
    }

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

    // Live world for the planner; the target's own body cells can't get a
    // block placed in them.
    interface CellTest {
        boolean test(int x, int y, int z);
    }

    private static final class Terrain implements ScaffoldPlanner.Terrain {
        private final World w;
        private final int tx, ty, tz;
        private final CellTest takenByOther;
        private final java.util.Map<Long, Material> cache = new java.util.HashMap<>();

        Terrain(World w, int tx, int ty, int tz, CellTest takenByOther) {
            this.w = w;
            this.tx = tx;
            this.ty = ty;
            this.tz = tz;
            this.takenByOther = takenByOther;
        }

        private Material at(int x, int y, int z) {
            if (y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
            long k = ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
            Material m = cache.get(k);
            if (m == null) {
                m = w.getBlockAt(x, y, z).getType();
                cache.put(k, m);
            }
            return m;
        }

        private static boolean danger(Material m) {
            return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                    || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.CAMPFIRE
                    || m == Material.SOUL_CAMPFIRE || m == Material.SWEET_BERRY_BUSH
                    || m == Material.POWDER_SNOW || m == Material.WITHER_ROSE;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && m.isSolid();
        }

        @Override
        public boolean passable(int x, int y, int z) {
            if (takenByOther.test(x, y, z)) return false; // another bot's route/pillar
            Material m = at(x, y, z);
            return m != null && !m.isSolid() && !danger(m) && m != Material.COBWEB;
        }

        @Override
        public boolean placeable(int x, int y, int z) {
            if (x == tx && z == tz && (y == ty || y == ty + 1)) return false;
            if (takenByOther.test(x, y, z)) return false;
            Material m = at(x, y, z);
            return m != null && (m.isAir() || m == Material.WATER || m == Material.SHORT_GRASS
                    || m == Material.TALL_GRASS || m == Material.SNOW || m == Material.FERN
                    || m == Material.DEAD_BUSH || m == Material.VINE);
        }

        @Override
        public boolean dangerous(int x, int y, int z) {
            Material m = at(x, y, z);
            return m != null && danger(m);
        }

        @Override
        public boolean pendingBuild(int x, int y, int z) {
            return false;
        }
    }
}
