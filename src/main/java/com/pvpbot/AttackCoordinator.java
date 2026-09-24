package com.pvpbot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class AttackCoordinator {
    private static final int INVULNERABILITY_TICKS = 11;

    private static final int MAX_JITTER_TICKS = 3;

    private static final int ENTRY_EXPIRY_TICKS = 200;

    private static final class Booking {
        UUID holder;
        int freeAtTick;
        int lastTouchedTick;
    }

    private final Map<UUID, Booking> bookings = new HashMap<>();
    private int tick;

    public void tick() {
        tick++;
        if (tick % 100 != 0) return;
        bookings.entrySet().removeIf(e -> tick - e.getValue().lastTouchedTick > ENTRY_EXPIRY_TICKS);
    }

    public int currentTick() {
        return tick;
    }

    public boolean mayAttack(UUID victim, UUID attacker) {
        if (victim == null || attacker == null) return true;

        Booking booking = bookings.get(victim);
        if (booking == null) return true;

        if (attacker.equals(booking.holder)) return true;

        return tick >= booking.freeAtTick;
    }

    public void recordHit(UUID victim, UUID attacker) {
        if (victim == null || attacker == null) return;

        Booking booking = bookings.computeIfAbsent(victim, k -> new Booking());
        booking.holder = attacker;
        booking.lastTouchedTick = tick;
        booking.freeAtTick = tick + INVULNERABILITY_TICKS
                + ThreadLocalRandom.current().nextInt(MAX_JITTER_TICKS + 1);
    }

    public int ticksUntilFree(UUID victim, UUID attacker) {
        Booking booking = bookings.get(victim);
        if (booking == null || attacker.equals(booking.holder)) return 0;
        return Math.max(0, booking.freeAtTick - tick);
    }

    public void clear() {
        bookings.clear();
    }
}
