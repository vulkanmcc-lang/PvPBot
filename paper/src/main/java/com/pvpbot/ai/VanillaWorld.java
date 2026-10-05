package com.pvpbot.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

// Placing, breaking and right-clicking blocks the way a client does it:
// through the server's own handlers (ServerPlayerGameMode.useItemOn /
// destroyBlock), against a real block face, in reach. Vanilla then does
// everything else - placement rules (no placing into an entity, onto
// nothing, or into a solid block), block states from the click, the item
// leaving the hand, sounds, BlockPlace/BlockBreak/PlayerInteract events,
// item drops, tool durability, hunger. Bots used to write blocks straight
// into the world: free floating placements, no drops, no tool wear.
public final class VanillaWorld {

    // Block interaction range (4.5) plus the server's 1-block allowance for
    // a player's reported position.
    public static final double REACH = 5.5;

    private VanillaWorld() {
    }

    // ---- placing

    // Place the item in hotbar `slot` into `target` by clicking a face of
    // `support` (or of any neighbour that can be clicked when it's null).
    // `want`, if given, is the exact state the caller needs (a schematic's
    // stair facing, a top slab): the click is aimed for it, and if the
    // block still lands turned another way the bot "re-aims" it to that
    // state - what a player would get by looking the right way first.
    public static boolean place(BotAIContext ctx, Player bp, int slot, Block target,
                                Block support, BlockData want) {
        return place(ctx, bp, slot, target, support, want, null);
    }

    // yaw: face this way while clicking (beds and doors take their facing
    // from the player's), pitch still toward the click. Null = look at it.
    public static boolean place(BotAIContext ctx, Player bp, int slot, Block target,
                                Block support, BlockData want, Float yaw) {
        if (ctx == null || bp == null || target == null) return false;
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return false;
        if (slot < 0 || slot > 8) return false;
        org.bukkit.inventory.ItemStack item = bp.getInventory().getItem(slot);
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return false;
        if (!replaceable(target)) return false;

        BlockFace face = null;
        if (support != null && clickable(support)) face = support.getFace(target);
        if (face == null || face == BlockFace.SELF || !face.isCartesian()) {
            support = null;
            for (BlockFace f : ORDER) {
                Block n = target.getRelative(f);
                if (!clickable(n)) continue;
                if (!inReach(h, faceCenter(n, f.getOppositeFace()))) continue;
                support = n;
                face = f.getOppositeFace();
                break;
            }
        }
        if (support == null || face == null) return false;

        Vec3 hit = faceCenter(support, face);
        // A top slab / upside-down stair: click the upper half of a side
        // face, as a player does.
        if (want != null && wantsTopHalf(want) && face != BlockFace.UP && face != BlockFace.DOWN) {
            hit = new Vec3(hit.x, support.getY() + 0.75, hit.z);
        }
        if (!inReach(h, hit)) return false;

        if (bp.getInventory().getHeldItemSlot() != slot) {
            bp.getInventory().setHeldItemSlot(slot);
            ctx.packetBroadcaster.broadcastEquipment();
        }
        if (yaw != null) {
            Vec3 eye = h.getEyePosition();
            double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz))));
            VanillaUse.face(ctx, h, yaw, pitch);
        } else {
            faceTowards(ctx, h, hit);
        }

        Material before = target.getType();
        boolean wasSneaking = h.isShiftKeyDown();
        // Clicking a chest/door/lever would use it instead of placing -
        // players hold sneak for that.
        boolean sneak = support.getType().isInteractable();
        if (sneak && !wasSneaking) h.setShiftKeyDown(true);
        InteractionResult result;
        try {
            BlockHitResult click = new BlockHitResult(hit, direction(face),
                    new BlockPos(support.getX(), support.getY(), support.getZ()), false);
            result = h.gameMode.useItemOn(h, h.level(), h.getMainHandItem(), InteractionHand.MAIN_HAND, click);
        } catch (Throwable t) {
            result = null;
        } finally {
            if (sneak && !wasSneaking) h.setShiftKeyDown(false);
        }
        boolean placed = result != null && result.consumesAction()
                && (target.getType() != before || !replaceable(target));
        if (!placed) return false;

        if (want != null && target.getType() == want.getMaterial() && !target.getBlockData().matches(want)) {
            target.setBlockData(want, false);
        }
        h.swing(InteractionHand.MAIN_HAND, true);
        ctx.packetBroadcaster.broadcastAnimation(h, 0);
        ctx.packetBroadcaster.broadcastEquipment();
        return true;
    }

    public static boolean place(BotAIContext ctx, Player bp, int slot, Block target) {
        return place(ctx, bp, slot, target, null, null);
    }

    // Is there any face around `target` the bot can click to place into it?
    public static boolean hasSupport(BotAIContext ctx, Block target) {
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return false;
        for (BlockFace f : ORDER) {
            Block n = target.getRelative(f);
            if (clickable(n) && inReach(h, faceCenter(n, f.getOppositeFace()))) return true;
        }
        return false;
    }

    // ---- breaking

    // Break it with whatever is in the main hand, as the end of a mining
    // swing: the BlockBreakEvent, the drops (silk touch / fortune), tool
    // durability and exhaustion all come from vanilla. False if refused.
    public static boolean breakBlock(BotAIContext ctx, Block b) {
        if (ctx == null || b == null || b.getType().isAir()) return false;
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return false;
        try {
            return h.gameMode.destroyBlock(new BlockPos(b.getX(), b.getY(), b.getZ()));
        } catch (Throwable t) {
            return false;
        }
    }

    // Ticks this bot needs to mine `b` with what it holds right now, by
    // vanilla's own formula (tool, Efficiency, Haste/Mining Fatigue, in
    // water, off the ground). -1 = it can't be mined at all.
    public static int breakTicks(BotAIContext ctx, Block b) {
        ServerPlayer h = ctx == null ? null : ctx.bot.getHandle();
        if (h == null || b == null) return -1;
        try {
            BlockPos pos = new BlockPos(b.getX(), b.getY(), b.getZ());
            float perTick = h.level().getBlockState(pos).getDestroyProgress(h, h.level(), pos);
            if (perTick <= 0f) return -1;
            if (perTick >= 1f) return 1;
            return (int) Math.ceil(1.0 / perTick);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- buckets: a real right click aimed at the block

    // Pour the water/lava bucket in hotbar `slot` into the air cell `into`,
    // by aiming at the face of a solid block beside it (below first).
    public static boolean pourInto(BotAIContext ctx, Player bp, int slot, Block into) {
        ServerPlayer h = ctx == null ? null : ctx.bot.getHandle();
        if (h == null || into == null) return false;
        for (BlockFace f : ORDER) {
            Block n = into.getRelative(f);
            if (n.getType().isAir() || n.isLiquid()) continue;
            BlockFace face = f.getOppositeFace();
            if (!inReach(h, n, face)) continue;
            float[] aim = aimAt(h, n, face);
            return VanillaUse.useFromHotbar(ctx, bp, slot, aim[0], aim[1]).used();
        }
        return false;
    }

    // Aim the bucket in `slot` straight at `at` (a water/lava source to
    // scoop up, or a non-full block like a cobweb whose outline the click
    // lands on).
    public static boolean useBucketOn(BotAIContext ctx, Player bp, int slot, Block at) {
        ServerPlayer h = ctx == null ? null : ctx.bot.getHandle();
        if (h == null || at == null) return false;
        Vec3 c = new Vec3(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        if (!inReach(h, c)) return false;
        Vec3 eye = h.getEyePosition();
        double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz))));
        return VanillaUse.useFromHotbar(ctx, bp, slot, yaw, pitch).used();
    }

    // ---- using a block (doors, gates, trapdoors, levers, buttons)

    public static boolean interact(BotAIContext ctx, Block b) {
        if (ctx == null || b == null) return false;
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return false;
        Vec3 eye = h.getEyePosition();
        BlockFace face = nearestFace(b, eye);
        Vec3 hit = faceCenter(b, face);
        if (!inReach(h, hit)) return false;
        faceTowards(ctx, h, hit);
        boolean wasSneaking = h.isShiftKeyDown();
        if (wasSneaking) h.setShiftKeyDown(false);
        try {
            BlockHitResult click = new BlockHitResult(hit, direction(face),
                    new BlockPos(b.getX(), b.getY(), b.getZ()), false);
            InteractionResult r = h.gameMode.useItemOn(h, h.level(), h.getMainHandItem(),
                    InteractionHand.MAIN_HAND, click);
            if (r != null && r.consumesAction()) {
                h.swing(InteractionHand.MAIN_HAND, true);
                ctx.packetBroadcaster.broadcastAnimation(h, 0);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (wasSneaking) h.setShiftKeyDown(true);
        }
    }

    // ---- using the item in a hotbar slot on a block (flint and steel on
    // TNT or a netherrack top, seeds on farmland, a bone meal...)

    public static boolean useOn(BotAIContext ctx, Player bp, int slot, Block b, BlockFace face) {
        if (ctx == null || bp == null || b == null || slot < 0 || slot > 8) return false;
        ServerPlayer h = ctx.bot.getHandle();
        if (h == null) return false;
        if (face == null) face = nearestFace(b, h.getEyePosition());
        Vec3 hit = faceCenter(b, face);
        if (!inReach(h, hit)) return false;
        if (bp.getInventory().getHeldItemSlot() != slot) {
            bp.getInventory().setHeldItemSlot(slot);
            ctx.packetBroadcaster.broadcastEquipment();
        }
        faceTowards(ctx, h, hit);
        boolean wasSneaking = h.isShiftKeyDown();
        if (wasSneaking) h.setShiftKeyDown(false);
        try {
            BlockHitResult click = new BlockHitResult(hit, direction(face),
                    new BlockPos(b.getX(), b.getY(), b.getZ()), false);
            InteractionResult r = h.gameMode.useItemOn(h, h.level(), h.getMainHandItem(),
                    InteractionHand.MAIN_HAND, click);
            if (r != null && r.consumesAction()) {
                h.swing(InteractionHand.MAIN_HAND, true);
                ctx.packetBroadcaster.broadcastAnimation(h, 0);
                ctx.packetBroadcaster.broadcastEquipment();
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (wasSneaking) h.setShiftKeyDown(true);
        }
    }

    // ---- aiming a bucket / flint and steel / seeds at a block face

    // {yaw, pitch} that put the crosshair on the middle of `face` of `b`.
    public static float[] aimAt(ServerPlayer h, Block b, BlockFace face) {
        Vec3 hit = faceCenter(b, face);
        Vec3 eye = h.getEyePosition();
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) Math.toDegrees(-Math.atan2(dy, Math.max(1.0E-4, flat)))};
    }

    public static boolean inReach(ServerPlayer h, Block b, BlockFace face) {
        return inReach(h, faceCenter(b, face));
    }

    // ---- helpers

    private static final BlockFace[] ORDER = {
            BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP};

    private static boolean clickable(Block b) {
        Material m = b.getType();
        return !m.isAir() && m != Material.WATER && m != Material.LAVA && !replaceableType(m);
    }

    // Air, fluids and anything a placement just replaces (grass, snow
    // layer, vines...).
    public static boolean replaceable(Block b) {
        Material m = b.getType();
        return m.isAir() || m == Material.WATER || m == Material.LAVA || replaceableType(m);
    }

    private static boolean replaceableType(Material m) {
        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN
                || m == Material.LARGE_FERN || m == Material.DEAD_BUSH || m == Material.SNOW
                || m == Material.VINE || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.STRUCTURE_VOID;
    }

    private static boolean wantsTopHalf(BlockData d) {
        if (d instanceof org.bukkit.block.data.type.Slab s) {
            return s.getType() == org.bukkit.block.data.type.Slab.Type.TOP;
        }
        if (d instanceof org.bukkit.block.data.type.Stairs s) {
            return s.getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
        }
        if (d instanceof org.bukkit.block.data.type.TrapDoor t) {
            return t.getHalf() == org.bukkit.block.data.Bisected.Half.TOP;
        }
        return false;
    }

    private static Vec3 faceCenter(Block b, BlockFace face) {
        return new Vec3(b.getX() + 0.5 + face.getModX() * 0.5,
                b.getY() + 0.5 + face.getModY() * 0.5,
                b.getZ() + 0.5 + face.getModZ() * 0.5);
    }

    private static BlockFace nearestFace(Block b, Vec3 eye) {
        BlockFace best = BlockFace.UP;
        double bestD = Double.MAX_VALUE;
        for (BlockFace f : ORDER) {
            Vec3 c = faceCenter(b, f);
            double d = c.distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = f;
            }
        }
        return best;
    }

    private static boolean inReach(ServerPlayer h, Vec3 hit) {
        return h.getEyePosition().distanceToSqr(hit) <= REACH * REACH;
    }

    private static void faceTowards(BotAIContext ctx, ServerPlayer h, Vec3 hit) {
        Vec3 eye = h.getEyePosition();
        double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(1.0E-4, flat)));
        VanillaUse.face(ctx, h, yaw, pitch);
    }

    // The yaw a player faces when looking toward `f`.
    public static float yawOf(BlockFace f) {
        return switch (f) {
            case SOUTH -> 0f;
            case WEST -> 90f;
            case NORTH -> 180f;
            case EAST -> -90f;
            default -> 0f;
        };
    }

    private static Direction direction(BlockFace f) {
        return switch (f) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            default -> Direction.WEST;
        };
    }
}
