package com.pvpbot.ai;

import com.pvpbot.BotAI;
import com.pvpbot.BotManager;
import java.util.concurrent.ThreadLocalRandom;

import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import com.pvpbot.perf.PlayerSnapshot;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class TargetingController {
    private static final double GUARD_INTERCEPT_RADIUS = 12.0;

    private static final double INVIS_CONFUSE_RADIUS = 20.0;

    private static final int CONFUSE_TICKS = 120;

    private static final int ARMOUR_GIVES_AWAY = 2;

    private final BotAIContext context;

    public TargetingController(BotAIContext context) {
        this.context = context;
    }

    public void updateDamageTracking(Player botPlayer) {
        double currentHealth = botPlayer.getHealth();
        if (context.lastHealth < 0) context.lastHealth = currentHealth;

        if (currentHealth < context.lastHealth) {
            context.selfHurtTicks = 10;

            context.buffScanCooldown = 0;

            if (!context.externallyNotifiedDamage) {
                ServerPlayer handle = context.bot.getHandle();
                if (handle != null) {
                    double bx = handle.getX();
                    double by = handle.getY();
                    double bz = handle.getZ();

                    PlayerSnapshot.WorldView view = PlayerSnapshot.forWorld(botPlayer.getWorld());
                    double closestEnemyDist = 25.0;
                    Player closestEnemy = null;

                    for (int i = 0; i < view.count && i < view.players.length && i < view.x.length && i < view.y.length && i < view.z.length; i++) {
                        Player p = view.players[i];
                        if (p == null || p == botPlayer) continue;

                        double dx = view.x[i] - bx;
                        double dy = view.y[i] - by;
                        double dz = view.z[i] - bz;
                        double dist = dx * dx + dy * dy + dz * dz;

                        if (dist >= closestEnemyDist) continue;
                        if (isFriendly(p)) continue;

                        closestEnemyDist = dist;
                        closestEnemy = p;
                    }

                    if (closestEnemy != null) {
                        context.lastDamager = closestEnemy;
                        context.lastDamageTime = context.tickCounter;
                        context.movementController.applyIncomingKnockback(botPlayer, closestEnemy);
                    }
                }
            } else if (context.lastDamager != null) {
                context.movementController.applyIncomingKnockback(botPlayer, context.lastDamager);
            }
        }
        context.externallyNotifiedDamage = false;

        if (context.lastDamager != null
                && (context.tickCounter - context.lastDamageTime) > context.DAMAGE_MEMORY_TICKS) {
            context.lastDamager = null;
        }

        context.lastHealth = currentHealth;
    }

    private boolean hasFactionCombatTarget() {
        return context.factionCombatTarget != null
                && context.factionCombatTicks > 0
                && isValidTarget(context.factionCombatTarget);
    }

    public void findTarget() {
        if (context.factionCombatTicks > 0) {
            context.factionCombatTicks--;

            if (context.factionCombatTicks <= 0) {
                context.factionCombatTarget = null;
            }
        }

        if (context.forcedTarget != null) {
            if (TargetFilter.isEngageable(context.forcedTarget, context.bot.getBukkitPlayer())) {
                if (context.target != context.forcedTarget) switchTo(context.forcedTarget);
                return;
            }
            context.forcedTarget = null;
        }

        if (!context.settings.isHostile() || isFactionAttackStopped()) {
            context.target = null;
            context.assistTarget = null;
            context.lastDamager = null;
            return;
        }

        if (context.factionCombatTarget != null) {
            if (context.factionCombatTicks > 0
                    && isValidTarget(context.factionCombatTarget)) {
                if (context.target != context.factionCombatTarget) {
                    switchTo(context.factionCombatTarget);
                }
                return;
            }

            context.factionCombatTarget = null;
            context.factionCombatTicks = 0;
        }

        Player pBot = context.bot.getBukkitPlayer();
        ServerPlayer handle = context.bot.getHandle();
        if (pBot == null || handle == null) return;

        final double bx = handle.getX();
        final double by = handle.getY();
        final double bz = handle.getZ();

        boolean guarding = context.guardAnchor != null
                && context.guardAnchor.getWorld() == pBot.getWorld();

        final double cx, cy, cz;
        final double range;
        if (guarding) {
            cx = context.guardAnchor.getX();
            cy = context.guardAnchor.getY();
            cz = context.guardAnchor.getZ();
            range = context.guardRadius;
        } else {
            cx = bx;
            cy = by;
            cz = bz;
            range = context.settings.getTargetRange();
        }
        double rangeSq = range * range;

        double dropSq = guarding
                ? context.guardLeash * context.guardLeash
                : rangeSq * 1.5625;

        Player current = (context.target != null && isValidTarget(context.target)) ? context.target : null;
        if (current != null && distSqTo(current, cx, cy, cz) > dropSq) {
            current = null;
        }

        boolean commitExpired = (context.tickCounter - context.lastTargetSwitchTick)
                >= BotAIContext.TARGET_COMMIT_TICKS

                || context.attackDeniedTicks > 20;

        Player damager = (context.lastDamager != null && isValidTarget(context.lastDamager))
                ? context.lastDamager : null;
        context.lastDamager = null;

        if (damager != null && damager != current
                && distSqTo(damager, cx, cy, cz) < (guarding ? dropSq : rangeSq)
                && (current == null || commitExpired)) {
            switchTo(damager);
            return;
        }

        Player assist = context.assistTarget;
        if (assist != null) {
            boolean expired = (context.tickCounter - context.assistTargetTick)
                    > BotAIContext.ASSIST_MEMORY_TICKS;
            if (expired || !isValidTarget(assist)) {
                context.assistTarget = null;
            } else if (assist != current
                    && distSqTo(assist, cx, cy, cz)
                    < rangeSq * BotAIContext.ASSIST_RANGE_FACTOR_SQ
                    && (current == null || commitExpired)) {
                context.assistTarget = null;
                switchTo(assist);
                return;
            }
        }

        if (current != null && context.targetCacheTicks++ < 5) {
            context.target = current;
            return;
        }
        context.targetCacheTicks = 0;

        PlayerSnapshot.WorldView view = PlayerSnapshot.forWorld(pBot.getWorld());
        Player best = null;
        double closest = rangeSq;

        boolean bodyguard = context.guardMode == BotAIContext.GuardMode.LEADER
                && context.guardAnchor != null;
        double sweepSq = closest;
        if (bodyguard) {
            double engage = Math.min(context.guardRadius, GUARD_INTERCEPT_RADIUS);
            sweepSq = Math.min(closest, engage * engage);
        }

        updateConfusion(view, cx, cy, cz);
        boolean confused = context.confusedTicks > 0;

        boolean threatMode = !confused && context.settings.isThreatTargeting();
        boolean wounded = pBot.getHealth() <= pBot.getMaxHealth() * 0.4;
        finalistCount = 0;
        double bestEff = Double.MAX_VALUE;
        bestFinalistDistSq = closest;
        myFaction = com.pvpbot.PvPBotPlugin.getInstance().getBotManager()
                .getPlayerFaction(context.bot.getUUID());

        // assistOnly is unconditional - unlike noAutoTargetWhileIdle it
        // doesn't back off just because the bot has a leader assigned
        // (following a leader alone shouldn't license picking fights).
        boolean suppressProactiveTargeting = context.settings.isAssistOnly()
                || (context.settings.isNoAutoTargetWhileIdle() && context.hasNoActiveOrders());

        if (context.factionCombatTicks <= 0) {
            context.factionCombatTarget = null;
        }

        int candidateCount = 0;

        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null || p == pBot) continue;

            double dx = view.x[i] - cx;
            double dy = view.y[i] - cy;
            double dz = view.z[i] - cz;
            double d = dx * dx + dy * dy + dz * dz;

            if (confused) {
                if (d >= sweepSq) continue;

                if (isEffectivelyInvisible(p) && d > 4.0) continue;

                if (ThreadLocalRandom.current().nextInt(++candidateCount) == 0) best = p;
                continue;
            }

            if (d >= sweepSq) continue;
            if (isFriendly(p)) continue;

            context.enemyMemory.noteSeen(p, view.x[i], view.y[i], view.z[i], context.tickCounter);

            if (!threatMode) {
                sweepSq = d;
                closest = d;
                best = p;
                continue;
            }

            double eff = d * cheapWeight(p, wounded);
            if (eff < bestEff) bestEff = eff;
            pushFinalist(p, d, eff);
        }

        if (threatMode && finalistCount > 0) {
            best = refineFinalists(pBot, cx, cy, cz);
            closest = bestFinalistDistSq;
        }

        if (current == null) {

            if (context.factionCombatTicks > 0
                    && context.factionCombatTarget != null
                    && isValidTarget(context.factionCombatTarget)) {

                switchTo(context.factionCombatTarget);
                return;
            }

            if (!suppressProactiveTargeting) {
                Player ally = allyEngagement(pBot, bx, by, bz);
                if (ally != null) {
                    switchTo(ally);
                    return;
                }
            }

            if (!suppressProactiveTargeting && best != null) {
                switchTo(best);
                return;
            }

            context.target = null;
            return;
        }

        if (best != null && best != current && commitExpired) {
            double curDistSq = distSqTo(current, cx, cy, cz);
            if (closest < curDistSq * BotAIContext.SWITCH_CLOSER_FACTOR_SQ) {
                switchTo(best);
                return;
            }
        }
        context.target = current;
    }

    private static final int MAX_FINALISTS = 4;
    private final Player[] finalists = new Player[MAX_FINALISTS];
    private final double[] finalistDistSq = new double[MAX_FINALISTS];
    private final double[] finalistScore = new double[MAX_FINALISTS];
    private int finalistCount = 0;
    private double bestFinalistDistSq = Double.MAX_VALUE;
    private String myFaction;

    private double cheapWeight(Player p, boolean botIsWounded) {
        double w = 1.0;

        double frac = p.getMaxHealth() <= 0 ? 1.0 : p.getHealth() / p.getMaxHealth();
        w *= 0.45 + 0.55 * frac;

        int allies = com.pvpbot.perf.FocusBoard.alliesOn(myFaction, p.getUniqueId());
        if (context.target == p && allies > 0) allies--;

        if (allies == 1) {
            w *= 0.80;
        } else if (allies == 2) {
            w *= 0.95;
        } else if (allies >= 3) {
            w *= 1.0 + 0.35 * Math.min(allies - 2, 4);
        }

        if (botIsWounded) w *= 1.0 + 0.1 * Math.min(allies, 3);

        return w;
    }

    private void pushFinalist(Player p, double distSq, double score) {
        if (finalistCount < MAX_FINALISTS) {
            finalists[finalistCount] = p;
            finalistDistSq[finalistCount] = distSq;
            finalistScore[finalistCount] = score;
            finalistCount++;
            return;
        }
        int worst = 0;
        for (int i = 1; i < MAX_FINALISTS; i++) {
            if (finalistScore[i] > finalistScore[worst]) worst = i;
        }
        if (score < finalistScore[worst]) {
            finalists[worst] = p;
            finalistDistSq[worst] = distSq;
            finalistScore[worst] = score;
        }
    }

    private Player refineFinalists(Player pBot, double cx, double cy, double cz) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        Player bestBlind = null;
        double bestBlindScore = Double.MAX_VALUE;

        for (int i = 0; i < finalistCount; i++) {
            Player p = finalists[i];
            if (p == null) continue;

            double score = finalistScore[i] * gearWeight(p);

            boolean visible = p == context.target
                    || context.combatController.hasLineOfSight(pBot, p);

            if (visible) {
                if (score < bestScore) {
                    bestScore = score;
                    best = p;
                    bestFinalistDistSq = finalistDistSq[i];
                }
            } else if (score < bestBlindScore) {
                bestBlindScore = score;
                bestBlind = p;
            }
        }

        if (best != null) return best;

        if (bestBlind != null) {
            bestFinalistDistSq = distSqTo(bestBlind, cx, cy, cz);
            return bestBlind;
        }
        return null;
    }

    private double gearWeight(Player p) {
        double armour = 0.0;
        try {
            org.bukkit.inventory.EntityEquipment eq = p.getEquipment();
            if (eq != null) {
                armour += tierOf(eq.getHelmet());
                armour += tierOf(eq.getChestplate());
                armour += tierOf(eq.getLeggings());
                armour += tierOf(eq.getBoots());
            }
        } catch (Throwable ignored) {
        }

        return 1.0 + 0.09 * armour;
    }

    private static double tierOf(org.bukkit.inventory.ItemStack item) {
        if (item == null) return 0.0;
        String n = item.getType().name();
        if (n.startsWith("NETHERITE_")) return 1.0;
        if (n.startsWith("DIAMOND_")) return 0.85;
        if (n.startsWith("IRON_")) return 0.55;
        if (n.startsWith("CHAINMAIL_")) return 0.4;
        if (n.startsWith("GOLDEN_")) return 0.3;
        if (n.startsWith("LEATHER_")) return 0.2;
        return 0.0;
    }

    private static double distSqTo(Player p, double bx, double by, double bz) {
        try {
            ServerPlayer h = ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
            double dx = h.getX() - bx;
            double dy = h.getY() - by;
            double dz = h.getZ() - bz;
            return dx * dx + dy * dy + dz * dz;
        } catch (Throwable t) {
            org.bukkit.Location l = p.getLocation();
            double dx = l.getX() - bx;
            double dy = l.getY() - by;
            double dz = l.getZ() - bz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private void switchTo(Player p) {
        if (p != context.target) {
            context.investigateTarget = null;
            context.investigateTicks = 0;
            context.critPhase = BotAIContext.CritPhase.IDLE;
            context.critPhaseTicks = 0;
            context.critFallTicks = 0;
            context.criticalRetryTicks = 0;
            context.comboCount = 0;
            context.lastLandedAttackTick = -1000;
            context.lastTargetSwitchTick = context.tickCounter;
            context.targetCacheTicks = 0;

            context.pathFailures = 0;
            context.chaseBlockedTicks = 0;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            context.pathComplete = false;

            context.shieldPredictTicks = 0;
            context.shieldHoldTicks = 0;
            context.shieldFlickerTicks = 0;
            context.enemyPredictCooldown = 0;
            context.lastEnemyDist = Double.MAX_VALUE;

            context.attackDeniedTicks = 0;
        }
        context.target = p;
    }

    public void notifyDamage(Player attacker) {
        if (!context.settings.isHostile() || isFactionAttackStopped()) return;
        if (attacker == null) return;
        context.lastDamager = attacker;
        context.lastDamageTime = context.tickCounter;
        context.externallyNotifiedDamage = true;

        context.forceFullTicks = 60;
    }

    private Player allyEngagement(Player pBot, double bx, double by, double bz) {
        if (!context.settings.isTeamTarget()) return null;

        com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
        if (mgr == null || myFaction == null) return null;

        double radius = context.settings.getTeamTargetRadius();
        double radiusSq = radius * radius;

        Player bestAllyTarget = null;
        double bestSq = Double.MAX_VALUE;

        for (com.pvpbot.PvPBot mate : mgr.getFactionBots(myFaction)) {
            if (mate == null || !mate.isAlive()) continue;
            if (mate.getUUID().equals(context.bot.getUUID())) continue;

            Player matePlayer = mate.getBukkitPlayer();
            if (matePlayer == null || matePlayer.getWorld() != pBot.getWorld()) continue;
            if (distSqTo(matePlayer, bx, by, bz) > radiusSq) continue;

            Player theirTarget;
            try {
                BotAI mateAI = mate.getAI();
                if (mateAI == null) continue;
                theirTarget = mateAI.getContext().target;
            } catch (Throwable t) {
                continue;
            }
            if (theirTarget == null) continue;
            if (!isValidTarget(theirTarget)) continue;
            if (isFriendly(theirTarget)) continue;

            double d = distSqTo(theirTarget, bx, by, bz);
            if (d > radiusSq) continue;
            if (d < bestSq) {
                bestSq = d;
                bestAllyTarget = theirTarget;
            }
        }

        return bestAllyTarget;
    }

    public void notifyAllyAttacked(Player attacker) {
        if (!context.settings.isHostile()) return;
        if (attacker == null || isFriendly(attacker)) return;

        context.factionCombatTarget = attacker;
        context.factionCombatTicks = BotAIContext.FACTION_COMBAT_MEMORY_TICKS;

        notifyLeaderAttacked(attacker);
    }

    public void notifyLeaderAttacked(Player attacker) {
        if (!context.settings.isHostile()) return;
        if (attacker == null || isFriendly(attacker)) return;

        context.factionCombatTarget = attacker;
        context.factionCombatTicks = BotAIContext.FACTION_COMBAT_MEMORY_TICKS;

        context.assistTarget = attacker;
        context.assistTargetTick = context.tickCounter;
        context.forceFullTicks = 60;
    }

    public void notifyFactionAttack(Player enemy) {
        if (enemy == null) return;

        BotManager mgr = PvPBotPlugin.getInstance().getBotManager();
        if (mgr == null) return;

        String faction = mgr.getPlayerFaction(context.bot.getUUID());
        if (faction == null) return;

        if (!mgr.isLeader(context.bot.getUUID())) return;

        for (PvPBot mate : mgr.getFactionBots(faction)) {
            if (mate == null || !mate.isAlive()) continue;

            if (mate.getUUID().equals(context.bot.getUUID())) continue;

            BotAIContext mateContext = mate.getAI().getContext();

            mateContext.factionCombatTarget = enemy;
            mateContext.factionCombatTicks = 120;
            mateContext.forceFullTicks = 60;
        }
    }

    private void updateConfusion(PlayerSnapshot.WorldView view,
                                 double cx, double cy, double cz) {
        if (context.confusedTicks > 0) context.confusedTicks--;
        if (!context.settings.isInvisibilityConfusion()) {
            context.confusedTicks = 0;
            return;
        }

        double radiusSq = INVIS_CONFUSE_RADIUS * INVIS_CONFUSE_RADIUS;
        for (int i = 0; i < view.count; i++) {
            Player p = view.players[i];
            if (p == null) continue;

            double dx = view.x[i] - cx;
            double dy = view.y[i] - cy;
            double dz = view.z[i] - cz;
            if (dx * dx + dy * dy + dz * dz >= radiusSq) continue;

            if (!isEffectivelyInvisible(p)) continue;

            context.confusedTicks = CONFUSE_TICKS;
            return;
        }
    }

    private boolean isEffectivelyInvisible(Player p) {
        try {
            if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)) return false;
        } catch (Throwable t) {
            return false;
        }

        int worn = 0;
        for (org.bukkit.inventory.ItemStack piece : p.getInventory().getArmorContents()) {
            if (piece != null && !piece.getType().isAir()) worn++;
        }
        return worn < ARMOUR_GIVES_AWAY;
    }

    private boolean isValidTarget(Player p) {
        if (!TargetFilter.isEngageable(p, context.bot.getBukkitPlayer())) return false;

        if (p == context.forcedTarget) return true;

        if (context.confusedTicks <= 0 && isFriendly(p)) return false;

        if (context.guardAnchor != null) {
            Location a = context.guardAnchor;
            if (p.getWorld() != a.getWorld()) return false;
            double dx = p.getX() - a.getX();
            double dy = p.getY() - a.getY();
            double dz = p.getZ() - a.getZ();
            if (dx * dx + dy * dy + dz * dz > context.guardLeash * context.guardLeash) return false;
        }
        return true;
    }

    private boolean isFriendly(Player p) {
        BotManager mgr = PvPBotPlugin.getInstance().getBotManager();
        return mgr != null && mgr.isFriendly(context.bot.getUUID(), p.getUniqueId());
    }

    private boolean isFactionAttackStopped() {
        BotManager mgr = PvPBotPlugin.getInstance().getBotManager();
        if (mgr == null) return false;
        String faction = mgr.getPlayerFaction(context.bot.getUUID());
        return faction != null && mgr.isFactionStopAttack(faction);
    }
}
