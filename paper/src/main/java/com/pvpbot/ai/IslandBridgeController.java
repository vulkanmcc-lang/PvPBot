package com.pvpbot.ai;

import com.pvpbot.schem.IslandBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Builds an End island bridge the way a player would: end stone only, one
// block at a time out over the void.
//
//  - One bot leads. It stands a block behind the end of the bridge,
//    sneaking (so it can't walk off an edge), places the next middle block,
//    then steps forward onto the bridge it just extended - jumping up a
//    step or dropping down one where the bridge slopes.
//  - Everyone else fills in the two side lanes behind the lead, walking
//    along the middle of the bridge (never across the void) to reach them,
//    and hangs back out of the lead's way when there's nothing to fill.
//  - While on the job bots don't push each other (no bot-on-bot collision)
//    and sneak; one that still ends up falling into the void is put back on
//    the bridge.
//  - If the lead drops out, the next bot takes over.
public class IslandBridgeController {
    public static final Material BLOCK = Material.END_STONE;

    private static final int PLACE_DELAY = 4;
    private static final double REACH = 4.4;
    private static final int STEP_TIMEOUT = 60;
    private static final int HANG_BACK = 3;

    // ---------------------------------------------------------------------
    // Shared job
    // ---------------------------------------------------------------------

    public static final class Job {
        final World world;
        final List<int[]> center;
        final List<int[]> sides;
        final Set<UUID> crew = new LinkedHashSet<>();
        final Map<Integer, UUID> sideOwner = new HashMap<>();
        final Set<Integer> sideSkipped = new java.util.HashSet<>();
        final int lowestY;
        public final UUID requester;
        UUID lead;
        int frontier = 0;
        int lastTick = Integer.MIN_VALUE;
        boolean cancelled;

        Job(UUID requester, World world, IslandBridge.Plan plan) {
            this.requester = requester;
            this.world = world;
            this.center = plan.center();
            this.sides = plan.sides();
            int low = Integer.MAX_VALUE;
            for (int[] c : center) low = Math.min(low, c[1]);
            this.lowestY = low;
        }

        boolean solid(int[] c) {
            return world.getBlockAt(c[0], c[1], c[2]).getType().isSolid();
        }

        // Once per tick: how far the middle line is built without a gap.
        void tick() {
            int now = Bukkit.getCurrentTick();
            if (now == lastTick) return;
            lastTick = now;
            while (frontier + 1 < center.size() && solid(center.get(frontier + 1))) frontier++;
        }

        boolean middleDone() {
            return frontier >= center.size() - 1;
        }

        boolean done() {
            if (!middleDone()) return false;
            for (int i = 0; i < sides.size(); i++) {
                if (!sideSkipped.contains(i) && !solid(sides.get(i))) return false;
            }
            return true;
        }

        public int length() {
            return center.size() - 1;
        }

        public void cancel() {
            cancelled = true;
            JOBS.remove(requester, this);
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();

    public static Job start(UUID requester, World world, IslandBridge.Plan plan) {
        Job old = JOBS.remove(requester);
        if (old != null) old.cancel();
        Job j = new Job(requester, world, plan);
        JOBS.put(requester, j);
        return j;
    }

    public static boolean stop(UUID requester) {
        Job j = JOBS.remove(requester);
        if (j == null) return false;
        j.cancel();
        return true;
    }

    // ---------------------------------------------------------------------
    // Per bot
    // ---------------------------------------------------------------------

    private final BotAIContext context;
    private Job job;
    private int mySide = -1;
    private int placeDelay;
    private int stepTicks;
    private int stepFrom = -1;
    private int repathCooldown;

    public IslandBridgeController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return job != null;
    }

    public Job job() {
        return job;
    }

    public String status() {
        if (job == null) return "idle";
        return (context.bot.getUUID().equals(job.lead) ? "lead " : "sides ")
                + job.frontier + "/" + job.length();
    }

    public void join(Job j, int blocksEach) {
        abort();
        job = j;
        UUID me = context.bot.getUUID();
        j.crew.add(me);
        if (j.lead == null) j.lead = me;
        Player p = context.bot.getBukkitPlayer();
        if (p != null) {
            try {
                p.setCollidable(false); // nobody gets shoved off the bridge
            } catch (Throwable ignored) {
            }
            int have = count(p);
            if (have < blocksEach) {
                giveBlocks(p, blocksEach - have);
            }
        }
        context.movementController.clearFormationOrder();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    public void abort() {
        if (job != null) {
            UUID me = context.bot.getUUID();
            job.crew.remove(me);
            if (me.equals(job.lead)) job.lead = job.crew.isEmpty() ? null : job.crew.iterator().next();
            job.sideOwner.values().removeIf(me::equals);
            if (job.crew.isEmpty()) job.cancel();
        }
        job = null;
        mySide = -1;
        stepFrom = -1;
        context.holdSneak = false;
        Player p = context.bot.getBukkitPlayer();
        if (p != null) {
            try {
                p.setCollidable(true);
            } catch (Throwable ignored) {
            }
        }
        ServerPlayer h = context.bot.getHandle();
        if (h != null && h.isShiftKeyDown()) h.setShiftKeyDown(false);
    }

    public boolean handle(Player botPlayer) {
        if (job == null) return false;
        ServerPlayer h = context.bot.getHandle();
        if (botPlayer == null || h == null || job.cancelled || botPlayer.getWorld() != job.world) {
            abort();
            return false;
        }
        job.tick();
        if (job.done()) {
            abort();
            return false;
        }
        if (repathCooldown > 0) repathCooldown--;
        if (placeDelay > 0) placeDelay--;
        context.navBranch = "ISLAND_BRIDGE";
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.suppressSprint = true;
        context.target = null; // over the void, the bridge comes first

        // Fell anyway: back onto the bridge before the void gets us.
        if (h.getDeltaMovement().y < -0.3 && overVoid(botPlayer)
                && botPlayer.getLocation().getY() < job.lowestY - 2) {
            int[] safe = job.center.get(Math.max(0, job.frontier - 1));
            context.bot.teleportTo(new Location(job.world, safe[0] + 0.5, safe[1] + 1, safe[2] + 0.5,
                    botPlayer.getLocation().getYaw(), 30f));
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            return true;
        }

        if (count(botPlayer) < 4) giveBlocks(botPlayer, 64);

        if (context.bot.getUUID().equals(job.lead) && !job.middleDone()) {
            tickLead(botPlayer, h);
        } else {
            tickSides(botPlayer, h);
        }
        return true;
    }

    // ---------------------------------------------------------------------

    private void tickLead(Player botPlayer, ServerPlayer h) {
        int f = job.frontier;
        // Stand a block back from the end (not on the edge) - unless the
        // bridge drops two blocks there and the next block would be out of
        // reach from that far up.
        int standIdx = Math.max(0, f - 1);
        if (job.center.get(standIdx)[1] - job.center.get(f + 1)[1] >= 2) standIdx = f;
        if (!standingOn(botPlayer, h, standIdx)) {
            walkAlong(botPlayer, h, standIdx);
            return;
        }
        context.holdSneak = true;
        int[] next = job.center.get(f + 1);
        Block nb = job.world.getBlockAt(next[0], next[1], next[2]);
        lookAt(botPlayer, nb);
        if (nb.getType().isSolid()) return; // frontier moves next tick
        if (placeDelay > 0) return;
        if (!place(botPlayer, h, nb)) {
            // Can't place there (protected): give up on the bridge.
            job.cancel();
            abort();
        }
    }

    private void tickSides(Player botPlayer, ServerPlayer h) {
        if (mySide >= 0 && (mySide >= job.sides.size() || job.solid(job.sides.get(mySide))
                || job.sideSkipped.contains(mySide))) {
            job.sideOwner.remove(mySide);
            mySide = -1;
        }
        if (mySide < 0) mySide = claimSide(botPlayer);
        if (mySide < 0) {
            // Nothing to fill right now: wait behind the lead, out of the way.
            int crewIdx = 0;
            for (UUID u : job.crew) {
                if (u.equals(context.bot.getUUID())) break;
                crewIdx++;
            }
            int wait = Math.max(0, job.frontier - HANG_BACK - crewIdx);
            if (!standingOn(botPlayer, h, wait)) walkAlong(botPlayer, h, wait);
            else context.holdSneak = true;
            return;
        }
        int[] side = job.sides.get(mySide);
        int idx = side[3];
        Block sb = job.world.getBlockAt(side[0], side[1], side[2]);
        int here = nearestIndex(botPlayer);
        if (eyeDist(botPlayer, sb) <= REACH && here >= 0 && Math.abs(here - idx) <= 2 && h.onGround()) {
            context.holdSneak = true;
            lookAt(botPlayer, sb);
            if (placeDelay > 0) return;
            if (!place(botPlayer, h, sb)) {
                job.sideSkipped.add(mySide);
                job.sideOwner.remove(mySide);
                mySide = -1;
            }
            return;
        }
        walkAlong(botPlayer, h, idx);
    }

    private int claimSide(Player botPlayer) {
        int best = -1;
        int here = Math.max(0, nearestIndex(botPlayer));
        int bestD = Integer.MAX_VALUE;
        for (int i = 0; i < job.sides.size(); i++) {
            int[] s = job.sides.get(i);
            if (s[3] > job.frontier) continue;               // no middle block to stand on yet
            if (s[3] >= job.frontier - 1 && !job.middleDone()) continue; // the lead's spot
            if (job.sideOwner.containsKey(i) || job.sideSkipped.contains(i)) continue;
            if (job.solid(s)) continue;
            int d = Math.abs(s[3] - here);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best >= 0) job.sideOwner.put(best, context.bot.getUUID());
        return best;
    }

    // ---------------------------------------------------------------------
    // Getting around: along the middle of the bridge one block at a time,
    // or (off the bridge) walking/pathing to its start.
    // ---------------------------------------------------------------------

    private boolean standingOn(Player botPlayer, ServerPlayer h, int idx) {
        int[] c = job.center.get(idx);
        Location l = botPlayer.getLocation();
        double off = Math.hypot(c[0] + 0.5 - l.getX(), c[2] + 0.5 - l.getZ());
        return off < 0.45 && Math.abs(l.getY() - (c[1] + 1)) < 0.6 && h.onGround();
    }

    // Index of the middle-line block we're on (or next to), -1 if we're not
    // on the bridge at all.
    private int nearestIndex(Player botPlayer) {
        Location l = botPlayer.getLocation();
        int best = -1;
        double bestD = 2.0 * 2.0;
        for (int i = 0; i <= Math.min(job.frontier, job.center.size() - 1); i++) {
            int[] c = job.center.get(i);
            double dx = c[0] + 0.5 - l.getX(), dy = c[1] + 1 - l.getY(), dz = c[2] + 0.5 - l.getZ();
            double d = dx * dx + dy * dy * 0.5 + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private void walkAlong(Player botPlayer, ServerPlayer h, int goalIdx) {
        int here = nearestIndex(botPlayer);
        if (here < 0) {
            // Not on the bridge yet: get to its start (on the island).
            int[] s = job.center.get(0);
            walkFar(botPlayer, h, new Location(job.world, s[0] + 0.5, s[1] + 1, s[2] + 0.5));
            context.holdSneak = false;
            return;
        }
        int nextIdx = here == goalIdx ? goalIdx : here + Integer.signum(goalIdx - here);
        // Already past the middle of `here`? head for the next one directly.
        int[] c = job.center.get(nextIdx);
        stepTo(botPlayer, h, nextIdx, c);
    }

    private void stepTo(Player botPlayer, ServerPlayer h, int idx, int[] c) {
        if (stepFrom != idx) {
            stepFrom = idx;
            stepTicks = 0;
        }
        Location l = botPlayer.getLocation();
        double dx = c[0] + 0.5 - l.getX(), dz = c[2] + 0.5 - l.getZ();
        double flat = Math.hypot(dx, dz);
        double rise = (c[1] + 1) - l.getY();
        if (++stepTicks > STEP_TIMEOUT) {
            // Wedged somehow: hop straight onto it.
            stepTicks = 0;
            context.bot.teleportTo(new Location(job.world, c[0] + 0.5, c[1] + 1, c[2] + 0.5,
                    l.getYaw(), l.getPitch()));
            return;
        }
        if (flat < 0.2 && Math.abs(rise) < 0.6) return;
        // Dropping down a step needs the sneak off (sneaking stops at edges);
        // everything else is done sneaking.
        context.holdSneak = rise > -0.5;
        if (!context.holdSneak && h.isShiftKeyDown()) h.setShiftKeyDown(false);
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
        if (flat > 0.05) {
            context.movementController.worldDirToInputs(h, dx / flat, dz / flat, flat > 0.8 ? 1.0f : 0.6f);
        }
        if (rise > 0.5 && h.onGround() && flat < 1.3) {
            if (h.isShiftKeyDown()) h.setShiftKeyDown(false);
            context.holdSneak = false;
            context.movementController.requestJump();
        }
    }

    private void walkFar(Player botPlayer, ServerPlayer h, Location dest) {
        MovementController mc = context.movementController;
        Location me = botPlayer.getLocation();
        double flat = Math.hypot(dest.getX() - me.getX(), dest.getZ() - me.getZ());
        if (flat < 5.0 && mc.canWalkStraightTo(dest)) {
            double dx = dest.getX() - me.getX(), dz = dest.getZ() - me.getZ();
            mc.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
            if (flat > 0.05) mc.worldDirToInputs(h, dx / flat, dz / flat, flat < 1.0 ? 0.5f : 1.0f);
            if (h.horizontalCollision && h.onGround()) mc.requestJump();
            return;
        }
        boolean exhausted = context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size();
        if (exhausted) {
            if (repathCooldown <= 0) {
                repathCooldown = 20;
                context.pathfindingController.calculatePathAsync(me, dest);
            }
        } else {
            mc.followPath();
        }
    }

    // ---------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------

    private static int count(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == BLOCK) n += it.getAmount();
        }
        return n;
    }

    private void giveBlocks(Player p, int amount) {
        while (amount > 0) {
            int stack = Math.min(64, amount);
            p.getInventory().addItem(new ItemStack(BLOCK, stack));
            amount -= stack;
        }
        context.packetBroadcaster.broadcastEquipment();
    }

    private boolean place(Player botPlayer, ServerPlayer h, Block at) {
        int slot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == BLOCK);
        if (slot < 0 || slot > 8) {
            giveBlocks(botPlayer, 64);
            slot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == BLOCK);
            if (slot < 0 || slot > 8) return true; // try again next time
        }
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        org.bukkit.block.BlockState replaced = at.getState();
        Block against = at.getRelative(org.bukkit.block.BlockFace.DOWN);
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.DOWN}) {
            Block o = at.getRelative(f);
            if (o.getType().isSolid()) {
                against = o;
                break;
            }
        }
        at.setType(BLOCK, true);
        org.bukkit.event.block.BlockPlaceEvent ev = new org.bukkit.event.block.BlockPlaceEvent(
                at, replaced, against, new ItemStack(BLOCK), botPlayer, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) {
            replaced.update(true, false);
            return false;
        }
        ItemStack held = botPlayer.getInventory().getItem(slot);
        if (held != null) {
            if (held.getAmount() <= 1) botPlayer.getInventory().setItem(slot, null);
            else held.setAmount(held.getAmount() - 1);
        }
        h.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(h, 0);
        try {
            job.world.playSound(at.getLocation().add(0.5, 0.5, 0.5),
                    at.getBlockData().getSoundGroup().getPlaceSound(), 1.0f, 0.8f);
        } catch (Throwable ignored) {
        }
        placeDelay = PLACE_DELAY + java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        return true;
    }

    // Nothing solid for 10 blocks under us.
    private static boolean overVoid(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        for (int d = 1; d <= 10; d++) {
            if (w.getBlockAt(l.getBlockX(), l.getBlockY() - d, l.getBlockZ()).getType().isSolid()) return false;
        }
        return true;
    }

    private static double eyeDist(Player p, Block b) {
        Location e = p.getEyeLocation();
        double dx = b.getX() + 0.5 - e.getX(), dy = b.getY() + 0.5 - e.getY(), dz = b.getZ() + 0.5 - e.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void lookAt(Player botPlayer, Block b) {
        Location e = botPlayer.getEyeLocation();
        double dx = b.getX() + 0.5 - e.getX(), dy = b.getY() + 0.5 - e.getY(), dz = b.getZ() + 0.5 - e.getZ();
        context.requestLook((float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, Math.hypot(dx, dz)))),
                BotAIContext.LOOK_COMBAT, false);
    }
}
