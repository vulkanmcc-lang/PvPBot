package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

// "everyone bow Steve": fight one player at range with a bow (or crossbow).
//
// Keeps a comfortable distance (backs off when rushed, closes in when out
// of range or out of sight), draws for a full-power shot, and aims with a
// small ballistics solve: it simulates the arrow's flight (vanilla drag and
// gravity) to find the pitch that lands on the target, and leads a moving
// target by where it'll be when the arrow gets there. Out of arrows (or no
// bow) -> melee as a normal forced target.
public class ArcherController {
    private static final double MIN_RANGE = 7.0;
    private static final double MAX_RANGE = 26.0;
    private static final int BOW_DRAW_TICKS = 22;
    private static final int CROSSBOW_DRAW_TICKS = 26;
    private static final double ARROW_SPEED = 3.0;
    private static final int MAX_ACTIVE_TICKS = 20 * 180;

    private final BotAIContext context;

    private UUID targetId;
    private boolean drawing;
    private int drawTicks;
    private int shotCooldown;
    private int activeTicks;
    private int repathCooldown;
    private int strafeDir = 1;
    private int strafeTicks;
    private Vector lastTargetPos;
    private Vector targetVel = new Vector();

    public ArcherController(BotAIContext context) {
        this.context = context;
    }

    public boolean isActive() {
        return targetId != null;
    }

    public String status() {
        return targetId == null ? "idle" : (drawing ? "drawing " + drawTicks : "aiming") + " cd=" + shotCooldown;
    }

    public static boolean hasBowAndArrows(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean bow = inv.contains(Material.BOW) || inv.contains(Material.CROSSBOW);
        if (!bow) return false;
        if (hasInfinityBow(p)) return true;
        return inv.contains(Material.ARROW) || inv.contains(Material.SPECTRAL_ARROW)
                || inv.contains(Material.TIPPED_ARROW);
    }

    private static boolean hasInfinityBow(Player p) {
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == Material.BOW
                    && it.getEnchantmentLevel(Enchantment.INFINITY) > 0) {
                return true;
            }
        }
        return false;
    }

    public void start(Player target) {
        stop();
        targetId = target.getUniqueId();
        context.releaseStandDown();
        context.movementController.clearFormationOrder();
        context.currentPath.clear();
        context.pathNodeIndex = 0;
    }

    public void stop() {
        if (drawing) {
            ServerPlayer h = context.bot.getHandle();
            if (h != null && h.isUsingItem()) h.stopUsingItem();
        }
        targetId = null;
        drawing = false;
        drawTicks = 0;
        activeTicks = 0;
        lastTargetPos = null;
    }

    public boolean handleArcher(Player botPlayer) {
        if (targetId == null) return false;
        ServerPlayer handle = context.bot.getHandle();
        if (botPlayer == null || handle == null) { stop(); return false; }

        Player target = org.bukkit.Bukkit.getPlayer(targetId);
        if (target == null) {
            var bot = com.pvpbot.PvPBotPlugin.getInstance().getBotManager().getBots().get(targetId);
            target = bot != null && bot.isAlive() ? bot.getBukkitPlayer() : null;
        }
        if (target == null || target.isDead() || target.getWorld() != botPlayer.getWorld()
                || ++activeTicks > MAX_ACTIVE_TICKS) {
            stop();
            return false;
        }
        if (context.fleeing || context.eating || context.drinkingPotionTimer > 0) return false;

        if (!hasBowAndArrows(botPlayer)) {
            // Out of arrows: finish it up close.
            Player t = target;
            stop();
            context.forcedTarget = t;
            context.target = t;
            return false;
        }

        if (shotCooldown > 0) shotCooldown--;
        if (repathCooldown > 0) repathCooldown--;
        trackVelocity(target);

        Location me = botPlayer.getLocation();
        double dist = me.distance(target.getLocation());
        boolean sight = botPlayer.hasLineOfSight(target);

        // ---- positioning
        context.suppressSprint = drawing;
        if (dist < MIN_RANGE) {
            Vector away = me.toVector().subtract(target.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize();
            context.movementController.worldDirToInputs(handle, away.getX(), away.getZ(), 1.0f);
            if (handle.horizontalCollision && handle.onGround()) context.movementController.requestJump();
        } else if (dist > MAX_RANGE || !sight) {
            walkTowards(botPlayer, target.getLocation());
        } else {
            // In range: small side-steps so we're not a sitting duck.
            if (--strafeTicks <= 0) {
                strafeDir = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
                strafeTicks = 20 + ThreadLocalRandom.current().nextInt(30);
            }
            context.forwardInput = 0f;
            context.strafeInput = drawing ? 0.2f * strafeDir : 0.5f * strafeDir;
        }

        // ---- aim (always, so the bot looks where it'll shoot)
        double[] aim = solveAim(botPlayer.getEyeLocation(), target);
        // Yaw and pitch in ONE request: two separate same-priority requests
        // drop the second, which left bots staring at the sky.
        context.requestLook((float) aim[0], (float) aim[1], BotAIContext.LOOK_COMBAT + 5, false);
        float yawErr = Math.abs(wrap((float) aim[0] - handle.getYRot()));
        float pitchErr = Math.abs((float) aim[1] - handle.getXRot());
        boolean onTarget = yawErr < 6f && pitchErr < 6f;

        // ---- draw & release
        int slot = bowSlot(botPlayer);
        if (slot < 0) return true;
        if (botPlayer.getInventory().getHeldItemSlot() != slot) {
            botPlayer.getInventory().setHeldItemSlot(slot);
            context.packetBroadcaster.broadcastEquipment();
            drawing = false;
        }
        boolean crossbow = botPlayer.getInventory().getItem(slot).getType() == Material.CROSSBOW;

        // Everything below is a real right click / release: vanilla works out
        // the arrow's power from how long it was drawn, picks the ammo, and
        // applies Power/Flame/Punch/Infinity/Multishot/Piercing, durability
        // and the shot sound - same as a player's bow.
        if (crossbow && crossbowLoaded(handle)) {
            drawing = false;
            if (shotCooldown > 0 || !sight || !onTarget || dist > MAX_RANGE + 6) return true;
            float[] shot = withAimError(aim);
            VanillaUse.face(context, handle, shot[0], shot[1]);
            if (VanillaUse.use(handle, InteractionHand.MAIN_HAND).used()) {
                handle.swing(InteractionHand.MAIN_HAND, true);
                context.packetBroadcaster.broadcastAnimation(handle, 0);
                context.packetBroadcaster.broadcastEquipment();
                shotCooldown = 10;
            }
            return true;
        }

        if (!drawing) {
            // A crossbow gets loaded whenever it's empty (no need to see them
            // yet); a bow is only drawn when there's a shot coming.
            boolean wantDraw = crossbow || (shotCooldown <= 0 && sight && dist <= MAX_RANGE + 6);
            if (wantDraw && VanillaUse.use(handle, InteractionHand.MAIN_HAND).used()) {
                context.packetBroadcaster.broadcastEntityData();
                drawing = true;
                drawTicks = 0;
            }
            return true;
        }

        if (!handle.isUsingItem()) {
            // Something else took the hands (a swap, a hit that cancelled it).
            drawing = false;
            return true;
        }

        drawTicks++;
        if (crossbow) {
            // Hold until fully charged, then let go - that's what loads it.
            if (drawTicks >= CROSSBOW_DRAW_TICKS) {
                handle.releaseUsingItem();
                context.packetBroadcaster.broadcastEntityData();
                drawing = false;
            }
            return true;
        }

        if (!sight && drawTicks > 60) {
            handle.stopUsingItem();
            context.packetBroadcaster.broadcastEntityData();
            drawing = false;
            return true;
        }
        // Fully drawn but not lined up yet: hold the draw until we are.
        if (drawTicks < BOW_DRAW_TICKS || !sight || !onTarget) return true;

        float[] shot = withAimError(aim);
        VanillaUse.face(context, handle, shot[0], shot[1]);
        handle.releaseUsingItem();
        context.packetBroadcaster.broadcastEntityData();
        context.packetBroadcaster.broadcastEquipment();
        drawing = false;
        shotCooldown = 6;
        return true;
    }

    private static boolean crossbowLoaded(ServerPlayer handle) {
        try {
            return net.minecraft.world.item.CrossbowItem.isCharged(handle.getMainHandItem());
        } catch (Throwable t) {
            return false;
        }
    }

    // The bot's own hand shake on top of the bow's vanilla spread: a small
    // aim error that shrinks with difficulty.
    private float[] withAimError(double[] aim) {
        double spreadDeg = switch (context.settings.getDifficulty()) {
            case EASY -> 2.6;
            case NORMAL -> 1.45;
            case HARD -> 0.7;
            default -> 0.35;
        };
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new float[]{
                (float) (aim[0] + r.nextGaussian() * spreadDeg),
                (float) (aim[1] + r.nextGaussian() * spreadDeg)};
    }

    // Returns {yaw, pitch} in degrees that put a full-power arrow on the
    // target's chest, leading its current motion.
    private double[] solveAim(Location eye, Player target) {
        Vector aimPoint = target.getLocation().toVector().add(new Vector(0, 1.1, 0));
        double flight = 0;
        double pitch = 0;
        for (int iter = 0; iter < 3; iter++) {
            Vector predicted = aimPoint.clone().add(targetVel.clone().multiply(flight));
            double dx = predicted.getX() - eye.getX();
            double dz = predicted.getZ() - eye.getZ();
            double dh = predicted.getY() - eye.getY();
            double horiz = Math.sqrt(dx * dx + dz * dz);
            double[] sol = solvePitch(horiz, dh);
            pitch = sol[0];
            flight = sol[1];
        }
        Vector predicted = aimPoint.clone().add(targetVel.clone().multiply(flight));
        double dx = predicted.getX() - eye.getX();
        double dz = predicted.getZ() - eye.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return new double[]{yaw, -Math.toDegrees(pitch)};
    }

    // Binary-search the launch angle (radians, up = positive) whose simulated
    // arc passes `dh` blocks above/below eye level at `horiz` blocks out.
    // Returns {angle, flightTicks}.
    static double[] solvePitch(double horiz, double dh) {
        // Height reached at `horiz` rises with the angle up to some peak and
        // falls after it; search only the rising (flat-shot) side of that.
        double hi = Math.toRadians(-70), peak = Double.NEGATIVE_INFINITY;
        for (int deg = -70; deg <= 85; deg++) {
            double[] y = heightAt(Math.toRadians(deg), horiz);
            if (Double.isNaN(y[0])) continue;
            if (y[0] > peak) {
                peak = y[0];
                hi = Math.toRadians(deg);
            }
        }
        double lo = Math.toRadians(-70);
        double best = Math.atan2(dh, Math.max(0.1, horiz));
        double ticks = horiz / ARROW_SPEED;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            double[] y = heightAt(mid, horiz);
            if (Double.isNaN(y[0])) {
                lo = mid; // never got that far: aim higher
                continue;
            }
            best = mid;
            ticks = y[1];
            if (y[0] < dh) lo = mid;
            else hi = mid;
        }
        return new double[]{best, ticks};
    }

    // Vanilla arrow physics per tick: move, then drag 0.99 and gravity 0.05.
    private static double[] heightAt(double angle, double horiz) {
        double vx = ARROW_SPEED * Math.cos(angle), vy = ARROW_SPEED * Math.sin(angle);
        double x = 0, y = 0;
        for (int t = 1; t <= 120; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= horiz) {
                double f = (horiz - x) / Math.max(1e-6, nx - x);
                return new double[]{y + (ny - y) * f, t - 1 + f};
            }
            x = nx;
            y = ny;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return new double[]{Double.NaN, 120};
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private void trackVelocity(Player target) {
        Vector now = target.getLocation().toVector();
        if (lastTargetPos != null) {
            Vector v = now.clone().subtract(lastTargetPos);
            if (v.lengthSquared() > 4.0) v = new Vector(); // teleport, not movement
            targetVel.multiply(0.6).add(v.multiply(0.4));
        }
        lastTargetPos = now;
    }

    private int bowSlot(Player p) {
        int slot = context.inventoryController.ensureInHotbar(p, it -> it.getType() == Material.BOW);
        if (slot < 0) slot = context.inventoryController.ensureInHotbar(p, it -> it.getType() == Material.CROSSBOW);
        return slot >= 0 && slot <= 8 ? slot : -1;
    }

    private void walkTowards(Player botPlayer, Location dest) {
        MovementController mc = context.movementController;
        if (context.currentPath.isEmpty() || context.pathNodeIndex >= context.currentPath.size()) {
            if (repathCooldown <= 0) {
                repathCooldown = 20;
                context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), dest);
            }
            Location me = botPlayer.getLocation();
            double dx = dest.getX() - me.getX(), dz = dest.getZ() - me.getZ();
            double len = Math.hypot(dx, dz);
            if (len > 0.5) mc.worldDirToInputs(context.bot.getHandle(), dx / len, dz / len, 1.0f);
        } else {
            mc.followPath();
        }
    }
}
