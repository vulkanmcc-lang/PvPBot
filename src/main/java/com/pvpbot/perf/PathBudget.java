package com.pvpbot.perf;

public final class PathBudget {
    public static int maxRequestsPerTick = 12;

    public static int reserveRequestsPerTick = 4;

    public static int maxChunksPerTick = 384;

    private static int requests = 0;
    private static int reserveUsed = 0;
    private static int chunks = 0;

    private static long totalRequests = 0;
    private static long totalChunks = 0;
    private static long totalDenied = 0;
    private static long totalStarvedGrants = 0;

    private PathBudget() {
    }

    public static void resetTick() {
        requests = 0;
        reserveUsed = 0;
        chunks = 0;
    }

    public static boolean tryConsume(int chunkCount, boolean starved) {
        if (chunks + chunkCount > maxChunksPerTick) {
            totalDenied++;
            return false;
        }

        if (requests < maxRequestsPerTick) {
            requests++;
        } else if (starved && reserveUsed < reserveRequestsPerTick) {
            reserveUsed++;
            totalStarvedGrants++;
        } else {
            totalDenied++;
            return false;
        }

        chunks += chunkCount;
        totalRequests++;
        totalChunks += chunkCount;
        return true;
    }

    public static boolean tryConsume(int chunkCount) {
        return tryConsume(chunkCount, false);
    }

    public static long getTotalSnapshots() {
        return totalRequests;
    }

    public static long getTotalChunks() {
        return totalChunks;
    }

    public static long getTotalDenied() {
        return totalDenied;
    }

    public static long getTotalStarvedGrants() {
        return totalStarvedGrants;
    }
}
