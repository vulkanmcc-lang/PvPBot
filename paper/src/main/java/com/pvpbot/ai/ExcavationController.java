package com.pvpbot.ai;

import com.pvpbot.mine.ExcavationJob;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

// One bot's part in an ExcavationJob: claim a block, get within reach of it,
// break it with the right tool (pickaxe / shovel / axe / hands), repeat.
// In DESTROY jobs a bot carrying TNT + flint & steel first works through the
// job's blast points: walk up, place TNT, light it, run, wait for the bang.
//
// Water: a block with water beside or above it is never simply broken (the
// water would pour into the dig). The bot plugs that water with a block
// first, then breaks it. A bot that ends up standing in water inside the
// job (a lake or river over the area) dives: it sinks to the bottom, digs
// three blocks straight down, caps the shaft above its head so the water
// can't follow, waits for the shaft to drain and carries on mining from
// underneath - the lake stays on top of a sealed roof.
public class ExcavationController {
    private enum Phase { IDLE, WORK, BLAST_PLACE, BLAST_FLEE, DIVE }

    private static final double REACH = 4.3;
    private static final double REACH_SQ = REACH * REACH;
    private static final int STALL_TICKS = 30;
    // A bot never sits on one block longer than this (plus the block's own
    // break time) - whatever the reason, it moves on to another.
    private static final int CELL_WATCHDOG_TICKS = 160;
    private static final int IDLE_GIVE_UP_TICKS = 400;
    private static final double FLEE_DISTANCE = 9.0;
    private static final int FUSE_TICKS = 80;

    private final BotAIContext context;

    private ExcavationJob job;
    private Phase phase = Phase.IDLE;
    private ExcavationJob.Cell cell;
    private ExcavationJob.BlastPoint blast;

    private Block breaking;
    private float breakProgress;
    private int breakTicks;
    private int lastStage = -1;

    private Location lastPos;
    private int stallTicks;
    private int idleTicks;
    private int repathCooldown;
    private int blastWaitTicks;
    private int blastCooldown;
    private int fleeTicks;
    private TNTPrimed lit;

    // Water handling
    private static final int DIVE_DEPTH = 3;
    private static final int DIVE_TIMEOUT = 500;
    private static final int DIVE_COOLDOWN = 120;
    private static final int MAX_DIVES = 8;
    private static final int LOW_AIR = 90;
    private static final int GIVE_SEAL_BLOCKS = 32;
    private static final int WET_RETRY_TICKS = 80;
    private int diveX, diveZ;
    private int diveStartY = Integer.MIN_VALUE;
    private int diveTicks;
    private int diveSealedAt = -1;
    private int diveCooldown;
    private int divesDone;
    private int dryTicks;
    private boolean wet;          // this job has water in it: be patient with cells
    private boolean gaveSealBlocks;

    // Performance
    private static final int CLAIM_RETRY_TICKS = 10;
    private static final int SPOT_REFRESH_TICKS = 40;
    private int claimCooldown;
    // "Do I carry the right tool for this?" per block type - the inventory
    // scan is the expensive part of claiming, and tools rarely change.
    private final java.util.Map<Material, Boolean> toolCache = new java.util.EnumMap<>(Material.class);
    private int toolCacheTick = Integer.MIN_VALUE;
    // Where to stand for the current cell (searched once, not every tick).
    private ExcavationJob.Cell spotCell;
    private Location spot;
    private int spotAge;
    private int cellTicks;

    public ExcavationController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return job != null;
    }

    public ExcavationJob job() {
        return job;
    }

    public String status() {
        if (job == null) return "idle";
        return phase + " " + job.mode + " " + job.broken() + "/" + job.total()
                + (job.blastPoints() > 0 ? " blasts " + job.blasted() + "/" + job.blastPoints() : "");
    }

    public void join(ExcavationJob job) {
        abort();
        this.job = job;
        this.phase = Phase.WORK;
        job.addCrew(context.bot.getUUID());
        wet = false;
        gaveSealBlocks = false;
        divesDone = 0;
        diveCooldown = 0;
        claimCooldown = 0;
        toolCache.clear();
        spotCell = null;
        spot = null;
        followCheck = 0;
        replans = 0;
        // A leftover "walk to this spot" order (come here / regroup) would
        // otherwise take priority over the work every tick.
        context.movementController.clearFormationOrder();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    public void abort() {
        clearStage();
        if (job != null) {
            job.release(cell);
            job.release(blast);
            job.removeCrew(context.bot.getUUID());
        }
        job = null;
        cell = null;
        blast = null;
        phase = Phase.IDLE;
        breaking = null;
        breakProgress = 0f;
        stallTicks = 0;
        idleTicks = 0;
        lit = null;
        diveStartY = Integer.MIN_VALUE;
        diveSealedAt = -1;
        context.waterSinkTicks = 0;
    }

    // A target only pulls a bot off its dig when fighting it is actually
    // possible right now: it's in sight and close, or it's hitting us.
    // An enemy under 10 blocks of stone is not a reason to stand there
    // jumping at the floor - keep digging (possibly toward them).
    private boolean canFightNow(Player botPlayer) {
        Player t = context.target;
        if (t == null || t.getWorld() != botPlayer.getWorld()) return false;
        if (context.tickCounter - context.lastDamageTime < 40 && context.lastDamager == t) return true;
        double d = t.getLocation().distance(botPlayer.getLocation());
        boolean sees = context.combatController.hasLineOfSight(botPlayer, t);
        // Close AND nothing between us: swing. Close with a block in between
        // (they're in a 1-wide gap behind it): that block goes first.
        if (d <= context.settings.getReach() + 0.5) return sees;
        // Digging our way to them: seeing them (through a 1-wide gap, a
        // window, over a wall) doesn't mean we can get there - keep digging
        // until we're close enough to swing.
        if (job != null && job.mode == ExcavationJob.Mode.DIG_TO) return false;
        return d <= 12.0 && sees;
    }

    // ---------------------------------------------------------------------
    // Digging to a player (voice "mine down to X", or a bot's own chase dig
    // toward an enemy it can't reach): follow them as they move.
    // ---------------------------------------------------------------------

    private static final int FOLLOW_CHECK_TICKS = 20;
    private static final double ARRIVE_DISTANCE = 2.8;
    private static final double REPLAN_DRIFT = 3.0;
    private static final int MAX_REPLANS = 40;
    private int followCheck;
    private int replans;

    // Start digging toward `target` on our own (combat: can't reach them).
    public boolean startChase(Player botPlayer, Player target) {
        if (target == null || target.getWorld() != botPlayer.getWorld()) return false;
        java.util.List<java.util.UUID> crew = java.util.List.of(context.bot.getUUID());
        ExcavationJob j = ExcavationJob.startDigTo(context.bot.getUUID(), crew,
                java.util.List.of(botPlayer.getLocation()), target.getLocation(), target.getUniqueId());
        // Only worth it if there's actually something in the way.
        if (j.breakableLeft() == 0) {
            j.cancel();
            return false;
        }
        j.chase = true;
        join(j);
        return true;
    }

    public boolean isChasing() {
        return job != null && job.chase;
    }

    // false = this bot is done with the job (arrived / target gone).
    private boolean followDigTarget(Player botPlayer) {
        if (job.digTarget == null) return true;
        Player t = org.bukkit.Bukkit.getPlayer(job.digTarget);
        if (t == null || !t.isOnline() || t.isDead() || t.getWorld() != botPlayer.getWorld()) {
            abort();
            return false;
        }
        // A chase dig belongs to one fight: target changed, chase over.
        if (job.chase && context.target != null && context.target != t) {
            abort();
            return false;
        }
        Location me = botPlayer.getLocation();
        if (me.distance(t.getLocation()) <= Math.max(ARRIVE_DISTANCE, context.settings.getReach())
                && context.combatController.hasLineOfSight(botPlayer, t)) {
            // Through. An enemy gets fought from here; a friend just got
            // company.
            abort();
            return false;
        }
        if (--followCheck > 0) return true;
        followCheck = FOLLOW_CHECK_TICKS;
        Location end = job.laneEnd(context.bot.getUUID());
        boolean drifted = end == null || end.distance(t.getLocation()) > REPLAN_DRIFT;
        boolean exhausted = job.laneLeft(context.bot.getUUID()) == 0;
        if ((drifted || exhausted) && replans < MAX_REPLANS) {
            if (cell != null) {
                job.release(cell);
                cell = null;
            }
            clearStage();
            breaking = null;
            spotCell = null;
            claimCooldown = 0;
            replans++;
            job.replanLane(context.bot.getUUID(), me, t.getLocation());
        }
        return true;
    }

    public static boolean hasTntKit(Player p) {
        PlayerInventory inv = p.getInventory();
        return inv.contains(Material.TNT) && inv.contains(Material.FLINT_AND_STEEL);
    }

    public boolean handleExcavation(Player botPlayer) {
        if (job == null) return false;
        if (botPlayer == null) { abort(); return false; }
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        // A lit fuse always gets finished - never stand next to your own TNT
        // because an enemy showed up.
        if (phase == Phase.BLAST_FLEE) {
            job.tick();
            tickFlee(botPlayer, handle);
            return true;
        }

        if (context.fleeing || (context.target != null && canFightNow(botPlayer))) {
            // Fight first, keep the job; hand our claim back meanwhile.
            job.release(cell);
            cell = null;
            clearStage();
            breaking = null;
            return false;
        }

        job.tick();
        if (job.isFinished()) {
            abort();
            return false;
        }
        if (job.mode == ExcavationJob.Mode.DIG_TO && !followDigTarget(botPlayer)) {
            return false;
        }
        if (repathCooldown > 0) repathCooldown--;
        if (blastCooldown > 0) blastCooldown--;
        if (diveCooldown > 0) diveCooldown--;
        context.suppressSprint = true;

        if (phase == Phase.DIVE) {
            tickDive(botPlayer, handle);
            return true;
        }
        if (phase == Phase.WORK && shouldDive(botPlayer)) {
            startDive(botPlayer);
            tickDive(botPlayer, handle);
            return true;
        }

        if (phase == Phase.BLAST_PLACE) {
            tickBlastPlace(botPlayer, handle);
            return true;
        }

        if (job.mode == ExcavationJob.Mode.DESTROY && blastCooldown <= 0 && cell == null
                && hasTntKit(botPlayer) && job.hasBlastsLeft()) {
            blast = job.claimBlast(context.bot.getUUID(), botPlayer.getLocation());
            if (blast != null) {
                phase = Phase.BLAST_PLACE;
                blastWaitTicks = 0;
                stallTicks = 0;
                tickBlastPlace(botPlayer, handle);
                return true;
            }
        }

        if (cell == null) {
            if (claimCooldown > 0) {
                claimCooldown--;
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                if (++idleTicks > IDLE_GIVE_UP_TICKS) abort();
                return true;
            }
            cell = job.claim(context.bot.getUUID(), botPlayer, b -> canBreakCached(botPlayer, b));
            stallTicks = 0;
            if (cell == null && job.chase) {
                // Nothing left in the way on this route: back to fighting.
                abort();
                context.chaseCooldown = Math.max(context.chaseCooldown, 40);
                return false;
            }
            if (cell == null) {
                claimCooldown = CLAIM_RETRY_TICKS;
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                // Nothing we can do right now (other bots have the rest, or
                // it all needs tools we don't carry): leave eventually.
                idleTicks++;
                if (idleTicks > IDLE_GIVE_UP_TICKS) abort();
                return true;
            }
            idleTicks = 0;
            cellTicks = 0;
        }

        tickWork(botPlayer, handle);
        return true;
    }

    // =====================================================================
    // Breaking blocks
    // =====================================================================

    private static final int MIN_BREAK_TICKS = 5;
    private static final int BETWEEN_BLOCKS_TICKS = 4;
    private int breakTotal = 1;
    private int pauseTicks = 0;

    // Survival break time in ticks, worked out the way vanilla does it
    // (tool tier + Efficiency, Haste / Mining Fatigue, off-ground and
    // underwater penalties, wrong-tool penalty), with a floor so nothing
    // pops instantly - a bot swinging a god pickaxe still visibly mines.
    private int breakTicksFor(Player p, Block b) {
        // Vanilla's own mining time for what's in hand right now (tool,
        // wrong-tool penalty, Efficiency, Haste / Mining Fatigue, in water,
        // off the ground).
        int ticks = VanillaWorld.breakTicks(context, b);
        return ticks < 0 ? 600 : ticks;
    }

    private static double toolSpeed(Material m) {
        String n = m.name();
        if (m == Material.SHEARS) return 2.0;
        if (n.endsWith("_SWORD")) return 1.5;
        if (n.startsWith("NETHERITE")) return 9.0;
        if (n.startsWith("DIAMOND")) return 8.0;
        if (n.startsWith("IRON")) return 6.0;
        if (n.startsWith("STONE")) return 4.0;
        if (n.startsWith("GOLDEN")) return 12.0;
        if (n.startsWith("WOODEN")) return 2.0;
        return 1.0;
    }

    private void tickWork(Player botPlayer, ServerPlayer handle) {
        if (pauseTicks > 0) {
            pauseTicks--;
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return;
        }
        Block target = job.world.getBlockAt(cell.x, cell.y, cell.z);
        if (!ExcavationJob.breakable(target.getType())) {
            job.complete(cell);
            cell = null;
            return;
        }
        if (++cellTicks > CELL_WATCHDOG_TICKS + (breaking != null ? breakTotal : 0)) {
            giveUpOnCell();
            return;
        }

        Location eye = botPlayer.getEyeLocation();
        double distSq = distSq(eye, target);
        boolean blockedInReach = false;
        if (distSq <= REACH_SQ) {
            Block blocker = firstSolidBetween(eye, target);
            if (blocker == null) {
                if (standingOn(botPlayer, target) && !safeToDropOnto(target)) {
                    job.fail(cell);
                    cell = null;
                    return;
                }
                mine(botPlayer, handle, target, true);
                return;
            }
            if (job.mayDig(blocker.getX(), blocker.getY(), blocker.getZ())
                    && ExcavationJob.breakable(blocker.getType())) {
                mine(botPlayer, handle, blocker, false); // it's part of the job anyway
                return;
            }
            // Close enough but something outside the dig (terrain past the
            // edge, a tree) is in the way: we need a different spot.
            blockedInReach = true;
        }

        // Walk to somewhere we can reach and see it from. No such spot at
        // all (a treetop, a roof out of reach): skip it now instead of
        // walking into the wall for seconds.
        if (spotCell != cell || ++spotAge > SPOT_REFRESH_TICKS) {
            spot = standSpotFor(botPlayer, target);
            spotCell = cell;
            spotAge = 0;
        }
        Location spot = this.spot;
        if (blockedInReach) {
            Location me = botPlayer.getLocation();
            boolean alreadyThere = spot != null
                    && Math.hypot(spot.getX() - me.getX(), spot.getZ() - me.getZ()) < 0.6
                    && Math.abs(spot.getY() - me.getY()) < 1.0;
            if (spot == null || alreadyThere) {
                // Nowhere better to stand from here: let someone else try it.
                giveUpOnCell();
                return;
            }
        }
        if (spot == null && distSq > REACH_SQ) {
            if (wet) {
                // Under water: it may become reachable once the dry part of
                // the dig gets to it. Try others first, come back later.
                job.defer(cell, WET_RETRY_TICKS);
            } else {
                job.fail(cell);
                job.fail(cell);
                job.fail(cell);
            }
            cell = null;
            return;
        }
        walkTo(botPlayer, spot != null ? spot : target.getLocation().add(0.5, 1.0, 0.5));
        if (stalled(botPlayer)) giveUpOnCell();
    }

    // This bot can't get this block done: hand it on (other bots may reach
    // it from their side) and pick another straight away.
    private void giveUpOnCell() {
        if (cell != null) {
            if (wet) job.defer(cell, WET_RETRY_TICKS);
            else job.unreachableFor(cell, context.bot.getUUID());
        }
        cell = null;
        spotCell = null;
        stallTicks = 0;
        cellTicks = 0;
        clearStage();
        breaking = null;
        claimCooldown = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private void mine(Player botPlayer, ServerPlayer handle, Block block, boolean isCell) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        stallTicks = 0;

        if (breaking == null || !breaking.equals(block)) {
            // Plug any water next to it before opening it up.
            int w = sealWaterAround(botPlayer, handle, block);
            if (w == 0) return; // placed a plug this tick - break it next time
            if (w < 0) {
                if (cell != null) {
                    if (wet) job.defer(cell, WET_RETRY_TICKS);
                    else job.fail(cell);
                    cell = null;
                }
                return;
            }
        }

        int r = breakStep(botPlayer, handle, block);
        if (r == 0) return;
        int bx = block.getX(), by = block.getY(), bz = block.getZ();
        if (r < 0) {
            // Protected or refused - don't keep hammering it.
            if (cell != null) {
                job.fail(cell);
                cell = null;
            }
            return;
        }
        if (isCell) {
            job.complete(cell);
            cell = null;
        } else {
            job.noteBroken(bx, by, bz);
        }
    }

    // One tick of breaking `block` at survival speed. 0 = still going,
    // 1 = broken, -1 = refused (protection plugin, unbreakable).
    private int breakStep(Player botPlayer, ServerPlayer handle, Block block) {
        lookAt(botPlayer, block.getLocation().add(0.5, 0.5, 0.5));

        if (breaking == null || !breaking.equals(block)) {
            clearStage();
            breaking = block;
            breakProgress = 0f;
            breakTicks = 0;
            equipBestTool(botPlayer, block);
            context.packetBroadcaster.broadcastEquipment();
        }

        if (breakTicks++ % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }

        if (breakTicks == 1) breakTotal = breakTicksFor(botPlayer, block);
        breakProgress = (float) breakTicks / Math.max(1, breakTotal);
        sendStage(block, (int) (breakProgress * 10));
        if (breakTicks < breakTotal) return 0;
        pauseTicks = BETWEEN_BLOCKS_TICKS;

        clearStage();
        breaking = null;
        breakProgress = 0f;

        // Real survival break: events, protection plugins, drops, tool wear.
        boolean ok = VanillaWorld.breakBlock(context, block);

        if (!ok && ExcavationJob.breakable(block.getType())) return -1;
        return 1;
    }

    private boolean canBreakCached(Player p, Block b) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        if (now - toolCacheTick > 100) { // re-check every 5 s (picked up a pickaxe?)
            toolCache.clear();
            toolCacheTick = now;
        }
        return toolCache.computeIfAbsent(b.getType(), m -> canBreak(p, b));
    }

    // Can this bot break it in a useful way? Blocks that only drop with the
    // right tool (stone, ores...) need that tool; everything else can go by
    // hand if need be.
    private static boolean canBreak(Player p, Block b) {
        boolean needsTool;
        try {
            needsTool = b.getBlockData().requiresCorrectToolForDrops();
        } catch (Throwable t) {
            needsTool = false;
        }
        if (!needsTool) return true;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && !it.getType().isAir() && b.isPreferredTool(it)) return true;
        }
        return false;
    }

    private void equipBestTool(Player p, Block b) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        int bestTier = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            if (!b.isPreferredTool(it)) continue;
            int tier = tier(it.getType());
            if (tier > bestTier) {
                bestTier = tier;
                best = i;
            }
        }
        if (best < 0) return;
        int slot = best;
        if (slot > 8) {
            int held = inv.getHeldItemSlot();
            ItemStack tool = inv.getItem(slot);
            inv.setItem(slot, inv.getItem(held));
            inv.setItem(held, tool);
            slot = held;
        }
        inv.setHeldItemSlot(slot);
    }

    private static int tier(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 6;
        if (n.startsWith("DIAMOND")) return 5;
        if (n.startsWith("IRON")) return 4;
        if (n.startsWith("STONE")) return 3;
        if (n.startsWith("GOLDEN")) return 2;
        if (n.startsWith("WOODEN")) return 1;
        return 0;
    }

    // =====================================================================
    // Water
    // =====================================================================

    // Water, or something that is water as far as flooding goes (kelp,
    // seagrass, bubble columns, waterlogged blocks).
    static boolean isWater(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP
                || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) {
            return true;
        }
        return b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    // Can a plug block simply be put there (it's liquid/plant, not a
    // waterlogged stair or the like)?
    private static boolean pluggable(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN || m == Material.KELP
                || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS;
    }

    private static final org.bukkit.block.BlockFace[] FLOOD_FACES = {
            org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};

    // Before `block` is broken: every water block that would flow into the
    // hole (above it or beside it) gets a plug. 1 = nothing to do, 0 = placed
    // a plug this tick, -1 = there's water we can't plug (out of reach,
    // waterlogged, no blocks) - leave this block alone.
    private int sealWaterAround(Player botPlayer, ServerPlayer handle, Block block) {
        Location eye = botPlayer.getEyeLocation();
        Block feet = botPlayer.getLocation().getBlock();
        Block head = feet.getRelative(0, 1, 0);
        for (org.bukkit.block.BlockFace f : FLOOD_FACES) {
            Block n = block.getRelative(f);
            boolean lava = n.getType() == Material.LAVA;
            if (!lava && !isWater(n)) continue;
            // Already standing in that water: plugging it would bury us, and
            // we're wet anyway - the dive logic deals with that.
            if (n.equals(feet) || n.equals(head)) continue;
            if (!pluggable(n)) return -1;
            if (distSq(eye, n) > 5.0 * 5.0) return -1;
            if (!placeSeal(botPlayer, handle, n)) return -1;
            return 0;
        }
        return 1;
    }

    // Put a plain block into `at` (water) with a real place event.
    private boolean placeSeal(Player botPlayer, ServerPlayer handle, Block at) {
        int slot = sealSlot(botPlayer);
        if (slot < 0 && !gaveSealBlocks && InventoryController.freeBuildBlocks()) {
            // Nothing to plug with: same deal as pillaring - hand out some
            // cobblestone once so the job doesn't just flood.
            context.inventoryController.giveBuildBlocks(botPlayer, GIVE_SEAL_BLOCKS);
            gaveSealBlocks = true;
            slot = sealSlot(botPlayer);
        }
        if (slot < 0 || slot > 8) return false;

        Block against = at.getRelative(org.bukkit.block.BlockFace.DOWN);
        for (org.bukkit.block.BlockFace f : FLOOD_FACES) {
            Block o = at.getRelative(f.getOppositeFace());
            if (o.getType().isSolid()) {
                against = o;
                break;
            }
        }
        // A real right click on a solid face next to the water.
        if (!VanillaWorld.place(context, botPlayer, slot, at, against, null)) return false;
        job.markSealed(at.getX(), at.getY(), at.getZ());
        wet = true;
        pauseTicks = Math.max(pauseTicks, 2);
        clearStage();
        breaking = null;
        return true;
    }

    // Hotbar slot of the block to plug water with - this bot's build block
    // (end stone for random-named bots, plain blocks for the rest).
    private int sealSlot(Player botPlayer) {
        return context.inventoryController.findBuildBlockSlot(botPlayer);
    }

    // In water inside the job (or right at its edge) with something diggable
    // under the water: time to go under it.
    private boolean shouldDive(Player botPlayer) {
        if (job.isLaneJob() || job.hasLane(context.bot.getUUID())) return false;
        if (diveCooldown > 0 || divesDone >= MAX_DIVES) return false;
        Location l = botPlayer.getLocation();
        if (!isWater(l.getBlock())) return false;
        int x = l.getBlockX(), z = l.getBlockZ();
        if (x < job.minX - 2 || x > job.maxX + 2 || z < job.minZ - 2 || z > job.maxZ + 2) return false;
        int y = l.getBlockY();
        for (int d = 1; d <= 8; d++) {
            Block b = job.world.getBlockAt(x, y - d, z);
            if (isWater(b) && pluggable(b)) continue;
            return ExcavationJob.breakable(b.getType()) && !job.isSealed(b.getX(), b.getY(), b.getZ());
        }
        return false;
    }

    private void startDive(Player botPlayer) {
        job.release(cell);
        cell = null;
        clearStage();
        breaking = null;
        breakProgress = 0f;
        phase = Phase.DIVE;
        Location l = botPlayer.getLocation();
        diveX = l.getBlockX();
        diveZ = l.getBlockZ();
        diveStartY = Integer.MIN_VALUE;
        diveTicks = 0;
        diveSealedAt = -1;
        dryTicks = 0;
        wet = true;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private void endDive(boolean ok) {
        phase = Phase.WORK;
        divesDone++;
        diveCooldown = ok ? 20 : DIVE_COOLDOWN;
        diveStartY = Integer.MIN_VALUE;
        diveSealedAt = -1;
        clearStage();
        breaking = null;
        context.waterSinkTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private void tickDive(Player botPlayer, ServerPlayer handle) {
        diveTicks++;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        Location l = botPlayer.getLocation();
        int feetY = l.getBlockY();
        Block feet = job.world.getBlockAt(diveX, feetY, diveZ);
        Block head = feet.getRelative(0, 1, 0);

        if (diveSealedAt >= 0) {
            // Capped: wait for the water left in the shaft to drain away.
            boolean dry = !isWater(feet) && !isWater(head);
            dryTicks = dry ? dryTicks + 1 : 0;
            if (dryTicks > 6 || diveTicks - diveSealedAt > 80) endDive(true);
            return;
        }
        context.waterSinkTicks = 2;
        if (diveTicks > DIVE_TIMEOUT) {
            endDive(false);
            return;
        }

        // Stay over the shaft. Drifted off it before digging started: dig
        // where we are instead.
        if (l.getBlockX() != diveX || l.getBlockZ() != diveZ) {
            if (diveStartY == Integer.MIN_VALUE || diveStartY - feetY <= 0) {
                diveX = l.getBlockX();
                diveZ = l.getBlockZ();
                diveStartY = Integer.MIN_VALUE;
                feet = job.world.getBlockAt(diveX, feetY, diveZ);
            }
        }
        double cdx = diveX + 0.5 - l.getX(), cdz = diveZ + 0.5 - l.getZ();
        double off = Math.hypot(cdx, cdz);
        if (off > 0.2) {
            context.movementController.worldDirToInputs(handle, cdx / off, cdz / off, 0.35f);
        }

        Block below = job.world.getBlockAt(diveX, feetY - 1, diveZ);
        boolean onBottom = handle.onGround() || below.getType().isSolid();
        if (diveStartY == Integer.MIN_VALUE) {
            if (!onBottom) return; // still sinking
            diveStartY = feetY;
        }
        int depth = diveStartY - feetY;
        boolean lowAir = botPlayer.getRemainingAir() < LOW_AIR;

        if (depth >= DIVE_DEPTH || (lowAir && depth >= 2)) {
            capShaft(botPlayer, handle, feetY);
            return;
        }
        if (lowAir) {
            // Not deep enough for a cap to hold the water back - surface.
            endDive(false);
            return;
        }
        if (!onBottom) return; // dropping into the block we just dug

        Material under = job.world.getBlockAt(diveX, feetY - 2, diveZ).getType();
        boolean diggable = ExcavationJob.breakable(below.getType())
                && !job.isSealed(below.getX(), below.getY(), below.getZ())
                && under != Material.LAVA && under != Material.MAGMA_BLOCK
                && !(under.isAir() && job.world.getBlockAt(diveX, feetY - 3, diveZ).getType().isAir());
        if (!diggable) {
            if (depth >= 2) capShaft(botPlayer, handle, feetY);
            else endDive(false);
            return;
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }
        int r = breakStep(botPlayer, handle, below);
        if (r > 0) {
            if (job.isCell(below.getX(), below.getY(), below.getZ())) {
                job.noteBroken(below.getX(), below.getY(), below.getZ());
            }
        } else if (r < 0) {
            if (depth >= 2) capShaft(botPlayer, handle, feetY);
            else endDive(false);
        }
    }

    // Put the lid on: the block right above the head.
    private void capShaft(Player botPlayer, ServerPlayer handle, int feetY) {
        Block cap = job.world.getBlockAt(diveX, feetY + 2, diveZ);
        if (cap.getType().isSolid()) {
            job.markSealed(cap.getX(), cap.getY(), cap.getZ());
            diveSealedAt = diveTicks;
            return;
        }
        if (!pluggable(cap) && !cap.getType().isAir()) {
            endDive(false);
            return;
        }
        if (placeSeal(botPlayer, handle, cap)) {
            diveSealedAt = diveTicks;
            dryTicks = 0;
        } else {
            endDive(false);
        }
    }

    // =====================================================================
    // TNT
    // =====================================================================

    private void tickBlastPlace(Player botPlayer, ServerPlayer handle) {
        if (blast == null) { phase = Phase.WORK; return; }
        if (!hasTntKit(botPlayer)) {
            job.release(blast);
            blast = null;
            phase = Phase.WORK;
            return;
        }
        Block spot = job.world.getBlockAt(blast.x, blast.y, blast.z);
        Location center = spot.getLocation().add(0.5, 0.5, 0.5);

        if (distSq(botPlayer.getEyeLocation(), spot) > 3.5 * 3.5) {
            walkTo(botPlayer, center);
            if (stalled(botPlayer)) {
                job.completeBlast(blast, false); // can't get there - skip it
                blast = null;
                phase = Phase.WORK;
            }
            return;
        }
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        lookAt(botPlayer, center);

        // Never blow up a player, and give other bots a moment to clear out.
        boolean playerNear = false, botNear = false;
        for (Entity e : job.world.getNearbyEntities(center, 6, 6, 6)) {
            if (!(e instanceof LivingEntity) || e.getUniqueId().equals(context.bot.getUUID())) continue;
            if (e instanceof Player pl) {
                boolean isBot = com.pvpbot.PvPBotPlugin.getInstance().getBotManager()
                        .getBots().containsKey(pl.getUniqueId());
                if (isBot) botNear = true;
                else playerNear = true;
            }
        }
        if (playerNear || (botNear && blastWaitTicks < 60)) {
            if (++blastWaitTicks > 100) {
                job.release(blast);
                blast = null;
                phase = Phase.WORK;
                blastCooldown = 200;
            }
            return;
        }

        if (!(spot.getType().isAir() || !spot.getType().isSolid())) {
            job.completeBlast(blast, false);
            blast = null;
            phase = Phase.WORK;
            return;
        }

        // Place the TNT and light it with the flint and steel, both as real
        // right clicks: vanilla primes it (80-tick fuse, the bot as its
        // source) and wears the flint and steel.
        int tntSlot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.TNT);
        if (tntSlot < 0 || tntSlot > 8) return;
        if (!VanillaWorld.place(context, botPlayer, tntSlot, spot, spot.getRelative(0, -1, 0), null)) {
            job.completeBlast(blast, false);
            blast = null;
            phase = Phase.WORK;
            return;
        }

        int fsSlot = context.inventoryController.ensureInHotbar(botPlayer,
                it -> it.getType() == Material.FLINT_AND_STEEL);
        boolean litIt = fsSlot >= 0 && fsSlot <= 8
                && VanillaWorld.useOn(context, botPlayer, fsSlot, spot, null);
        if (!litIt || spot.getType() == Material.TNT) {
            // Couldn't light it - mine the TNT back up rather than litter.
            if (spot.getType() == Material.TNT) VanillaWorld.breakBlock(context, spot);
            job.completeBlast(blast, false);
            blast = null;
            phase = Phase.WORK;
            return;
        }
        lit = null;
        for (org.bukkit.entity.Entity e : job.world.getNearbyEntities(
                spot.getLocation().add(0.5, 0.5, 0.5), 1.5, 1.5, 1.5)) {
            if (e instanceof TNTPrimed t) {
                lit = t;
                break;
            }
        }

        phase = Phase.BLAST_FLEE;
        fleeTicks = 0;
    }

    private void tickFlee(Player botPlayer, ServerPlayer handle) {
        fleeTicks++;
        if (blast == null) { phase = Phase.WORK; return; }
        Location c = new Location(job.world, blast.x + 0.5, blast.y, blast.z + 0.5);
        Location me = botPlayer.getLocation();
        double dx = me.getX() - c.getX(), dz = me.getZ() - c.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);

        if (d < FLEE_DISTANCE && fleeTicks < FUSE_TICKS) {
            if (d < 0.05) { dx = 1; dz = 0; d = 1; }
            context.suppressSprint = false;
            context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
            context.movementController.worldDirToInputs(handle, dx / d, dz / d, 1.0f);
            if (context.bot.getHandle().horizontalCollision && handle.onGround()) {
                context.movementController.requestJump();
            }
        } else {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            lookAt(botPlayer, c);
        }

        boolean gone = lit == null || !lit.isValid() || lit.isDead();
        if ((gone && fleeTicks > 5) || fleeTicks > FUSE_TICKS + 40) {
            job.completeBlast(blast, true);
            blast = null;
            lit = null;
            phase = Phase.WORK;
            blastCooldown = 10;
        }
    }

    // =====================================================================
    // Movement / geometry
    // =====================================================================

    // Somewhere to stand that can reach and see `target`. Candidates are
    // tried nearest-first with the cheap checks first, so the ray casts
    // (the expensive part) only run until the first good spot turns up.
    private Location standSpotFor(Player botPlayer, Block target) {
        Location me = botPlayer.getLocation();
        double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
        List<double[]> cands = new java.util.ArrayList<>();
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;
                    int x = target.getX() + dx, y = target.getY() + dy, z = target.getZ() + dz;
                    double ex = x + 0.5 - tx, ey = y + 1.62 - ty, ez = z + 0.5 - tz;
                    if (ex * ex + ey * ey + ez * ez > REACH_SQ * 0.85) continue;
                    double mx = x + 0.5 - me.getX(), my = y - me.getY(), mz = z + 0.5 - me.getZ();
                    cands.add(new double[]{mx * mx + my * my + mz * mz, x, y, z});
                }
            }
        }
        cands.sort(java.util.Comparator.comparingDouble(c -> c[0]));
        Location dryBest = null, wetBest = null;
        double wetD = Double.MAX_VALUE;
        for (double[] c : cands) {
            if (dryBest != null) break;
            // A dry spot farther than this loses to the wet one anyway.
            if (wetBest != null && c[0] > wetD) break;
            int x = (int) c[1], y = (int) c[2], z = (int) c[3];
            if (!standable(x, y, z)) continue;
            Location feet = new Location(job.world, x + 0.5, y, z + 0.5);
            if (firstSolidBetween(feet.clone().add(0, 1.62, 0), target) != null) continue;
            // Dry spots first: standing in water means mining at a fifth of
            // the speed (and a dive).
            boolean wetSpot = isWater(job.world.getBlockAt(x, y, z)) || isWater(job.world.getBlockAt(x, y + 1, z));
            if (!wetSpot) {
                dryBest = feet;
            } else if (wetBest == null) {
                wetBest = feet;
                wetD = c[0] + 60.0;
            }
        }
        return dryBest != null ? dryBest : wetBest;
    }

    private boolean standable(int x, int y, int z) {
        Material floor = job.world.getBlockAt(x, y - 1, z).getType();
        if (!floor.isSolid() || floor == Material.MAGMA_BLOCK || floor == Material.CACTUS) return false;
        Material feet = job.world.getBlockAt(x, y, z).getType();
        Material head = job.world.getBlockAt(x, y + 1, z).getType();
        return !feet.isSolid() && feet != Material.LAVA && !head.isSolid() && head != Material.LAVA;
    }

    private boolean standingOn(Player p, Block b) {
        Location l = p.getLocation();
        return l.getBlockX() == b.getX() && l.getBlockZ() == b.getZ() && l.getBlockY() - 1 == b.getY();
    }

    // Breaking the block under our feet: fine if we land within 3 blocks on
    // something that isn't lava.
    private boolean safeToDropOnto(Block b) {
        for (int d = 1; d <= 4; d++) {
            Material m = job.world.getBlockAt(b.getX(), b.getY() - d, b.getZ()).getType();
            if (m == Material.LAVA || m == Material.FIRE || m == Material.MAGMA_BLOCK) return false;
            if (m.isSolid() || m == Material.WATER) return true;
        }
        return false;
    }

    private Block firstSolidBetween(Location eye, Block target) {
        double tx = target.getX() + 0.5, ty = target.getY() + 0.5, tz = target.getZ() + 0.5;
        double dx = tx - eye.getX(), dy = ty - eye.getY(), dz = tz - eye.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(len / 0.2);
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = (int) Math.floor(eye.getX() + dx * t);
            int y = (int) Math.floor(eye.getY() + dy * t);
            int z = (int) Math.floor(eye.getZ() + dz * t);
            if (x == lx && y == ly && z == lz) continue;
            lx = x;
            ly = y;
            lz = z;
            if (x == target.getX() && y == target.getY() && z == target.getZ()) return null;
            Block b = job.world.getBlockAt(x, y, z);
            if (b.getType().isSolid()) return b;
        }
        return null;
    }

    private void walkTo(Player botPlayer, Location dest) {
        MovementController mc = context.movementController;
        Location me = botPlayer.getLocation();
        double flat = Math.hypot(dest.getX() - me.getX(), dest.getZ() - me.getZ());
        if (flat < 0.22 && Math.abs(dest.getY() - me.getY()) < 1.2) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return;
        }
        if (flat < 6.0 && mc.canWalkStraightTo(dest)) {
            double dx = dest.getX() - me.getX(), dz = dest.getZ() - me.getZ();
            mc.easeYawTo((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.worldDirToInputs(context.bot.getHandle(), dx / flat, dz / flat, flat < 1.5 ? 0.5f : 1.0f);
            if (context.bot.getHandle().horizontalCollision && context.bot.getHandle().onGround()) {
                mc.requestJump();
            }
            return;
        }
        boolean exhausted = context.currentPath.isEmpty()
                || context.pathNodeIndex >= context.currentPath.size();
        if (exhausted) {
            if (repathCooldown <= 0) {
                repathCooldown = 20;
                context.pathfindingController.calculatePathAsync(me, dest);
            }
            context.forwardInput = 0f;
            context.strafeInput = 0f;
        } else {
            mc.followPath();
        }
    }

    private boolean stalled(Player botPlayer) {
        Location now = botPlayer.getLocation();
        if (lastPos != null && lastPos.getWorld() == now.getWorld()
                && lastPos.distanceSquared(now) > 0.5 * 0.5) {
            lastPos = now;
            stallTicks = 0;
            return false;
        }
        if (lastPos == null) lastPos = now;
        return ++stallTicks > STALL_TICKS;
    }

    private void lookAt(Player botPlayer, Location at) {
        Location eye = botPlayer.getEyeLocation();
        double dx = at.getX() - eye.getX(), dy = at.getY() - eye.getY(), dz = at.getZ() - eye.getZ();
        context.requestLook((float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, Math.hypot(dx, dz)))),
                BotAIContext.LOOK_COMBAT, false);
    }

    private static double distSq(Location eye, Block b) {
        double dx = b.getX() + 0.5 - eye.getX(), dy = b.getY() + 0.5 - eye.getY(), dz = b.getZ() + 0.5 - eye.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void sendStage(Block block, int stage) {
        stage = Math.max(0, Math.min(9, stage));
        if (stage == lastStage) return;
        lastStage = stage;
        sendDestroyPacket(block.getX(), block.getY(), block.getZ(), stage);
    }

    private void clearStage() {
        if (lastStage >= 0 && breaking != null) {
            sendDestroyPacket(breaking.getX(), breaking.getY(), breaking.getZ(), -1);
        }
        lastStage = -1;
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
