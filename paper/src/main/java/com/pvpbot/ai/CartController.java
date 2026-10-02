package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CartController {
    private enum Phase { IDLE, AIM_UP, SHOOT_ARROW, PLACE_RAIL, PLACE_CART,
                         LOAD_XBOW, BACK_UP, PLACE_FLAME, SHOOT_XBOW }

    private static final double MIN_RANGE = 3.0;

    private static final double MAX_RANGE = 16.0;

    private static final int STEP_TICKS = 2;
    private static final int AIM_DURATION = 3;
    private static final int SHOOT_DELAY = 2;
    private static final int PLACE_DELAY = 2;
    private static final int XBOW_LOAD_TICKS = 5;
    private static final double AIM_ANGLE_DEGREES = 30.0;

    private static final double ARROW_GRAVITY = 0.05;

    private static final double TARGET_FLIGHT_TICKS = 7.0;
    private static final double MIN_ARROW_SPEED = 0.55;
    private static final double MAX_ARROW_SPEED = 2.6;
    private static final double DRAG_COMPENSATION = 0.96;

    private static final double AGRO_MIN_RANGE = 14.0;
    private static final double AGRO_MAX_RANGE = 45.0;
    private static final double AGRO_CHANCE = 0.25;
    private static final int AGRO_COOLDOWN = 400;
    private static final int AGRO_WINDOW = 100;

    private static final int OWN_CART_MEMORY = 120;

    private static final int BACK_UP_TICKS = 7;
    private static final double BACK_UP_DISTANCE = 4.5;

    private final BotAIContext context;

    private Phase phase = Phase.IDLE;
    private int stepTimer = 0;
    private int cooldown = 0;
    private boolean usingCrossbow = false;
    private Location railLoc;
    private Entity cart;
    private int abortTicks = 0;
    private Location predictedArrowLand;
    private org.bukkit.util.Vector launchVector;
    private int arrowFlightTicks;
    private Location flameLoc;
    private int backUpTicks;
    private Entity ownCart;
    private int ownCartTicks;
    private ItemStack leggingsBackup;
    private int leggingsSlot = -1;

    public CartController(BotAIContext context) {
        this.context = context;
    }

    public Entity getOwnCart() {
        if (ownCartTicks <= 0) return null;
        return ownCart;
    }

    public void tickOwnCart() {
        if (ownCartTicks > 0) ownCartTicks--;
    }

    private void equipBlastLeggings(Player p) {
        if (leggingsBackup != null) return;

        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        ItemStack current = inv.getLeggings();
        int best = -1;
        int bestLevel = blastLevel(current);

        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.getType().name().endsWith("_LEGGINGS")) continue;
            int lvl = blastLevel(it);
            if (lvl > bestLevel) { bestLevel = lvl; best = i; }
        }
        if (best < 0) return;

        leggingsBackup = current == null ? new ItemStack(Material.AIR) : current.clone();
        leggingsSlot = best;
        inv.setLeggings(inv.getItem(best));
        inv.setItem(best, current);
        context.packetBroadcaster.broadcastEquipment();
    }

    private void restoreLeggings(Player p) {
        if (leggingsBackup == null) return;
        try {
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            ItemStack worn = inv.getLeggings();
            inv.setLeggings(leggingsBackup.getType() == Material.AIR ? null : leggingsBackup);
            if (leggingsSlot >= 0 && worn != null) inv.setItem(leggingsSlot, worn);
            context.packetBroadcaster.broadcastEquipment();
        } catch (Throwable ignored) {
        }
        leggingsBackup = null;
        leggingsSlot = -1;
    }

    private int blastLevel(ItemStack it) {
        if (it == null) return -1;
        try {
            for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e
                    : it.getEnchantments().entrySet()) {
                if (e.getKey() == null) continue;
                if (String.valueOf(e.getKey().getKey()).toLowerCase().contains("blast")) {
                    return e.getValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public String debugLine() {
        return "cart=" + phase + (usingCrossbow ? " xbow" : " insta") + " cd=" + cooldown;
    }

    public void abort() {
        if (cart != null) {
            ownCart = cart;
            ownCartTicks = OWN_CART_MEMORY;
        }
        Player owner = context.bot.getBukkitPlayer();
        if (owner != null) restoreLeggings(owner);
        phase = Phase.IDLE;
        railLoc = null;
        cart = null;
        stepTimer = 0;
        abortTicks = 0;
        predictedArrowLand = null;
        flameLoc = null;
    }

    public boolean tryAgroPearl(Player botPlayer, Player target, double distance) {
        if (!context.settings.isCartPvp()) return false;
        if (isActive() || cooldown > 0 || context.agroCartCooldown > 0) return false;
        if (context.fleeing || context.eating || context.drinkingPotionTimer > 0) return false;
        if (distance < AGRO_MIN_RANGE || distance > AGRO_MAX_RANGE) return false;

        if (!hasItem(botPlayer, Material.RAIL)) return false;
        if (!hasItem(botPlayer, Material.TNT_MINECART)) return false;
        if (!hasItem(botPlayer, Material.BOW) && !hasItem(botPlayer, Material.CROSSBOW)) return false;

        int slot = findSlot(botPlayer, Material.ENDER_PEARL);
        if (slot < 0 || slot > 8) return false;

        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= AGRO_CHANCE) {
            return false;
        }

        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        ServerPlayer handle = context.bot.getHandle();
        Location eye = botPlayer.getEyeLocation();
        Location aim = target.getLocation().clone().add(0, 1.0, 0);
        // Solved for a vanilla pearl throw (1.5 speed), then thrown as a
        // real right click.
        org.bukkit.util.Vector vel = computeLaunchVector(eye, aim, 1.5, 0.03, false);
        if (vel == null) vel = eye.getDirection().multiply(1.5);

        float yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(vel.getY(),
                Math.hypot(vel.getX(), vel.getZ())));
        if (!VanillaUse.useFromHotbar(context, botPlayer, slot, yaw, pitch).used()) return false;

        context.agroCartCooldown = AGRO_COOLDOWN;
        context.agroCartWindow = AGRO_WINDOW;
        return true;
    }

    public boolean handleCart(Player botPlayer, Player target, double distance) {
        if (cooldown > 0) cooldown--;

        if (!context.settings.isCartPvp()) {
            if (isActive()) abort();
            return false;
        }
        if (botPlayer == null || target == null) {
            if (isActive()) abort();
            return false;
        }

        if (phase == Phase.IDLE) {
            if (cooldown > 0) return false;

            if (distance < MIN_RANGE || distance > MAX_RANGE) return false;
            if (!hasCartKit(botPlayer)) {
                cooldown = 20;
                return false;
            }

            if (!context.combatController.hasLineOfSight(botPlayer, target)) return false;

            usingCrossbow = context.settings.isXbowCart()
                    && hasItem(botPlayer, Material.CROSSBOW)
                    && hasItem(botPlayer, Material.FLINT_AND_STEEL);

            if (usingCrossbow) {
                phase = Phase.LOAD_XBOW;
                stepTimer = 0;
                abortTicks = 0;
            } else {
                phase = Phase.AIM_UP;
                stepTimer = 0;
                abortTicks = 0;
            }

            equipBlastLeggings(botPlayer);

            notifyTeamCarting(botPlayer);
            return true;
        }

        // Real draws and crossbow charging take time.
        if (++abortTicks > 160) {
            abort();
            cooldown = 100;
            return false;
        }

        if (stepTimer > 0) {
            stepTimer--;
            holdStill(botPlayer, target);
            return true;
        }

        switch (phase) {
            case AIM_UP -> { return doAimUp(botPlayer, target); }
            case SHOOT_ARROW -> { return doShootArrow(botPlayer, target); }
            case PLACE_RAIL -> { return doRail(botPlayer, target); }
            case PLACE_CART -> { return doCart(botPlayer, target); }
            case LOAD_XBOW -> { return doLoadXbow(botPlayer, target); }
            case BACK_UP -> { return doBackUp(botPlayer, target); }
            case PLACE_FLAME -> { return doPlaceFlame(botPlayer, target); }
            case SHOOT_XBOW -> { return doShootXbow(botPlayer, target, distance); }
            default -> { return false; }
        }
    }

    private void holdStill(Player botPlayer, Player target) {
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        if (railLoc != null) {
            context.movementController.lookAt(railLoc.clone().add(0.5, 0.3, 0.5), 0.0);
        }
    }

    private boolean doAimUp(Player botPlayer, Player target) {
        Block spot = findRailSpot(botPlayer, target);
        if (spot == null) {
            abort();
            cooldown = 60;
            return false;
        }
        railLoc = spot.getLocation();

        Location eye = botPlayer.getEyeLocation();
        Location aimAt = railLoc.clone().add(0.5, 0.6, 0.5);

        double horizToRail = Math.hypot(aimAt.getX() - eye.getX(), aimAt.getZ() - eye.getZ());
        double speed = Math.max(MIN_ARROW_SPEED,
                Math.min(MAX_ARROW_SPEED, horizToRail / TARGET_FLIGHT_TICKS));
        launchVector = computeLaunchVector(eye, aimAt, speed, ARROW_GRAVITY, false);
        if (launchVector == null) {
            abort();
            cooldown = 60;
            return false;
        }
        predictedArrowLand = aimAt;

        context.movementController.lookAt(eye.clone().add(launchVector.clone().multiply(4)), 2.0);

        int slot = findFlameBowSlot(botPlayer);
        if (slot < 0) slot = findSlot(botPlayer, Material.BOW);
        if (slot < 0 || slot > 8) {
            abort();
            cooldown = 60;
            return false;
        }
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        // Start drawing for real (needs arrows, like anyone's bow).
        if (!VanillaUse.use(context.bot.getHandle(), net.minecraft.world.InteractionHand.MAIN_HAND).used()) {
            abort();
            cooldown = 60;
            return false;
        }

        phase = Phase.SHOOT_ARROW;
        stepTimer = AIM_DURATION;
        return true;
    }

    private boolean doShootArrow(Player botPlayer, Player target) {
        net.minecraft.server.level.ServerPlayer h = context.bot.getHandle();
        if (h == null || !h.isUsingItem()
                || h.getUseItem().getItem() != net.minecraft.world.item.Items.BOW) {
            abort();
            cooldown = 60;
            return false;
        }

        org.bukkit.util.Vector vel = launchVector;
        if (vel == null) {
            Location eye = botPlayer.getEyeLocation();
            Location fallbackAim = railLoc.clone().add(0.5, 0.6, 0.5);
            double hz = Math.hypot(fallbackAim.getX() - eye.getX(),
                    fallbackAim.getZ() - eye.getZ());
            double sp = Math.max(MIN_ARROW_SPEED,
                    Math.min(MAX_ARROW_SPEED, hz / TARGET_FLIGHT_TICKS));
            vel = computeLaunchVector(eye, fallbackAim, sp, ARROW_GRAVITY, false);
        }
        if (vel == null) {
            h.stopUsingItem();
            abort();
            cooldown = 60;
            return false;
        }
        vel = vel.clone().multiply(DRAG_COMPENSATION);

        // The arrow's speed is set by how long the bow is drawn (vanilla:
        // power = (t^2 + 2t) / 3 for t = ticks / 20, speed = 3 * power), so
        // hold the draw exactly as long as this shot's speed needs.
        double speed = Math.min(3.0, vel.length());
        double power = speed / 3.0;
        int drawNeeded = (int) Math.ceil(20.0 * (-1.0 + Math.sqrt(1.0 + 3.0 * power)));
        if (h.getTicksUsingItem() < Math.max(3, drawNeeded)) {
            stepTimer = 1;
            return true;
        }

        // Face exactly along the shot and let go.
        float yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(vel.getY(), Math.hypot(vel.getX(), vel.getZ())));
        VanillaUse.face(context, h, yaw, pitch);
        h.releaseUsingItem();
        context.packetBroadcaster.broadcastEntityData();

        double horiz = Math.hypot(railLoc.getX() + 0.5 - botPlayer.getLocation().getX(),
                railLoc.getZ() + 0.5 - botPlayer.getLocation().getZ());
        double vxz = Math.hypot(vel.getX(), vel.getZ());
        arrowFlightTicks = vxz < 1e-6 ? 20 : (int) Math.ceil(horiz / vxz);


        phase = Phase.PLACE_RAIL;
        stepTimer = Math.max(0, Math.min(SHOOT_DELAY, arrowFlightTicks - 4));
        return true;
    }

    private boolean doRail(Player botPlayer, Player target) {
        if (railLoc == null) {
            abort();
            cooldown = 60;
            return false;
        }

        int slot = findSlot(botPlayer, Material.RAIL);
        if (slot < 0) {
            abort();
            return false;
        }

        // A crossbow being charged gets let go first - that's what loads it.
        if (!finishLoadingCrossbow()) {
            stepTimer = 1;
            return true;
        }

        if (slot > 8) slot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.RAIL);
        Block railBlock = railLoc.getBlock();
        if (slot < 0 || slot > 8 || !VanillaWorld.place(context, botPlayer, slot, railBlock,
                railBlock.getRelative(org.bukkit.block.BlockFace.DOWN), null)) {
            abort();
            cooldown = 100;
            return false;
        }

        phase = Phase.PLACE_CART;
        stepTimer = PLACE_DELAY;
        return true;
    }

    private boolean doCart(Player botPlayer, Player target) {
        if (railLoc == null) {
            abort();
            return false;
        }
        int slot = findSlot(botPlayer, Material.TNT_MINECART);
        if (slot < 0) {
            abort();
            return false;
        }

        // Right click the TNT minecart onto the rail - vanilla spawns it.
        if (slot > 8) slot = context.inventoryController.ensureInHotbar(botPlayer,
                it -> it.getType() == Material.TNT_MINECART);
        if (slot < 0 || slot > 8 || !VanillaWorld.useOn(context, botPlayer, slot, railLoc.getBlock(),
                org.bukkit.block.BlockFace.UP)) {
            abort();
            cooldown = 60;
            return false;
        }
        cart = null;
        for (org.bukkit.entity.Entity e : railLoc.getWorld().getNearbyEntities(
                railLoc.clone().add(0.5, 0.3, 0.5), 1.2, 1.2, 1.2)) {
            if (e instanceof org.bukkit.entity.minecart.ExplosiveMinecart m) {
                cart = m;
                break;
            }
        }
        if (cart == null) {
            abort();
            cooldown = 60;
            return false;
        }
        ownCart = cart;
        ownCartTicks = OWN_CART_MEMORY;

        if (usingCrossbow) {
            phase = Phase.BACK_UP;
            stepTimer = 0;
            backUpTicks = BACK_UP_TICKS;
        } else {
            abort();
            cooldown = context.settings.getCartCooldownTicks();
        }
        return true;
    }

    private boolean doLoadXbow(Player botPlayer, Player target) {
        int slot = findSlot(botPlayer, Material.CROSSBOW);
        if (slot < 0) {
            abort();
            cooldown = 60;
            return false;
        }

        if (slot > 8) slot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.CROSSBOW);
        if (slot < 0 || slot > 8) {
            abort();
            cooldown = 60;
            return false;
        }
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();
        // Start charging it for real (needs arrows); an already loaded one
        // is good to go.
        net.minecraft.server.level.ServerPlayer xh = context.bot.getHandle();
        if (!net.minecraft.world.item.CrossbowItem.isCharged(xh.getMainHandItem())
                && !VanillaUse.use(xh, net.minecraft.world.InteractionHand.MAIN_HAND).used()) {
            abort();
            cooldown = 60;
            return false;
        }

        Block spot = findRailSpot(botPlayer, target);
        if (spot == null) {
            abort();
            cooldown = 60;
            return false;
        }
        railLoc = spot.getLocation();

        flameLoc = null;

        phase = Phase.PLACE_RAIL;
        stepTimer = XBOW_LOAD_TICKS;
        return true;
    }

    private boolean doBackUp(Player botPlayer, Player target) {
        if (railLoc == null) {
            abort();
            cooldown = 60;
            return false;
        }

        Location bot = botPlayer.getLocation();
        double dx = bot.getX() - (railLoc.getX() + 0.5);
        double dz = bot.getZ() - (railLoc.getZ() + 0.5);
        double len = Math.sqrt(dx * dx + dz * dz);

        if (len > 0.001) {
            context.movementController.worldDirToInputs(
                    context.bot.getHandle(), dx / len, dz / len, 1.0f);
        }
        context.movementController.lookAt(railLoc.clone().add(0.5, 0.4, 0.5), 2.0);

        if (--backUpTicks > 0 && len < BACK_UP_DISTANCE) return true;

        phase = Phase.PLACE_FLAME;
        stepTimer = 1;
        return true;
    }

    private boolean doPlaceFlame(Player botPlayer, Player target) {
        if (railLoc == null) {
            abort();
            cooldown = 60;
            return false;
        }

        if (flameLoc == null) flameLoc = pickFlameSpot(botPlayer);
        if (flameLoc == null) {
            phase = Phase.SHOOT_XBOW;
            return true;
        }

        int slot = findSlot(botPlayer, Material.FLINT_AND_STEEL);
        if (slot < 0) {
            phase = Phase.SHOOT_XBOW;
            return true;
        }

        // Light it with a real flint-and-steel click on the floor's top
        // face (vanilla places the fire and wears the flint and steel).
        Block flameBlock = flameLoc.getBlock();
        if (slot > 8) slot = context.inventoryController.ensureInHotbar(botPlayer,
                it -> it.getType() == Material.FLINT_AND_STEEL);
        if (flameBlock.getType().isAir() && slot >= 0 && slot <= 8) {
            VanillaWorld.useOn(context, botPlayer, slot, flameBlock.getRelative(org.bukkit.block.BlockFace.DOWN),
                    org.bukkit.block.BlockFace.UP);
        }
        if (flameBlock.getType() != Material.FIRE) {
            flameLoc = null;
            phase = Phase.SHOOT_XBOW;
            stepTimer = 1;
            return true;
        }

        phase = Phase.SHOOT_XBOW;
        stepTimer = PLACE_DELAY;
        return true;
    }

    private Location pickFlameSpot(Player botPlayer) {
        if (railLoc == null) return null;

        Location bot = botPlayer.getLocation();
        Location cartCentre = railLoc.clone().add(0.5, 0.0, 0.5);
        org.bukkit.util.Vector toCart = cartCentre.toVector()
                .subtract(bot.toVector()).setY(0);
        double gap = toCart.length();
        if (gap < 1.2) return null;
        toCart.normalize();

        double[] offsets = {gap * 0.66, gap * 0.5, gap * 0.8, 1.5, 2.5};
        for (double d : offsets) {
            if (d < 1.0 || d > gap - 0.6) continue;

            Block b = bot.clone().add(toCart.clone().multiply(d)).getBlock();
            if (!b.getType().isAir()) continue;
            if (!b.getRelative(BlockFace.DOWN).getType().isSolid()) continue;

            if (b.getX() == railLoc.getBlockX() && b.getZ() == railLoc.getBlockZ()) continue;

            return b.getLocation();
        }
        return null;
    }

    private boolean doShootXbow(Player botPlayer, Player target, double distance) {
        if (cart == null || cart.isDead()) {
            abort();
            cooldown = 40;
            return false;
        }

        if (distance < MIN_RANGE) {
            abort();
            cooldown = 60;
            return false;
        }

        int slot = findSlot(botPlayer, Material.CROSSBOW);
        if (slot < 0) {
            abort();
            cooldown = 60;
            return false;
        }

        if (slot > 8) slot = context.inventoryController.ensureInHotbar(botPlayer, it -> it.getType() == Material.CROSSBOW);
        if (slot < 0 || slot > 8) {
            abort();
            cooldown = 60;
            return false;
        }
        botPlayer.getInventory().setHeldItemSlot(slot);
        context.packetBroadcaster.broadcastEquipment();

        // Fire the loaded crossbow for real, aimed along the solved arc.
        Location aim = cart.getLocation().clone().add(0, 0.2, 0);
        Location eye = botPlayer.getEyeLocation();
        org.bukkit.util.Vector vel = computeLaunchVector(eye, aim, 3.15, 0.05);
        if (vel == null) vel = aim.toVector().subtract(eye.toVector()).normalize().multiply(3.15);
        float yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(vel.getY(), Math.hypot(vel.getX(), vel.getZ())));
        net.minecraft.server.level.ServerPlayer sh = context.bot.getHandle();
        if (!net.minecraft.world.item.CrossbowItem.isCharged(sh.getMainHandItem())
                || !VanillaUse.useFromHotbar(context, botPlayer, slot, yaw, pitch).used()) {
            abort();
            cooldown = 60;
            return false;
        }

        abort();
        cooldown = context.settings.getCartCooldownTicks();
        return true;
    }

    private void notifyTeamCarting(Player botPlayer) {
        try {
            com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
            if (mgr == null) return;

            String faction = mgr.getPlayerFaction(context.bot.getUUID());
            if (faction == null) return;

            java.util.UUID cartingBotUUID = context.bot.getUUID();

            for (com.pvpbot.PvPBot mate : mgr.getFactionBots(faction)) {
                if (mate == null || !mate.isAlive()) continue;
                if (mate.getUUID().equals(context.bot.getUUID())) continue;

                BotAIContext mateContext = mate.getAI().getContext();
                mateContext.cartingTeammate = cartingBotUUID;
                mateContext.cartingWarningTicks = 60;
            }
        } catch (Throwable ignored) {
        }
    }

    private Location predictArrowLanding(Player botPlayer, Location targetLoc) {
        Location eye = botPlayer.getEyeLocation();
        double speed = 2.6;
        org.bukkit.util.Vector launch = computeLaunchVector(eye, targetLoc, speed, 0.05);
        if (launch == null) return targetLoc;

        double gravity = 0.05;
        Location current = eye.clone();
        org.bukkit.util.Vector vel = launch.clone();

        for (int i = 0; i < 200; i++) {
            current.add(vel);
            vel.setY(vel.getY() - gravity);

            if (current.getY() <= targetLoc.getY() && vel.getY() < 0) {
                return current;
            }

            double dx = current.getX() - targetLoc.getX();
            double dz = current.getZ() - targetLoc.getZ();
            if (dx * dx + dz * dz < 0.5 * 0.5) return current;
        }
        return targetLoc;
    }

    private org.bukkit.util.Vector computeLaunchVector(Location from, Location to, double speed, double gravity) {
        return computeLaunchVector(from, to, speed, gravity, false);
    }

    private org.bukkit.util.Vector computeLaunchVector(Location from, Location to,
                                                       double speed, double gravity,
                                                       boolean highArc) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dy = to.getY() - from.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1e-6) return null;

        double v = speed;
        double g = gravity;

        double v2 = v * v;
        double v4 = v2 * v2;

        double under = v4 - g * (g * horiz * horiz + 2 * dy * v2);
        if (under < 0) return null;

        double root = Math.sqrt(under);
        double tanTheta = (highArc ? (v2 + root) : (v2 - root)) / (g * horiz);
        double theta = Math.atan(tanTheta);

        double vy = v * Math.sin(theta);
        double vxz = v * Math.cos(theta);

        double nx = dx / horiz;
        double nz = dz / horiz;

        org.bukkit.util.Vector out = new org.bukkit.util.Vector(nx * vxz, vy, nz * vxz);
        return out;
    }

    private boolean hasCartKit(Player p) {
        return hasItem(p, Material.RAIL)
                && hasItem(p, Material.TNT_MINECART)
                && (hasItem(p, Material.CROSSBOW) || hasItem(p, Material.BOW));
    }

    private boolean hasItem(Player p, Material m) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == m) return true;
        }
        return false;
    }

    private Block findRailSpot(Player botPlayer, Player target) {
        Location bl = botPlayer.getLocation();
        double dx = target.getLocation().getX() - bl.getX();
        double dz = target.getLocation().getZ() - bl.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return null;
        dx /= len;
        dz /= len;

        for (double step = 1.5; step <= 2.5; step += 1.0) {
            int x = (int) Math.floor(bl.getX() + dx * step);
            int z = (int) Math.floor(bl.getZ() + dz * step);
            int y = bl.getBlockY();

            Block at = bl.getWorld().getBlockAt(x, y, z);
            Block below = bl.getWorld().getBlockAt(x, y - 1, z);
            if (at.getType().isAir() && below.getType().isSolid()) return at;
        }
        return null;
    }

    private int findSlot(Player p, Material m) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = p.getInventory().getItem(i);
            if (it != null && it.getType() == m) {
                return i <= 8 ? i : context.inventoryController.ensureInHotbar(
                        p, s -> s.getType() == m);
            }
        }
        return -1;
    }

    private int findFlameBowSlot(Player p) {
        return context.inventoryController.ensureInHotbar(p, it -> {
            if (it.getType() != Material.BOW) return false;
            try {
                return it.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FLAME) > 0;
            } catch (Throwable t) {
                return false;
            }
        });
    }

    // Let go of a crossbow that's been held long enough to load (vanilla
    // loads it on release). False = still charging.
    private boolean finishLoadingCrossbow() {
        net.minecraft.server.level.ServerPlayer h = context.bot.getHandle();
        if (h == null || !h.isUsingItem()) return true;
        net.minecraft.world.item.ItemStack using = h.getUseItem();
        if (using.getItem() != net.minecraft.world.item.Items.CROSSBOW) return true;
        int quick = 0;
        try {
            quick = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(using)
                    .getEnchantmentLevel(org.bukkit.enchantments.Enchantment.QUICK_CHARGE);
        } catch (Throwable ignored) {
        }
        int charge = Math.max(0, 25 - 5 * quick);
        if (h.getTicksUsingItem() < charge) return false;
        h.releaseUsingItem();
        context.packetBroadcaster.broadcastEntityData();
        return true;
    }

}
