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

        // Voice orders only ever come from faction leaders, and only reach
        // the bots of the factions they lead. Anyone else is ignored
        // outright - no parsing, no feedback.
        List<PvPBot> led = ledBots(manager, speaker);
        if (led.isEmpty()) return;

        List<String> ledNames = new ArrayList<>();
        for (PvPBot b : led) ledNames.add(ChatColor.stripColor(b.getName()));
        Parsed order = VoiceCommandParser.parse(transcript, manager.getFactionNames(),
                candidateNames(manager, speaker), ledNames);
        if (order == null) {
            // Talking to the bots but no order we know: show what was heard
            // so a misrecognised word is easy to spot. Plain chatter stays
            // silent.
            if (VoiceCommandParser.mentionsBots(transcript, manager.getFactionNames(), ledNames)) {
                lastCommand.put(speaker.getUniqueId(), now);
                speaker.sendActionBar(Component.text("🎙 didn't catch an order in: \"" + transcript + "\"",
                        NamedTextColor.YELLOW));
            }
            return;
        }
        lastCommand.put(speaker.getUniqueId(), now);

        List<PvPBot> bots = selectBots(manager, speaker, led, order);
        String who = order.subjectBot() != null ? order.subjectBot()
                : order.subjectFaction() == null ? "everyone" : order.subjectFaction().toLowerCase();
        if (bots.isEmpty()) {
            fail(speaker, transcript, order.subjectFaction() != null
                    ? "you don't lead " + who : "none of your bots are alive");
            return;
        }
        Set<String> factions = factionsOf(manager, bots);
        // Any new order replaces a climb-to or bow order in progress; an
        // order to go somewhere or work also ends a patrol, which would
        // otherwise keep the bot on its route.
        boolean relocates = switch (order.intent()) {
            case COME, FOLLOW, WAIT, ADVANCE, MINE, DESTROY, FORMATION, PILLAR_TO, BOW -> true;
            default -> false;
        };
        for (PvPBot b : bots) {
            var ctx = b.getAI().getContext();
            ctx.reachController.stop();
            ctx.archerController.stop();
            if (relocates && ctx.patrolController.isActive()) ctx.patrolController.stop();
        }
        boolean movement = switch (order.intent()) {
            case COME, FOLLOW, WAIT, ADVANCE, MINE, DESTROY, FORMATION, BREAK_FORMATION -> true;
            default -> false;
        };
        if (movement) {
            // New job or position: drop the old one.
            for (PvPBot b : bots) b.getAI().getContext().excavationController.abort();
            if (order.intent() != Intent.FORMATION && order.subjectBot() == null) {
                for (String f : factions) manager.breakFactionFormation(f);
            }
        }

        switch (order.intent()) {
            case ATTACK -> {
                releaseStandDown(manager, factions, bots);
                cancelFollow(speaker.getUniqueId());
                attack(manager, speaker, transcript, order, bots, who);
            }
            case RUSH -> {
                releaseStandDown(manager, factions, bots);
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
                standDown(bots);
                ok(speaker, transcript, who + " → standing down for " + (STAND_DOWN_TICKS / 20)
                        + "s (" + bots.size() + ")");
            }
            case ENGAGE -> {
                releaseStandDown(manager, factions, bots);
                ok(speaker, transcript, who + " → weapons free (" + bots.size() + ")");
            }
            case COME -> {
                cancelFollow(speaker.getUniqueId());
                int moved = gatherAt(bots, speaker.getLocation());
                ok(speaker, transcript, who + " → coming to you (" + moved + ")");
            }
            case FOLLOW -> {
                startFollow(speaker, bots, false);
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
                releaseStandDown(manager, factions, bots);
                cancelFollow(speaker.getUniqueId());
                int moved = gatherAt(bots, aheadOf(speaker, ADVANCE_DISTANCE));
                ok(speaker, transcript, who + " → pushing forward (" + moved + ")");
            }
            case ALERT -> {
                releaseStandDown(manager, factions, bots);
                List<Player> threats = enemiesNear(manager, speaker, speaker.getLocation(), ALERT_RANGE);
                if (threats.isEmpty()) {
                    ok(speaker, transcript, who + " → on alert");
                } else {
                    int sent = spreadOver(bots, threats);
                    ok(speaker, transcript, who + " → engaging " + threats.size()
                            + " threat(s) near you (" + sent + ")");
                }
            }
            case PILLAR_TO, BOW -> {
                releaseStandDown(manager, factions, bots);
                cancelFollow(speaker.getUniqueId());
                Player victim = switch (order.targetKind()) {
                    case NAME -> resolveName(manager, order.target());
                    case LOOK -> lookedAt(speaker);
                    case NEAREST -> nearest(speaker.getLocation(),
                            enemiesNear(manager, speaker, speaker.getLocation(), NEAREST_RANGE),
                            speaker.getUniqueId());
                    default -> null;
                };
                if (victim == null) {
                    fail(speaker, transcript, order.targetKind() == VoiceCommandParser.TargetKind.NAME
                            ? order.target() + " isn't online"
                            : order.heardTarget() != null && !order.heardTarget().isBlank()
                            ? "no player sounds like \"" + order.heardTarget() + "\""
                            : "look at who you mean");
                    return;
                }
                if (victim.getUniqueId().equals(speaker.getUniqueId())) {
                    fail(speaker, transcript, "they won't turn on you");
                    return;
                }
                String vName = ChatColor.stripColor(victim.getName());
                if (order.intent() == Intent.PILLAR_TO) {
                    int sent = 0, given = 0;
                    for (PvPBot b : bots) {
                        if (b.getUUID().equals(victim.getUniqueId())) continue;
                        b.getAI().getContext().excavationController.abort();
                        if (b.getAI().getContext().reachController.start(victim)) given++;
                        sent++;
                    }
                    ok(speaker, transcript, who + " → pillaring up to " + vName + " (" + sent + ")"
                            + (given > 0 ? " - gave " + given + " cobblestone" : ""));
                } else {
                    int archers = 0, melee = 0;
                    for (PvPBot b : bots) {
                        if (b.getUUID().equals(victim.getUniqueId())) continue;
                        b.getAI().getContext().excavationController.abort();
                        Player bp = b.getBukkitPlayer();
                        if (bp != null && com.pvpbot.ai.ArcherController.hasBowAndArrows(bp)) {
                            b.getAI().getContext().archerController.start(victim);
                            archers++;
                        } else {
                            b.setForcedTarget(victim);
                            melee++;
                        }
                    }
                    ok(speaker, transcript, who + " → shooting " + vName + " (" + archers + " with bows"
                            + (melee > 0 ? ", " + melee + " without - melee" : "") + ")");
                }
            }
            case ARMOR_ON, ARMOR_OFF -> {
                boolean on = order.intent() == Intent.ARMOR_ON;
                int n = 0;
                for (PvPBot b : bots) {
                    if (on ? manager.equipArmorDelayed(b) : manager.removeArmorDelayed(b)) n++;
                }
                if (n == 0) {
                    fail(speaker, transcript, on ? "no armor to put on (or already doing it)"
                            : "nothing to take off (or already doing it)");
                } else {
                    ok(speaker, transcript, who + " → " + (on ? "putting their armor on" : "taking their armor off")
                            + " (" + n + ")");
                }
            }
            case FORMATION -> {
                cancelFollow(speaker.getUniqueId());
                if (order.subjectBot() != null) {
                    startFollow(speaker, bots, true);
                    ok(speaker, transcript, who + " → staying behind you");
                    return;
                }
                for (String f : factions) {
                    manager.setFactionFormation(f, FormationManager.Shape.GRID, FormationManager.DEFAULT_SPACING);
                }
                for (PvPBot b : bots) b.getAI().getContext().movementController.clearFormationOrder();
                ok(speaker, transcript, who + " → grid formation behind you (" + bots.size()
                        + ") - \"break formation\" to release");
            }
            case BREAK_FORMATION -> {
                cancelFollow(speaker.getUniqueId());
                ok(speaker, transcript, who + " → formation broken (" + bots.size() + ")");
            }
            case MINE, DESTROY -> {
                cancelFollow(speaker.getUniqueId());
                boolean destroy = order.intent() == Intent.DESTROY;
                Location center = workCenter(speaker);
                com.pvpbot.mine.ExcavationJob job = com.pvpbot.mine.ExcavationJob.start(
                        destroy ? com.pvpbot.mine.ExcavationJob.Mode.DESTROY : com.pvpbot.mine.ExcavationJob.Mode.MINE,
                        speaker.getUniqueId(), center);
                if (job.total() == 0 && job.blastPoints() == 0) {
                    job.cancel();
                    fail(speaker, transcript, "nothing there to " + (destroy ? "destroy" : "mine"));
                    return;
                }
                int tools = 0, tnt = 0;
                for (PvPBot b : bots) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().excavationController.join(job);
                    Player bp = b.getBukkitPlayer();
                    if (bp == null) continue;
                    if (com.pvpbot.ai.ExcavationController.hasTntKit(bp)) tnt++;
                    for (org.bukkit.inventory.ItemStack it : bp.getInventory().getStorageContents()) {
                        if (it == null) continue;
                        String n = it.getType().name();
                        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL")) { tools++; break; }
                    }
                }
                int size = (job.maxX - job.minX + 1);
                String extra = destroy
                        ? (tnt > 0 ? ", " + tnt + " with TNT" : ", no TNT - using tools")
                        : (tools < bots.size() ? ", " + (bots.size() - tools) + " without a pickaxe/shovel" : "");
                ok(speaker, transcript, who + " → " + (destroy ? "destroying" : "mining") + " a "
                        + size + "×" + size + " area, " + job.total() + " blocks (" + bots.size() + extra + ")");
            }
            case WORK_STOP -> {
                int stopped = 0;
                boolean destroying = false;
                for (PvPBot b : bots) {
                    var ec = b.getAI().getContext().excavationController;
                    if (ec.isActive()) {
                        if (ec.job() != null && ec.job().mode == com.pvpbot.mine.ExcavationJob.Mode.DESTROY) {
                            destroying = true;
                        }
                        ec.abort();
                        stopped++;
                    }
                }
                com.pvpbot.mine.ExcavationJob.stop(speaker.getUniqueId());
                ok(speaker, transcript, who + " → stopped " + (destroying ? "destroying" : "mining")
                        + " (" + stopped + ")");
            }
        }
    }

    // The block the speaker is looking at (within 48), else 8 blocks ahead.
    private static Location workCenter(Player speaker) {
        org.bukkit.block.Block b = speaker.getTargetBlockExact(48);
        if (b != null) return b.getLocation();
        Location ahead = aheadOf(speaker, 8.0);
        return ahead.subtract(0, 1, 0);
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

    // ---- stand-down: per bot and timed, so it works with or without a
    // faction and a "chill" never leaves bots passive forever.

    private static void standDown(List<PvPBot> bots) {
        for (PvPBot b : bots) {
            b.setForcedTarget(null);
            b.getAI().getContext().standDown(STAND_DOWN_TICKS);
        }
    }

    private static void releaseStandDown(BotManager manager, Set<String> factions, List<PvPBot> bots) {
        for (PvPBot b : bots) b.getAI().getContext().standDownTicks = 0;
        // Also lift a manual "/pvpbot faction stopattack" on the factions ordered to fight.
        for (String f : factions) {
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

    // behind = keep a grid slot behind the speaker until told otherwise
    // (used for one named bot; whole factions use the faction formation).
    private void startFollow(Player speaker, List<PvPBot> bots, boolean behind) {
        cancelFollow(speaker.getUniqueId());
        UUID id = speaker.getUniqueId();
        List<PvPBot> crew = new ArrayList<>(bots);
        final Location[] lastAnchor = {null};
        final int[] ticks = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            ticks[0] += 20;
            crew.removeIf(b -> !b.isAlive());
            if (p == null || !p.isOnline() || p.isDead() || crew.isEmpty()
                    || (!behind && ticks[0] > FOLLOW_TICKS)) {
                cancelFollow(id);
                return;
            }
            Location here = p.getLocation();
            if (lastAnchor[0] == null || lastAnchor[0].getWorld() != here.getWorld()
                    || lastAnchor[0].distanceSquared(here) > 2.5 * 2.5) {
                if (behind) {
                    Location anchor = here.clone();
                    anchor.setYaw(anchor.getYaw() + 180f);
                    List<Location> slots = FormationManager.compute(anchor, FormationManager.Shape.GRID,
                            crew.size(), FormationManager.DEFAULT_SPACING);
                    for (int i = 0; i < crew.size() && i < slots.size(); i++) {
                        crew.get(i).orderToFormationSlot(slots.get(i), 200);
                    }
                } else {
                    gatherAt(crew, here);
                }
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

    // Every live bot in a faction the speaker leads.
    private static List<PvPBot> ledBots(BotManager manager, Player speaker) {
        UUID me = speaker.getUniqueId();
        List<PvPBot> out = new ArrayList<>();
        for (Map.Entry<String, UUID> e : manager.getFactionLeaders().entrySet()) {
            if (!me.equals(e.getValue())) continue;
            for (PvPBot b : manager.getFactionBots(e.getKey())) {
                if (b.isAlive() && !b.getUUID().equals(me)) out.add(b);
            }
        }
        return out;
    }

    // Narrow the speaker's bots down to who was addressed.
    private static List<PvPBot> selectBots(BotManager manager, Player speaker, List<PvPBot> led, Parsed order) {
        if (order.subjectBot() != null) {
            for (PvPBot b : led) {
                if (ChatColor.stripColor(b.getName()).equals(order.subjectBot())) return List.of(b);
            }
            return List.of();
        }
        if (order.subjectFaction() != null) {
            if (!speaker.getUniqueId().equals(manager.getFactionLeader(order.subjectFaction()))) return List.of();
            List<PvPBot> out = new ArrayList<>();
            for (PvPBot b : manager.getFactionBots(order.subjectFaction())) if (b.isAlive()) out.add(b);
            return out;
        }
        return led;
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

    // Feedback goes to the action bar only - voice orders shouldn't flood chat.
    private static void ok(Player speaker, String heard, String what) {
        speaker.sendActionBar(Component.text("🎙 " + what, NamedTextColor.GREEN));
    }

    private static void fail(Player speaker, String heard, String why) {
        speaker.sendActionBar(Component.text("🎙 " + why, NamedTextColor.RED));
    }
}
