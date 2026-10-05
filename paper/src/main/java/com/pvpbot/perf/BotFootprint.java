package com.pvpbot.perf;

import com.pvpbot.BotManager;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

// What a bot costs the server just by being a player, trimmed down. Every
// bot is a real ServerPlayer, so out of the box vanilla treats it like
// someone with a client: it spawns mobs around it, loads and simulates
// chunks at the server's full view distance, serialises every one of those
// chunks into packets for its (fake) connection, and receives a move packet
// for every entity it can see - including every other bot. At 100 bots that
// is most of a tick before any AI runs. None of it is needed: bots read the
// world server-side.
//
//   performance.bot-affects-spawning   false  - no mob spawning / mob caps for bots
//   performance.bot-view-distance      3      - chunks kept loaded round a bot
//   performance.bot-simulation-distance 2     - chunks ticked round a bot
//   performance.bot-send-distance      2      - chunk packets built for its connection
//   performance.hide-teammate-bots     true   - bots on the same side don't track
//                                               each other (no packets between them)
//
// Real players still see every bot exactly as before.
public final class BotFootprint {
    private BotFootprint() {
    }

    // Applied once, right after a bot joins.
    public static void apply(Player bot) {
        PvPBotPlugin pl = PvPBotPlugin.getInstance();
        if (pl == null || bot == null) return;
        FileConfiguration c = pl.getConfig();
        try {
            bot.setAffectsSpawning(c.getBoolean("performance.bot-affects-spawning", false));
        } catch (Throwable ignored) {
        }
        int view = c.getInt("performance.bot-view-distance", 3);
        if (view > 0) {
            int sim = Math.max(2, Math.min(view, c.getInt("performance.bot-simulation-distance", 2)));
            int send = Math.max(2, Math.min(view, c.getInt("performance.bot-send-distance", 2)));
            try {
                bot.setViewDistance(Math.max(2, Math.min(32, view)));
            } catch (Throwable ignored) {
            }
            try {
                bot.setSimulationDistance(sim);
            } catch (Throwable ignored) {
            }
            try {
                bot.setSendViewDistance(send);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int refreshTicks = 0;

    // Every 5 s: bots that are on the same side stop tracking each other
    // (hidden from one another - they never fight, and a hidden entity is
    // also one the hider's projectiles pass through, so enemies stay
    // visible to keep bow/pearl fights working). Faction changes are
    // picked up on the next pass.
    public static void tick(BotManager manager) {
        if (++refreshTicks < 100) return;
        refreshTicks = 0;
        PvPBotPlugin pl = PvPBotPlugin.getInstance();
        if (pl == null || manager == null) return;
        boolean hide = pl.getConfig().getBoolean("performance.hide-teammate-bots", true);

        List<PvPBot> bots = new ArrayList<>();
        for (PvPBot b : manager.getBots().values()) {
            if (b.isAlive() && b.getBukkitPlayer() != null) bots.add(b);
        }
        for (int i = 0; i < bots.size(); i++) {
            PvPBot a = bots.get(i);
            Player pa = a.getBukkitPlayer();
            for (int j = i + 1; j < bots.size(); j++) {
                PvPBot b = bots.get(j);
                Player pb = b.getBukkitPlayer();
                boolean shouldHide = hide && manager.isFriendly(a.getUUID(), b.getUUID());
                set(pl, pa, pb, shouldHide);
                set(pl, pb, pa, shouldHide);
            }
        }
    }

    private static void set(PvPBotPlugin pl, Player viewer, Player other, boolean hidden) {
        try {
            if (hidden && viewer.canSee(other)) viewer.hidePlayer(pl, other);
            else if (!hidden && !viewer.canSee(other)) viewer.showPlayer(pl, other);
        } catch (Throwable ignored) {
        }
    }
}
