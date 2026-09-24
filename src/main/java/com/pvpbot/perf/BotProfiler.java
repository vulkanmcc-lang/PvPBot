package com.pvpbot.perf;

public final class BotProfiler {
    public enum Section {
        TARGETING,
        HEALING,
        INVENTORY,
        HAZARD,
        HOLE_ESCAPE,
        RESTOCK,
        BUILD,
        BRIDGE_SCAN,
        MOVEMENT,
        COMBAT,
        PHYSICS,
        PATH_SNAPSHOT
    }

    private static final Section[] SECTIONS = Section.values();

    public static boolean enabled = false;

    private static final long[] totalNanos = new long[SECTIONS.length];
    private static final long[] calls = new long[SECTIONS.length];

    private static final Section[] stack = new Section[8];
    private static final long[] startedAt = new long[8];

    private static final long[] childNanos = new long[8];
    private static int depth = 0;

    private static long windowStart = System.nanoTime();

    private BotProfiler() {
    }

    public static void start(Section s) {
        if (!enabled || depth >= stack.length) return;
        stack[depth] = s;
        startedAt[depth] = System.nanoTime();
        childNanos[depth] = 0L;
        depth++;
    }

    public static void end() {
        if (!enabled || depth <= 0) return;
        depth--;
        Section s = stack[depth];
        if (s == null) return;

        long elapsed = System.nanoTime() - startedAt[depth];
        totalNanos[s.ordinal()] += elapsed - childNanos[depth];
        calls[s.ordinal()]++;
        if (depth > 0) childNanos[depth - 1] += elapsed;
    }

    public static void resetStack() {
        depth = 0;
    }

    public static void reset() {
        java.util.Arrays.fill(totalNanos, 0L);
        java.util.Arrays.fill(calls, 0L);
        windowStart = System.nanoTime();
        depth = 0;
    }

    public static double windowSeconds() {
        return Math.max(0.001, (System.nanoTime() - windowStart) / 1_000_000_000.0);
    }

    public static double msPerTick(Section s) {
        double ticks = Math.max(1.0, windowSeconds() * 20.0);
        return (totalNanos[s.ordinal()] / 1_000_000.0) / ticks;
    }

    public static long callCount(Section s) {
        return calls[s.ordinal()];
    }

    public static Section[] rankedSections() {
        Section[] out = SECTIONS.clone();
        java.util.Arrays.sort(out, (a, b) -> Long.compare(totalNanos[b.ordinal()], totalNanos[a.ordinal()]));
        return out;
    }

    public static boolean hasSamples() {
        for (long c : calls) if (c > 0) return true;
        return false;
    }
}
