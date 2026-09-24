package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class FarmController {
    private enum Phase { IDLE, WAIT, TRAVEL, HARVEST, PLANT, CHEST_TRAVEL, CHEST_OPEN, CHEST_LINGER }

    private static final int CHEST_LINGER_TICKS = 70;

    private static final Map<Material, Material> CROP_TO_SEED = new LinkedHashMap<>();
    private static final Map<Material, Material> SEED_TO_CROP = new LinkedHashMap<>();

    static {
        link(Material.WHEAT, Material.WHEAT_SEEDS);
        link(Material.CARROTS, Material.CARROT);
        link(Material.POTATOES, Material.POTATO);
        link(Material.BEETROOTS, Material.BEETROOT_SEEDS);
    }

    private static void link(Material crop, Material seed) {
        CROP_TO_SEED.put(crop, seed);
        SEED_TO_CROP.put(seed, crop);
    }

    private static final double ARRIVE_SQ = 1.5 * 1.5;
    private static final int WAIT_RECHECK_TICKS = 200;

    private final BotAIContext context;

    private Phase phase = Phase.IDLE;

    private World world;
    private int minX, maxX, minZ, maxZ, plotY;
    private long expiresAtMillis = -1;

    private Location workLoc;
    private Block breakingBlock;
    private int workTicksTotal;
    private int workTicksElapsed;
    private int lastDestroyStage = -1;
    private Material intendedSeed;

    private int waitTicks;
    private int repathCooldown;
    private int harvested;
    private int planted;

    private Location chestLoc;
    private CommandSender chestReportTo;
    private int chestOpenTimer;

    public FarmController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public String status() {
        if (phase == Phase.IDLE) return "idle";
        if (phase == Phase.CHEST_TRAVEL || phase == Phase.CHEST_OPEN || phase == Phase.CHEST_LINGER) {
            return "checking a chest";
        }
        String left = expiresAtMillis < 0 ? ""
                : " (" + Math.max(0, (expiresAtMillis - System.currentTimeMillis()) / 1000) + "s left)";
        return phase + " — harvested " + harvested + ", planted " + planted + left;
    }

    public String startPlot(Location corner1, Location corner2, int seconds) {
        Player p = context.bot.getBukkitPlayer();
        if (p == null) return "not alive";
        if (corner1.getWorld() == null || !corner1.getWorld().equals(corner2.getWorld())) {
            return "corners must be in the same world";
        }

        abort();
        this.world = corner1.getWorld();
        this.minX = Math.min(corner1.getBlockX(), corner2.getBlockX());
        this.maxX = Math.max(corner1.getBlockX(), corner2.getBlockX());
        this.minZ = Math.min(corner1.getBlockZ(), corner2.getBlockZ());
        this.maxZ = Math.max(corner1.getBlockZ(), corner2.getBlockZ());
        this.plotY = corner1.getBlockY();
        this.expiresAtMillis = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : -1;
        this.harvested = 0;
        this.planted = 0;
        this.phase = Phase.WAIT;
        this.waitTicks = 0;
        return null;
    }

    public String beginChestCheck(Location chest, CommandSender reportTo) {
        Player p = context.bot.getBukkitPlayer();
        if (p == null) return "not alive";
        if (isActive() && phase != Phase.CHEST_TRAVEL && phase != Phase.CHEST_OPEN) {
            return "busy farming — stop that first";
        }
        this.chestLoc = chest.clone();
        this.chestReportTo = reportTo;
        this.phase = Phase.CHEST_TRAVEL;
        return null;
    }

    public void abort() {
        clearDestroyStage();
        if (phase == Phase.CHEST_LINGER && chestLoc != null) {
            ShulkerIO.closeVisual(chestLoc.getBlock());
        }
        phase = Phase.IDLE;
        world = null;
        workLoc = null;
        breakingBlock = null;
        workTicksTotal = 0;
        workTicksElapsed = 0;
        chestLoc = null;
        chestReportTo = null;
    }

    public boolean handleFarm(Player botPlayer) {
        if (!isActive()) return false;
        if (botPlayer == null) { abort(); return false; }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        if (context.target != null || context.fleeing) {
            clearDestroyStage();
            return false;
        }

        if (repathCooldown > 0) repathCooldown--;

        switch (phase) {
            case CHEST_TRAVEL -> { return tickChestTravel(botPlayer); }
            case CHEST_OPEN -> { return tickChestOpen(botPlayer, handle); }
            case CHEST_LINGER -> { return tickChestLinger(botPlayer); }
            case WAIT -> { return tickWait(botPlayer); }
            case TRAVEL -> { return tickTravel(botPlayer); }
            case HARVEST -> { return tickHarvest(botPlayer, handle); }
            case PLANT -> { return tickPlant(botPlayer, handle); }
            default -> { return false; }
        }
    }

    private boolean tickWait(Player botPlayer) {
        if (expiresAtMillis > 0 && System.currentTimeMillis() > expiresAtMillis) {
            abort();
            return false;
        }
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (waitTicks-- > 0) return true;

        Location found = findWork(botPlayer);
        if (found == null) {
            waitTicks = WAIT_RECHECK_TICKS;
            return true;
        }
        workLoc = found;
        phase = Phase.TRAVEL;
        return true;
    }

    private boolean tickTravel(Player botPlayer) {
        if (workLoc == null) {
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        Block b = workLoc.getBlock();
        if (!isMatureCrop(b) && !isReplantableFarmland(b)) {
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        Location loc = botPlayer.getLocation();
        double dx = workLoc.getX() + 0.5 - loc.getX();
        double dz = workLoc.getZ() + 0.5 - loc.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < ARRIVE_SQ) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            if (isMatureCrop(b)) {
                phase = Phase.HARVEST;
            } else {
                intendedSeed = firstAvailableSeed(botPlayer);
                phase = Phase.PLANT;
            }
            return true;
        }

        boolean blocked = !context.movementController.canWalkStraightTo(workLoc);
        boolean tooFar = distSq > 256.0;
        if (blocked || tooFar) {
            boolean exhausted = context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size();
            if (exhausted && repathCooldown <= 0) {
                context.pathfindingController.calculatePathAsync(loc, workLoc);
                repathCooldown = 20;
            }
            if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
                context.movementController.followPath();
                return true;
            }
            context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_TRAVEL);
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return true;
        }

        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        return true;
    }

    private boolean tickHarvest(Player botPlayer, ServerPlayer handle) {
        Block block = workLoc.getBlock();
        if (!isMatureCrop(block)) {
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        lookAtWork(botPlayer);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (workTicksTotal <= 0) {
            clearDestroyStage();
            breakingBlock = block;
            workTicksTotal = 4;
            workTicksElapsed = 0;
        }
        if (workTicksElapsed % 4 == 0) {
            handle.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(handle, 0);
        }
        workTicksElapsed++;
        sendDestroyStage(block, (workTicksElapsed * 10) / Math.max(1, workTicksTotal));
        if (workTicksElapsed < workTicksTotal) return true;

        clearDestroyStage();
        workTicksTotal = 0;
        workTicksElapsed = 0;

        Material cropType = block.getType();
        BlockBreakEvent event = new BlockBreakEvent(block, botPlayer);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            block.breakNaturally(botPlayer.getInventory().getItemInMainHand());
            harvested++;
        }

        intendedSeed = CROP_TO_SEED.get(cropType);
        phase = Phase.PLANT;
        return true;
    }

    private boolean tickPlant(Player botPlayer, ServerPlayer handle) {
        Block block = workLoc.getBlock();
        if (!block.getType().isAir()) {
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }
        Block below = block.getRelative(BlockFace.DOWN);
        if (below.getType() != Material.FARMLAND) {
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        Material cropOut = intendedSeed == null ? null : SEED_TO_CROP.get(intendedSeed);
        int slot = intendedSeed == null ? -1 : findSlot(botPlayer, intendedSeed);
        if (cropOut == null || slot < 0) {
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        lookAtWork(botPlayer);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        int held = botPlayer.getInventory().getHeldItemSlot();
        if (held != slot) {
            botPlayer.getInventory().setHeldItemSlot(slot);
        }

        BlockState replaced = block.getState();
        block.setBlockData(cropOut.createBlockData(), false);

        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, below,
                botPlayer.getInventory().getItemInMainHand(), botPlayer, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            replaced.update(true, false);
            workLoc = null;
            phase = Phase.WAIT;
            waitTicks = 0;
            return true;
        }

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        removeOne(botPlayer, slot);
        planted++;

        workLoc = null;
        phase = Phase.WAIT;
        waitTicks = 0;
        return true;
    }

    private boolean tickChestTravel(Player botPlayer) {
        if (chestLoc == null) {
            phase = Phase.IDLE;
            return false;
        }

        Location loc = botPlayer.getLocation();
        double dx = chestLoc.getX() + 0.5 - loc.getX();
        double dz = chestLoc.getZ() + 0.5 - loc.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq < ARRIVE_SQ && Math.abs(chestLoc.getY() - loc.getY()) < 2.0) {
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            phase = Phase.CHEST_OPEN;
            chestOpenTimer = 10;
            return true;
        }

        boolean blocked = !context.movementController.canWalkStraightTo(chestLoc);
        boolean tooFar = distSq > 256.0;
        if (blocked || tooFar) {
            boolean exhausted = context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size();
            if (exhausted && repathCooldown <= 0) {
                context.pathfindingController.calculatePathAsync(loc, chestLoc);
                repathCooldown = 20;
            }
            if (!context.currentPath.isEmpty() && context.pathNodeIndex < context.currentPath.size()) {
                context.movementController.followPath();
                return true;
            }
            context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_TRAVEL);
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return true;
        }

        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
        return true;
    }

    private boolean tickChestOpen(Player botPlayer, ServerPlayer handle) {
        Location eye = botPlayer.getEyeLocation();
        double dx = (chestLoc.getX() + 0.5) - eye.getX();
        double dy = (chestLoc.getY() + 0.5) - eye.getY();
        double dz = (chestLoc.getZ() + 0.5) - eye.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_UTILITY);
        context.requestLookPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, flat))),
                BotAIContext.LOOK_UTILITY);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (chestOpenTimer-- > 0) return true;

        Block block = chestLoc.getBlock();
        ItemStack[] contents = ShulkerIO.read(block);
        ShulkerIO.openVisual(block);
        try {
            block.getWorld().playSound(chestLoc, Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
        } catch (Throwable ignored) {
        }

        if (chestReportTo != null) {
            reportChestContents(contents);
        }

        chestReportTo = null;
        phase = Phase.CHEST_LINGER;
        chestOpenTimer = CHEST_LINGER_TICKS;
        return true;
    }

    private boolean tickChestLinger(Player botPlayer) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (chestLoc != null) {
            Location eye = botPlayer.getEyeLocation();
            double dx = (chestLoc.getX() + 0.5) - eye.getX();
            double dz = (chestLoc.getZ() + 0.5) - eye.getZ();
            context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_UTILITY);
        }

        if (chestOpenTimer-- > 0) return true;

        if (chestLoc != null) {
            Block block = chestLoc.getBlock();
            ShulkerIO.closeVisual(block);
            try {
                block.getWorld().playSound(chestLoc, Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.0f);
            } catch (Throwable ignored) {
            }
        }

        chestLoc = null;
        phase = Phase.IDLE;
        return true;
    }

    private void reportChestContents(ItemStack[] contents) {
        String name = context.bot.getName();
        if (contents == null) {
            chestReportTo.sendMessage("\u00a7cThat's not a container.");
            return;
        }
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (ItemStack s : contents) {
            if (s == null || s.getType().isAir()) continue;
            counts.merge(s.getType(), s.getAmount(), Integer::sum);
        }
        if (counts.isEmpty()) {
            chestReportTo.sendMessage("\u00a77" + name + " checked the chest \u2014 it's empty.");
            return;
        }
        StringBuilder sb = new StringBuilder("\u00a7e" + name + " checked the chest: \u00a7f");
        boolean first = true;
        for (Map.Entry<Material, Integer> e : counts.entrySet()) {
            if (!first) sb.append("\u00a77, \u00a7f");
            sb.append(e.getValue()).append("x ").append(e.getKey().name().toLowerCase());
            first = false;
        }
        chestReportTo.sendMessage(sb.toString());
    }

    private Location findWork(Player botPlayer) {
        if (world == null) return null;
        boolean anySeed = hasAnySeed(botPlayer);
        Location botLoc = botPlayer.getLocation();

        Location best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = plotY - 1; y <= plotY + 1; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    boolean candidate = isMatureCrop(b) || (anySeed && isReplantableFarmland(b));
                    if (!candidate) continue;

                    double dx = (x + 0.5) - botLoc.getX();
                    double dz = (z + 0.5) - botLoc.getZ();
                    double d = dx * dx + dz * dz;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = new Location(world, x, y, z);
                    }
                }
            }
        }
        return best;
    }

    private boolean isMatureCrop(Block b) {
        Material t = b.getType();
        if (!CROP_TO_SEED.containsKey(t)) return false;
        if (!(b.getBlockData() instanceof Ageable a)) return false;
        return a.getAge() >= a.getMaximumAge();
    }

    private boolean isReplantableFarmland(Block b) {
        if (!b.getType().isAir()) return false;
        return b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND;
    }

    private boolean hasAnySeed(Player p) {
        for (Material seed : SEED_TO_CROP.keySet()) {
            if (findSlot(p, seed) >= 0) return true;
        }
        return false;
    }

    private Material firstAvailableSeed(Player p) {
        for (Material seed : SEED_TO_CROP.keySet()) {
            if (findSlot(p, seed) >= 0) return seed;
        }
        return null;
    }

    private int findSlot(Player p, Material want) {
        return context.inventoryController.ensureInHotbar(p, it -> it.getType() == want);
    }

    private void removeOne(Player p, int slot) {
        ItemStack s = p.getInventory().getItem(slot);
        if (s == null) return;
        if (s.getAmount() <= 1) {
            p.getInventory().setItem(slot, null);
        } else {
            s.setAmount(s.getAmount() - 1);
            p.getInventory().setItem(slot, s);
        }
        p.updateInventory();
    }

    private void lookAtWork(Player botPlayer) {
        Location eye = botPlayer.getEyeLocation();
        double dx = (workLoc.getX() + 0.5) - eye.getX();
        double dy = (workLoc.getY() + 0.5) - eye.getY();
        double dz = (workLoc.getZ() + 0.5) - eye.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        context.requestLookYaw((float) Math.toDegrees(Math.atan2(-dx, dz)), BotAIContext.LOOK_UTILITY);
        context.requestLookPitch((float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, flat))),
                BotAIContext.LOOK_UTILITY);
    }

    private void sendDestroyStage(Block block, int stage) {
        stage = Math.max(0, Math.min(9, stage));
        if (stage == lastDestroyStage) return;
        lastDestroyStage = stage;
        sendDestroyPacket(block.getX(), block.getY(), block.getZ(), stage);
    }

    private void clearDestroyStage() {
        if (lastDestroyStage < 0 || breakingBlock == null) {
            lastDestroyStage = -1;
            return;
        }
        sendDestroyPacket(breakingBlock.getX(), breakingBlock.getY(), breakingBlock.getZ(), -1);
        lastDestroyStage = -1;
        breakingBlock = null;
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
