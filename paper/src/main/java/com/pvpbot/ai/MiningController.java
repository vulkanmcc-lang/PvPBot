package com.pvpbot.ai;

import com.pvpbot.mine.MiningJob;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.concurrent.ThreadLocalRandom;

public class MiningController {
    private enum Phase { IDLE, TRAVEL, DESCEND, TUNNEL, STASH, ESCAPE, RETURN }

    private static final double LANE_ARRIVE = 2.0;

    private static final double HOME_ARRIVE = 3.0;

    private static final int MIN_RUN = 6;
    private static final int MAX_RUN = 18;

    private static final int TICKET_RADIUS = 2;

    private static final int[][] DIRS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    private final BotAIContext context;
    private final ChunkTicketHolder tickets = new ChunkTicketHolder(TICKET_RADIUS);

    private Phase phase = Phase.IDLE;
    private MiningJob job;
    private Location home;
    private Location laneStart;

    private int dirX = 1;
    private int dirZ = 0;
    private int runLeft;

    private Block breaking;
    private int breakTicksTotal;
    private int breakTicksElapsed;
    private int lastDestroyStage = -1;

    private int phaseTicks;
    private int repathCooldown;
    private int stuckTicks;
    private int mined;
    private int foundHere;

    private enum Stash { PLACE, FILL, BREAK }
    private Stash stashStep;
    private Location stashLoc;
    private Material stashType;
    private ItemStack[] stashBackup;
    private int stashTimer;

    private Location escapeFrom;
    private int escapeTicks;

    private final ArrivalTracker arrival = new ArrivalTracker(LANE_ARRIVE, 60);

    public MiningController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public String status() {
        if (!isActive()) return "idle";
        return phase + (job == null ? "" : " (" + job.secondsLeft() + "s left, "
                + foundHere + " found)");
    }

    public int getFound() {
        return foundHere;
    }

    public String debugLine() {
        Player p = context.bot.getBukkitPlayer();
        StringBuilder sb = new StringBuilder();
        sb.append("phase=").append(phase);
        if (p != null) {
            sb.append(" feetY=").append(p.getLocation().getBlockY());
        }
        if (laneStart != null) {
            sb.append(" laneY=").append(laneStart.getBlockY())
                    .append(" laneXZ=").append(laneStart.getBlockX())
                    .append(",").append(laneStart.getBlockZ());
            if (p != null) {
                double dx = laneStart.getX() - p.getLocation().getX();
                double dz = laneStart.getZ() - p.getLocation().getZ();
                sb.append(" hDist=").append(String.format("%.1f", Math.sqrt(dx * dx + dz * dz)));
            }
        }
        if (job != null) {
            sb.append(" band=").append(job.getMinY()).append("-").append(job.getMaxY());
            sb.append(" left=").append(job.secondsLeft()).append("s");
        }
        sb.append(" dir=").append(dirX).append(",").append(dirZ);
        sb.append(" run=").append(runLeft);
        sb.append(" breaking=").append(breaking == null ? "-"
                : breaking.getType() + "@" + breakTicksElapsed + "/" + breakTicksTotal);
        sb.append(" noProg=").append(arrival.stalledFor());
        if (p != null && job != null) {
            sb.append(" inRegion=").append(
                    job.insideHorizontally(p.getLocation().getBlockX(),
                            p.getLocation().getBlockZ()));
        }
        sb.append(" pick=").append(hasPickaxe());
        sb.append(" found=").append(foundHere).append(" mined=").append(mined);
        if (stashStep != null) sb.append(" stash=").append(stashStep);
        return sb.toString();
    }

    public boolean hasPickaxe() {
        Player p = context.bot.getBukkitPlayer();
        return p != null && findPickaxeSlot(p) >= 0;
    }

    public String join(MiningJob job, int crewSize) {
        Player p = context.bot.getBukkitPlayer();
        if (p == null) return "not alive";
        if (findPickaxeSlot(p) < 0) return "has no pickaxe";

        abort();
        this.job = job;
        this.home = p.getLocation().clone();
        int lane = job.laneFor(context.bot);
        this.laneStart = job.laneStart(lane, Math.max(crewSize, job.crewSize()));
        this.phase = Phase.TRAVEL;
        this.phaseTicks = 0;
        this.arrival.begin(this.laneStart);
        this.foundHere = 0;
        this.mined = 0;
        pickNewHeading();
        return null;
    }

    public void abort() {
        clearDestroyStage();

        reclaimStash();
        tickets.release();
        phase = Phase.IDLE;
        job = null;
        home = null;
        laneStart = null;
        breaking = null;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        phaseTicks = 0;
        repathCooldown = 0;
        stuckTicks = 0;
        arrival.cancel();
    }

    public boolean handleMining(Player botPlayer) {
        if (!isActive()) return false;
        if (botPlayer == null) { abort(); return false; }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        if (context.target != null || context.fleeing) {
            clearDestroyStage();
            return false;
        }

        phaseTicks++;
        if (repathCooldown > 0) repathCooldown--;
        tickets.refresh(botPlayer.getLocation());

        if (phase != Phase.RETURN && (job == null || job.isExpired())) {
            beginReturn();
        }

        switch (phase) {
            case TRAVEL -> { return tickTravel(botPlayer); }
            case DESCEND -> { return tickDescend(botPlayer, handle); }
            case TUNNEL -> { return tickTunnel(botPlayer, handle); }
            case STASH -> { return tickStash(botPlayer, handle); }
            case ESCAPE -> { return tickEscape(botPlayer, handle); }
            case RETURN -> { return tickReturn(botPlayer); }
            default -> { return false; }
        }
    }

    private boolean tickTravel(Player botPlayer) {
        if (laneStart == null) {
            beginReturn();
            return true;
        }
        if (laneStart.getWorld() != botPlayer.getWorld()) {
            beginReturn();
            return true;
        }

        double dist = laneStart.distance(botPlayer.getLocation());
        if (dist <= LANE_ARRIVE) {
            phase = Phase.TUNNEL;
            phaseTicks = 0;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            return true;
        }

        Location here = botPlayer.getLocation();
        int feetY = here.getBlockY();
        int bx = here.getBlockX();
        int bz = here.getBlockZ();

        double dx = laneStart.getX() - here.getX();
        double dz = laneStart.getZ() - here.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if (!arrival.isActive()) arrival.begin(laneStart);
        ArrivalTracker.State state = arrival.update(botPlayer);

        boolean inRegion = job.insideHorizontally(bx, bz);
        boolean giveUpWalking = state == ArrivalTracker.State.STALLED
                || state == ArrivalTracker.State.ARRIVED;

        if (inRegion || hDist <= 4.0 || giveUpWalking) {
            if (feetY >= job.getMinY() && feetY <= job.getMaxY()) {
                phase = Phase.TUNNEL;
                phaseTicks = 0;
                context.currentPath.clear();
                context.pathNodeIndex = 0;
                return true;
            }
            if (feetY > job.getMaxY()) {
                phase = Phase.DESCEND;
                phaseTicks = 0;
                context.currentPath.clear();
                context.pathNodeIndex = 0;
                return true;
            }
        }

        if (phaseTicks > 4000) {
            beginReturn();
            return true;
        }

        walkTowards(botPlayer, laneStart);
        return true;
    }

    private boolean tickDescend(Player botPlayer, ServerPlayer handle) {
        Location loc = botPlayer.getLocation();
        int feetY = loc.getBlockY();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        Material feetMat = botPlayer.getWorld().getBlockAt(bx, feetY, bz).getType();
        if (feetMat == Material.LAVA || feetMat == Material.WATER) {
            if (job != null) job.noteHazard(bx, feetY, bz);
            beginEscape(botPlayer);
            return true;
        }

        int wantY = laneStart == null ? job.getMinY() + 1 : laneStart.getBlockY();
        wantY = Math.max(job.getMinY() + 1, Math.min(wantY, job.getMaxY()));
        if (feetY <= wantY) {
            phase = Phase.TUNNEL;
            phaseTicks = 0;
            return true;
        }

        if (phaseTicks > 6000) {
            beginReturn();
            return true;
        }

        if (!job.insideHorizontally(bx + dirX * 2, bz + dirZ * 2)) {
            turnAround();
        }

        if (hazardBehind(botPlayer, bx, feetY, bz) || hazardBelow(botPlayer, bx, feetY, bz)) {
            pickNewHeading();
            return true;
        }

        Block stepInto = botPlayer.getWorld().getBlockAt(bx + dirX, feetY, bz + dirZ);
        Block stepDown = botPlayer.getWorld().getBlockAt(bx + dirX, feetY - 1, bz + dirZ);
        Block stepHead = botPlayer.getWorld().getBlockAt(bx + dirX, feetY + 1, bz + dirZ);

        if (stepHead.getType().isSolid()) {
            digBlock(botPlayer, handle, stepHead, false);
            return true;
        }
        if (stepInto.getType().isSolid()) {
            digBlock(botPlayer, handle, stepInto, false);
            return true;
        }
        if (stepDown.getType().isSolid()) {
            digBlock(botPlayer, handle, stepDown, false);
            return true;
        }

        context.requestLookYaw(headingYaw(), BotAIContext.LOOK_TRAVEL);
        context.requestLookPitch(20f, BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        return true;
    }

    private boolean hazardBelow(Player botPlayer, int bx, int feetY, int bz) {
        for (int y = feetY - 3; y <= feetY - 1; y++) {
            Material m = botPlayer.getWorld().getBlockAt(bx + dirX, y, bz + dirZ).getType();
            if (m == Material.LAVA || m == Material.WATER) {
                if (job != null) job.noteHazard(bx + dirX, y, bz + dirZ);
                return true;
            }
        }
        return false;
    }

    private boolean tickTunnel(Player botPlayer, ServerPlayer handle) {
        Location loc = botPlayer.getLocation();
        int feetY = loc.getBlockY();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        Material feetMat = botPlayer.getWorld().getBlockAt(bx, feetY, bz).getType();
        if (feetMat == Material.LAVA || feetMat == Material.WATER) {
            if (job != null) job.noteHazard(bx, feetY, bz);
            beginEscape(botPlayer);
            return true;
        }

        if (!job.insideHorizontally(bx + dirX * 2, bz + dirZ * 2)) {
            turnAround();
        }
        if (feetY > job.getMaxY()) {
            phase = Phase.DESCEND;
            phaseTicks = 0;
            return true;
        }
        if (feetY < job.getMinY()) {
            phase = Phase.TRAVEL;
            phaseTicks = 0;
            arrival.begin(laneStart);
            return true;
        }

        Block prize = findExposedTarget(botPlayer, feetY);
        if (prize != null) {
            if (digBlock(botPlayer, handle, prize, true)) {
                foundHere++;
                if (job != null) job.noteFound(1);

                if (beginStash(botPlayer)) return true;
            }
            return true;
        }

        Block ahead = botPlayer.getWorld().getBlockAt(bx + dirX, feetY, bz + dirZ);
        Block aheadHead = botPlayer.getWorld().getBlockAt(bx + dirX, feetY + 1, bz + dirZ);

        if (hazardBehind(botPlayer, bx, feetY, bz)) {
            pickNewHeading();
            return true;
        }

        if (isDangerous(ahead) || isDangerous(aheadHead)) {
            if (job != null) job.noteHazard(bx + dirX, feetY, bz + dirZ);
            pickNewHeading();
            return true;
        }

        if (!ahead.getType().isAir() && ahead.getType().isSolid()) {
            digBlock(botPlayer, handle, ahead, false);
            return true;
        }
        if (!aheadHead.getType().isAir() && aheadHead.getType().isSolid()) {
            digBlock(botPlayer, handle, aheadHead, false);
            return true;
        }

        context.requestLookYaw(headingYaw(), BotAIContext.LOOK_TRAVEL);
        context.requestLookPitch(0f, BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;

        Block floor = botPlayer.getWorld().getBlockAt(bx + dirX, feetY - 1, bz + dirZ);
        if (!floor.getType().isSolid()) {
            pickNewHeading();
            context.forwardInput = 0f;
            return true;
        }

        if (--runLeft <= 0) pickNewHeading();
        return true;
    }

    private boolean hazardBehind(Player botPlayer, int bx, int feetY, int bz) {
        if (job != null && job.hazardNear(bx + dirX * 2, feetY, bz + dirZ * 2, 2)) {
            return true;
        }
        for (int step = 1; step <= 2; step++) {
            int x = bx + dirX * step;
            int z = bz + dirZ * step;
            for (int y = feetY - 1; y <= feetY + 2; y++) {
                Material m = botPlayer.getWorld().getBlockAt(x, y, z).getType();
                if (m == Material.LAVA || m == Material.WATER) {
                    if (job != null) job.noteHazard(x, y, z);
                    return true;
                }
            }
        }
        return false;
    }

    private void beginEscape(Player botPlayer) {
        clearDestroyStage();
        breaking = null;
        escapeFrom = botPlayer.getLocation().clone();
        escapeTicks = 0;
        phase = Phase.ESCAPE;
    }

    private boolean tickEscape(Player botPlayer, ServerPlayer handle) {
        escapeTicks++;

        Location loc = botPlayer.getLocation();
        Material feetMat = botPlayer.getWorld()
                .getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).getType();
        Material headMat = botPlayer.getWorld()
                .getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ()).getType();

        boolean stillIn = feetMat == Material.LAVA || feetMat == Material.WATER
                || headMat == Material.LAVA || headMat == Material.WATER;

        if (!stillIn && handle.onGround()) {
            pickNewHeading();

            if (escapeFrom != null) {
                int dx = loc.getBlockX() - escapeFrom.getBlockX();
                int dz = loc.getBlockZ() - escapeFrom.getBlockZ();
                if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
                    dirX = Integer.signum(dx);
                    dirZ = 0;
                } else if (dz != 0) {
                    dirX = 0;
                    dirZ = Integer.signum(dz);
                }
            }
            escapeFrom = null;
            phase = Phase.TUNNEL;
            phaseTicks = 0;
            return true;
        }

        if (escapeTicks > 200) {
            beginReturn();
            return true;
        }

        context.requestLookYaw(reverseHeadingYaw(), BotAIContext.LOOK_UTILITY);
        context.requestLookPitch(0f, BotAIContext.LOOK_UTILITY);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        context.movementController.requestJump();
        return true;
    }

    private float reverseHeadingYaw() {
        return (float) Math.toDegrees(Math.atan2(dirX, -dirZ));
    }

    private boolean beginStash(Player botPlayer) {
        if (job == null) return false;
        if (countTargetInInventory(botPlayer) <= 0) return false;

        int slot = findStashShulkerSlot(botPlayer);
        if (slot < 0) return false;

        Block spot = findStashSpot(botPlayer);
        if (spot == null) return false;

        ItemStack box = botPlayer.getInventory().getItem(slot);
        if (box == null) return false;

        ItemStack[] existing = readShulkerContents(box);
        if (existing == null) return false;

        stashBackup = existing;
        stashType = box.getType();
        stashLoc = spot.getLocation();
        botPlayer.getInventory().setItem(slot, null);
        botPlayer.updateInventory();

        stashStep = Stash.PLACE;
        stashTimer = 4;
        phase = Phase.STASH;
        phaseTicks = 0;
        return true;
    }

    private boolean tickStash(Player botPlayer, ServerPlayer handle) {
        if (stashLoc == null || stashType == null) {
            phase = Phase.TUNNEL;
            return true;
        }

        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.movementController.lookAt(stashLoc.clone().add(0.5, 0.5, 0.5), 0.0);

        Block block = stashLoc.getBlock();

        switch (stashStep) {
            case PLACE -> {
                if (stashTimer-- > 0) return true;
                if (!placeStash(botPlayer, block)) {
                    returnStashItem(botPlayer, buildStashItem(stashBackup));
                    endStash();
                    return true;
                }
                handle.swing(InteractionHand.MAIN_HAND, true);
                context.packetBroadcaster.broadcastAnimation(handle, 0);
                stashStep = Stash.FILL;
                stashTimer = 10;
            }
            case FILL -> {
                if (stashTimer-- > 0) return true;
                moveTargetsInto(botPlayer, block);
                stashStep = Stash.BREAK;
                stashTimer = 0;
            }
            case BREAK -> {
                if (!breakStash(botPlayer, handle, block)) return true;
                endStash();
            }
        }
        return true;
    }

    private boolean placeStash(Player botPlayer, Block block) {
        if (!block.getType().isAir()) return false;
        BlockState replaced = block.getState();
        block.setType(stashType, false);

        org.bukkit.event.block.BlockPlaceEvent event =
                new org.bukkit.event.block.BlockPlaceEvent(
                        block, replaced, block.getRelative(org.bukkit.block.BlockFace.DOWN),
                        buildStashItem(stashBackup), botPlayer, true,
                        org.bukkit.inventory.EquipmentSlot.HAND);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            replaced.update(true, false);
            return false;
        }

        ShulkerIO.write(block, stashBackup);
        try {
            block.getWorld().playSound(stashLoc, org.bukkit.Sound.BLOCK_SHULKER_BOX_OPEN, 0.8f, 1.0f);
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void moveTargetsInto(Player botPlayer, Block block) {
        try {
            Material want = job.getTarget();
            for (int i = 0; i < 36; i++) {
                ItemStack s = botPlayer.getInventory().getItem(i);
                if (s == null || s.getType() != want) continue;

                ItemStack remainder = ShulkerIO.add(block, s.clone());
                if (remainder == null) {
                    botPlayer.getInventory().setItem(i, null);
                } else {
                    botPlayer.getInventory().setItem(i, remainder);
                    break;
                }
            }
            botPlayer.updateInventory();
        } catch (Throwable ignored) {
        }
    }

    private boolean breakStash(Player botPlayer, ServerPlayer handle, Block block) {
        if (!isShulkerMaterial(block.getType())) return true;

        if (breaking == null || !breaking.equals(block)) {
            clearDestroyStage();
            breaking = block;
            equipPickaxe(botPlayer);
            breakTicksTotal = computeBreakTicks(botPlayer, block);
            breakTicksElapsed = 0;
        }

        if (breakTicksElapsed % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }
        breakTicksElapsed++;
        sendDestroyStage(block, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));
        if (breakTicksElapsed < breakTicksTotal) return false;

        clearDestroyStage();
        breaking = null;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            pickNewHeading();
            return true;
        }

        ItemStack[] finalContents = stashBackup;
        ItemStack[] fromBlock = ShulkerIO.read(block);
        if (fromBlock != null) finalContents = fromBlock;

        Material broken = block.getType();
        block.setType(Material.AIR, false);
        try {
            block.getWorld().playEffect(stashLoc, org.bukkit.Effect.STEP_SOUND, broken);
        } catch (Throwable ignored) {
        }

        returnStashItem(botPlayer, buildStashItem(finalContents));
        return true;
    }

    private ItemStack buildStashItem(ItemStack[] contents) {
        ItemStack rebuilt = new ItemStack(stashType == null ? Material.SHULKER_BOX : stashType, 1);
        try {
            if (rebuilt.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                    && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox sb) {
                if (contents != null) sb.getInventory().setContents(contents);
                bsm.setBlockState(sb);
                rebuilt.setItemMeta(bsm);
            }
        } catch (Throwable ignored) {
        }
        return rebuilt;
    }

    private void returnStashItem(Player botPlayer, ItemStack item) {
        if (item == null || botPlayer == null) return;
        java.util.HashMap<Integer, ItemStack> left = botPlayer.getInventory().addItem(item);
        for (ItemStack over : left.values()) {
            botPlayer.getWorld().dropItemNaturally(botPlayer.getLocation(), over);
        }
        botPlayer.updateInventory();
    }

    private void reclaimStash() {
        if (stashLoc == null || stashType == null) return;
        Player botPlayer = context.bot.getBukkitPlayer();
        try {
            Block block = stashLoc.getBlock();
            if (isShulkerMaterial(block.getType())) {
                ItemStack[] finalContents = stashBackup;
                ItemStack[] fromBlock = ShulkerIO.read(block);
                if (fromBlock != null) finalContents = fromBlock;
                ItemStack rebuilt = buildStashItem(finalContents);
                block.setType(Material.AIR, false);
                if (botPlayer != null) {
                    returnStashItem(botPlayer, rebuilt);
                } else if (stashLoc.getWorld() != null) {
                    stashLoc.getWorld().dropItemNaturally(stashLoc, rebuilt);
                }
            }
        } catch (Throwable ignored) {
        }
        stashLoc = null;
        stashType = null;
        stashBackup = null;
        stashStep = null;
    }

    private void endStash() {
        stashLoc = null;
        stashType = null;
        stashBackup = null;
        stashStep = null;
        stashTimer = 0;
        phase = Phase.TUNNEL;
        phaseTicks = 0;
    }

    private int findStashShulkerSlot(Player p) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s == null || !isShulkerMaterial(s.getType())) continue;
            if (ItemTags.isCargo(s)) continue;
            return i;
        }
        return -1;
    }

    private Block findStashSpot(Player botPlayer) {
        Location loc = botPlayer.getLocation();
        int feetY = loc.getBlockY();

        int[][] order = { {-dirX, -dirZ}, {dirZ, -dirX}, {-dirZ, dirX} };
        for (int[] d : order) {
            Block b = botPlayer.getWorld().getBlockAt(
                    loc.getBlockX() + d[0], feetY, loc.getBlockZ() + d[1]);
            if (!b.getType().isAir()) continue;
            if (!b.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()) continue;
            return b;
        }
        return null;
    }

    private int countTargetInInventory(Player p) {
        if (job == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s != null && s.getType() == job.getTarget()) n += s.getAmount();
        }
        return n;
    }

    private static boolean isShulkerMaterial(Material m) {
        return m.name().endsWith("SHULKER_BOX");
    }

    private static ItemStack[] readShulkerContents(ItemStack shulkerItem) {
        try {
            if (shulkerItem.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                    && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox sb) {
                ItemStack[] src = sb.getInventory().getContents();
                ItemStack[] copy = new ItemStack[src.length];
                for (int i = 0; i < src.length; i++) {
                    copy[i] = src[i] == null ? null : src[i].clone();
                }
                return copy;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean tickReturn(Player botPlayer) {
        if (home == null || home.getWorld() != botPlayer.getWorld()) {
            finish();
            return false;
        }

        if (home.distance(botPlayer.getLocation()) <= HOME_ARRIVE) {
            report();
            finish();
            return false;
        }

        if (phaseTicks > 9000) {
            report();
            finish();
            return false;
        }

        walkTowards(botPlayer, home);
        return true;
    }

    private void beginReturn() {
        clearDestroyStage();
        reclaimStash();
        breaking = null;
        phase = Phase.RETURN;
        phaseTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    private void finish() {
        MiningJob j = job;
        abort();
        if (j != null && j.isExpired()) j.close();
    }

    private void report() {
        if (job == null || job.getRequester() == null) return;
        Player p = org.bukkit.Bukkit.getPlayer(job.getRequester());
        if (p == null || !p.isOnline()) return;
        p.sendMessage("§7" + context.bot.getName() + " returned — mined §f" + mined
                + "§7 blocks, found §a" + foundHere + " " + job.getTarget().name().toLowerCase()
                + "§7.");
    }

    private boolean digBlock(Player botPlayer, ServerPlayer handle, Block block, boolean isPrize) {
        if (breaking == null || !breaking.equals(block)) {
            clearDestroyStage();
            breaking = block;
            equipPickaxe(botPlayer);
            breakTicksTotal = computeBreakTicks(botPlayer, block);
            breakTicksElapsed = 0;
        }

        Location eye = botPlayer.getEyeLocation();
        double dx = (block.getX() + 0.5) - eye.getX();
        double dy = (block.getY() + 0.5) - eye.getY();
        double dz = (block.getZ() + 0.5) - eye.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)),
                BotAIContext.LOOK_UTILITY);
        context.requestLookPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, flat))),
                BotAIContext.LOOK_UTILITY);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (breakTicksElapsed % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }

        breakTicksElapsed++;
        sendDestroyStage(block, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));

        if (breakTicksElapsed < breakTicksTotal) return false;

        clearDestroyStage();
        breaking = null;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            pickNewHeading();
            return false;
        }

        block.breakNaturally(botPlayer.getInventory().getItemInMainHand());
        mined++;
        if (job != null) job.noteMined();
        return true;
    }

    private Block findExposedTarget(Player botPlayer, int feetY) {
        if (job == null) return null;
        Material want = job.getTarget();
        Location loc = botPlayer.getLocation();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();

        for (int y = feetY - 1; y <= feetY + 2; y++) {
            for (int x = bx - 2; x <= bx + 2; x++) {
                for (int z = bz - 2; z <= bz + 2; z++) {
                    Block b = botPlayer.getWorld().getBlockAt(x, y, z);
                    if (b.getType() != want) continue;
                    if (!job.contains(x, y, z)) continue;
                    return b;
                }
            }
        }
        return null;
    }

    private boolean isDangerous(Block b) {
        Material m = b.getType();
        return m == Material.LAVA || m == Material.FIRE || m == Material.MAGMA_BLOCK;
    }

    private void pickNewHeading() {
        int[] d = DIRS[ThreadLocalRandom.current().nextInt(DIRS.length)];

        if (d[0] == -dirX && d[1] == -dirZ) {
            d = DIRS[(ThreadLocalRandom.current().nextInt(DIRS.length - 1) + 1) % DIRS.length];
        }
        dirX = d[0];
        dirZ = d[1];
        runLeft = MIN_RUN + ThreadLocalRandom.current().nextInt(MAX_RUN - MIN_RUN + 1);
    }

    private void turnAround() {
        dirX = -dirX;
        dirZ = -dirZ;
        runLeft = MIN_RUN + ThreadLocalRandom.current().nextInt(MAX_RUN - MIN_RUN + 1);
    }

    private float headingYaw() {
        return (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
    }

    private void walkTowards(Player botPlayer, Location dest) {
        MovementController mc = context.movementController;

        boolean blocked = !mc.canWalkStraightTo(dest);
        boolean tooFar = dest.distance(botPlayer.getLocation()) > 16.0;

        if (blocked || tooFar) {
            boolean exhausted = context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size();
            if (exhausted && repathCooldown <= 0 && context.pathRecalcCooldown <= 0) {
                context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), dest);
                repathCooldown = 20;
            }
            if (!context.currentPath.isEmpty()
                    && context.pathNodeIndex < context.currentPath.size()) {
                mc.followPath();
                return;
            }
            double dx = dest.getX() - botPlayer.getLocation().getX();
            double dz = dest.getZ() - botPlayer.getLocation().getZ();
            context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_TRAVEL);
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return;
        }

        double dx = dest.getX() - botPlayer.getLocation().getX();
        double dz = dest.getZ() - botPlayer.getLocation().getZ();
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)),
                BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
    }

    private int findPickaxeSlot(Player p) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s != null && s.getType().name().endsWith("_PICKAXE")) return i;
        }
        return -1;
    }

    private void equipPickaxe(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType().name().endsWith("_PICKAXE")) return;

        int slot = findPickaxeSlot(p);
        if (slot < 0) return;
        if (slot <= 8) {
            p.getInventory().setHeldItemSlot(slot);
            return;
        }

        int held = p.getInventory().getHeldItemSlot();
        if (held < 0 || held > 8) held = 0;
        ItemStack pick = p.getInventory().getItem(slot);
        ItemStack swap = p.getInventory().getItem(held);
        p.getInventory().setItem(held, pick);
        p.getInventory().setItem(slot, swap);
        p.getInventory().setHeldItemSlot(held);
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
        if (hand != null && hand.getType().name().endsWith("_PICKAXE")) {
            String n = hand.getType().name();
            if (n.startsWith("WOODEN")) speed = 2.0;
            else if (n.startsWith("STONE")) speed = 4.0;
            else if (n.startsWith("IRON")) speed = 6.0;
            else if (n.startsWith("DIAMOND")) speed = 8.0;
            else if (n.startsWith("NETHERITE")) speed = 9.0;
            else if (n.startsWith("GOLDEN")) speed = 12.0;
            try {
                int eff = hand.getEnchantmentLevel(Enchantment.EFFICIENCY);
                if (eff > 0) speed += eff * eff + 1;
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
