package com.pvpbot.ai;

import com.pvpbot.BotManager;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.UUID;

public class DeliveryController {
    public enum Phase { IDLE, TO_LEADER, HANDOVER, PICKUP, TRAVEL, DONE }

    private static final double LEADER_REACH = 3.0;

    private static final double ARRIVE_DIST = 2.0;

    private static final int TICKET_RADIUS = 2;

    private final ChunkTicketHolder tickets = new ChunkTicketHolder(TICKET_RADIUS);

    private final ArrivalTracker arrival = new ArrivalTracker(ARRIVE_DIST, 100);

    private final BotAIContext context;

    private Phase phase = Phase.IDLE;
    private Location destination;
    private UUID droppedItemId;
    private int phaseTicks;
    private int repathCooldown;
    private String failure;

    private UUID requester;

    public DeliveryController(BotAIContext context) {
        this.context = context;
    }

    public String begin(Location dest, UUID requester) {
        Player botPlayer = context.bot.getBukkitPlayer();
        if (botPlayer == null) return "that bot is not alive";
        if (dest == null || dest.getWorld() == null) return "bad destination";
        if (dest.getWorld() != botPlayer.getWorld()) {
            return "the destination is in a different world to the bot";
        }

        Player leader = resolveLeader();
        if (leader == null) {
            return "that bot has no faction leader online to collect from";
        }
        if (leader.getWorld() != botPlayer.getWorld()) {
            return "the faction leader is in a different world";
        }

        abort();
        this.destination = dest.clone();
        this.requester = requester;
        this.phase = Phase.TO_LEADER;
        this.phaseTicks = 0;
        this.failure = null;
        return null;
    }

    public boolean isActive() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    public Phase getPhase() {
        return phase;
    }

    public String status() {
        if (!isActive()) return "idle";
        String where = destination == null ? "?" :
                (int) destination.getX() + "," + (int) destination.getY() + "," + (int) destination.getZ();
        return phase + " -> " + where;
    }

    public void abort() {
        arrival.cancel();
        tickets.release();
        phase = Phase.IDLE;
        destination = null;
        droppedItemId = null;
        phaseTicks = 0;
        repathCooldown = 0;
        requester = null;
        failure = null;
    }

    public boolean handleDelivery(Player botPlayer) {
        if (!isActive()) return false;
        if (botPlayer == null) { abort(); return false; }

        ServerPlayer handle = context.bot.getHandle();
        if (handle == null) { abort(); return false; }

        if (context.target != null || context.fleeing) {
            return false;
        }

        phaseTicks++;
        if (repathCooldown > 0) repathCooldown--;

        tickets.refresh(botPlayer.getLocation());

        switch (phase) {
            case TO_LEADER -> { return tickToLeader(botPlayer); }
            case HANDOVER -> { return tickHandover(botPlayer); }
            case PICKUP -> { return tickPickup(botPlayer); }
            case TRAVEL -> { return tickTravel(botPlayer); }
            default -> { return false; }
        }
    }

    private boolean tickToLeader(Player botPlayer) {
        Player leader = resolveLeader();
        if (leader == null || leader.getWorld() != botPlayer.getWorld()) {
            fail("the faction leader is no longer available");
            return false;
        }

        double dist = leader.getLocation().distance(botPlayer.getLocation());
        if (dist <= LEADER_REACH) {
            phase = Phase.HANDOVER;
            phaseTicks = 0;
            context.currentPath.clear();
            context.pathNodeIndex = 0;
            return true;
        }

        if (phaseTicks > 3000) {
            fail("could not reach the faction leader");
            return false;
        }

        walkTowards(botPlayer, leader.getLocation());
        return true;
    }

    private boolean tickHandover(Player botPlayer) {
        Player leader = resolveLeader();
        if (leader == null) {
            fail("the faction leader is no longer available");
            return false;
        }

        context.requestLookYaw(yawTowards(botPlayer, leader.getLocation()),
                BotAIContext.LOOK_UTILITY);
        context.forwardInput = 0f;
        context.strafeInput = 0f;

        if (phaseTicks < 12) return true;

        int slot = findShulkerSlot(leader.getInventory());
        if (slot < 0) {
            fail("the faction leader has no shulker box to hand over");
            return false;
        }

        ItemStack box = leader.getInventory().getItem(slot);
        if (box == null || box.getType().isAir()) {
            fail("the faction leader has no shulker box to hand over");
            return false;
        }

        ItemStack single = box.clone();
        single.setAmount(1);
        if (box.getAmount() <= 1) {
            leader.getInventory().setItem(slot, null);
        } else {
            box.setAmount(box.getAmount() - 1);
            leader.getInventory().setItem(slot, box);
        }
        leader.updateInventory();

        ItemTags.markCargo(single);

        Item dropped = leader.getWorld().dropItem(
                leader.getLocation().clone().add(0, 1.0, 0), single);
        dropped.setPickupDelay(10);
        droppedItemId = dropped.getUniqueId();

        phase = Phase.PICKUP;
        phaseTicks = 0;
        return true;
    }

    private boolean tickPickup(Player botPlayer) {
        Item item = findDroppedItem(botPlayer.getWorld());

        if (item == null || item.isDead()) {
            if (hasShulker(botPlayer)) {
                beginTravel();
                return true;
            }
            fail("the shulker box was lost before the bot could collect it");
            return false;
        }

        if (phaseTicks > 600) {
            fail("the bot could not collect the shulker box");
            return false;
        }

        double dist = item.getLocation().distance(botPlayer.getLocation());

        if (dist <= 1.6) {
            if (botPlayer.getInventory().firstEmpty() == -1 && !freeASlot(botPlayer)) {
                fail("the bot's inventory is full and nothing could be dropped");
                return false;
            }

            ItemStack stack = item.getItemStack();
            botPlayer.getInventory().addItem(stack);
            botPlayer.updateInventory();
            item.remove();
            droppedItemId = null;
            beginTravel();
            return true;
        }

        walkTowards(botPlayer, item.getLocation());
        return true;
    }

    private void beginTravel() {
        phase = Phase.TRAVEL;
        phaseTicks = 0;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        repathCooldown = 0;
    }

    private boolean tickTravel(Player botPlayer) {
        if (destination == null) {
            fail("no destination");
            return false;
        }

        if (!arrival.isActive()) arrival.begin(destination);

        double vert = Math.abs(destination.getY() - botPlayer.getLocation().getY());
        ArrivalTracker.State state = arrival.update(botPlayer);

        if (state == ArrivalTracker.State.ARRIVED && vert <= 3.0) {
            dropParcel(botPlayer);
            succeed();
            return true;
        }

        if (state == ArrivalTracker.State.STALLED) {
            dropParcel(botPlayer);
            report("§e" + context.bot.getName() + " could not reach the exact spot — "
                    + "left the shulker box as close as it could get.");
            finish();
            return false;
        }

        if (phaseTicks > 12000) {
            fail("gave up trying to reach the destination");
            return false;
        }

        walkTowards(botPlayer, destination);
        return true;
    }

    private void walkTowards(Player botPlayer, Location dest) {
        MovementController mc = context.movementController;

        boolean blocked = !mc.canWalkStraightTo(dest);
        boolean tooFar = dest.distance(botPlayer.getLocation()) > 16.0;

        if (blocked || tooFar) {
            boolean pathExhausted = context.currentPath.isEmpty()
                    || context.pathNodeIndex >= context.currentPath.size();
            if (pathExhausted && repathCooldown <= 0 && context.pathRecalcCooldown <= 0) {
                context.pathfindingController.calculatePathAsync(botPlayer.getLocation(), dest);

                repathCooldown = 20;
            }
            if (!context.currentPath.isEmpty()
                    && context.pathNodeIndex < context.currentPath.size()) {
                mc.followPath();
                return;
            }
            context.requestLookYaw(yawTowards(botPlayer, dest), BotAIContext.LOOK_TRAVEL);
            context.forwardInput = 0f;
            context.strafeInput = 0f;
            return;
        }

        context.requestLookYaw(yawTowards(botPlayer, dest),
                BotAIContext.LOOK_TRAVEL);
        context.forwardInput = 1.0f;
        context.strafeInput = 0f;
    }

    private float yawTowards(Player from, Location to) {
        double dx = to.getX() - from.getLocation().getX();
        double dz = to.getZ() - from.getLocation().getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private Player resolveLeader() {
        BotManager mgr = PvPBotPlugin.getInstance().getBotManager();
        if (mgr == null) return null;
        UUID leaderId = mgr.getLeaderFor(context.bot.getUUID());
        if (leaderId == null || leaderId.equals(context.bot.getUUID())) return null;
        Player leader = org.bukkit.Bukkit.getPlayer(leaderId);
        if (leader == null || !leader.isOnline() || leader.isDead()) return null;
        return leader;
    }

    private static boolean isShulker(ItemStack stack) {
        return stack != null && stack.getType().name().endsWith("SHULKER_BOX");
    }

    private int findShulkerSlot(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (isShulker(inv.getItem(i))) return i;
        }
        return -1;
    }

    private boolean hasShulker(Player p) {
        return findShulkerSlot(p.getInventory()) >= 0;
    }

    private Item findDroppedItem(World world) {
        if (droppedItemId == null) return null;
        org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(droppedItemId);
        if (e instanceof Item item && item.getWorld() == world) return item;
        return null;
    }

    private boolean freeASlot(Player p) {
        Inventory inv = p.getInventory();
        int worst = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType().isAir()) continue;
            if (isShulker(s)) continue;
            if (isProtected(s.getType())) continue;
            worst = i;
            break;
        }
        if (worst < 0) return false;

        ItemStack s = inv.getItem(worst);
        inv.setItem(worst, null);
        p.getWorld().dropItemNaturally(p.getLocation().add(0, 1.0, 0), s);
        p.updateInventory();
        return true;
    }

    private boolean isProtected(Material m) {
        String n = m.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE")) return true;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) return true;
        if (m == Material.SHIELD || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.TRIDENT || m == Material.MACE) return true;
        if (m == Material.TOTEM_OF_UNDYING || m == Material.ENCHANTED_GOLDEN_APPLE
                || m == Material.GOLDEN_APPLE || m == Material.ENDER_PEARL
                || m == Material.WIND_CHARGE || m == Material.EXPERIENCE_BOTTLE) return true;
        if (m.isEdible()) return true;

        if (m.isBlock() && m.isSolid()) return true;
        return false;
    }

    private void dropParcel(Player botPlayer) {
        int slot = findCargoSlot(botPlayer);
        if (slot < 0) slot = findShulkerSlot(botPlayer.getInventory());
        if (slot < 0) return;

        ItemStack parcel = botPlayer.getInventory().getItem(slot);
        if (parcel == null) return;
        botPlayer.getInventory().setItem(slot, null);
        botPlayer.updateInventory();

        ItemTags.clearCargo(parcel);

        Location at = botPlayer.getLocation();
        Block spot = at.getBlock();
        try {
            if (spot.getType().isAir()
                    && spot.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()) {
                BlockState replaced = spot.getState();
                spot.setType(parcel.getType(), false);

                org.bukkit.event.block.BlockPlaceEvent event =
                        new org.bukkit.event.block.BlockPlaceEvent(
                                spot, replaced,
                                spot.getRelative(org.bukkit.block.BlockFace.DOWN),
                                parcel.clone(), botPlayer, true,
                                org.bukkit.inventory.EquipmentSlot.HAND);
                org.bukkit.Bukkit.getPluginManager().callEvent(event);

                if (event.isCancelled() || !event.canBuild()) {
                    replaced.update(true, false);
                } else {
                    ItemStack[] contents = readShulkerContents(parcel);
                    if (contents != null) ShulkerIO.write(spot, contents);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }

        botPlayer.getWorld().dropItemNaturally(at.clone().add(0, 0.5, 0), parcel);
    }

    private int findCargoSlot(Player p) {
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isShulker(s) && ItemTags.isCargo(s)) return i;
        }
        return -1;
    }

    private static ItemStack[] readShulkerContents(ItemStack shulkerItem) {
        try {
            if (shulkerItem.getItemMeta() instanceof BlockStateMeta bsm
                    && bsm.getBlockState() instanceof ShulkerBox sb) {
                ItemStack[] src = sb.getInventory().getContents();
                ItemStack[] copy = new ItemStack[src.length];
                for (int i = 0; i < src.length; i++) {
                    copy[i] = src[i] == null ? null : src[i].clone();
                }
                return copy;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void succeed() {
        report("§a" + context.bot.getName() + " delivered the shulker box.");
        finish();
    }

    private void fail(String reason) {
        failure = reason;
        report("§c" + context.bot.getName() + " could not finish the delivery: " + reason);
        finish();
    }

    private void finish() {
        arrival.cancel();
        tickets.release();
        phase = Phase.DONE;
        destination = null;
        droppedItemId = null;
        requester = null;
        context.currentPath.clear();
        context.pathNodeIndex = 0;
        context.forwardInput = 0f;
        context.strafeInput = 0f;
        phase = Phase.IDLE;
    }

    private void report(String message) {
        if (requester == null) return;
        Player p = org.bukkit.Bukkit.getPlayer(requester);
        if (p != null && p.isOnline()) p.sendMessage(message);
        else PvPBotPlugin.getInstance().getLogger().info("[PvPBot] " + message);
    }

    public String lastFailure() {
        return failure;
    }
}
