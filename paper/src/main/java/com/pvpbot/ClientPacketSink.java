package com.pvpbot;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class ClientPacketSink extends ChannelOutboundHandlerAdapter {
    private final ServerPlayer handle;

    private final AtomicReference<Vec3> pendingAbsolute = new AtomicReference<>();

    private final AtomicReference<double[]> pendingFallback = new AtomicReference<>();

    private static final double ECHO_EPSILON_SQ = 0.01 * 0.01;

    private static final double MIN_SHOVE_SQ = 0.05 * 0.05;

    public ClientPacketSink(ServerPlayer handle) {
        this.handle = handle;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        try {
            inspect(msg);
        } catch (Throwable ignored) {
        }
        promise.trySuccess();
    }

    private void inspect(Object msg) {
        if (msg instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.getId() == handle.getId()) {
                double xa = readVelocityComponent(motion, "xa", "getXa");
                double ya = readVelocityComponent(motion, "ya", "getYa");
                double za = readVelocityComponent(motion, "za", "getZa");
                pendingAbsolute.set(new Vec3(clampVelocity(xa), clampVelocity(ya), clampVelocity(za)));
            }
        }

    }

    private static double readVelocityComponent(Object target, String... names) {
        for (String name : names) {
            try {
                Method m = target.getClass().getMethod(name);
                Object value = m.invoke(target);
                if (!(value instanceof Number n)) continue;
                Class<?> ret = m.getReturnType();
                boolean integral = ret == int.class || ret == Integer.class
                        || ret == short.class || ret == Short.class
                        || ret == long.class || ret == Long.class;
                return integral ? n.doubleValue() / 8000.0 : n.doubleValue();
            } catch (ReflectiveOperationException ignored) {
            }
        }
        throw new IllegalStateException("No compatible velocity accessor found on "
                + target.getClass().getName());
    }

    private static double clampVelocity(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(-5.0, Math.min(5.0, v));
    }

    public void offerFallback(double strength, double dirX, double dirZ) {
        if (strength <= 0) return;
        if (dirX == 0 && dirZ == 0) dirZ = 1.0;
        pendingFallback.set(new double[]{strength, dirX, dirZ});
    }

    private static void applyVanillaKnockback(ServerPlayer target,
                                              double strength, double dirX, double dirZ) {
        strength *= 0.7;
        strength *= 1.0 - knockbackResistance(target);
        if (strength <= 0) return;

        Vec3 current = target.getDeltaMovement();
        Vec3 impulse = new Vec3(dirX, 0.0, dirZ).normalize().scale(strength);
        target.setDeltaMovement(
                current.x / 2.0 - impulse.x,
                target.onGround() ? Math.min(0.4, current.y / 2.0 + strength) : current.y,
                current.z / 2.0 - impulse.z);
        markImpulse(target);
    }

    private static volatile Object knockbackResistanceAttribute;
    private static volatile Method attributeValueGetter;
    private static volatile boolean knockbackResistanceLookupFailed;

    private static double knockbackResistance(ServerPlayer target) {
        if (knockbackResistanceLookupFailed) return 0.0;
        try {
            if (knockbackResistanceAttribute == null) {
                Class<?> attributes = Class.forName(
                        "net.minecraft.world.entity.ai.attributes.Attributes");
                Field field;
                try {
                    field = attributes.getField("KNOCKBACK_RESISTANCE");
                } catch (NoSuchFieldException e) {
                    field = attributes.getField("GENERIC_KNOCKBACK_RESISTANCE");
                }
                knockbackResistanceAttribute = field.get(null);
            }
            if (attributeValueGetter == null) {
                for (Method m : ServerPlayer.class.getMethods()) {
                    if (m.getName().equals("getAttributeValue")
                            && m.getParameterCount() == 1
                            && m.getReturnType() == double.class) {
                        attributeValueGetter = m;
                        break;
                    }
                }
                if (attributeValueGetter == null) throw new NoSuchMethodException("getAttributeValue");
            }
            Object value = attributeValueGetter.invoke(target, knockbackResistanceAttribute);
            return value instanceof Number n ? Math.max(0.0, Math.min(1.0, n.doubleValue())) : 0.0;
        } catch (Throwable t) {
            knockbackResistanceLookupFailed = true;
            return 0.0;
        }
    }

    private static void markImpulse(ServerPlayer target) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("hasImpulse");
                f.setAccessible(true);
                f.setBoolean(target, true);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    public boolean applyPending(ServerPlayer target) {
        boolean applied = false;

        Vec3 absolute = pendingAbsolute.getAndSet(null);
        if (absolute != null) {
            Vec3 current = target.getDeltaMovement();

            boolean meaningfulShove = absolute.lengthSqr() > MIN_SHOVE_SQ;
            if (meaningfulShove && absolute.subtract(current).lengthSqr() > ECHO_EPSILON_SQ) {
                target.setDeltaMovement(absolute);
                markImpulse(target);
                applied = true;
            }
        }

        double[] fallback = pendingFallback.getAndSet(null);
        if (fallback != null && !applied) {
            applyVanillaKnockback(target, fallback[0], fallback[1], fallback[2]);
            applied = true;
        }

        return applied;
    }
}
