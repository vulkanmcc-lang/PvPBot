package com.pvpbot.ai;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

// "Commander kill Steve": the faction's commander doesn't just run off - it
// walks up to whoever gave the order, faces them, nods twice ("yes sir"),
// and only then goes after the target.
public class CommanderController {
    private static final int PHASE_APPROACH = 1;
    private static final int PHASE_NOD = 2;

    private static final double REPORT_DISTANCE = 2.6;   // close enough to salute
    private static final double STAND_OFF = 1.8;         // where to stop in front of the leader
    private static final int APPROACH_TIMEOUT = 20 * 25; // can't get there: skip the ceremony
    private static final int NOD_TICKS = 22;
    private static final float NOD_DEPTH = 32f;

    private final BotAIContext context;

    public CommanderController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return context.commanderPhase != 0;
    }

    public void start(Player speaker, Player victim) {
        context.commanderSpeaker = speaker.getUniqueId();
        context.commanderVictim = victim;
        context.commanderPhase = PHASE_APPROACH;
        context.commanderPhaseTicks = 0;
        context.forcedTarget = null;
        context.target = null;
        context.movementController.clearFormationOrder();
    }

    public void stop() {
        if (context.commanderPhase == 0) return;
        context.commanderPhase = 0;
        context.commanderPhaseTicks = 0;
        context.commanderSpeaker = null;
        context.commanderVictim = null;
        context.movementController.clearFormationOrder();
    }

    public boolean handle(Player botPlayer) {
        if (context.commanderPhase == 0) return false;
        Player victim = context.commanderVictim;
        if (victim == null || !victim.isOnline() || victim.isDead()) {
            stop();
            return false;
        }
        UUID sid = context.commanderSpeaker;
        Player speaker = sid == null ? null : Bukkit.getPlayer(sid);
        if (speaker == null || !speaker.isOnline() || speaker.isDead()
                || speaker.getWorld() != botPlayer.getWorld()) {
            attack(victim);
            return false;
        }

        // Orders first, fighting after.
        context.target = null;
        context.commanderPhaseTicks++;
        context.navBranch = "COMMANDER";

        if (context.commanderPhase == PHASE_APPROACH) {
            Location me = botPlayer.getLocation();
            Location sp = speaker.getLocation();
            double dist = me.distance(sp);
            if (dist <= REPORT_DISTANCE || context.commanderPhaseTicks > APPROACH_TIMEOUT) {
                context.movementController.clearFormationOrder();
                context.forwardInput = 0f;
                context.strafeInput = 0f;
                context.commanderPhase = PHASE_NOD;
                context.commanderPhaseTicks = 0;
                faceSpeaker(botPlayer, speaker, 0f);
                return true;
            }
            // Stop just in front of the leader, on our side of them.
            Vector away = me.toVector().subtract(sp.toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-4) away = sp.getDirection().setY(0);
            if (away.lengthSquared() < 1.0e-4) away = new Vector(0, 0, 1);
            away.normalize().multiply(STAND_OFF);
            Location slot = sp.clone().add(away);
            slot.setYaw((float) Math.toDegrees(Math.atan2(away.getX(), -away.getZ())));
            context.formationSlot = slot;
            context.formationTicks = 40;
            context.suppressSprint = false;
            if (!context.movementController.handleFormationMarch()) {
                context.forwardInput = 0f;
                context.strafeInput = 0f;
            }
            return true;
        }

        // Nod: two dips of the head while looking the leader in the eye.
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        int t = context.commanderPhaseTicks;
        float dip = 0f;
        if (t < 16) {
            double wave = Math.sin(t * Math.PI / 4.0);
            dip = (float) Math.max(0.0, wave) * NOD_DEPTH;
        }
        faceSpeaker(botPlayer, speaker, dip);
        if (t >= NOD_TICKS) {
            attack(victim);
            return false;
        }
        return true;
    }

    private void faceSpeaker(Player botPlayer, Player speaker, float dip) {
        Location eye = botPlayer.getEyeLocation();
        Location to = speaker.getEyeLocation();
        double dx = to.getX() - eye.getX(), dy = to.getY() - eye.getY(), dz = to.getZ() - eye.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.max(0.05, Math.hypot(dx, dz))));
        pitch = Math.max(-90f, Math.min(90f, pitch + dip));
        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, false);
    }

    private void attack(Player victim) {
        stop();
        context.releaseStandDown();
        context.bot.setForcedTarget(victim);
    }
}
