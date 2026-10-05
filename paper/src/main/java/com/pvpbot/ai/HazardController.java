package com.pvpbot.ai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

public class HazardController {
    private static final double RAIL_ALERT_RADIUS = 7.0;

    private static final int THREAT_MEMORY_TICKS = 100;

    private static final int SCAN_INTERVAL_TICKS = 4;

    private static final int SCAN_INTERVAL_IDLE_TICKS = 40;

    private static final double CART_BREAK_REACH = 3.4;

    private static final double CART_PANIC_RADIUS = 4.5;

    private static final double MACE_THREAT_HORIZ = 8.0;
    private static final double MACE_THREAT_VERT = 24.0;
    private static final double MACE_ARROW_MIN_RANGE = 3.0;
    private static final double MACE_ARROW_MAX_RANGE = 45.0;
    private static final double MACE_ALLY_COORD_RANGE = 32.0;
    private static final int MACE_COUNTER_COOLDOWN = 100;
    private static final int MACE_SCAN_INTERVAL = 6;

    private int maceCounterCooldown = 0;
    private int maceScanCooldown = 0;

    private final BotAIContext context;

    public HazardController(BotAIContext context) {
        this.context = context;
    }

    public void notifyRailPlaced(Location railLoc, Player placer) {
        if (!context.settings.isCartDefense()) return;
        Player self = context.bot.getBukkitPlayer();
        if (self == null || railLoc.getWorld() != self.getWorld()) return;
        if (railLoc.distance(self.getLocation()) > RAIL_ALERT_RADIUS) return;
        if (placer != null && isFriendly(placer)) return;

        arm(railLoc);
    }

    private void arm(Location loc) {
        context.cartThreat = loc.clone();
        context.cartThreatTicks = THREAT_MEMORY_TICKS;
        context.forceFullTicks = Math.max(context.forceFullTicks, THREAT_MEMORY_TICKS);
        if (context.cartReactionTicks <= 0 && !context.cartReacting) {
            int base = Math.max(2, context.settings.getReactionTicks());
            context.cartReactionTicks = base + 2 + ThreadLocalRandom.current().nextInt(5);
        }
    }

    public boolean handleThreat(Player botPlayer) {
        if (tryCounterMace(botPlayer)) return true;

        if (!context.settings.isCartDefense()) {
            clear();
            return false;
        }
        if (context.cartBlockCooldown > 0) context.cartBlockCooldown--;

        if (context.cartingWarningTicks > 0) {
            context.cartingWarningTicks--;
            if (context.cartingTeammate != null) {
                com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
                if (mgr != null) {
                    for (com.pvpbot.PvPBot bot : mgr.getBots().values()) {
                        if (bot != null && bot.isAlive() && bot.getUUID().equals(context.cartingTeammate)) {
                            Player cartingMate = bot.getBukkitPlayer();
                            if (cartingMate != null && cartingMate.isOnline() && cartingMate.getWorld() == botPlayer.getWorld()) {
                                double distance = cartingMate.getLocation().distance(botPlayer.getLocation());
                                if (distance < 8.0) {
                                    return retreatFromTeammateCart(botPlayer, cartingMate.getLocation(), distance);
                                }
                            }
                        }
                    }
                }
            }
            if (context.cartingWarningTicks <= 0) {
                context.cartingTeammate = null;
            }
        }

        int scanInterval = (context.cartThreat != null || context.target != null)
                ? SCAN_INTERVAL_TICKS : SCAN_INTERVAL_IDLE_TICKS;
        if (context.tickCounter % scanInterval == 0) {
            Entity cart = findThreateningCart(botPlayer);
            if (cart != null) arm(cart.getLocation());
        }

        if (context.cartThreat == null) return false;

        if (context.cartThreatTicks > 0) context.cartThreatTicks--;
        if (context.cartThreatTicks <= 0
                || context.cartThreat.getWorld() != botPlayer.getWorld()) {
            clear();
            return false;
        }

        if (context.cartReactionTicks > 0) {
            context.cartReactionTicks--;
            return false;
        }
        context.cartReacting = true;

        double distance = context.cartThreat.distance(botPlayer.getLocation());

        if (distance > RAIL_ALERT_RADIUS + 3.0) {
            clear();
            return false;
        }

        Entity cart = findThreateningCart(botPlayer);

        if (tryBlockOff(botPlayer)) return true;

        if (cart != null && cart.getLocation().distance(botPlayer.getLocation()) <= CART_BREAK_REACH) {
            breakCart(botPlayer, cart);
            return true;
        }

        return retreatFromBlast(botPlayer, distance);
    }

    private boolean tryBlockOff(Player p) {
        if (context.cartBlockCooldown > 0) return false;

        int slot = context.inventoryController.findBlockSlot(p);
        if (slot < 0 || slot > 8) return false;

        Vector toThreat = context.cartThreat.toVector()
                .subtract(p.getLocation().toVector()).setY(0);
        if (toThreat.lengthSquared() < 0.01) return false;
        toThreat.normalize();

        Location feetTarget = p.getLocation().clone().add(toThreat.clone().multiply(1.1));
        Block feet = feetTarget.getBlock();
        Block head = feet.getRelative(BlockFace.UP);

        Block chosen = null;
        if (isReplaceable(feet)) chosen = feet;
        else if (isReplaceable(head)) chosen = head;

        if (chosen == null) {
            context.cartBlockCooldown = 20;
            return false;
        }

        if (!hasAdjacentSupport(chosen)) {
            context.cartBlockCooldown = 10;
            return false;
        }

        int previousSlot = p.getInventory().getHeldItemSlot();

        // A real right click against one of its neighbours.
        if (slot < 0 || slot > 8 || !VanillaWorld.place(context, p, slot, chosen, null, null)) {
            context.cartBlockCooldown = 10;
            return false;
        }
        p.getInventory().setHeldItemSlot(previousSlot);
        context.packetBroadcaster.broadcastEquipment();

        context.forwardInput = 0f;
        context.strafeInput = 0f;
        context.cartBlockCooldown = 6 + ThreadLocalRandom.current().nextInt(4);
        return true;
    }

    private void breakCart(Player p, Entity cart) {
        if (context.cartingTeammate != null) {
            com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
            if (mgr != null) {
                for (com.pvpbot.PvPBot bot : mgr.getBots().values()) {
                    if (bot != null && bot.isAlive() && bot.getUUID().equals(context.cartingTeammate)) {
                        Player cartingMate = bot.getBukkitPlayer();
                        if (cartingMate != null && cartingMate.isOnline()) {
                            double distance = cart.getLocation().distance(cartingMate.getLocation());
                            if (distance < 5.0) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        ServerPlayer handle = context.bot.getHandle();

        context.movementController.lookAt(
                cart.getLocation().add(0, 0.4, 0), context.settings.getAimNoise() * 0.5);

        context.forwardInput = -0.4f;
        context.strafeInput = 0.3f;

        if (context.attackCooldown > 1) return;

        handle.swing(InteractionHand.MAIN_HAND, true);
        context.packetBroadcaster.broadcastAnimation(handle, 0);
        try {
            net.minecraft.world.entity.Entity nms =
                    ((org.bukkit.craftbukkit.entity.CraftEntity) cart).getHandle();
            handle.attack(nms);
        } catch (Throwable ignored) {
        }
    }

    private boolean retreatFromTeammateCart(Player p, Location cartingMateLoc, double distance) {
        ServerPlayer handle = context.bot.getHandle();

        Vector away = p.getLocation().toVector()
                .subtract(cartingMateLoc.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
        away.normalize();

        float awayYaw = (float) Math.toDegrees(Math.atan2(-away.getX(), away.getZ()));
        context.movementController.easeYawTo(awayYaw);

        context.forwardInput = 1.0f;
        context.strafeInput = 0f;

        context.inventoryController.manageShieldBlock(p);

        if (handle.onGround()) {
            context.movementController.requestJump();
        }
        return true;
    }

    private boolean retreatFromBlast(Player p, double distance) {
        ServerPlayer handle = context.bot.getHandle();

        Vector away = p.getLocation().toVector()
                .subtract(context.cartThreat.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
        away.normalize();

        float awayYaw = (float) Math.toDegrees(Math.atan2(-away.getX(), away.getZ()));
        context.movementController.easeYawTo(awayYaw);

        context.forwardInput = 1.0f;
        context.strafeInput = 0f;

        context.smashThreatTicks = Math.max(context.smashThreatTicks, 12);
        context.inventoryController.manageShieldBlock(p);

        if (distance < CART_PANIC_RADIUS) {
            context.healingController.throwEscapePearl(p, awayYaw);
        }

        if (handle.onGround() && distance < CART_PANIC_RADIUS) {
            context.movementController.requestJump();
        }
        return true;
    }

    private Entity findThreateningCart(Player botPlayer) {
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity e : botPlayer.getNearbyEntities(
                RAIL_ALERT_RADIUS, RAIL_ALERT_RADIUS, RAIL_ALERT_RADIUS)) {
            if (!(e instanceof org.bukkit.entity.Minecart)) continue;

            if (context.cartController != null && e.equals(context.cartController.getOwnCart())) {
                continue;
            }

            try {
                if (!botPlayer.hasLineOfSight(e)) continue;
            } catch (Throwable ignored) {
                continue;
            }

            double d = e.getLocation().distanceSquared(botPlayer.getLocation());
            boolean explosive = e instanceof org.bukkit.entity.minecart.ExplosiveMinecart;
            if (explosive) d -= 100.0;
            if (d < bestDistSq) { bestDistSq = d; best = e; }
        }
        return best;
    }

    public static boolean isRail(Material material) {
        return material != null && material.name().endsWith("RAIL");
    }

    private boolean isReplaceable(Block b) {
        return b.getType() == Material.AIR || b.isPassable();
    }

    private boolean hasAdjacentSupport(Block b) {
        for (BlockFace face : new BlockFace[]{
                BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (b.getRelative(face).getType().isSolid()) return true;
        }
        return false;
    }

    private boolean tryCounterMace(Player botPlayer) {
        if (maceCounterCooldown > 0) maceCounterCooldown--;
        if (maceScanCooldown > 0) maceScanCooldown--;
        if (counterTarget != null) return continueCounterDraw(botPlayer);
        // Nobody on the server is in the air with a mace: nothing to counter
        // (this one check replaces a bots x bots x players scan).
        if (airborneMaceUsers().isEmpty()) return false;

        if (maceCounterCooldown > 0) return false;
        if (context.eating || context.drinkingPotionTimer > 0 || context.fleeing) return false;
        if (maceScanCooldown > 0) return false;
        maceScanCooldown = MACE_SCAN_INTERVAL;

        Player wielder = findMaceThreatTo(botPlayer);
        if (wielder == null) {
            com.pvpbot.BotManager mgr = com.pvpbot.PvPBotPlugin.getInstance().getBotManager();
            String faction = mgr == null ? null : mgr.getPlayerFaction(context.bot.getUUID());
            if (mgr != null && faction != null) {
                double rangeSq = MACE_ALLY_COORD_RANGE * MACE_ALLY_COORD_RANGE;
                for (com.pvpbot.PvPBot mate : mgr.getFactionBots(faction)) {
                    if (mate == null || !mate.isAlive()) continue;
                    if (mate.getUUID().equals(context.bot.getUUID())) continue;

                    Player mp = mate.getBukkitPlayer();
                    if (mp == null || mp.getWorld() != botPlayer.getWorld()) continue;
                    if (mp.getLocation().distanceSquared(botPlayer.getLocation()) > rangeSq) continue;

                    wielder = findMaceThreatTo(mp);
                    if (wielder != null) break;
                }
            }
        }
        if (wielder == null) return false;

        double dist = botPlayer.getLocation().distance(wielder.getLocation());
        if (dist < MACE_ARROW_MIN_RANGE || dist > MACE_ARROW_MAX_RANGE) return false;
        if (!context.combatController.hasLineOfSight(botPlayer, wielder)) return false;

        int arrowSlot = context.inventoryController.ensureInHotbar(
                botPlayer, HazardController::isSlowFallingArrow);
        if (arrowSlot < 0 || arrowSlot > 8) return false;
        int bowSlot = context.inventoryController.ensureInHotbar(
                botPlayer, it -> it.getType() == Material.BOW);
        if (bowSlot < 0 || bowSlot > 8) return false;

        shootSlowFallingArrow(botPlayer, wielder, bowSlot, arrowSlot);
        maceCounterCooldown = MACE_COUNTER_COOLDOWN;
        return true;
    }

    private Player findMaceThreatTo(Player victim) {
        if (victim == null) return null;
        Location vLoc = victim.getLocation();
        double vx = vLoc.getX(), vy = vLoc.getY(), vz = vLoc.getZ();

        for (Player p : airborneMaceUsers()) {
            if (p == victim || p.getWorld() != victim.getWorld()) continue;
            if (isFriendly(p)) continue;
            Location l = p.getLocation();
            double dxa = l.getX() - vx, dza = l.getZ() - vz;
            if (dxa * dxa + dza * dza > MACE_THREAT_HORIZ * MACE_THREAT_HORIZ) continue;
            double above = l.getY() - vy;
            if (above < 1.0 || above > MACE_THREAT_VERT) continue;
            return p;
        }
        return null;
    }

    // Everyone on the server who is off the ground with a mace on them,
    // worked out once per tick for all bots. Whether a player carries a mace
    // (a 36-slot inventory scan) is remembered for a second.
    private static int airborneTick = Integer.MIN_VALUE;
    private static final java.util.List<Player> AIRBORNE_MACE = new java.util.ArrayList<>();
    private static final java.util.Map<java.util.UUID, long[]> CARRIES_MACE = new java.util.HashMap<>();

    static java.util.List<Player> airborneMaceUsers() {
        int now = org.bukkit.Bukkit.getCurrentTick();
        if (now == airborneTick) return AIRBORNE_MACE;
        airborneTick = now;
        AIRBORNE_MACE.clear();
        if (now % 200 == 0) CARRIES_MACE.keySet().removeIf(id -> org.bukkit.Bukkit.getPlayer(id) == null);
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (p.isOnGround() || p.isDead()) continue;
            long[] memo = CARRIES_MACE.get(p.getUniqueId());
            if (memo == null || now - memo[0] > 20) {
                memo = new long[]{now, InventoryController.carriesMace(p) ? 1 : 0};
                CARRIES_MACE.put(p.getUniqueId(), memo);
            }
            if (memo[1] == 1) AIRBORNE_MACE.add(p);
        }
        return AIRBORNE_MACE;
    }

    private static boolean isSlowFallingArrow(ItemStack it) {
        if (it == null || it.getType() != Material.TIPPED_ARROW) return false;
        if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta)) return false;
        org.bukkit.potion.PotionType base = meta.getBasePotionType();
        return base == org.bukkit.potion.PotionType.SLOW_FALLING
                || base == org.bukkit.potion.PotionType.LONG_SLOW_FALLING;
    }

    // The counter is a real bow shot: start drawing now, let go once it's
    // drawn far enough (continueCounterDraw). Vanilla picks the arrow the
    // way it does for anyone - the slow-falling ones were moved up front
    // into the hotbar for it.
    private Player counterTarget = null;
    private static final int COUNTER_DRAW_TICKS = 12;
    private static final int COUNTER_MAX_DRAW = 40;

    private void shootSlowFallingArrow(Player botPlayer, Player wielder, int bowSlot, int arrowSlot) {
        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) return;
        botPlayer.getInventory().setHeldItemSlot(bowSlot);
        context.packetBroadcaster.broadcastEquipment();
        if (VanillaUse.use(handle, InteractionHand.MAIN_HAND).used()) {
            context.packetBroadcaster.broadcastEntityData();
            counterTarget = wielder;
        }
    }

    private boolean continueCounterDraw(Player botPlayer) {
        ServerPlayer handle = context.bot.getHandle();
        Player wielder = counterTarget;
        boolean drawing = handle != null && handle.isUsingItem()
                && handle.getUseItem().getItem() == net.minecraft.world.item.Items.BOW;
        if (!drawing || wielder == null || !wielder.isValid() || wielder.isDead()
                || wielder.getWorld() != botPlayer.getWorld() || wielder.isOnGround()
                || handle.getTicksUsingItem() > COUNTER_MAX_DRAW) {
            if (drawing) {
                handle.stopUsingItem();
                context.packetBroadcaster.broadcastEntityData();
            }
            counterTarget = null;
            return false;
        }

        Location aim = wielder.getLocation().clone().add(0, wielder.getEyeHeight() * 0.6, 0);
        Location eye = botPlayer.getEyeLocation();
        Vector dir = aim.toVector().subtract(eye.toVector());
        double horiz = Math.sqrt(dir.getX() * dir.getX() + dir.getZ() * dir.getZ());
        dir.setY(dir.getY() + horiz * 0.12);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(dir.getY(), Math.max(1.0E-4, horiz)));
        context.requestLook(yaw, pitch, BotAIContext.LOOK_CRITICAL, false);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (handle.getTicksUsingItem() < COUNTER_DRAW_TICKS) return true;

        VanillaUse.face(context, handle, yaw, pitch);
        handle.releaseUsingItem();
        context.packetBroadcaster.broadcastEntityData();
        counterTarget = null;
        botPlayer.getInventory().setHeldItemSlot(context.inventoryController.findBestWeaponSlot(botPlayer));
        context.packetBroadcaster.broadcastEquipment();
        return true;
    }

    private boolean isFriendly(Player p) {
        return com.pvpbot.PvPBotPlugin.getInstance().getBotManager()
                .isFriendly(context.bot.getUUID(), p.getUniqueId());
    }

    private void clear() {
        context.cartThreat = null;
        context.cartThreatTicks = 0;
        context.cartReactionTicks = 0;
        context.cartReacting = false;
    }
}
