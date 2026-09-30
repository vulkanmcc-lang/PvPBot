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
import org.bukkit.inventory.meta.Damageable;

// One bot's part in an ExcavationJob: claim a block, get within reach of it,
// break it with the right tool (pickaxe / shovel / axe / hands), repeat.
// In DESTROY jobs a bot carrying TNT + flint & steel first works through the
// job's blast points: walk up, place TNT, light it, run, wait for the bang.
public class ExcavationController {
    private enum Phase { IDLE, WORK, BLAST_PLACE, BLAST_FLEE }

    private static final double REACH = 4.3;
    private static final double REACH_SQ = REACH * REACH;
    private static final int STALL_TICKS = 70;
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
    }

    public void abort() {
        clearStage();
        if (job != null) {
            job.release(cell);
            job.release(blast);
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

        if (context.target != null || context.fleeing) {
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
        if (repathCooldown > 0) repathCooldown--;
        if (blastCooldown > 0) blastCooldown--;
        context.suppressSprint = true;

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
            cell = job.claim(context.bot.getUUID(), botPlayer, b -> canBreak(botPlayer, b));
            stallTicks = 0;
            if (cell == null) {
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                // Nothing we can do right now (other bots have the rest, or
                // it all needs tools we don't carry): leave eventually.
                if (++idleTicks > IDLE_GIVE_UP_TICKS) abort();
                return true;
            }
            idleTicks = 0;
        }

        tickWork(botPlayer, handle);
        return true;
    }

    // =====================================================================
    // Breaking blocks
    // =====================================================================

    private void tickWork(Player botPlayer, ServerPlayer handle) {
        Block target = job.world.getBlockAt(cell.x, cell.y, cell.z);
        if (!ExcavationJob.breakable(target.getType())) {
            job.complete(cell);
            cell = null;
            return;
        }

        Location eye = botPlayer.getEyeLocation();
        double distSq = distSq(eye, target);
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
            if (job.contains(blocker.getX(), blocker.getY(), blocker.getZ())
                    && ExcavationJob.breakable(blocker.getType())) {
                mine(botPlayer, handle, blocker, false); // it's part of the job anyway
                return;
            }
        }

        // Walk to somewhere we can reach and see it from.
        Location spot = standSpotFor(botPlayer, target);
        walkTo(botPlayer, spot != null ? spot : target.getLocation().add(0.5, 1.0, 0.5));
        if (stalled(botPlayer)) {
            job.fail(cell);
            cell = null;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
        }
    }

    private void mine(Player botPlayer, ServerPlayer handle, Block block, boolean isCell) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        stallTicks = 0;
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

        float speed;
        try {
            speed = block.getBreakSpeed(botPlayer);
        } catch (Throwable t) {
            speed = 0.05f;
        }
        breakProgress += Math.max(speed, 0.002f);
        sendStage(block, (int) (breakProgress * 10));
        if (breakProgress < 1.0f && breakTicks < 600) return;

        clearStage();
        breaking = null;
        breakProgress = 0f;

        int bx = block.getX(), by = block.getY(), bz = block.getZ();
        boolean ok;
        try {
            // Real survival break: events, protection plugins, drops, tool wear.
            ok = botPlayer.breakBlock(block);
        } catch (Throwable t) {
            org.bukkit.event.block.BlockBreakEvent ev = new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
            org.bukkit.Bukkit.getPluginManager().callEvent(ev);
            ok = !ev.isCancelled() && block.breakNaturally(botPlayer.getInventory().getItemInMainHand());
        }

        if (!ok && ExcavationJob.breakable(block.getType())) {
            // Protected or refused - don't keep hammering it.
            if (isCell) {
                job.fail(cell);
                cell = null;
            } else if (cell != null) {
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

        // Place the TNT (a real place event, so protection still applies)...
        int tntSlot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.TNT);
        if (tntSlot < 0 || tntSlot > 8) return;
        botPlayer.getInventory().setHeldItemSlot(tntSlot);
        context.packetBroadcaster.broadcastEquipment();
        org.bukkit.block.BlockState replaced = spot.getState();
        spot.setType(Material.TNT, false);
        org.bukkit.event.block.BlockPlaceEvent place = new org.bukkit.event.block.BlockPlaceEvent(
                spot, replaced, spot.getRelative(0, -1, 0), new ItemStack(Material.TNT), botPlayer, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(place);
        if (place.isCancelled()) {
            replaced.update(true, false);
            job.completeBlast(blast, false);
            blast = null;
            phase = Phase.WORK;
            return;
        }
        consumeOne(botPlayer, tntSlot);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);

        // ...then light it with the flint and steel.
        int fsSlot = context.inventoryController.ensureInHotbar(botPlayer,
                it -> it.getType() == Material.FLINT_AND_STEEL);
        if (fsSlot >= 0 && fsSlot <= 8) {
            botPlayer.getInventory().setHeldItemSlot(fsSlot);
            context.packetBroadcaster.broadcastEquipment();
        }
        org.bukkit.event.block.TNTPrimeEvent prime = new org.bukkit.event.block.TNTPrimeEvent(
                spot, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.PLAYER, botPlayer, null);
        org.bukkit.Bukkit.getPluginManager().callEvent(prime);
        if (prime.isCancelled()) {
            // TNT stays as a block - take it back rather than litter.
            spot.setType(Material.AIR, false);
            botPlayer.getInventory().addItem(new ItemStack(Material.TNT));
            job.completeBlast(blast, false);
            blast = null;
            phase = Phase.WORK;
            return;
        }
        spot.setType(Material.AIR, false);
        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        job.world.playSound(center, Sound.ITEM_FLINTANDSTEEL_USE, 1.0f, 1.0f);
        lit = job.world.spawn(spot.getLocation().add(0.5, 0.0, 0.5), TNTPrimed.class, t -> {
            t.setFuseTicks(FUSE_TICKS);
            t.setSource(botPlayer);
        });
        wearFlintAndSteel(botPlayer);

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

    private void wearFlintAndSteel(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack fs = inv.getItemInMainHand();
        if (fs.getType() != Material.FLINT_AND_STEEL) return;
        if (fs.getItemMeta() instanceof Damageable dmg) {
            int next = dmg.getDamage() + 1;
            if (next >= Material.FLINT_AND_STEEL.getMaxDurability()) {
                inv.setItemInMainHand(null);
            } else {
                dmg.setDamage(next);
                fs.setItemMeta(dmg);
            }
        }
        context.packetBroadcaster.broadcastEquipment();
    }

    private void consumeOne(Player p, int slot) {
        ItemStack s = p.getInventory().getItem(slot);
        if (s == null) return;
        if (s.getAmount() <= 1) p.getInventory().setItem(slot, null);
        else s.setAmount(s.getAmount() - 1);
        context.packetBroadcaster.broadcastEquipment();
    }

    // =====================================================================
    // Movement / geometry
    // =====================================================================

    private Location standSpotFor(Player botPlayer, Block target) {
        Location me = botPlayer.getLocation();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int x = target.getX() + dx, y = target.getY() + dy, z = target.getZ() + dz;
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;
                    if (!standable(x, y, z)) continue;
                    Location feet = new Location(job.world, x + 0.5, y, z + 0.5);
                    Location eye = feet.clone().add(0, 1.62, 0);
                    if (distSq(eye, target) > REACH_SQ * 0.85) continue;
                    if (firstSolidBetween(eye, target) != null) continue;
                    double d = feet.distanceSquared(me);
                    if (d < bestD) {
                        bestD = d;
                        best = feet;
                    }
                }
            }
        }
        return best;
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
        if (flat < 0.4 && Math.abs(dest.getY() - me.getY()) < 1.2) {
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
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_COMBAT);
        context.requestLookPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, Math.hypot(dx, dz)))),
                BotAIContext.LOOK_COMBAT);
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
