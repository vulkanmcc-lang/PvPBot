package com.pvpbot.ai;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public class PacketBroadcaster {
    private final BotAIContext context;

    public static boolean USE_VANILLA_TRACKER = true;

    private static final int RESYNC_INTERVAL = 60;

    private long encX, encY, encZ;
    private byte lastYRotByte, lastXRotByte, lastHeadYawByte;
    private Vec3 lastSentMotion = Vec3.ZERO;
    private int resyncCounter = 0;
    private boolean initialised = false;

    public PacketBroadcaster(BotAIContext context) {
        this.context = context;
    }

    public void broadcastPosition() {
        if (USE_VANILLA_TRACKER) return;

        ServerPlayer h = context.bot.getHandle();
        if (h == null || h.isRemoved()) return;

        long nx = encode(h.getX());
        long ny = encode(h.getY());
        long nz = encode(h.getZ());
        byte yRotByte = degToByte(h.getYRot());
        byte xRotByte = degToByte(h.getXRot());
        byte headByte = degToByte(h.getYHeadRot());

        if (!initialised) {
            encX = nx; encY = ny; encZ = nz;
            lastYRotByte = yRotByte; lastXRotByte = xRotByte; lastHeadYawByte = headByte;
            initialised = true;
            return;
        }

        long dx = nx - encX;
        long dy = ny - encY;
        long dz = nz - encZ;

        boolean moved = dx != 0 || dy != 0 || dz != 0;
        boolean rotated = yRotByte != lastYRotByte || xRotByte != lastXRotByte;

        boolean deltaTooBig = dx < Short.MIN_VALUE || dx > Short.MAX_VALUE
                || dy < Short.MIN_VALUE || dy > Short.MAX_VALUE
                || dz < Short.MIN_VALUE || dz > Short.MAX_VALUE;

        if (++resyncCounter >= RESYNC_INTERVAL || deltaTooBig) {
            resyncCounter = 0;
            sendPacketToAll(new ClientboundTeleportEntityPacket(
                    h.getId(),
                    new net.minecraft.world.entity.PositionMoveRotation(
                            new Vec3(h.getX(), h.getY(), h.getZ()),
                            h.getDeltaMovement(),
                            h.getYRot(), h.getXRot()),
                    java.util.Collections.emptySet(),
                    h.onGround()));
            encX = nx; encY = ny; encZ = nz;
            lastYRotByte = yRotByte; lastXRotByte = xRotByte;
        } else if (moved && rotated) {
            sendPacketToAll(new ClientboundMoveEntityPacket.PosRot(
                    h.getId(), (short) dx, (short) dy, (short) dz,
                    yRotByte, xRotByte, h.onGround()));
            encX = nx; encY = ny; encZ = nz;
            lastYRotByte = yRotByte; lastXRotByte = xRotByte;
        } else if (moved) {
            sendPacketToAll(new ClientboundMoveEntityPacket.Pos(
                    h.getId(), (short) dx, (short) dy, (short) dz, h.onGround()));
            encX = nx; encY = ny; encZ = nz;
        } else if (rotated) {
            sendPacketToAll(new ClientboundMoveEntityPacket.Rot(
                    h.getId(), yRotByte, xRotByte, h.onGround()));
            lastYRotByte = yRotByte; lastXRotByte = xRotByte;
        }

        if (headByte != lastHeadYawByte) {
            sendPacketToAll(new ClientboundRotateHeadPacket(h, headByte));
            lastHeadYawByte = headByte;
        }

        Vec3 motion = h.getDeltaMovement();
        if (motion.distanceToSqr(lastSentMotion) > 0.0025) {
            sendPacketToAll(new ClientboundSetEntityMotionPacket(h.getId(), motion));
            lastSentMotion = motion;
        }
    }

    private static long encode(double coord) {
        return Math.round(coord * 4096.0D);
    }

    private static byte degToByte(float deg) {
        return (byte) Math.floor(deg * 256.0F / 360.0F);
    }

    public void broadcastAnimation(ServerPlayer h, int a) {
        sendPacketToAll(new ClientboundAnimatePacket(h, a));
    }

    public void broadcastEntityData() {
        ServerPlayer h = context.bot.getHandle();
        if (h == null) return;
        var d = h.getEntityData().getNonDefaultValues();
        if (d != null) sendPacketToAll(new ClientboundSetEntityDataPacket(h.getId(), d));
    }

    public void broadcastEvent(ServerPlayer h, byte e) {
        sendPacketToAll(new ClientboundEntityEventPacket(h, e));
    }

    public void broadcastEquipment() {
        ServerPlayer h = context.bot.getHandle();
        if (h == null) return;
        sendPacketToAll(new ClientboundSetEquipmentPacket(h.getId(), java.util.List.of(
                Pair.of(EquipmentSlot.MAINHAND, h.getItemBySlot(EquipmentSlot.MAINHAND)),
                Pair.of(EquipmentSlot.OFFHAND, h.getItemBySlot(EquipmentSlot.OFFHAND)),
                Pair.of(EquipmentSlot.HEAD, h.getItemBySlot(EquipmentSlot.HEAD)),
                Pair.of(EquipmentSlot.CHEST, h.getItemBySlot(EquipmentSlot.CHEST)),
                Pair.of(EquipmentSlot.LEGS, h.getItemBySlot(EquipmentSlot.LEGS)),
                Pair.of(EquipmentSlot.FEET, h.getItemBySlot(EquipmentSlot.FEET))
        )));
    }

    public void broadcastRotation(ServerPlayer h) {
        if (USE_VANILLA_TRACKER || h == null) return;
        sendPacketToAll(new ClientboundMoveEntityPacket.Rot(
                h.getId(), degToByte(h.getYRot()), degToByte(h.getXRot()), h.onGround()));
        sendPacketToAll(new ClientboundRotateHeadPacket(h, degToByte(h.getYHeadRot())));
    }

    public void broadcastEquipmentIfChanged() {
        if (context.equipmentBroadcastCooldown > 0) {
            context.equipmentBroadcastCooldown--;
            return;
        }

        ServerPlayer h = context.bot.getHandle();
        net.minecraft.world.item.ItemStack mainHand = h.getItemBySlot(EquipmentSlot.MAINHAND);
        net.minecraft.world.item.ItemStack offHand = h.getItemBySlot(EquipmentSlot.OFFHAND);

        boolean changed = (context.lastMainHand == null && !mainHand.isEmpty()) ||
                (context.lastMainHand != null && context.lastMainHand.isEmpty() != mainHand.isEmpty()) ||
                (context.lastMainHand != null && context.lastMainHand.getItem() != mainHand.getItem()) ||
                (context.lastOffHand == null && !offHand.isEmpty()) ||
                (context.lastOffHand != null && context.lastOffHand.isEmpty() != offHand.isEmpty()) ||
                (context.lastOffHand != null && context.lastOffHand.getItem() != offHand.getItem());

        if (changed) {
            broadcastEquipment();
            context.lastMainHand = mainHand.copy();
            context.lastOffHand = offHand.copy();
            context.equipmentBroadcastCooldown = 5;
        }
    }

    public void sendPacketToAll(net.minecraft.network.protocol.Packet<?> p) {
        Player self = context.bot.getBukkitPlayer();
        Player[] real = com.pvpbot.perf.PlayerSnapshot.getRealPlayers();
        int n = com.pvpbot.perf.PlayerSnapshot.getRealCount();
        for (int i = 0; i < n; i++) {
            Player player = real[i];
            if (player == null || player == self) continue;
            ServerPlayer sp = ((CraftPlayer) player).getHandle();
            if (sp != null && sp.connection != null) {
                sp.connection.send(p);
            }
        }
    }
}
