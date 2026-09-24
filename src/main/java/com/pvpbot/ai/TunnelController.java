package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class TunnelController {
    private static final int MAX_BLOCKS = 24;

    private static boolean isUndiggable(Material m) {
        if (m == Material.BEDROCK || m == Material.BARRIER
                || m == Material.END_PORTAL_FRAME || m == Material.OBSIDIAN
                || m == Material.CRYING_OBSIDIAN || m == Material.REINFORCED_DEEPSLATE
                || m == Material.ANCIENT_DEBRIS) {
            return true;
        }

        return m == Material.LAVA || m == Material.WATER;
    }

    private final BotAIContext context;

    private boolean active = false;
    private int blocksBroken = 0;
    private int dirX = 0;
    private int dirZ = 0;
    private int verticalBias = 0;
    private Location goal;

    private Block breaking;
    private int breakTicksTotal;
    private int breakTicksElapsed;
    private int lastStage = -1;

    private int cooldown = 0;

    public TunnelController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return active;
    }

    public String debugLine() {
        return "tunnel=" + (active ? "YES" : "no") + " broke=" + blocksBroken
                + " dir=" + dirX + "," + dirZ + " vert=" + verticalBias + " cd=" + cooldown;
    }

    public boolean begin(Player botPlayer, Location dest) {
        if (active || cooldown > 0 || botPlayer == null || dest == null) return false;
        if (!context.settings.isTunnelWhenStuck()) return false;
        if (dest.getWorld() != botPlayer.getWorld()) return false;

        Location here = botPlayer.getLocation();
        double dx = dest.getX() - here.getX();
        double dz = dest.getZ() - here.getZ();
        double dy = dest.getY() - here.getY();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return false;

        if (Math.abs(dx) >= Math.abs(dz)) {
            dirX = dx > 0 ? 1 : -1;
            dirZ = 0;
        } else {
            dirX = 0;
            dirZ = dz > 0 ? 1 : -1;
        }

        verticalBias = dy > 3.0 ? 1 : dy < -3.0 ? -1 : 0;

        goal = dest.clone();
        active = true;
        blocksBroken = 0;
        breaking = null;
        return true;
    }

    public void abort() {
        clearStage();
        active = false;
        breaking = null;
        blocksBroken = 0;
        goal = null;
    }

    public boolean tick(Player botPlayer) {
        if (cooldown > 0) cooldown--;
        if (!active) return false;

        ServerPlayer h = context.bot.getHandle();
        if (h == null || botPlayer == null) {
            abort();
            return false;
        }

        if (context.target != null || context.fleeing) {
            abort();
            cooldown = 40;
            return false;
        }

        if (blocksBroken >= MAX_BLOCKS) {
            abort();
            cooldown = 200;
            return false;
        }

        if (goal != null && botPlayer.getLocation().distance(goal) < 2.0) {
            abort();
            return false;
        }

        Location loc = botPlayer.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        int aheadX = bx + dirX;
        int aheadZ = bz + dirZ;
        int targetFeetY = by + (verticalBias > 0 ? 1 : 0);

        org.bukkit.World w = loc.getWorld();

        Block head = w.getBlockAt(aheadX, targetFeetY + 1, aheadZ);
        Block feet = w.getBlockAt(aheadX, targetFeetY, aheadZ);
        Block floor = w.getBlockAt(aheadX, by - 1, aheadZ);

        if (isHazardNear(w, aheadX, by, aheadZ)) {
            abort();
            cooldown = 200;
            return false;
        }

        Block toBreak = null;
        if (isSolidDiggable(head) && canAttemptBreak(botPlayer, head)) toBreak = head;
        else if (isSolidDiggable(feet) && canAttemptBreak(botPlayer, feet)) toBreak = feet;
        else if (verticalBias < 0 && isSolidDiggable(floor) && canAttemptBreak(botPlayer, floor)) toBreak = floor;

        if (toBreak == null) {
            clearStage();
            breaking = null;
            context.movementController.worldDirToInputs(h, dirX, dirZ);
            if (verticalBias > 0 && h.onGround()) context.movementController.requestJump();
            return true;
        }

        return chew(botPlayer, h, toBreak);
    }

    private boolean isSolidDiggable(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;

        if (com.pvpbot.nav.NavGrid.thinBlocking(m)) return !isUndiggable(m);
        if (!m.isSolid()) return false;
        return !isUndiggable(m);
    }

    private static boolean isToolFreeBreak(Material m) {
        String n = m.name();
        return n.endsWith("_LEAVES") || m == Material.VINE || m == Material.GLOW_LICHEN
                || m == Material.COBWEB || m == Material.SNOW || n.endsWith("_CARPET");
    }

    private boolean canAttemptBreak(Player p, Block b) {
        if (isToolFreeBreak(b.getType())) return true;
        return findDiggingTool(p) >= 0;
    }

    private boolean isHazardNear(org.bukkit.World w, int x, int y, int z) {
        for (int dy = -1; dy <= 2; dy++) {
            for (int d = 1; d <= 2; d++) {
                Material m = w.getBlockAt(x + dirX * (d - 1), y + dy, z + dirZ * (d - 1)).getType();
                if (m == Material.LAVA || m == Material.WATER) return true;
            }
        }
        return false;
    }

    private boolean chew(Player botPlayer, ServerPlayer h, Block block) {
        if (breaking == null || !breaking.equals(block)) {
            clearStage();
            breaking = block;
            equipTool(botPlayer, block);
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
        context.requestLookPitch(
                (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.001, flat))),
                BotAIContext.LOOK_UTILITY);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (breakTicksElapsed % 4 == 0) {
            h.swing(InteractionHand.MAIN_HAND, true);
            context.packetBroadcaster.broadcastAnimation(h, 0);
        }

        breakTicksElapsed++;
        sendStage(block, (breakTicksElapsed * 10) / Math.max(1, breakTicksTotal));

        if (breakTicksElapsed < breakTicksTotal) return true;

        clearStage();
        breaking = null;

        org.bukkit.event.block.BlockBreakEvent event =
                new org.bukkit.event.block.BlockBreakEvent(block, botPlayer);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            abort();
            cooldown = 400;
            return false;
        }

        block.breakNaturally(botPlayer.getInventory().getItemInMainHand());
        blocksBroken++;
        return true;
    }

    private int findDiggingTool(Player p) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s == null) continue;
            String n = s.getType().name();
            if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")) return i;
        }
        return -1;
    }

    private void equipTool(Player p, Block block) {
        String want = preferredToolSuffix(block.getType());
        int slot = context.inventoryController.ensureInHotbar(
                p, it -> it.getType().name().endsWith(want));
        if (slot < 0) {
            slot = context.inventoryController.ensureInHotbar(
                    p, it -> it.getType().name().endsWith("_PICKAXE"));
        }
        if (slot >= 0 && slot <= 8) {
            p.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
        }
    }

    private String preferredToolSuffix(Material m) {
        String n = m.name();
        if (n.contains("LOG") || n.contains("PLANK") || n.contains("WOOD")
                || n.contains("FENCE") || n.contains("DOOR")) {
            return "_AXE";
        }
        if (n.contains("DIRT") || n.contains("GRAVEL") || n.contains("SAND")
                || n.contains("GRASS_BLOCK") || n.contains("CLAY") || n.contains("SNOW")
                || n.contains("SOUL_")) {
            return "_SHOVEL";
        }
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
            if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL")) {
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
        int ticks = (int) Math.ceil(30.0 * hardness / Math.max(0.01, speed));
        return Math.max(2, Math.min(ticks, 200));
    }

    private void sendStage(Block block, int stage) {
        stage = Math.max(0, Math.min(9, stage));
        if (stage == lastStage) return;
        lastStage = stage;
        sendStagePacket(block.getX(), block.getY(), block.getZ(), stage);
    }

    private void clearStage() {
        if (lastStage < 0 || breaking == null) {
            lastStage = -1;
            return;
        }
        sendStagePacket(breaking.getX(), breaking.getY(), breaking.getZ(), -1);
        lastStage = -1;
    }

    private void sendStagePacket(int x, int y, int z, int stage) {
        try {
            ServerPlayer h = context.bot.getHandle();
            if (h == null) return;
            context.packetBroadcaster.sendPacketToAll(
                    new net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket(
                            h.getId(), new net.minecraft.core.BlockPos(x, y, z), stage));
        } catch (Throwable ignored) {
        }
    }
}
