package com.pvpbot.ai;

import com.pvpbot.BotManager;
import com.pvpbot.PvPBotPlugin;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

// Gets a bot out of a hole it can't walk out of - a pit, a shaft, a dug-out
// mining area - when where it needs to be (its ordered spot, its leader, the
// commander's leader, a forced target) is up above.
//
// Watching costs next to nothing: one distance per tick. Only when the bot
// has made no progress toward a destination that is 2+ blocks higher for
// three seconds, and it's boxed in (a pit by the hole check, or the path
// finder keeps failing), does it act:
//   1. walk to the wall on the side of the destination (pillaring next to a
//      wall means the top of that wall is the step off),
//   2. pillar up - jump, place a block under itself - until it's level with
//      a walkable edge (or the destination),
//   3. step off onto it, then hand back to normal navigation.
// A bot with no blocks is given some cobblestone, like the pillar-to order.
public class ClimbOutController {
    private static final int STALL_TICKS = 60;
    private static final double MIN_RISE = 2.0;
    private static final int WALL_SEARCH = 6;
    private static final int APPROACH_TIMEOUT = 60;
    private static final int CLIMB_TIMEOUT = 20 * 25;
    private static final int STEP_TICKS = 20;
    private static final int GIFT_BLOCKS = 24;
    private static final int GIFT_COOLDOWN = 20 * 120;

    private enum Phase { WATCH, APPROACH, CLIMB, STEP }

    private final BotAIContext context;
    private Phase phase = Phase.WATCH;
    private double bestDist = Double.MAX_VALUE;
    private int noProgress;
    private int cooldown;
    private int phaseTicks;
    private int colX, colZ;
    private int startFeetY;
    private double destY;
    private Location dest;
    private int stepDx, stepDz;
    private Location stepFrom;
    private int lastGiftTick = Integer.MIN_VALUE / 2;

    public ClimbOutController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return phase != Phase.WATCH;
    }

    public void stop() {
        phase = Phase.WATCH;
        noProgress = 0;
        bestDist = Double.MAX_VALUE;
    }

    public String status() {
        return phase == Phase.WATCH ? "watch" : phase + " " + phaseTicks;
    }

    public boolean handle(Player botPlayer) {
        ServerPlayer h = context.bot.getHandle();
        if (botPlayer == null || h == null) return false;
        if (cooldown > 0) cooldown--;

        switch (phase) {
            case APPROACH -> {
                return tickApproach(botPlayer, h);
            }
            case CLIMB -> {
                return tickClimb(botPlayer, h);
            }
            case STEP -> {
                return tickStep(botPlayer, h);
            }
            default -> {
            }
        }

        if (cooldown > 0 || busy()) {
            noProgress = 0;
            bestDist = Double.MAX_VALUE;
            return false;
        }
        Location d = destination(botPlayer);
        if (d == null) {
            noProgress = 0;
            bestDist = Double.MAX_VALUE;
            return false;
        }
        Location me = botPlayer.getLocation();
        if (d.getY() - me.getY() < MIN_RISE) {
            noProgress = 0;
            bestDist = Double.MAX_VALUE;
            return false;
        }
        double dist = me.distance(d);
        if (dist < bestDist - 0.75) {
            bestDist = dist;
            noProgress = 0;
            return false;
        }
        if (++noProgress < STALL_TICKS) return false;

        // Stuck with the way up. Boxed in, or just a bad path?
        if (!context.movementController.isTrappedBelow(me) && context.pathFailures < 2) {
            noProgress = STALL_TICKS / 2;
            return false;
        }
        begin(botPlayer, d);
        return true;
    }

    // ---------------------------------------------------------------------

    private boolean busy() {
        if (context.bridging) return true;
        if (context.target != null && context.target != context.forcedTarget) return true; // combat has its own moves
        if (context.excavationController != null && context.excavationController.isActive()) return true;
        if (context.buildController != null && context.buildController.isBusy()) return true;
        if (context.reachController != null && context.reachController.isActive()) return true;
        if (context.archerController != null && context.archerController.isActive()) return true;
        if (context.tunnelController != null && context.tunnelController.isActive()) return true;
        if (context.miningController != null && context.miningController.isActive()) return true;
        if (context.areaMiningController != null && context.areaMiningController.isActive()) return true;
        if (context.farmController != null && context.farmController.isActive()) return true;
        if (context.deliveryController != null && context.deliveryController.isActive()) return true;
        return context.settings.isFrozen();
    }

    // Where the bot is trying to get to, if anywhere.
    private Location destination(Player botPlayer) {
        World w = botPlayer.getWorld();
        if (context.formationSlot != null && context.formationSlot.getWorld() == w) return context.formationSlot;
        if (context.commanderPhase == 1 && context.commanderSpeaker != null) {
            Player sp = Bukkit.getPlayer(context.commanderSpeaker);
            if (sp != null && sp.getWorld() == w) return sp.getLocation();
        }
        Player forced = context.forcedTarget;
        if (forced != null && forced.isOnline() && !forced.isDead() && forced.getWorld() == w) {
            return forced.getLocation();
        }
        if (context.guardAnchor != null && context.guardAnchor.getWorld() == w
                && context.guardAnchor.distanceSquared(botPlayer.getLocation()) > 9.0) {
            return context.guardAnchor;
        }
        BotManager mgr = PvPBotPlugin.getInstance().getBotManager();
        UUID leaderId = mgr == null ? null : mgr.getLeaderFor(context.bot.getUUID());
        if (leaderId != null && !leaderId.equals(context.bot.getUUID())) {
            Player leader = Bukkit.getPlayer(leaderId);
            if (leader != null && leader.isOnline() && !leader.isDead() && leader.getWorld() == w) {
                return leader.getLocation();
            }
        }
        return null;
    }

    private void begin(Player botPlayer, Location d) {
        dest = d.clone();
        destY = d.getY();
        Location me = botPlayer.getLocation();
        startFeetY = me.getBlockY();
        int[] col = wallColumn(me, d);
        colX = col[0];
        colZ = col[1];
        phase = (colX == me.getBlockX() && colZ == me.getBlockZ()) ? Phase.CLIMB : Phase.APPROACH;
        phaseTicks = 0;
        // Any walk order stays as it is: it just doesn't get the ticks
        // while we climb, and picks up again once we're out.
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    // A floor cell next to a wall, on the side facing the destination,
    // near the bot. Pillaring there puts the wall's top right beside us.
    private int[] wallColumn(Location me, Location d) {
        World w = me.getWorld();
        int bx = me.getBlockX(), by = me.getBlockY(), bz = me.getBlockZ();
        double ddx = d.getX() - me.getX(), ddz = d.getZ() - me.getZ();
        double dl = Math.max(0.001, Math.hypot(ddx, ddz));
        ddx /= dl;
        ddz /= dl;
        int[] best = {bx, bz};
        double bestScore = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = -WALL_SEARCH; dx <= WALL_SEARCH; dx++) {
            for (int dz = -WALL_SEARCH; dz <= WALL_SEARCH; dz++) {
                int x = bx + dx, z = bz + dz;
                if (!standable(w, x, by, z)) continue;
                double wallScore = Double.MAX_VALUE;
                for (int[] dir : dirs) {
                    if (!w.getBlockAt(x + dir[0], by, z + dir[1]).getType().isSolid()) continue;
                    // The wall is on the destination's side: better.
                    double facing = dir[0] * ddx + dir[1] * ddz;
                    wallScore = Math.min(wallScore, 2.0 - facing * 2.0);
                }
                if (wallScore == Double.MAX_VALUE) continue;
                double score = Math.hypot(dx, dz) + wallScore;
                if (score < bestScore) {
                    bestScore = score;
                    best = new int[]{x, z};
                }
            }
        }
        return best;
    }

    private static boolean standable(World w, int x, int y, int z) {
        Material floor = w.getBlockAt(x, y - 1, z).getType();
        if (!floor.isSolid() || floor == Material.MAGMA_BLOCK || floor == Material.CACTUS) return false;
        Material feet = w.getBlockAt(x, y, z).getType();
        Material head = w.getBlockAt(x, y + 1, z).getType();
        return !feet.isSolid() && !head.isSolid() && feet != Material.LAVA && head != Material.LAVA;
    }

    private boolean tickApproach(Player botPlayer, ServerPlayer h) {
        context.navBranch = "CLIMB_OUT";
        phaseTicks++;
        Location me = botPlayer.getLocation();
        double cx = colX + 0.5 - me.getX(), cz = colZ + 0.5 - me.getZ();
        double off = Math.hypot(cx, cz);
        if (off < 0.3 || phaseTicks > APPROACH_TIMEOUT) {
            // There (or can't get there): climb from where we stand.
            colX = me.getBlockX();
            colZ = me.getBlockZ();
            startFeetY = me.getBlockY();
            phase = Phase.CLIMB;
            phaseTicks = 0;
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return true;
        }
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-cx, cz)));
        context.movementController.worldDirToInputs(h, cx / off, cz / off, off < 1.0 ? 0.4f : 1.0f);
        return true;
    }

    private boolean tickClimb(Player botPlayer, ServerPlayer h) {
        context.navBranch = "CLIMB_OUT";
        phaseTicks++;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        if (phaseTicks > CLIMB_TIMEOUT) {
            finish(false);
            return false;
        }
        Location me = botPlayer.getLocation();
        World w = me.getWorld();
        int x = me.getBlockX(), z = me.getBlockZ();
        int feetY = me.getBlockY();

        if (h.onGround()) {
            // A walkable edge at our height (the top of the wall): step off.
            if (feetY > startFeetY) {
                int[] out = stepOut(w, x, feetY, z);
                if (out != null) {
                    stepDx = out[0];
                    stepDz = out[1];
                    stepFrom = me.clone();
                    phase = Phase.STEP;
                    phaseTicks = 0;
                    return true;
                }
            }
            if (me.getY() >= destY - 0.2) {
                finish(true);
                return false;
            }
            // Roof over our head: can't go up from here.
            if (w.getBlockAt(x, feetY + 2, z).getType().isSolid()) {
                finish(false);
                return false;
            }
        }

        if (context.inventoryController.findBlockSlot(botPlayer) < 0) {
            int now = Bukkit.getCurrentTick();
            if (now - lastGiftTick < GIFT_COOLDOWN) {
                finish(false);
                return false;
            }
            lastGiftTick = now;
            botPlayer.getInventory().addItem(new ItemStack(Material.COBBLESTONE, GIFT_BLOCKS));
            context.packetBroadcaster.broadcastEquipment();
        }
        context.movementController.pillarUpStep(botPlayer);
        return true;
    }

    // Open, floored neighbour at this height - preferring the destination side.
    private int[] stepOut(World w, int x, int y, int z) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int[] best = null;
        double bestFacing = -2;
        double ddx = dest.getX() - (x + 0.5), ddz = dest.getZ() - (z + 0.5);
        double dl = Math.max(0.001, Math.hypot(ddx, ddz));
        for (int[] d : dirs) {
            if (!standable(w, x + d[0], y, z + d[1])) continue;
            double facing = (d[0] * ddx + d[1] * ddz) / dl;
            if (facing > bestFacing) {
                bestFacing = facing;
                best = d;
            }
        }
        return best;
    }

    private boolean tickStep(Player botPlayer, ServerPlayer h) {
        context.navBranch = "CLIMB_OUT";
        phaseTicks++;
        Location me = botPlayer.getLocation();
        double moved = stepFrom == null ? 99 : Math.hypot(me.getX() - stepFrom.getX(), me.getZ() - stepFrom.getZ());
        if (moved > 1.3 || phaseTicks > STEP_TICKS) {
            finish(moved > 1.3);
            return false;
        }
        context.movementController.easeYawTo((float) Math.toDegrees(Math.atan2(-stepDx, stepDz)));
        context.movementController.worldDirToInputs(h, stepDx, stepDz, 1.0f);
        if (h.horizontalCollision && h.onGround()) context.movementController.requestJump();
        return true;
    }

    private void finish(boolean ok) {
        phase = Phase.WATCH;
        phaseTicks = 0;
        noProgress = 0;
        bestDist = Double.MAX_VALUE;
        cooldown = ok ? 40 : 200;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        context.pathFailures = 0;
    }
}
