package com.pvpbot;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import org.bukkit.Bukkit;
import com.pvpbot.ai.BotAIContext;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import com.pvpbot.voice.PvPBotVoiceChat;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

public class PvPBot {
    private final ServerPlayer handle;
    private final String name;
    private final UUID uuid;
    private boolean alive = true;

    private volatile boolean beingRemoved = false;
    private ServerLevel level;
    private final BotAI ai;
    private final BotSettings settings;

    private static final int POSITION_RESYNC_INTERVAL = 10;
    private int resyncCounter = 0;

    private ClientPacketSink packetSink;

    private FakeChannel fakeChannel;

    private Connection fakeConnection;

    private static volatile PvPBot spawning;

    private static volatile boolean quietSpawn = false;

    private static volatile boolean forceBlackSkin = false;

    private static volatile boolean forceInvisibleOnSpawn = false;

    private volatile boolean silent = false;

    public static boolean isQuietSpawn() {
        return quietSpawn;
    }

    public boolean isSilent() {
        return silent;
    }

    public static void spawningQuietly(Runnable body) {
        boolean prev = quietSpawn;
        quietSpawn = true;
        try {
            body.run();
        } finally {
            quietSpawn = prev;
        }
    }

    public static void spawningInvisible(Runnable body) {
        boolean prev = forceInvisibleOnSpawn;
        forceInvisibleOnSpawn = true;
        try {
            body.run();
        } finally {
            forceInvisibleOnSpawn = prev;
        }
    }

    public static void spawningWithBlackSkin(Runnable body) {
        boolean prev = forceBlackSkin;
        forceBlackSkin = true;
        try {
            body.run();
        } finally {
            forceBlackSkin = prev;
        }
    }

    public static PvPBot getSpawning(UUID uuid) {
        PvPBot s = spawning;
        return (s != null && s.uuid.equals(uuid)) ? s : null;
    }

    public ClientPacketSink getPacketSink() { return packetSink; }

    public BotAI getAI() { return ai; }

    public void orderToFormationSlot(org.bukkit.Location slot) {
        orderToFormationSlot(slot, 600);
    }

    public void orderToFormationSlot(org.bukkit.Location slot, int ticks) {
        if (ai == null || slot == null) return;
        ai.getContext().formationSlot = slot.clone();
        ai.getContext().formationTicks = ticks;
    }

    public PvPBot(Location location, PvPBotPlugin plugin, BotSettings settings) {
        this(location, plugin, settings, NameGenerator.NameStyle.CLEAN);
    }

    public PvPBot(Location location, PvPBotPlugin plugin, BotSettings settings,
                  NameGenerator.NameStyle nameStyle) {
        this(location, plugin, settings, nameStyle, null, null);
    }

    public PvPBot(Location location, PvPBotPlugin plugin, BotSettings settings,
                  NameGenerator.NameStyle nameStyle, String fixedName, UUID fixedUuid) {
        this.settings = settings;
        this.silent   = quietSpawn;
        this.name     = (fixedName != null && !fixedName.isBlank())
                ? fixedName : NameGenerator.getRandomName(nameStyle);
        if (fixedName != null) NameGenerator.reserveName(this.name);

        UUID derived = fixedUuid != null ? fixedUuid : SkinPool.offlineUuidFor(this.name);
        this.uuid = Bukkit.getPlayer(derived) != null ? UUID.randomUUID() : derived;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        this.level = ((CraftWorld) location.getWorld()).getHandle();

        boolean blackSkin = forceBlackSkin || nameStyle == NameGenerator.NameStyle.VOID;
        GameProfile profile = blackSkin
                ? SkinPool.createBlackProfile(uuid, name)
                : SkinPool.createProfile(uuid, name);

        this.handle = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        this.handle.noPhysics = false;
        this.handle.setNoGravity(false);

        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());

        handle.getBukkitEntity().setMetadata("NPC",
                new org.bukkit.metadata.FixedMetadataValue(plugin, true));

        if (forceInvisibleOnSpawn) {
            // A plain metadata flag, not a real potion effect - addEffect()
            // tries to notify the connection immediately, which doesn't
            // exist yet this early. Set before placeNewPlayer broadcasts
            // the entity, so the very first spawn packet already carries
            // the invisible flag - no visible frame before it kicks in.
            handle.getBukkitEntity().setInvisible(true);
        }

        Connection connection = createFakeConnection();
        this.fakeConnection = connection;
        CommonListenerCookie cookie = createCookie(profile);

        spawning = this;
        try {
            server.getPlayerList().placeNewPlayer(connection, handle, cookie);
        } finally {
            spawning = null;
        }

        restoreFakeChannel(connection);

        PvPBotVoiceChat.connectBot(plugin, uuid, name);

        try {
            ServerLevel intended = ((CraftWorld) location.getWorld()).getHandle();
            if (handle.level() != intended) {
                getBukkitPlayer().teleport(location);
                this.level = intended;
            }
        } catch (Throwable t) {
            plugin().getLogger().warning(
                    "[PvPBot] Could not correct spawn dimension for " + name + ": " + t);
        }

        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setRot(location.getYaw(), location.getPitch());
        resyncConnection();

        forceClientLoaded(handle);

        AttributeInstance speedAttr = handle.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.removeModifiers();

            speedAttr.setBaseValue(0.10D);
        }

        AttributeInstance atkSpeedAttr = handle.getAttribute(Attributes.ATTACK_SPEED);
        if (atkSpeedAttr != null) {
            if (atkSpeedAttr.getBaseValue() <= 0.0) {
                atkSpeedAttr.setBaseValue(4.0D);
            }
        } else {
            plugin.getLogger().warning("[PvPBot] handle.getAttribute(Attributes.ATTACK_SPEED) "
                    + "returned null at spawn for " + this.name
                    + " - the ATTACK_SPEED attribute instance does not exist on this entity at all.");
        }
        AttributeInstance atkDmgAttr = handle.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atkDmgAttr != null) {
            if (atkDmgAttr.getBaseValue() <= 0.0) {
                atkDmgAttr.setBaseValue(1.0D);
            }
        } else {
            plugin.getLogger().warning("[PvPBot] handle.getAttribute(Attributes.ATTACK_DAMAGE) "
                    + "returned null at spawn for " + this.name
                    + " - the ATTACK_DAMAGE attribute instance does not exist on this entity at all.");
        }

        getBukkitPlayer().setGameMode(org.bukkit.GameMode.SURVIVAL);

        if (!com.pvpbot.ai.PacketBroadcaster.USE_VANILLA_TRACKER) {
            sendSpawnPackets();
        }

        this.ai = new BotAI(this, settings);

        this.ai.getContext().forceFullTicks = 60;

        this.ai.getContext().inventoryController.applyToolAttributes(getBukkitPlayer());
    }

    private static boolean clientLoadedWarned = false;

    public static void forceClientLoaded(ServerPlayer handle) {
        try {
            if (handle.connection != null && handle.connection.hasClientLoaded()) return;
        } catch (Throwable ignored) {
        }

        try {
            var m = handle.getClass().getMethod("setClientLoaded", boolean.class);
            m.invoke(handle, true);
            return;
        } catch (ReflectiveOperationException ignored) {  }

        Object conn = handle.connection;
        if (conn == null) return;

        for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
            for (var m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (!n.contains("clientloaded")) continue;
                if (n.startsWith("has") || n.startsWith("is")) continue;
                if (m.getReturnType() != void.class) continue;
                try {
                    if (m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        m.invoke(conn);
                        return;
                    }
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class) {
                        m.setAccessible(true);
                        m.invoke(conn, true);
                        return;
                    }
                } catch (ReflectiveOperationException ignored) {  }
            }
        }

        for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (f.getType() != boolean.class) continue;
                if (!f.getName().toLowerCase(java.util.Locale.ROOT).contains("clientloaded")) continue;
                try {
                    f.setAccessible(true);
                    f.setBoolean(conn, true);
                    return;
                } catch (ReflectiveOperationException ignored) {  }
            }
        }

        if (!clientLoadedWarned) {
            clientLoadedWarned = true;
            Bukkit.getLogger().warning("[PvPBot] Could not force the client-loaded flag on "
                    + conn.getClass().getName()
                    + "; bots may stay invulnerable/frozen until the vanilla load timeout.");
        }
    }

    private static CommonListenerCookie createCookie(GameProfile profile) {
        ClientInformation clientInfo = ClientInformation.createDefault();

        java.lang.reflect.RecordComponent[] components =
                CommonListenerCookie.class.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[]  args  = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            Class<?> type = components[i].getType();
            types[i] = type;
            if (type == GameProfile.class)                        args[i] = profile;
            else if (type == int.class)                           args[i] = 0;
            else if (type == ClientInformation.class)             args[i] = clientInfo;
            else if (type == boolean.class)                       args[i] = false;
            else if (java.util.Set.class.isAssignableFrom(type))

                args[i] = java.util.concurrent.ConcurrentHashMap.newKeySet();
            else if (type == String.class)                        args[i] = "vanilla";
            else                                                  args[i] = null;
        }

        try {
            java.lang.reflect.Constructor<CommonListenerCookie> ctor =
                    CommonListenerCookie.class.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().warning("[PvPBot] Reflective cookie construction failed ("
                    + e + "); falling back to nulls — plugin-messaging plugins may log "
                    + "errors when bots join.");
            return new CommonListenerCookie(profile, 0, clientInfo, false, null, null, null);
        }
    }

    public void reassertFakeChannel() {
        restoreFakeChannel(fakeConnection);
    }

    private void restoreFakeChannel(Connection connection) {
        if (fakeChannel == null || connection == null) return;
        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            Object current = channelField.get(connection);
            if (current == fakeChannel) return;

            channelField.set(connection, fakeChannel);
            Bukkit.getLogger().fine("[PvPBot] Restored the fake channel for " + name
                    + " (was wrapped by "
                    + (current == null ? "null" : current.getClass().getSimpleName()) + ")");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[PvPBot] Could not restore the fake channel for "
                    + name + ": " + e);
        }
    }

    private Connection createFakeConnection() {
        NetworkManager connection = new NetworkManager(PacketFlow.SERVERBOUND);

        FakeChannel embeddedChannel = new FakeChannel();
        this.fakeChannel = embeddedChannel;

        this.packetSink = new ClientPacketSink(handle);
        embeddedChannel.pipeline().addFirst("pvpbot_client_sink", packetSink);

        PipelineStubs.install(embeddedChannel.pipeline());
        embeddedChannel.pipeline().addLast("packet_handler", connection);

        try {
            Field channelField = Connection.class.getDeclaredField("channel");
            channelField.setAccessible(true);
            channelField.set(connection, embeddedChannel);

            Field addressField = Connection.class.getDeclaredField("address");
            addressField.setAccessible(true);
            addressField.set(connection, new java.net.InetSocketAddress("127.0.0.1", 0));
        } catch (Exception e) {
            Bukkit.getLogger().severe("[PvPBot] Could not wire the fake connection for "
                    + name + ": " + e);
        }

        return connection;
    }

    public void teleportTo(Location loc) {
        if (!alive || loc.getWorld() == null) return;

        try {
            ServerLevel target = ((CraftWorld) loc.getWorld()).getHandle();
            if (handle.level() != target) {
                getBukkitPlayer().teleport(loc);
                this.level = target;
                forceClientLoaded(handle);
            }
        } catch (Throwable ignored) {
        }

        handle.setPos(loc.getX(), loc.getY(), loc.getZ());
        handle.setRot(loc.getYaw(), loc.getPitch());
        handle.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        handle.resetFallDistance();
        resyncConnection();

        if (ai != null) {
            BotAIContext ctx = ai.getContext();
            ctx.prevTickX = loc.getX();
            ctx.prevTickY = loc.getY();
            ctx.prevTickZ = loc.getZ();
            ctx.prevTickInitialized = true;
        }
    }

    private void resyncConnection() {
        if (handle.connection == null) return;
        try {
            handle.connection.resetPosition();
            level.getChunkSource().move(handle);
        } catch (Throwable t) {
        }
    }

    public void equipKit(String kitName) {
        equipKit(kitName, true);
    }

    public void equipKit(String kitName, boolean wearArmor) {
        KitManager km = PvPBotPlugin.getInstance().getKitManager();
        if (!km.kitExists(kitName)) {
            plugin().getLogger().warning("[PvPBot] Kit '" + kitName + "' does not exist");
            return;
        }

        Player botPlayer = getBukkitPlayer();
        if (botPlayer == null) {
            plugin().getLogger().warning("[PvPBot] Bot player is null for kit '" + kitName + "'");
            return;
        }

        ItemStack[] contents = km.getKitContents(kitName);
        ItemStack[] armor    = km.getKitArmor(kitName);
        ItemStack offhand    = km.getKitOffhand(kitName);

        botPlayer.getInventory().clear();

        if (wearArmor) {
            botPlayer.getInventory().setContents(contents);
            botPlayer.getInventory().setBoots(armor[0]);
            botPlayer.getInventory().setLeggings(armor[1]);
            botPlayer.getInventory().setChestplate(armor[2]);
            botPlayer.getInventory().setHelmet(armor[3]);
            botPlayer.getInventory().setItemInOffHand(offhand);
        } else {
            // Stash mode - nothing worn, nothing held either, or an
            // "invisible" bot would still show a floating item/armor.
            // Everything just rides along in the pack instead.
            ItemStack[] stashed = contents.clone();
            int heldSlot = botPlayer.getInventory().getHeldItemSlot();
            if (heldSlot >= 0 && heldSlot < stashed.length
                    && stashed[heldSlot] != null && !stashed[heldSlot].getType().isAir()) {
                relocateToFreeSlot(stashed, heldSlot);
            }
            botPlayer.getInventory().setContents(stashed);

            for (ItemStack piece : armor) {
                if (piece != null && !piece.getType().isAir()) {
                    botPlayer.getInventory().addItem(piece);
                }
            }
            if (offhand != null && !offhand.getType().isAir()) {
                botPlayer.getInventory().addItem(offhand);
            }
        }

        ServerPlayer h = getHandle();

        AttributeInstance armorAttr = h.getAttribute(Attributes.ARMOR);
        if (armorAttr != null && armorAttr.getBaseValue() != 0.0) {
            armorAttr.setBaseValue(0.0);
        }
        AttributeInstance toughnessAttr = h.getAttribute(Attributes.ARMOR_TOUGHNESS);
        if (toughnessAttr != null && toughnessAttr.getBaseValue() != 0.0) {
            toughnessAttr.setBaseValue(0.0);
        }

        botPlayer.updateInventory();
        broadcastEquipment();

        ai.getContext().inventoryController.applyToolAttributes(botPlayer);

        plugin().getLogger().info("[PvPBot] Kit '" + kitName + "' applied to " + name);
    }

    private static void relocateToFreeSlot(ItemStack[] contents, int fromSlot) {
        for (int i = 0; i < contents.length; i++) {
            if (i == fromSlot) continue;
            if (contents[i] == null || contents[i].getType().isAir()) {
                contents[i] = contents[fromSlot];
                contents[fromSlot] = null;
                return;
            }
        }
    }

    private PvPBotPlugin plugin() {
        return PvPBotPlugin.getInstance();
    }

    public void broadcastEquipment() {
        var equipmentPacket = new ClientboundSetEquipmentPacket(
                handle.getId(),
                List.of(
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND, handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.OFFHAND,  handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.HEAD,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.CHEST,    handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.LEGS,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.FEET,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))
                )
        );

        Player self = getBukkitPlayer();
        Player[] real = com.pvpbot.perf.PlayerSnapshot.getRealPlayers();
        int n = com.pvpbot.perf.PlayerSnapshot.getRealCount();
        for (int i = 0; i < n; i++) {
            Player online = real[i];
            if (online == null || online == self) continue;
            ServerPlayer sp = ((CraftPlayer) online).getHandle();
            if (sp != null && sp.connection != null) {
                sp.connection.send(equipmentPacket);
            }
        }
    }

    public void tick() {
        tick(true);
    }

    public void tick(boolean fullAI) {
        if (!alive) return;

        if (++resyncCounter >= POSITION_RESYNC_INTERVAL) {
            resyncCounter = 0;
            resyncConnection();
        }

        if (fullAI) {
            ai.tick();
        } else {
            ai.tickLight();
        }
    }

    private void sendSpawnPackets() {
        for (Player online : Bukkit.getOnlinePlayers()) sendSpawnPacketsTo(online);
    }

    public void sendSpawnPacketsTo(Player player) {
        if (com.pvpbot.ai.PacketBroadcaster.USE_VANILLA_TRACKER) return;

        ServerPlayer observer = ((CraftPlayer) player).getHandle();

        observer.connection.send(new ClientboundAddEntityPacket(
                handle.getId(), handle.getUUID(),
                handle.getX(), handle.getY(), handle.getZ(),
                handle.getXRot(), handle.getYRot(),
                handle.getType(), 0,
                handle.getDeltaMovement(),
                handle.getYHeadRot()
        ));

        observer.connection.send(new ClientboundRotateHeadPacket(
                handle, (byte) ((handle.getYHeadRot() * 256f) / 360f)));

        var nonDefault = handle.getEntityData().getNonDefaultValues();
        if (nonDefault != null) {
            observer.connection.send(new ClientboundSetEntityDataPacket(handle.getId(), nonDefault));
        }

        observer.connection.send(new ClientboundSetEquipmentPacket(
                handle.getId(),
                List.of(
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND, handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.OFFHAND,  handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.HEAD,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.CHEST,    handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.LEGS,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)),
                        Pair.of(net.minecraft.world.entity.EquipmentSlot.FEET,     handle.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET))
                )
        ));
    }

    public void setGuardPost(Location anchor, double radius, float facing) {
        setGuardPost(anchor, radius, facing, BotAIContext.GuardMode.POST);
    }

    public void setGuardLeader(Location fallbackAnchor, double radius, float facing) {
        setGuardPost(fallbackAnchor, radius, facing, BotAIContext.GuardMode.LEADER);
    }

    public void setGuardPost(Location anchor, double radius, float facing,
                             BotAIContext.GuardMode mode) {
        BotAIContext ctx = ai.getContext();
        ctx.guardMode = mode;
        ctx.guardAnchor = anchor.clone();
        ctx.guardRadius = Math.max(2.0, radius);

        ctx.guardLeash = ctx.guardRadius + Math.min(12.0, Math.max(4.0, ctx.guardRadius * 0.5));
        ctx.guardFacing = facing;
        ctx.guardScanYaw = 0f;
        ctx.guardScanTicks = 0;
        ctx.guardReturning = false;

        ctx.formationSlot = null;
        ctx.followHolding = false;
    }

    public void clearGuardPost() {
        BotAIContext ctx = ai.getContext();
        ctx.guardAnchor = null;
        ctx.guardReturning = false;
        ctx.guardMode = BotAIContext.GuardMode.POST;
    }

    public BotAIContext.GuardMode getGuardMode() {
        return ai.getContext().guardMode;
    }

    public void setForcedTarget(org.bukkit.entity.Player target) {
        ai.getContext().forcedTarget = target;
        if (target != null) {
            ai.getContext().forceFullTicks = 100;
            ai.getContext().findTargetCooldown = 0;
        }
    }

    public org.bukkit.entity.Player getForcedTarget() {
        return ai.getContext().forcedTarget;
    }

    public boolean isGuarding() {
        return ai.getContext().guardAnchor != null;
    }

    public Location getGuardAnchor() {
        Location a = ai.getContext().guardAnchor;
        return a == null ? null : a.clone();
    }

    public double getGuardRadius() {
        return ai.getContext().guardRadius;
    }

    public void remove() {
        remove(true);
    }

    public void remove(boolean deletePlayerData) {
        beingRemoved = true;

        try {
            if (ai != null && ai.getContext().restockController != null) {
                ai.getContext().deliveryController.abort();
                ai.getContext().miningController.abort();
                ai.getContext().cartController.abort();
                ai.getContext().tunnelController.abort();
                ai.getContext().restockController.abort();

                ai.getContext().clutchController.abort(getBukkitPlayer());

                ai.getContext().buildController.abort();
            }
        } catch (Throwable ignored) {
        }

        alive = false;

        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();

        try {
            if (server != null && server.getPlayerList() != null
                    && server.getPlayerList().getPlayer(uuid) == handle) {
                server.getPlayerList().remove(handle);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (deletePlayerData) deletePlayerData();

        if (!handle.isRemoved()) handle.discard();

        if (deletePlayerData) NameGenerator.releaseName(name);
    }

    private void deletePlayerData() {
        try {
            File worldFolder = level.getWorld().getWorldFolder();
            File dir = new File(worldFolder, "playerdata");
            new File(dir, uuid + ".dat").delete();
            new File(dir, uuid + ".dat_old").delete();
        } catch (Throwable ignored) {
        }
    }

    public ServerPlayer getHandle()   { return handle; }
    public Player getBukkitPlayer()   { return handle.getBukkitEntity(); }
    public String getName()           { return name; }
    public UUID getUUID()             { return uuid; }
    public boolean isBeingRemoved()   { return beingRemoved; }

    private static final long PORTAL_GRACE_MS = 20_000L;

    private long portalBlockedUntilMs = System.currentTimeMillis() + PORTAL_GRACE_MS;

    public void armPortalGrace() {
        portalBlockedUntilMs = System.currentTimeMillis() + PORTAL_GRACE_MS;
    }

    public boolean portalsBlocked() {
        return System.currentTimeMillis() < portalBlockedUntilMs;
    }

    public int remainingPortalGraceTicks() {
        long ms = portalBlockedUntilMs - System.currentTimeMillis();
        if (ms <= 0L) return 1;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ms / 50L));
    }

    public boolean isAlive()          { return alive; }
    public Location getLocation()     { return getBukkitPlayer().getLocation(); }
}
