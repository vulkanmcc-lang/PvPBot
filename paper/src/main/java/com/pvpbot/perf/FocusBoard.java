package com.pvpbot.perf;

import com.pvpbot.BotManager;
import com.pvpbot.PvPBot;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FocusBoard {
    private FocusBoard() {
    }

    private static Map<String, Map<UUID, Integer>> byFaction = new HashMap<>();

    private static final String NO_FACTION = "";

    public static void rebuild(BotManager manager) {
        if (manager == null) {
            byFaction = new HashMap<>();
            return;
        }

        Map<String, Map<UUID, Integer>> next = new HashMap<>();

        java.util.List<PvPBot> bots;
        synchronized (manager.getBots()) {
            bots = new java.util.ArrayList<>(manager.getBots().values());
        }

        for (PvPBot bot : bots) {
            if (bot == null || !bot.isAlive() || bot.getAI() == null) continue;
            Player t = bot.getAI().getContext().target;
            if (t == null) continue;

            String faction = manager.getPlayerFaction(bot.getUUID());
            String key = faction == null ? NO_FACTION : faction.toLowerCase(java.util.Locale.ROOT);
            next.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(t.getUniqueId(), 1, Integer::sum);
        }

        byFaction = next;
    }

    public static int alliesOn(String faction, UUID target) {
        if (target == null) return 0;
        Map<UUID, Integer> m = byFaction.get(
                faction == null ? NO_FACTION : faction.toLowerCase(java.util.Locale.ROOT));
        if (m == null) return 0;
        return m.getOrDefault(target, 0);
    }
}
