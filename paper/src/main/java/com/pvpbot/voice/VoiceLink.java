package com.pvpbot.voice;

import com.pvpbot.BotManager;
import com.pvpbot.FormationManager;
import com.pvpbot.PvPBot;
import com.pvpbot.PvPBotPlugin;
import com.pvpbot.voice.VoiceCommandParser.Intent;
import com.pvpbot.voice.VoiceCommandParser.Parsed;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Server end of the PvPBot Voice Link Fabric mod.
//
// The mod listens to the player's own Simple Voice Chat microphone, turns
// speech into text on their PC, and sends anything that looks like a bot
// order here on "pvpbot:voice". This class parses it (VoiceCommandParser)
// and carries it out with the speaker's authority over the bots.
//
// Channels:
//   pvpbot:voice        client -> server  [byte 1][VarInt len][UTF-8 transcript]
//   pvpbot:voice_words  server -> client  [byte 1][VarInt n]([VarInt len][UTF-8])*
//                       command words (may open an order) and '@'-prefixed
//                       faction names (address the bots anywhere), so the
//                       client only ever sends utterances that might be orders.
public final class VoiceLink implements PluginMessageListener, Listener {
    public static final String CHANNEL = "pvpbot:voice";
    public static final String WORDS_CHANNEL = "pvpbot:voice_words";
    public static final String PERMISSION = "pvpbot.voice";

    private static final int PROTOCOL_VERSION = 1;
    private static final int MAX_TRANSCRIPT_BYTES = 512;
    private static final long MIN_INTERVAL_MS = 600;

    private static final double LOOK_RANGE = 64.0;
    private static final double NEAREST_RANGE = 64.0;
    private static final double RUSH_RANGE = 48.0;
    private static final double ALERT_RANGE = 24.0;
    private static final double ADVANCE_DISTANCE = 16.0;
    private static final int MOVE_TICKS = 12000;
    private static final int WAIT_TICKS = 1200;
    private static final int STAND_DOWN_TICKS = 20 * 20;
    private static final int FOLLOW_TICKS = 20 * 60;

    private final PvPBotPlugin plugin;
    private final Map<UUID, Long> lastCommand = new HashMap<>();
    private final Map<String, BukkitTask> standDownRelease = new HashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();

    private VoiceLink(PvPBotPlugin plugin) {
        this.plugin = plugin;
    }

    public static void register(PvPBotPlugin plugin) {
        VoiceLink link = new VoiceLink(plugin);
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, link);
        messenger.registerOutgoingPluginChannel(plugin, WORDS_CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(link, plugin);
        // Faction names change; keep clients' word lists current.
        Bukkit.getScheduler().runTaskTimer(plugin, link::broadcastWords, 20L * 60, 20L * 60);
        plugin.getLogger().info("Voice commands: listening for the PvPBot Voice Link mod on " + CHANNEL);
    }

    // =====================================================================
    // Networking
    // =====================================================================

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent e) {
        if (WORDS_CHANNEL.equals(e.getChannel())) sendWords(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastCommand.remove(e.getPlayer().getUniqueId());
        cancelFollow(e.getPlayer().getUniqueId());
    }

    private void broadcastWords() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getListeningPluginChannels().contains(WORDS_CHANNEL)) sendWords(p);
        }
    }

    private void sendWords(Player p) {
        // Faction names are marked with '@': they address the bots anywhere in
        // a sentence ("red team attack blue"); the rest only count when they
        // open one ("focus Steve").
        Set<String> words = new LinkedHashSet<>();
        for (String f : plugin.getBotManager().getFactionNames()) words.add("@" + f);
        words.addAll(VoiceCommandParser.vocabulary());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PROTOCOL_VERSION);
        List<byte[]> encoded = new ArrayList<>();
        for (String w : words) {
            byte[] b = w.toLowerCase().getBytes(StandardCharsets.UTF_8);
            if (b.length > 0 && b.length <= 64) encoded.add(b);
            if (encoded.size() >= 256) break;
        }
        writeVarInt(out, encoded.size());
        for (byte[] b : encoded) {
            writeVarInt(out, b.length);
            out.write(b, 0, b.length);
        }
        p.sendPluginMessage(plugin, WORDS_CHANNEL, out.toByteArray());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || player == null) return;
        String transcript = decode(message);
        if (transcript == null || transcript.isBlank()) return;

        if (Bukkit.isPrimaryThread()) {
            handle(player, transcript);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> handle(player, transcript));
        }
    }

    static String decode(byte[] data) {
        if (data == null || data.length < 2) return null;
        int pos = 0;
        int version = data[pos++] & 0xFF;
        if (version != PROTOCOL_VERSION) return null;

        int len = 0, shift = 0;
        while (true) {
            if (pos >= data.length || shift > 28) return null;
            byte b = data[pos++];
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (len < 0 || len > MAX_TRANSCRIPT_BYTES || pos + len > data.length) return null;
        return new String(data, pos, len, StandardCharsets.UTF_8);
    }

    // =====================================================================
    // Orders
    // =====================================================================

    private void handle(Player speaker, String transcript) {
        if (!speaker.isOnline()) return;
        if (!speaker.hasPermission(PERMISSION)) return;

        long now = System.currentTimeMillis();
        Long last = lastCommand.get(speaker.getUniqueId());
        if (last != null && now - last < MIN_INTERVAL_MS) return;

        BotManager manager = plugin.getBotManager();
        Parsed order = VoiceCommandParser.parse(transcript, manager.getFactionNames(),
                candidateNames(manager, speaker));
        if (order == null) return; // ordinary chatter
        lastCommand.put(speaker.getUniqueId(), now);

        List<PvPBot> bots = commandedBots(manager, speaker, order.subjectFaction());
        String who = order.subjectFaction() == null ? "everyone" : order.subjectFaction().toLowerCase();
        if (bots.isEmpty()) {
            // Only complain if they clearly meant the bots; a stray "let's
            // go" from someone with no bots shouldn't spam their chat.
            if (order.addressed()) {
                fail(speaker, transcript, order.subjectFaction() == null
                        ? "you don't lead any bots (become a faction leader first)"
                        : "you don't lead " + who);
            }
            return;
        }
        Set<String> factions = factionsOf(manager, bots);

        switch (order.intent()) {
            case ATTACK -> {
                releaseStandDown(manager, factions);
                cancelFollow(speaker.getUniqueId());
                attack(manager, speaker, transcript, order, bots, who);
            }
            case RUSH -> {
                releaseStandDown(manager, factions);
                cancelFollow(speaker.getUniqueId());
                List<Player> enemies = enemiesNear(manager, speaker, speaker.getLocation(), RUSH_RANGE);
                if (enemies.isEmpty()) {
                    fail(speaker, transcript, "no enemies nearby");
                    return;
                }
                int sent = spreadOver(bots, enemies);
                ok(speaker, transcript, who + " → rushing " + enemies.size() + " enemy(s) (" + sent + ")");
            }
            case STAND_DOWN -> {
                cancelFollow(speaker.getUniqueId());
                for (PvPBot b : bots) b.setForcedTarget(null);
                standDown(manager, factions);
                ok(speaker, transcript, who + " → standing down for " + (STAND_DOWN_TICKS / 20)
                        + "s (" + bots.size() + ")");
            }
            case ENGAGE -> {
                releaseStandDown(manager, factions);
                ok(speaker, transcript, who + " → weapons free (" + bots.size() + ")");
            }
            case COME -> {
                cancelFollow(speaker.getUniqueId());
                int moved = gatherAt(bots, speaker.getLocation());
                ok(speaker, transcript, who + " → coming to you (" + moved + ")");
            }
            case FOLLOW -> {
                startFollow(speaker, bots);
                ok(speaker, transcript, who + " → following you (" + bots.size() + ")");
            }
            case WAIT -> {
                cancelFollow(speaker.getUniqueId());
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp != null) b.orderToFormationSlot(bp.getLocation(), WAIT_TICKS);
                }
                ok(speaker, transcript, who + " → holding position (" + bots.size() + ")");
            }
            case ADVANCE -> {
                releaseStandDown(manager, factions);
                cancelFollow(speaker.getUniqueId());
                int moved = gatherAt(bots, aheadOf(speaker, ADVANCE_DISTANCE));
                ok(speaker, transcript, who + " → pushing forward (" + moved + ")");
            }
            case ALERT -> {
                releaseStandDown(manager, factions);
                List<Player> threats = enemiesNear(manager, speaker, speaker.getLocation(), ALERT_RANGE);
                if (threats.isEmpty()) {
                    ok(speaker, transcript, who + " → on alert");
                } else {
                    int sent = spreadOver(bots, threats);
                    ok(speaker, transcript, who + " → engaging " + threats.size()
                            + " threat(s) near you (" + sent + ")");
                }
            }
        }
    }

    private void attack(BotManager manager, Player speaker, String transcript,
                        Parsed order, List<PvPBot> bots, String who) {
        switch (order.targetKind()) {
            case FACTION -> {
                List<Player> enemies = new ArrayList<>();
                for (PvPBot b : manager.getFactionBots(order.target())) {
                    Player bp = b.getBukkitPlayer();
                    if (bp != null) enemies.add(bp);
                }
                for (UUID id : manager.getFactionMembers(order.target())) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline() && !enemies.contains(p)) enemies.add(p);
                }
                enemies.removeIf(p -> p.getUniqueId().equals(speaker.getUniqueId()) || p.isDead());
                if (enemies.isEmpty()) {
                    fail(speaker, transcript, order.target() + " has nobody alive to attack");
                    return;
                }
                int sent = spreadOver(bots, enemies);
                ok(speaker, transcript, who + " → attacking " + order.target().toLowerCase()
                        + " (" + sent + ")");
            }
            case NAME, LOOK, NEAREST -> {
                Player victim = switch (order.targetKind()) {
                    case NAME -> resolveName(manager, order.target());
                    case LOOK -> lookedAt(speaker);
                    default -> nearest(speaker.getLocation(),
                            enemiesNear(manager, speaker, speaker.getLocation(), NEAREST_RANGE),
                            speaker.getUniqueId());
                };
                if (victim == null) {
                    fail(speaker, transcript, switch (order.targetKind()) {
                        case NAME -> order.target() + " isn't online";
                        case LOOK -> "look at who you want them to attack";
                        default -> "no enemy nearby";
                    });
                    return;
                }
                if (victim.getUniqueId().equals(speaker.getUniqueId())) {
                    fail(speaker, transcript, "they won't turn on you");
                    return;
                }
                int sent = 0;
                for (PvPBot b : bots) {
                    if (b.getUUID().equals(victim.getUniqueId())) continue;
                    b.setForcedTarget(victim);
                    sent++;
                }
                ok(speaker, transcript, who + " → attacking "
                        + ChatColor.stripColor(victim.getName()) + " (" + sent + ")");
            }
            case NONE -> fail(speaker, transcript,
                    order.heardTarget() == null || order.heardTarget().isBlank()
                            ? "didn't catch who to attack"
                            : "no player sounds like \"" + order.heardTarget() + "\"");
        }
    }

    // ---- stand-down: timed, so a "chill" never leaves bots passive forever

    private void standDown(BotManager manager, Set<String> factions) {
        for (String f : factions) {
            manager.setFactionStopAttack(f, true);
            BukkitTask old = standDownRelease.remove(f.toLowerCase());
            if (old != null) old.cancel();
            standDownRelease.put(f.toLowerCase(), Bukkit.getScheduler().runTaskLater(plugin, () -> {
                standDownRelease.remove(f.toLowerCase());
                manager.setFactionStopAttack(f, false);
            }, STAND_DOWN_TICKS));
        }
    }

    private void releaseStandDown(BotManager manager, Set<String> factions) {
        for (String f : factions) {
            BukkitTask t = standDownRelease.remove(f.toLowerCase());
            if (t != null) t.cancel();
            if (manager.isFactionStopAttack(f)) manager.setFactionStopAttack(f, false);
        }
    }

    // ---- movement orders

    private int gatherAt(List<PvPBot> bots, Location anchor) {
        List<Location> slots = FormationManager.compute(anchor, FormationManager.Shape.SKIRMISH,
                bots.size(), FormationManager.DEFAULT_SPACING);
        int moved = 0;
        for (int i = 0; i < bots.size() && i < slots.size(); i++) {
            bots.get(i).orderToFormationSlot(slots.get(i), MOVE_TICKS);
            moved++;
        }
        return moved;
    }

    private void startFollow(Player speaker, List<PvPBot> bots) {
        cancelFollow(speaker.getUniqueId());
        UUID id = speaker.getUniqueId();
        List<PvPBot> crew = new ArrayList<>(bots);
        final Location[] lastAnchor = {null};
        final int[] ticks = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            ticks[0] += 20;
            if (p == null || !p.isOnline() || p.isDead() || ticks[0] > FOLLOW_TICKS) {
                cancelFollow(id);
                return;
            }
            Location here = p.getLocation();
            if (lastAnchor[0] == null || lastAnchor[0].getWorld() != here.getWorld()
                    || lastAnchor[0].distanceSquared(here) > 2.5 * 2.5) {
                crew.removeIf(b -> !b.isAlive());
                gatherAt(crew, here);
                lastAnchor[0] = here;
            }
        }, 0L, 20L);
        followTasks.put(id, task);
    }

    private void cancelFollow(UUID speaker) {
        BukkitTask t = followTasks.remove(speaker);
        if (t != null) t.cancel();
    }

    private static Location aheadOf(Player speaker, double distance) {
        Location eye = speaker.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(distance);
        Location target = eye.clone().add(dir);
        World w = target.getWorld();
        if (w != null) {
            int ground = w.getHighestBlockYAt(target.getBlockX(), target.getBlockZ()) + 1;
            if (Math.abs(ground - eye.getY()) <= 8) target.setY(ground);
        }
        return target;
    }

    // ---- targeting helpers

    // Each bot takes the enemy closest to itself, so a group spreads over a
    // group instead of all piling on one.
    private static int spreadOver(List<PvPBot> bots, List<Player> enemies) {
        int sent = 0;
        for (PvPBot b : bots) {
            Player bp = b.getBukkitPlayer();
            if (bp == null) continue;
            Player victim = nearest(bp.getLocation(), enemies, b.getUUID());
            if (victim == null) continue;
            b.setForcedTarget(victim);
            sent++;
        }
        return sent;
    }

    private static List<Player> enemiesNear(BotManager manager, Player speaker, Location center, double range) {
        List<Player> out = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (p == speaker || p.isDead()) continue;
            if (manager.isFriendly(speaker.getUniqueId(), p.getUniqueId())) continue;
            PvPBot asBot = manager.getBots().get(p.getUniqueId());
            if (asBot != null && speaker.getUniqueId().equals(manager.getLeaderFor(asBot.getUUID()))) continue;
            if (p.getLocation().distanceSquared(center) > range * range) continue;
            out.add(p);
        }
        return out;
    }

    // Bots the speaker may order. A faction/group leader commands their own
    // bots; someone with pvpbot.admin who leads nothing commands every bot.
    private List<PvPBot> commandedBots(BotManager manager, Player speaker, String faction) {
        UUID me = speaker.getUniqueId();
        boolean admin = speaker.hasPermission("pvpbot.admin");
        List<PvPBot> out = new ArrayList<>();

        if (faction != null) {
            if (admin || me.equals(manager.getFactionLeader(faction))) out.addAll(manager.getFactionBots(faction));
            return out;
        }
        for (PvPBot b : manager.getBots().values()) {
            if (b.isAlive() && me.equals(manager.getLeaderFor(b.getUUID()))) out.add(b);
        }
        if (out.isEmpty() && admin) {
            for (PvPBot b : manager.getBots().values()) if (b.isAlive()) out.add(b);
        }
        return out;
    }

    private static Set<String> factionsOf(BotManager manager, List<PvPBot> bots) {
        Set<String> out = new LinkedHashSet<>();
        for (PvPBot b : bots) {
            String f = manager.getPlayerFaction(b.getUUID());
            if (f != null) out.add(f);
        }
        return out;
    }

    private static List<String> candidateNames(BotManager manager, Player speaker) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(speaker.getUniqueId())) continue;
            names.put(ChatColor.stripColor(p.getName()), true);
        }
        for (PvPBot b : manager.getBots().values()) {
            if (b.isAlive()) names.put(ChatColor.stripColor(b.getName()), true);
        }
        return new ArrayList<>(names.keySet());
    }

    private static Player resolveName(BotManager manager, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null && p.isOnline()) return p;
        PvPBot b = manager.getBotByName(name);
        return b != null && b.isAlive() ? b.getBukkitPlayer() : null;
    }

    private static Player lookedAt(Player speaker) {
        Entity e = speaker.getTargetEntity((int) LOOK_RANGE);
        if (e instanceof Player p && !p.isDead()) return p;

        // Forgiving fallback: the closest player within ~12° of the crosshair.
        Location eye = speaker.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Player best = null;
        double bestAngle = Math.toRadians(12);
        for (Player other : speaker.getWorld().getPlayers()) {
            if (other == speaker || other.isDead()) continue;
            Vector to = other.getLocation().add(0, 1.0, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist < 0.5 || dist > LOOK_RANGE) continue;
            double angle = dir.angle(to);
            if (angle < bestAngle && speaker.hasLineOfSight(other)) {
                bestAngle = angle;
                best = other;
            }
        }
        return best;
    }

    private static Player nearest(Location from, List<Player> players, UUID exclude) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : players) {
            if (p.getUniqueId().equals(exclude) || p.getWorld() != from.getWorld()) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static void ok(Player speaker, String heard, String what) {
        speaker.sendActionBar(Component.text("🎙 " + what, NamedTextColor.GREEN));
        speaker.sendMessage(Component.text("🎙 \"" + heard + "\" ", NamedTextColor.DARK_GRAY)
                .append(Component.text(what, NamedTextColor.GRAY)));
    }

    private static void fail(Player speaker, String heard, String why) {
        speaker.sendActionBar(Component.text("🎙 " + why, NamedTextColor.RED));
        speaker.sendMessage(Component.text("🎙 \"" + heard + "\" ", NamedTextColor.DARK_GRAY)
                .append(Component.text(why, NamedTextColor.RED)));
    }
}
