package com.pvpbot.ai;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public final class TargetFilter {
    private TargetFilter() {
    }

    public static boolean isEngageable(Player candidate, Player bot) {
        if (candidate == null || bot == null) return false;
        if (!candidate.isOnline() || candidate.isDead()) return false;
        if (candidate.getWorld() != bot.getWorld()) return false;

        GameMode mode = candidate.getGameMode();
        return mode != GameMode.SPECTATOR && mode != GameMode.CREATIVE;
    }
}
