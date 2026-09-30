package com.pvpbot.ai;

import com.pvpbot.mine.AreaMiningJob;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

public class AreaMiningController {
    private enum Phase { IDLE, TRAVEL, MINE, RETURN }

    private static final double ARRIVE_DISTANCE = 2.0;
    private static final int[][] DIRS = { {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1} };

    private final BotAIContext context;
    private Phase phase = Phase.IDLE;
    private AreaMiningJob job;
    private Location targetPosition;
    private Location home;

    private int dirX = 1;
    private int dirZ = 0;
    private int runLeft;

    private Block breaking;
    private int breakTicksTotal;
    private int breakTicksElapsed;
    private int lastDestroyStage = -1;

    private int phaseTicks;
    private int mined;
    private int stuckTicks;

    public AreaMiningController(BotAIContext context) {
        this.context = context;
    }
    
    public boolean isActive() {
        return phase != Phase.IDLE;
    }
    
    public String status() {
        if (!isActive()) return "idle";
        return phase + (job == null ? "" : " (" + job.secondsLeft() + "s left)");
    }
    
    public String join(AreaMiningJob job, int crewSize) {
        Player p = context.bot.getBukkitPlayer();
        if (p == null) return "not alive";
        if (!context.miningController.hasPickaxe()) return "has no pickaxe";
        
        abort();
        this.job = job;
        this.home = p.getLocation().clone();
        int lane = job.assignLane(context.bot.getUUID());
        this.targetPosition = job.getTargetPosition(lane, crewSize);
        this.phase = Phase.MINE;
        this.phaseTicks = 0;
        this.mined = 0;
        pickNewHeading();
        return null;
    }
    
    public void abort() {
        phase = Phase.IDLE;
        job = null;
        home = null;
        targetPosition = null;
        breaking = null;
        breakTicksElapsed = 0;
        breakTicksTotal = 0;
        phaseTicks = 0;
        stuckTicks = 0;
    }

    public boolean handleAreaMining(Player botPlayer) {
        if (!isActive()) return false;
        if (botPlayer == null) { abort(); return false; }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        if (context.target != null || context.fleeing) {
            // Pause, don't abort - a passing enemy shouldn't permanently end
            // the job. phase/job state stays put and picks back up once the
            // distraction clears (nothing else re-issues this job otherwise).
            return false;
        }
        
        phaseTicks++;
        
        if (phase != Phase.RETURN && (job == null || job.isExpired())) {
            beginReturn();
        }
        
        switch (phase) {
            case TRAVEL -> { return tickTravel(botPlayer); }
            case MINE -> { return tickMine(botPlayer, handle); }
            case RETURN -> { return tickReturn(botPlayer); }
            default -> { return false; }
        }
    }
    
    private boolean tickTravel(Player botPlayer) {
        if (targetPosition == null || targetPosition.getWorld() != botPlayer.getWorld()) {
            beginReturn();
            return true;
        }
        
        double dist = targetPosition.distance(botPlayer.getLocation());
        if (dist <= ARRIVE_DISTANCE) {
            phase = Phase.MINE;
            phaseTicks = 0;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            return true;
        }
        
        if (phaseTicks > 4000) {
            beginReturn();
            return true;
        }
        
        walkTowards(botPlayer, targetPosition);
        return true;
    }
    
    private boolean tickMine(Player botPlayer, ServerPlayer handle) {
        Location loc = botPlayer.getLocation();
        int feetY = loc.getBlockY();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        
        Material feetMat = botPlayer.getWorld().getBlockAt(bx, feetY, bz).getType();
        if (feetMat == Material.LAVA || feetMat == Material.WATER) {
            // Yield the tick - HazardController/MovementController's hole
            // escape (which actually places blocks to climb) handle this
            // better than anything duplicated here.
            return false;
        }

        Block target = findMineableBlock(botPlayer, feetY);
        if (target != null) {
            if (digBlock(botPlayer, handle, target)) {
                mined++;
                if (job != null) job.noteMined();
            }
            return true;
        }
        
        Block ahead = botPlayer.getWorld().getBlockAt(bx + dirX, feetY, bz + dirZ);
        Block aheadHead = botPlayer.getWorld().getBlockAt(bx + dirX, feetY + 1, bz + dirZ);
        
        if (!ahead.getType().isAir() && ahead.getType().isSolid()) {
            digBlock(botPlayer, handle, ahead, false);
            return true;
        }
        if (!aheadHead.getType().isAir() && aheadHead.getType().isSolid()) {
            digBlock(botPlayer, handle, aheadHead, false);
            return true;
        }
        
        // Only move if we've been mining in one spot for a while
        if (runLeft > 0) {
            runLeft--;
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return true;
        }
        
        // Pick new direction and move
        pickNewHeading();
        context.requestLookYaw(headingYaw(), BotAIContext.LOOK_COMBAT);
        context.requestLookPitch(0f, BotAIContext.LOOK_COMBAT);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        
        Block floor = botPlayer.getWorld().getBlockAt(bx + dirX, feetY - 1, bz + dirZ);
        if (!floor.getType().isSolid()) {
            pickNewHeading();
            context.forwardInput = 0f;
        }
        
        return true;
    }
    
    private Block findMineableBlock(Player botPlayer, int feetY) {
        Location loc = botPlayer.getLocation();
        int bx = loc.getBlockX();
        int bz = loc.getBlockZ();
        
        for (int y = feetY - 1; y <= feetY + 2; y++) {
            for (int[] d : DIRS) {
                Block b = botPlayer.getWorld().getBlockAt(bx + d[0], y, bz + d[1]);
                if (shouldMine(b)) {
                    return b;
                }
            }
        }
        
        Block below = botPlayer.getWorld().getBlockAt(bx, feetY - 1, bz);
        if (shouldMine(below)) {
            return below;
        }
        
        return null;
    }
    
    private boolean shouldMine(Block block) {
        Material m = block.getType();
        if (m.isAir()) return false;
        if (m == Material.BEDROCK || m == Material.OBSIDIAN || m == Material.BARRIER) return false;
        if (m == Material.LAVA || m == Material.WATER) return false;
        if (m == Material.FIRE) return false;
        
        String name = m.name();
        // Exclude unbreakable or decorative blocks
        if (name.contains("PORTAL") || name.contains("END_GATEWAY") || name.contains("BEDROCK")) return false;
        if (name.contains("COMMAND") || name.contains("STRUCTURE")) return false;
        
        return true;
    }
    
    private boolean digBlock(Player botPlayer, ServerPlayer handle, Block block) {
        return digBlock(botPlayer, handle, block, true);
    }
    
    private boolean digBlock(Player botPlayer, ServerPlayer handle, Block block, boolean isTarget) {
        // Equip before timing the break, so the speed calc matches what's
        // actually in hand for the swings instead of whatever was held
        // last (which was the fixed-time drill-with-bare-fist bug).
        equipPickaxe(botPlayer);

        if (breaking == null || !breaking.equals(block)) {
            clearDestroyStage();
            breaking = block;
            breakTicksTotal = computeBreakTicks(botPlayer, block);
            breakTicksElapsed = 0;
        }

        Location eye = botPlayer.getEyeLocation();
        double dx = (block.getX() + 0.5) - eye.getX();
        double dy = (block.getY() + 0.5) - eye.getY();
        double dz = (block.getZ() + 0.5) - eye.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)),
                BotAIContext.LOOK_COMBAT);
        context.requestLookPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, flat))),
                BotAIContext.LOOK_COMBAT);
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        
        if (breakTicksElapsed % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
            try {
                block.getWorld().playSound(block.getLocation(),
                        block.getBlockData().getSoundGroup().getHitSound(), 0.5f, 1.0f);
            } catch (Throwable ignored) {
            }
        }
        
        breakTicksElapsed++;
        sendDestroyStage(block, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));
        
        if (breakTicksElapsed < breakTicksTotal) return false;
        
        clearDestroyStage();
        breaking = null;
        
        try {
            block.getWorld().playSound(block.getLocation(),
                    block.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
        
        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            pickNewHeading();
            return false;
        }
        
        block.breakNaturally(botPlayer.getInventory().getItemInMainHand());
        return true;
    }
    
    private boolean needsPickaxe(Material material) {
        String name = material.name();
        return name.contains("STONE") || name.contains("ORE") || name.contains("COBBLESTONE")
                || name.contains("ANDESITE") || name.contains("DIORITE") || name.contains("GRANITE")
                || name.contains("DEEPSLATE") || name.contains("NETHERITE");
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
        org.bukkit.inventory.ItemStack hand = botPlayer.getInventory().getItemInMainHand();
        if (hand != null) {
            String n = hand.getType().name();
            if (n.endsWith("_PICKAXE")) {
                if (n.startsWith("WOODEN")) speed = 2.0;
                else if (n.startsWith("STONE")) speed = 4.0;
                else if (n.startsWith("IRON")) speed = 6.0;
                else if (n.startsWith("DIAMOND")) speed = 8.0;
                else if (n.startsWith("NETHERITE")) speed = 9.0;
                else if (n.startsWith("GOLDEN")) speed = 12.0;
                try {
                    int eff = hand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.EFFICIENCY);
                    if (eff > 0) speed += eff * eff + 1;
                } catch (Throwable ignored) {
                }
            }
        }

        if (needsPickaxe(block.getType()) && speed == 1.0) {
            // No pickaxe in hand for a block that needs one - bare-handed,
            // vanilla-slow, not the drill-on-steroids fixed 2s this used to be.
            speed = 0.2;
        }

        int ticks = (int) Math.ceil(30.0 * hardness / Math.max(0.01, speed));
        return Math.max(2, Math.min(ticks, 200));
    }
    
    private void equipPickaxe(Player p) {
        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType().name().endsWith("_PICKAXE")) return;
        
        int slot = findPickaxeSlot(p);
        if (slot < 0) return;
        if (slot <= 8) {
            p.getInventory().setHeldItemSlot(slot);
            return;
        }
        
        int held = p.getInventory().getHeldItemSlot();
        if (held < 0 || held > 8) held = 0;
        org.bukkit.inventory.ItemStack pick = p.getInventory().getItem(slot);
        org.bukkit.inventory.ItemStack swap = p.getInventory().getItem(held);
        p.getInventory().setItem(held, pick);
        p.getInventory().setItem(slot, swap);
        p.getInventory().setHeldItemSlot(held);
    }
    
    private int findPickaxeSlot(Player p) {
        for (int i = 0; i < 36; i++) {
            org.bukkit.inventory.ItemStack s = p.getInventory().getItem(i);
            if (s != null && s.getType().name().endsWith("_PICKAXE")) return i;
        }
        return -1;
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
    
    private void pickNewHeading() {
        int[] d = DIRS[ThreadLocalRandom.current().nextInt(DIRS.length)];
        
        if (d[0] == -dirX && d[1] == -dirZ) {
            d = DIRS[(ThreadLocalRandom.current().nextInt(DIRS.length - 1) + 1) % DIRS.length];
        }
        dirX = d[0];
        dirZ = d[1];
        runLeft = 30 + ThreadLocalRandom.current().nextInt(30);
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
            if (exhausted) {
                context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), dest);
            } else {
                mc.followPath();
            }
        } else {
            double dx = dest.getX() - botPlayer.getX();
            double dz = dest.getZ() - botPlayer.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) { dx = 0; dz = 1; len = 1; }
            dx /= len; dz /= len;
            mc.worldDirToInputs(context.bot.getHandle(), dx, dz);
        }
    }
    
    private void beginReturn() {
        phase = Phase.RETURN;
        phaseTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }
    
    private boolean tickReturn(Player botPlayer) {
        if (home == null || home.getWorld() != botPlayer.getWorld()) {
            abort();
            return false;
        }
        
        if (home.distance(botPlayer.getLocation()) <= ARRIVE_DISTANCE) {
            abort();
            return false;
        }
        
        if (phaseTicks > 9000) {
            abort();
            return false;
        }
        
        walkTowards(botPlayer, home);
        return true;
    }
}
