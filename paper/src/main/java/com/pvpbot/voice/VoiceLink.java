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
    private static final double HOLD_RADIUS = 5.0;
    private static final int FOLLOW_TICKS = 20 * 60;

    private final PvPBotPlugin plugin;
    private final Map<UUID, Long> lastCommand = new HashMap<>();

    // Running "follow me" / "look at me" orders. A bot given a new order is
    // taken out of whichever of these it was in; the rest carry on.
    private static final class Crewed {
        final List<PvPBot> crew;
        BukkitTask task;

        Crewed(List<PvPBot> crew) {
            this.crew = crew;
        }
    }

    private final Map<UUID, List<Crewed>> followTasks = new HashMap<>();
    private final List<Crewed> lookTasks = new ArrayList<>();

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
        words.add("@commander");
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
        java.util.Collection<String> buildNames = plugin.getVoiceBuilds() == null
                ? List.of() : plugin.getVoiceBuilds().all().keySet();
        Parsed order = VoiceCommandParser.parse(transcript, manager.getFactionNames(),
                candidateNames(manager, speaker), ledNames, buildNames);
        if (order == null) {
            // Talking to the bots but no order we know: show what was heard
            // so a misrecognised word is easy to spot. Plain chatter stays
            // silent.
            if (VoiceCommandParser.mentionsBots(transcript, manager.getFactionNames(), ledNames)) {
                lastCommand.put(speaker.getUniqueId(), now);
                feedback(speaker, Component.text("🎙 didn't catch an order in: \"" + transcript + "\"",
                        NamedTextColor.YELLOW));
            }
            return;
        }
        lastCommand.put(speaker.getUniqueId(), now);

        List<PvPBot> bots = selectBots(manager, speaker, led, order);
        String who = order.commander()
                ? (order.subjectFaction() == null ? "commander" : order.subjectFaction().toLowerCase() + " commander")
                : order.subjectBot() != null ? order.subjectBot()
                : order.subjectFaction() == null ? "everyone" : order.subjectFaction().toLowerCase();
        if (bots.isEmpty()) {
            fail(speaker, transcript, order.commander()
                    ? "no commander - /pvpbot faction commander <faction> <bot>"
                    : order.subjectFaction() != null
                    ? "you don't lead " + who : "none of your bots are alive");
            return;
        }
        Set<String> factions = factionsOf(manager, bots);

        // What the bots were busy with, for "stop mining" feedback, before
        // the new order wipes it.
        int wasDigging = 0;
        boolean wasDestroying = false;
        boolean wasBridging = false;
        for (PvPBot b : bots) {
            if (b.getAI().getContext().islandBridgeController.isActive()) wasBridging = true;
            var ec = b.getAI().getContext().excavationController;
            if (ec.isActive()) {
                wasDigging++;
                if (ec.job() != null && ec.job().mode == com.pvpbot.mine.ExcavationJob.Mode.DESTROY) {
                    wasDestroying = true;
                }
            }
        }

        // A new order means: drop whatever the last one had you doing and do
        // this instead. Only orders that sit on top of anything (armor,
        // "look at me", "weapons free") leave the current task running.
        boolean interrupts = switch (order.intent()) {
            case ARMOR_ON, ARMOR_OFF, ARMOR_BEST, ARMOR_WORST, ENGAGE -> false;
            default -> true;
        };
        if (interrupts) {
            for (PvPBot b : bots) interrupt(b);
            if (order.intent() != Intent.PATH && order.intent() != Intent.TAKE_COVER) {
                dropOrphanedVoiceBuild(speaker.getUniqueId(), manager);
            }
        }
        boolean movement = switch (order.intent()) {
            case COME, FOLLOW, WAIT, ADVANCE, MINE, DESTROY, TUNNEL, BUILD_UP, PATH, FORMATION, BREAK_FORMATION,
                 MINE_TO, SCATTER, ISLAND_BRIDGE, TAKE_COVER -> true;
            default -> false;
        };
        if (movement && order.intent() != Intent.FORMATION && order.subjectBot() == null && !order.commander()) {
            for (String f : factions) manager.breakFactionFormation(f);
        }

        switch (order.intent()) {
            case ATTACK -> {
                releaseStandDown(manager, factions, bots);
                attack(manager, speaker, transcript, order, bots, who);
            }
            case RUSH -> {
                releaseStandDown(manager, factions, bots);
                List<Player> enemies = enemiesNear(manager, speaker, speaker.getLocation(), RUSH_RANGE);
                if (enemies.isEmpty()) {
                    fail(speaker, transcript, "no enemies nearby");
                    return;
                }
                if (order.commander()) {
                    for (PvPBot b : bots) {
                        Player bp = b.getBukkitPlayer();
                        Player victim = bp == null ? null : nearest(bp.getLocation(), enemies, b.getUUID());
                        if (victim != null) b.getAI().getContext().commanderController.start(speaker, victim);
                    }
                    ok(speaker, transcript, who + " → reporting to you, then rushing in");
                    return;
                }
                int sent = spreadOver(bots, enemies);
                ok(speaker, transcript, who + " → rushing " + enemies.size() + " enemy(s) (" + sent + ")");
            }
            case STAND_DOWN -> {
                standDown(bots);
                ok(speaker, transcript, who + " → weapons down until you order an attack (" + bots.size() + ")");
            }
            case ENGAGE -> {
                releaseStandDown(manager, factions, bots);
                ok(speaker, transcript, who + " → weapons free (" + bots.size() + ")");
            }
            case COME -> {
                int moved = gatherAt(bots, speaker.getLocation());
                ok(speaker, transcript, who + " → coming to you (" + moved + ")");
            }
            case FOLLOW -> {
                startFollow(speaker, bots, false);
                ok(speaker, transcript, who + " → following you (" + bots.size() + ")");
            }
            case WAIT -> {
                // A post right where each bot stands: it stays there (fights
                // anyone who comes close, then walks back) until the next
                // order moves it.
                int held = 0;
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp == null) continue;
                    b.setGuardPost(bp.getLocation(), HOLD_RADIUS, bp.getLocation().getYaw());
                    b.getAI().getContext().voiceHold = true;
                    held++;
                }
                ok(speaker, transcript, who + " → staying here until you say otherwise (" + held + ")");
            }
            case ADVANCE -> {
                releaseStandDown(manager, factions, bots);
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
            case TAKE_COVER -> {
                CoverRun old = coverRuns.remove(speaker.getUniqueId());
                if (old != null) old.end();
                stopVoiceBuild(speaker.getUniqueId(), manager);
                CoverRun run = new CoverRun(speaker, bots, who);
                String result = run.start(manager);
                if (result == null) {
                    fail(speaker, transcript, "nobody close enough to take cover with you");
                    return;
                }
                ok(speaker, transcript, who + " → " + result);
            }
            case PATH -> {
                org.bukkit.block.Block aim = speaker.getTargetBlockExact(160);
                if (aim == null) {
                    fail(speaker, transcript, "look at the block the path should go to");
                    return;
                }
                List<com.pvpbot.schem.BuildJob.Placement> blocks =
                        com.pvpbot.schem.PathPlan.plan(speaker.getLocation(), aim.getLocation());
                if (blocks.isEmpty()) {
                    fail(speaker, transcript, "nothing to build - it's too close or already solid");
                    return;
                }
                stopVoiceBuild(speaker.getUniqueId(), manager);
                String key = VOICE_BUILD_PREFIX + speaker.getUniqueId();
                com.pvpbot.schem.BuildJob job = com.pvpbot.schem.BuildJob.fromPlacements(
                        "path", speaker.getWorld(), blocks,
                        speaker.getLocation().getBlockX(), speaker.getLocation().getBlockY() - 1,
                        speaker.getLocation().getBlockZ(), who);
                job.setScaffoldMaterial(com.pvpbot.ai.InventoryController.BUILD_BLOCK);
                List<Player> crewPlayers = new ArrayList<>();
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp != null) crewPlayers.add(bp);
                }
                job.distribute(crewPlayers, job.bill());
                plugin.getBuildJobs().put(key, job);
                for (PvPBot b : bots) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().buildController.assign(job);
                }
                double dist = speaker.getLocation().distance(aim.getLocation());
                ok(speaker, transcript, who + " → building a covered path "
                        + (int) Math.min(dist, com.pvpbot.schem.PathPlan.MAX_LENGTH) + " blocks long ("
                        + job.total() + " blocks, " + bots.size() + " builders)");
            }
            case TUNNEL -> {
                float yaw = speaker.getLocation().getYaw();
                int dirX = 0, dirZ = 0;
                // Snap to the nearest compass direction: straight tunnels.
                int q = Math.round(((yaw % 360) + 360) % 360 / 90f) % 4;
                switch (q) {
                    case 0 -> dirZ = 1;   // south
                    case 1 -> dirX = -1;  // west
                    case 2 -> dirZ = -1;  // north
                    default -> dirX = 1;  // east
                }
                List<UUID> crew = new ArrayList<>();
                for (PvPBot b : bots) crew.add(b.getUUID());
                com.pvpbot.mine.ExcavationJob job = com.pvpbot.mine.ExcavationJob.startTunnel(
                        speaker.getUniqueId(), speaker.getLocation(), dirX, dirZ, crew, TUNNEL_LENGTH);
                for (PvPBot b : bots) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().excavationController.join(job);
                }
                String[] names = {"south", "west", "north", "east"};
                ok(speaker, transcript, who + " → tunnelling " + names[q] + " (" + bots.size()
                        + " tunnels, " + TUNNEL_LENGTH + " long)");
            }
            case BUILD_UP -> {
                // Give every bot its own column (its current one if nobody
                // else has it, else the nearest free one) so no two of them
                // try to place on the same block.
                java.util.Set<Long> taken = new java.util.HashSet<>();
                int started = 0, given = 0;
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp == null) continue;
                    int[] col = freeColumnNear(bp.getLocation(), taken);
                    if (col == null) continue;
                    taken.add(columnKey(col[0], col[1]));
                    b.setForcedTarget(null);
                    b.getAI().getContext().excavationController.abort();
                    if (b.getAI().getContext().reachController.startTower(col[0], col[1], BUILD_UP_HEIGHT)) given++;
                    started++;
                }
                ok(speaker, transcript, who + " → building up " + BUILD_UP_HEIGHT + " blocks (" + started + ")"
                        + (given > 0 ? " - gave " + given + " cobblestone" : ""));
            }
            case LOOK_AT_ME -> {
                // Anyone within 5 blocks of the speaker (height ignored)
                // backs off to ~6 blocks first, so they're not in your face;
                // then everyone holds their spot and watches you until the
                // next order.
                UUID id = speaker.getUniqueId();
                Location sp = speaker.getLocation();
                java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
                int backing = 0;
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp == null || bp.getWorld() != sp.getWorld()) continue;
                    double dx = bp.getLocation().getX() - sp.getX(), dz = bp.getLocation().getZ() - sp.getZ();
                    double flat = Math.hypot(dx, dz);
                    if (flat >= LOOK_CLEARANCE) continue;
                    if (flat < 0.3) {
                        double a = rnd.nextDouble() * Math.PI * 2;
                        dx = Math.cos(a);
                        dz = Math.sin(a);
                        flat = 1.0;
                    }
                    double out = LOOK_CLEARANCE + 1.0 + rnd.nextDouble();
                    int x = (int) Math.floor(sp.getX() + dx / flat * out);
                    int z = (int) Math.floor(sp.getZ() + dz / flat * out);
                    int y = groundNear(sp.getWorld(), x, z, bp.getLocation().getBlockY());
                    Location dest = new Location(sp.getWorld(), x + 0.5, y, z + 0.5);
                    dest.setYaw((float) Math.toDegrees(Math.atan2(-(sp.getX() - dest.getX()), sp.getZ() - dest.getZ())));
                    b.orderToFormationSlot(dest, 200);
                    backing++;
                }
                List<PvPBot> crew = new ArrayList<>(bots);
                Crewed look = new Crewed(crew);
                lookTasks.add(look);
                final int[] left = {LOOK_TICKS};
                look.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null || !p.isOnline() || --left[0] <= 0 || crew.isEmpty()) {
                        look.task.cancel();
                        lookTasks.remove(look);
                        return;
                    }
                    Location eye = p.getEyeLocation();
                    for (PvPBot b : crew) {
                        Player bp = b.getBukkitPlayer();
                        if (bp == null || !b.isAlive() || bp.getWorld() != eye.getWorld()) continue;
                        var ctx = b.getAI().getContext();
                        if (ctx.formationSlot != null) continue; // still backing off
                        if (ctx.guardAnchor == null) {
                            // Arrived (or couldn't go further): stay right here.
                            b.setGuardPost(bp.getLocation(), HOLD_RADIUS, bp.getLocation().getYaw());
                            ctx.voiceHold = true;
                        }
                        if (ctx.target != null) continue; // fighting beats looking
                        Location be = bp.getEyeLocation();
                        double dx = eye.getX() - be.getX(), dy = eye.getY() - be.getY(), dz = eye.getZ() - be.getZ();
                        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
                        // Above idle glances and walking looks (they'd pull the
                        // head away every second); fights skip this anyway.
                        ctx.requestLook(yaw, pitch, com.pvpbot.ai.BotAIContext.LOOK_CRITICAL - 1, false);
                    }
                }, 0L, 1L);
                ok(speaker, transcript, who + " → looking at you and staying put (" + bots.size() + ")"
                        + (backing > 0 ? " - " + backing + " backing off first" : ""));
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
            case ARMOR_BEST, ARMOR_WORST -> {
                boolean best = order.intent() == Intent.ARMOR_BEST;
                int n = 0;
                for (PvPBot b : bots) {
                    if (manager.equipRankedArmorDelayed(b, best)) n++;
                }
                if (n == 0) {
                    fail(speaker, transcript, best ? "already wearing their best armor"
                            : "already wearing their worst armor");
                } else {
                    ok(speaker, transcript, who + " → putting their " + (best ? "best" : "worst")
                            + " armor on (" + n + ")");
                }
            }
            case FORMATION -> {
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
                ok(speaker, transcript, who + " → formation broken (" + bots.size() + ")");
            }
            case MINE, DESTROY -> {
                boolean destroy = order.intent() == Intent.DESTROY;
                Location center = workCenter(speaker);
                // Mining: about a third of the crew (at least one when there
                // are two or more) heads off on wandering tunnels of their
                // own; the pit grows with the bots left to dig it.
                List<PvPBot> explorers = new ArrayList<>();
                if (!destroy && bots.size() >= 2) {
                    List<PvPBot> shuffled = new ArrayList<>(bots);
                    java.util.Collections.shuffle(shuffled);
                    int k = Math.max(1, (int) Math.round(bots.size() * 0.35));
                    explorers.addAll(shuffled.subList(0, Math.min(k, shuffled.size() - 1)));
                }
                int pitCrew = bots.size() - explorers.size();
                com.pvpbot.mine.ExcavationJob job = destroy
                        ? com.pvpbot.mine.ExcavationJob.start(com.pvpbot.mine.ExcavationJob.Mode.DESTROY,
                        speaker.getUniqueId(), center)
                        : com.pvpbot.mine.ExcavationJob.start(com.pvpbot.mine.ExcavationJob.Mode.MINE,
                        speaker.getUniqueId(), center, Math.max(5, Math.min(9, 3 + pitCrew)), 2, 4,
                        20L * 60L * 1000L);
                if (job.total() == 0 && job.blastPoints() == 0) {
                    job.cancel();
                    fail(speaker, transcript, "nothing there to " + (destroy ? "destroy" : "mine"));
                    return;
                }
                int size = (job.maxX - job.minX + 1);
                int areaBlocks = job.total();
                int tools = 0, tnt = 0;
                for (PvPBot b : bots) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().excavationController.join(job);
                    Player bp = b.getBukkitPlayer();
                    if (bp != null && explorers.contains(b)) job.addProspectorLane(b.getUUID(), bp.getLocation(), true);
                    if (bp == null) continue;
                    if (com.pvpbot.ai.ExcavationController.hasTntKit(bp)) tnt++;
                    for (org.bukkit.inventory.ItemStack it : bp.getInventory().getStorageContents()) {
                        if (it == null) continue;
                        String n = it.getType().name();
                        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL")) { tools++; break; }
                    }
                }
                String extra = destroy
                        ? (tnt > 0 ? ", " + tnt + " with TNT" : ", no TNT - using tools")
                        : (tools < bots.size() ? ", " + (bots.size() - tools) + " without a pickaxe/shovel" : "");
                ok(speaker, transcript, who + " → " + (destroy ? "destroying" : "mining") + " a "
                        + size + "×" + size + " area, " + areaBlocks + " blocks (" + bots.size() + extra + ")"
                        + (explorers.isEmpty() ? "" : " - " + explorers.size() + " tunnelling off on their own"));
            }
            case MINE_TO -> {
                Player victim = switch (order.targetKind()) {
                    case SELF -> speaker;
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
                            : "say who to mine down to");
                    return;
                }
                String vName = ChatColor.stripColor(victim.getName());
                Location vLoc = victim.getLocation();
                List<UUID> crew = new ArrayList<>();
                List<Location> starts = new ArrayList<>();
                List<PvPBot> diggers = new ArrayList<>();
                int deepest = 0;
                for (PvPBot b : bots) {
                    Player bp = b.getBukkitPlayer();
                    if (bp == null || b.getUUID().equals(victim.getUniqueId())) continue;
                    if (bp.getWorld() != vLoc.getWorld()) continue;
                    int drop = bp.getLocation().getBlockY() - vLoc.getBlockY();
                    if (drop < -1) continue; // they're above - digging won't get there
                    if (Math.hypot(bp.getLocation().getX() - vLoc.getX(), bp.getLocation().getZ() - vLoc.getZ())
                            > com.pvpbot.mine.ExcavationJob.DIG_TO_MAX_ACROSS) continue;
                    crew.add(b.getUUID());
                    starts.add(bp.getLocation());
                    diggers.add(b);
                    deepest = Math.max(deepest, drop);
                }
                if (diggers.isEmpty()) {
                    fail(speaker, transcript, vName + " is above them (or too far away) - try pillar to");
                    return;
                }
                com.pvpbot.mine.ExcavationJob job = com.pvpbot.mine.ExcavationJob.startDigTo(
                        speaker.getUniqueId(), crew, starts, vLoc, victim.getUniqueId());
                if (job.total() == 0) {
                    job.cancel();
                    fail(speaker, transcript, "nothing to dig through to " + vName);
                    return;
                }
                for (PvPBot b : diggers) b.getAI().getContext().excavationController.join(job);
                ok(speaker, transcript, who + " → digging to " + vName + " (" + diggers.size()
                        + " bot" + (diggers.size() == 1 ? "" : "s")
                        + (deepest > 0 ? ", " + deepest + " down" : "") + " - they follow if " + vName + " moves)");
            }
            case ISLAND_BRIDGE -> {
                if (speaker.getWorld().getEnvironment() != World.Environment.THE_END) {
                    fail(speaker, transcript, "island bridging only works in the End");
                    return;
                }
                com.pvpbot.schem.IslandBridge.Plan plan =
                        com.pvpbot.schem.IslandBridge.plan(speaker.getLocation());
                if (plan == null) {
                    fail(speaker, transcript, "no other island within "
                            + com.pvpbot.schem.IslandBridge.SEARCH_RADIUS + " blocks");
                    return;
                }
                int needed = plan.blocksToPlace(speaker.getWorld());
                if (needed == 0) {
                    fail(speaker, transcript, "that island is already connected");
                    return;
                }
                com.pvpbot.ai.IslandBridgeController.Job job = com.pvpbot.ai.IslandBridgeController.start(
                        speaker.getUniqueId(), speaker.getWorld(), plan);
                int each = needed / Math.max(1, bots.size()) + 16;
                // Nearest bot to the bridge start leads.
                List<PvPBot> crewOrder = new ArrayList<>(bots);
                Location start = plan.from();
                crewOrder.sort(java.util.Comparator.comparingDouble(b -> {
                    Player bp = b.getBukkitPlayer();
                    return bp == null || bp.getWorld() != start.getWorld()
                            ? Double.MAX_VALUE : bp.getLocation().distanceSquared(start);
                }));
                for (PvPBot b : crewOrder) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().islandBridgeController.join(job, each);
                }
                ok(speaker, transcript, who + " → bridging to the next island, " + job.length()
                        + " blocks out (" + needed + " end stone, " + bots.size() + " builders)");
            }
            case BUILD_SCHEM -> {
                if (order.targetKind() != VoiceCommandParser.TargetKind.NAME) {
                    fail(speaker, transcript, "no build called \"" + order.heardTarget()
                            + "\" - /pvpbot schematic mark <schematic> <name>");
                    return;
                }
                String schemName = plugin.getVoiceBuilds().schematicFor(order.target());
                var schem = schemName == null || plugin.getSchematicManager() == null
                        ? null : plugin.getSchematicManager().get(schemName);
                if (schem == null) {
                    fail(speaker, transcript, "can't load the schematic for \"" + order.target() + "\"");
                    return;
                }
                if (com.pvpbot.schem.SchematicPreview.tooBig(schem)) {
                    fail(speaker, transcript, order.target() + " is too big to build");
                    return;
                }
                stopVoiceBuild(speaker.getUniqueId(), manager);
                BuildRun old = buildRuns.remove(speaker.getUniqueId());
                if (old != null) old.end(false);
                int[] o = com.pvpbot.schem.SchematicPreview.originFor(speaker, schem);
                BuildRun run = new BuildRun(speaker.getUniqueId(), schem, speaker.getWorld(), o[0], o[1], o[2],
                        new ArrayList<>(bots), who, order.target());
                buildRuns.put(speaker.getUniqueId(), run);
                int toClear = run.start();
                ok(speaker, transcript, who + " → building a " + order.target() + " here ("
                        + com.pvpbot.schem.SchematicPreview.describeSize(schem) + ", " + bots.size() + " builders"
                        + (toClear > 0 ? ", clearing " + toClear + " blocks first)" : ")"));
            }
            case SCATTER -> {
                int n = scatter(speaker, bots);
                ok(speaker, transcript, who + " → scattering (" + n + ")");
            }
            case WORK_STOP -> {
                // The bots were already pulled off their digs above; a job
                // with nobody left on it ends by itself, so other areas the
                // speaker has other bots working on keep going.
                int stopped = wasDigging;
                boolean destroying = wasDestroying;
                boolean building = stopVoiceBuild(speaker.getUniqueId(), manager)
                        | com.pvpbot.ai.IslandBridgeController.stop(speaker.getUniqueId()) | wasBridging;
                if (building && stopped == 0) {
                    ok(speaker, transcript, who + " → stopped building");
                    return;
                }
                ok(speaker, transcript, who + " → stopped " + (destroying ? "destroying" : "mining")
                        + " (" + stopped + ")");
            }
        }
    }

    private static final String VOICE_BUILD_PREFIX = "voice-path-";

    // Cancel this speaker's voice-ordered path build: bots leave it, its
    // scaffold is cleared. Blocks already placed stay.
    private boolean stopVoiceBuild(UUID speaker, BotManager manager) {
        com.pvpbot.schem.BuildJob old = plugin.getBuildJobs().remove(VOICE_BUILD_PREFIX + speaker);
        if (old == null) return false;
        old.cancel();
        for (PvPBot b : manager.getBots().values()) {
            var bc = b.getAI().getContext().buildController;
            if (bc.currentJob() == old) bc.abort();
        }
        old.removeScaffoldNow((UUID) null);
        return true;
    }

    // A voice path nobody is building any more (every builder got a new
    // order) is called off so its scaffold doesn't sit there.
    private void dropOrphanedVoiceBuild(UUID speaker, BotManager manager) {
        com.pvpbot.schem.BuildJob job = plugin.getBuildJobs().get(VOICE_BUILD_PREFIX + speaker);
        if (job == null) return;
        for (PvPBot b : manager.getBots().values()) {
            if (b.getAI().getContext().buildController.currentJob() == job) return;
        }
        stopVoiceBuild(speaker, manager);
    }

    // Clean slate for a new order: every task, walk, hold, follow, look,
    // commander run and fight the bot had from before is dropped.
    private void interrupt(PvPBot b) {
        var ctx = b.getAI().getContext();
        ctx.reachController.stop();
        ctx.archerController.stop();
        ctx.commanderController.stop();
        ctx.climbOutController.stop();
        ctx.islandBridgeController.abort();
        ctx.excavationController.abort();
        if (ctx.patrolController.isActive()) ctx.patrolController.stop();
        if (ctx.areaMiningController.isActive()) ctx.areaMiningController.abort();
        if (ctx.miningController.isActive()) ctx.miningController.abort();
        if (ctx.farmController.isActive()) ctx.farmController.abort();
        if (ctx.deliveryController.isActive()) ctx.deliveryController.abort();
        if (ctx.golemFightController.isActive()) ctx.golemFightController.stop();
        if (ctx.tunnelController.isActive()) ctx.tunnelController.abort();
        if (ctx.buildController.isBusy() || ctx.buildController.currentJob() != null) ctx.buildController.abort();
        ctx.movementController.clearFormationOrder();
        if (ctx.voiceHold) b.clearGuardPost();
        for (List<Crewed> list : followTasks.values()) removeFrom(list, b);
        removeFrom(lookTasks, b);
        for (BuildRun run : buildRuns.values()) run.crew.remove(b);
        for (CoverRun run : coverRuns.values()) run.forget(b);
        b.setForcedTarget(null);
        ctx.target = null;
    }

    private static void removeFrom(List<Crewed> list, PvPBot b) {
        for (java.util.Iterator<Crewed> it = list.iterator(); it.hasNext(); ) {
            Crewed c = it.next();
            if (c.crew.remove(b) && c.crew.isEmpty()) {
                if (c.task != null) c.task.cancel();
                it.remove();
            }
        }
    }

    // ---- "everyone take cover"

    private final Map<UUID, CoverRun> coverRuns = new HashMap<>();

    // Bots this close to the speaker build the roof; the rest, out to
    // COVER_RANGE, sprint over to stand under it. Everyone ends up holding
    // a spot under the roof until the next order.
    private static final double COVER_BUILDER_RANGE = 14.0;
    private static final double COVER_RANGE = 50.0;
    private static final int COVER_MIN_BUILDERS = 2;
    private static final int COVER_RUN_TICKS = 20 * 40;
    private static final int COVER_TIMEOUT_TICKS = 20 * 60 * 4;

    private final class CoverRun {
        final UUID speaker;
        final String who;
        final List<PvPBot> candidates;
        final Map<PvPBot, Location> spotOf = new HashMap<>();
        final java.util.Set<PvPBot> builders = new java.util.HashSet<>();
        final java.util.Set<PvPBot> runners = new java.util.HashSet<>();
        final java.util.Set<PvPBot> posted = new java.util.HashSet<>();
        com.pvpbot.schem.BuildJob job;
        BukkitTask task;
        int ticks;

        CoverRun(Player speaker, List<PvPBot> bots, String who) {
            this.speaker = speaker.getUniqueId();
            this.who = who;
            this.candidates = new ArrayList<>(bots);
        }

        // Plans the roof, splits builders and runners, hands out spots.
        // Returns the feedback line, or null when no bot is in range.
        String start(BotManager manager) {
            Player sp = Bukkit.getPlayer(speaker);
            if (sp == null) return null;
            Location center = sp.getLocation();

            List<PvPBot> inRange = new ArrayList<>();
            for (PvPBot b : candidates) {
                Player bp = b.getBukkitPlayer();
                if (bp == null || bp.getWorld() != center.getWorld()) continue;
                if (flatDistance(bp.getLocation(), center) <= COVER_RANGE) inRange.add(b);
            }
            if (inRange.isEmpty()) return null;
            inRange.sort(java.util.Comparator.comparingDouble(
                    b -> flatDistance(b.getBukkitPlayer().getLocation(), center)));

            for (PvPBot b : inRange) {
                double d = flatDistance(b.getBukkitPlayer().getLocation(), center);
                if (d <= COVER_BUILDER_RANGE || builders.size() < COVER_MIN_BUILDERS) builders.add(b);
                else runners.add(b);
            }

            com.pvpbot.schem.ShelterPlan.Plan plan =
                    com.pvpbot.schem.ShelterPlan.plan(center, inRange.size() + 1);
            List<Location> spots = plan.spots();
            for (int i = 0; i < inRange.size(); i++) {
                Location spot = spots.isEmpty() ? center.clone()
                        : spots.get(Math.min(spots.size() - 1, i + 1)).clone(); // spot 0 is the speaker's
                spotOf.put(inRange.get(i), spot);
            }

            if (!plan.blocks().isEmpty()) {
                job = com.pvpbot.schem.BuildJob.fromPlacements("shelter", center.getWorld(), plan.blocks(),
                        center.getBlockX(), center.getBlockY() - 1, center.getBlockZ(), who);
                job.setScaffoldMaterial(com.pvpbot.ai.InventoryController.BUILD_BLOCK);
                List<Player> crewPlayers = new ArrayList<>();
                for (PvPBot b : builders) {
                    Player bp = b.getBukkitPlayer();
                    if (bp != null) crewPlayers.add(bp);
                }
                job.distribute(crewPlayers, job.bill());
                plugin.getBuildJobs().put(VOICE_BUILD_PREFIX + speaker, job);
                for (PvPBot b : builders) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().buildController.assign(job);
                }
            } else {
                // Already under a roof: everyone just gets in under it.
                runners.addAll(builders);
                builders.clear();
            }
            for (PvPBot b : runners) {
                b.setForcedTarget(null);
                b.orderRunTo(spotOf.get(b), COVER_RUN_TICKS);
            }

            coverRuns.put(speaker, this);
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);

            int side = plan.half() * 2 + 1;
            if (job == null) {
                return "already under cover - everyone in close (" + runners.size() + ")";
            }
            return "taking cover: " + builders.size() + " building a " + side + "x" + side + " roof ("
                    + job.total() + " blocks)" + (runners.isEmpty() ? "" : ", " + runners.size() + " running in");
        }

        void tick() {
            ticks += 10;
            for (PvPBot b : new ArrayList<>(spotOf.keySet())) {
                if (!b.isAlive()) {
                    forget(b);
                    continue;
                }
                if (posted.contains(b)) continue;
                var ctx = b.getAI().getContext();
                boolean ready;
                if (builders.contains(b)) {
                    // Done with (or dropped from) the roof.
                    ready = job == null || ctx.buildController.currentJob() != job;
                } else {
                    // Run order over (arrived, or ran out of time).
                    ready = ctx.formationSlot == null;
                }
                if (!ready) continue;
                Location spot = spotOf.get(b);
                b.setGuardPost(spot, 2.0, spot.getYaw());
                ctx.voiceHold = true;
                posted.add(b);
            }
            boolean jobOver = job == null || job.isFinished() || job.isCancelled();
            if (spotOf.isEmpty() || (jobOver && posted.size() >= spotOf.size())
                    || ticks >= COVER_TIMEOUT_TICKS) {
                Player sp = Bukkit.getPlayer(speaker);
                if (sp != null && job != null && job.isFinished()) {
                    feedback(sp, Component.text("🎙 " + who + " → roof's up, everyone's under it",
                            NamedTextColor.GREEN));
                }
                end();
            }
        }

        void forget(PvPBot b) {
            spotOf.remove(b);
            builders.remove(b);
            runners.remove(b);
            posted.remove(b);
        }

        void end() {
            if (task != null) task.cancel();
            coverRuns.remove(speaker, this);
        }
    }

    private static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- voice builds: "build me a <name> here"

    private final Map<UUID, BuildRun> buildRuns = new HashMap<>();

    // One "build me a ..." order: clear the schematic's box of everything
    // that isn't already right, then build it in segments.
    private final class BuildRun {
        final UUID speaker;
        final com.pvpbot.schem.Schematic schem;
        final World world;
        final int ox, oy, oz;
        final List<PvPBot> crew;
        final String who, name;
        com.pvpbot.mine.ExcavationJob clear;
        com.pvpbot.schem.BuildJob build;
        BukkitTask task;

        BuildRun(UUID speaker, com.pvpbot.schem.Schematic schem, World world, int ox, int oy, int oz,
                 List<PvPBot> crew, String who, String name) {
            this.speaker = speaker;
            this.schem = schem;
            this.world = world;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.crew = crew;
            this.who = who;
            this.name = name;
        }

        // Returns how many blocks have to be cleared first.
        int start() {
            clear = com.pvpbot.mine.ExcavationJob.startClear(speaker, world, ox, oy, oz,
                    ox + schem.width - 1, oy + schem.height - 1, oz + schem.length - 1,
                    b -> {
                        org.bukkit.block.data.BlockData want = schem.at(b.getX() - ox, b.getY() - oy, b.getZ() - oz);
                        return want != null && !want.getMaterial().isAir() && b.getBlockData().matches(want);
                    });
            int n = clear.total();
            if (n == 0) {
                clear.cancel();
                clear = null;
                startBuilding();
            } else {
                for (PvPBot b : crew) {
                    b.setForcedTarget(null);
                    b.getAI().getContext().excavationController.join(clear);
                }
            }
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            return n;
        }

        void tick() {
            crew.removeIf(b -> !b.isAlive());
            if (crew.isEmpty()) {
                end(false);
                return;
            }
            if (build == null) {
                if (clear == null || clear.isFinished()) startBuilding();
                return;
            }
            if (build.isCancelled()) {
                end(false);
                return;
            }
            if (build.isFinished()) end(true);
        }

        void startBuilding() {
            if (clear != null) {
                for (PvPBot b : crew) {
                    var ec = b.getAI().getContext().excavationController;
                    if (ec.job() == clear) ec.abort();
                }
                clear.cancel();
            }
            build = new com.pvpbot.schem.BuildJob(schem, world, ox, oy, oz, who);
            build.enableSegments(4, true);
            plugin.getBuildJobs().put(VOICE_BUILD_PREFIX + speaker, build);
            for (PvPBot b : crew) {
                Player bp = b.getBukkitPlayer();
                if (bp != null) {
                    build.emptyMainHand(bp);
                    b.broadcastEquipment();
                }
                b.getAI().getContext().buildController.assign(build);
            }
            Player sp = Bukkit.getPlayer(speaker);
            if (sp != null) {
                feedback(sp, Component.text("🎙 " + who + " → room cleared, building the " + name
                        + " (" + build.total() + " blocks)", NamedTextColor.GREEN));
            }
        }

        void end(boolean finished) {
            if (task != null) task.cancel();
            buildRuns.remove(speaker, this);
            if (clear != null && !clear.isFinished()) clear.cancel();
            Player sp = Bukkit.getPlayer(speaker);
            if (finished && sp != null) {
                feedback(sp, Component.text("🎙 " + who + " → the " + name + " is done", NamedTextColor.GREEN));
            }
        }
    }

    private static final int TUNNEL_LENGTH = 32;
    private static final int BUILD_UP_HEIGHT = 10;
    // "look at me" lasts until the next order (capped at 30 minutes).
    private static final int LOOK_TICKS = 20 * 60 * 30;
    private static final double LOOK_CLEARANCE = 5.0;

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    // Nearest column to `at` with solid ground and head room that no other
    // bot in this order has taken (searched in growing rings).
    private static int[] freeColumnNear(Location at, java.util.Set<Long> taken) {
        World w = at.getWorld();
        int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        for (int r = 0; r <= 5; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = bx + dx, z = bz + dz;
                    if (taken.contains(columnKey(x, z))) continue;
                    // Keep a one-block gap between towers so nobody bumps.
                    boolean crowded = false;
                    for (int ax = -1; ax <= 1 && !crowded; ax++) {
                        for (int az = -1; az <= 1; az++) {
                            if ((ax != 0 || az != 0) && taken.contains(columnKey(x + ax, z + az))) {
                                crowded = true;
                                break;
                            }
                        }
                    }
                    if (crowded) continue;
                    for (int y = by + 1; y >= by - 2; y--) {
                        if (w.getBlockAt(x, y - 1, z).getType().isSolid()
                                && !w.getBlockAt(x, y, z).getType().isSolid()
                                && !w.getBlockAt(x, y + 1, z).getType().isSolid()
                                && !w.getBlockAt(x, y + 2, z).getType().isSolid()) {
                            return new int[]{x, z};
                        }
                    }
                }
            }
        }
        return null;
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
                if (order.commander()) {
                    for (PvPBot b : bots) {
                        Player bp = b.getBukkitPlayer();
                        Player victim = bp == null ? null : nearest(bp.getLocation(), enemies, b.getUUID());
                        if (victim != null) b.getAI().getContext().commanderController.start(speaker, victim);
                    }
                    ok(speaker, transcript, who + " → reporting to you, then attacking "
                            + order.target().toLowerCase());
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
                // Ordering a hit on one of your own faction's bots kicks it out
                // of the faction first, so it stops counting as a friend (the
                // rest will actually fight it and it fights back).
                String kickedFrom = null;
                PvPBot victimBot = manager.getBots().get(victim.getUniqueId());
                if (victimBot != null) {
                    String vf = manager.getPlayerFaction(victim.getUniqueId());
                    if (vf != null && speaker.getUniqueId().equals(manager.getFactionLeader(vf))) {
                        var vctx = victimBot.getAI().getContext();
                        vctx.reachController.stop();
                        vctx.archerController.stop();
                        vctx.excavationController.abort();
                        vctx.movementController.clearFormationOrder();
                        victimBot.setForcedTarget(null);
                        manager.removeFromAllFactions(victim.getUniqueId());
                        manager.saveFactionsToConfig();
                        kickedFrom = vf;
                    }
                }
                if (order.commander()) {
                    int sent = 0;
                    for (PvPBot b : bots) {
                        if (b.getUUID().equals(victim.getUniqueId())) continue;
                        b.getAI().getContext().commanderController.start(speaker, victim);
                        sent++;
                    }
                    if (sent == 0) {
                        fail(speaker, transcript, "the commander can't attack itself");
                        return;
                    }
                    ok(speaker, transcript, who + " → reporting to you, then going for "
                            + ChatColor.stripColor(victim.getName())
                            + (kickedFrom != null ? " - kicked from " + kickedFrom.toLowerCase() : ""));
                    return;
                }
                int sent = 0;
                for (PvPBot b : bots) {
                    if (b.getUUID().equals(victim.getUniqueId())) continue;
                    b.setForcedTarget(victim);
                    sent++;
                }
                ok(speaker, transcript, who + " → attacking "
                        + ChatColor.stripColor(victim.getName()) + " (" + sent + ")"
                        + (kickedFrom != null ? " - kicked from " + kickedFrom.toLowerCase() : ""));
            }
            case NONE -> fail(speaker, transcript,
                    order.heardTarget() == null || order.heardTarget().isBlank()
                            ? "didn't catch who to attack"
                            : "no player sounds like \"" + order.heardTarget() + "\"");
        }
    }

    // ---- stand-down: per bot, so it works with or without a faction. It
    // lasts until the bots are told to fight again (attack / rush / bow /
    // pillar-to / "weapons free" / "watch out").

    private static void standDown(List<PvPBot> bots) {
        for (PvPBot b : bots) {
            b.setForcedTarget(null);
            var ctx = b.getAI().getContext();
            ctx.standDown(0);
            ctx.holdFire = true;
        }
    }

    private static void releaseStandDown(BotManager manager, Set<String> factions, List<PvPBot> bots) {
        for (PvPBot b : bots) b.getAI().getContext().releaseStandDown();
        // Also lift a manual "/pvpbot faction stopattack" on the factions ordered to fight.
        for (String f : factions) {
            if (manager.isFactionStopAttack(f)) manager.setFactionStopAttack(f, false);
        }
    }

    // ---- movement orders

    private static final int SCATTER_TICKS = 20 * 20;

    // Everyone runs a different way: directions spread evenly around the
    // group, handed out in the order the bots already stand around its
    // middle so nobody has to cross the pack to get to theirs.
    private static int scatter(Player speaker, List<PvPBot> bots) {
        List<PvPBot> crew = new ArrayList<>();
        double cx = 0, cz = 0;
        World w = null;
        for (PvPBot b : bots) {
            Player bp = b.getBukkitPlayer();
            if (bp == null) continue;
            if (w == null) w = bp.getWorld();
            if (bp.getWorld() != w) continue;
            crew.add(b);
            cx += bp.getLocation().getX();
            cz += bp.getLocation().getZ();
        }
        if (crew.isEmpty()) return 0;
        cx /= crew.size();
        cz /= crew.size();
        final double mx = cx, mz = cz;
        java.util.Map<PvPBot, Double> angleOf = new HashMap<>();
        for (PvPBot b : crew) {
            Location l = b.getBukkitPlayer().getLocation();
            double a = crew.size() == 1 && speaker.getWorld() == l.getWorld()
                    ? Math.atan2(l.getZ() - speaker.getLocation().getZ(), l.getX() - speaker.getLocation().getX())
                    : Math.atan2(l.getZ() - mz, l.getX() - mx);
            angleOf.put(b, a);
        }
        crew.sort(java.util.Comparator.comparingDouble(angleOf::get));
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        double base = angleOf.get(crew.get(0));
        int n = crew.size();
        int sent = 0;
        for (int i = 0; i < n; i++) {
            PvPBot b = crew.get(i);
            double a = base + 2.0 * Math.PI * i / n + (rnd.nextDouble() - 0.5) * 0.3;
            double dist = 24.0 + rnd.nextDouble() * 12.0;
            double ox = crew.size() == 1 ? b.getBukkitPlayer().getLocation().getX() : mx;
            double oz = crew.size() == 1 ? b.getBukkitPlayer().getLocation().getZ() : mz;
            int x = (int) Math.floor(ox + Math.cos(a) * dist);
            int z = (int) Math.floor(oz + Math.sin(a) * dist);
            int nearY = b.getBukkitPlayer().getLocation().getBlockY();
            int y = groundNear(w, x, z, nearY);
            Location dest = new Location(w, x + 0.5, y, z + 0.5);
            dest.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(a), Math.sin(a))));
            b.orderRunTo(dest, SCATTER_TICKS);
            sent++;
        }
        return sent;
    }

    // A standable Y at (x, z) close to `nearY`.
    private static int groundNear(World w, int x, int z, int nearY) {
        for (int d = 0; d <= 10; d++) {
            for (int y : new int[]{nearY + d, nearY - d}) {
                if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
                org.bukkit.Material floor = w.getBlockAt(x, y - 1, z).getType();
                if (floor.isSolid() && floor != org.bukkit.Material.MAGMA_BLOCK
                        && !w.getBlockAt(x, y, z).getType().isSolid()
                        && !w.getBlockAt(x, y + 1, z).getType().isSolid()
                        && w.getBlockAt(x, y, z).getType() != org.bukkit.Material.LAVA) {
                    return y;
                }
                if (d == 0) break;
            }
        }
        int top = w.getHighestBlockYAt(x, z) + 1;
        return Math.abs(top - nearY) <= 16 ? top : nearY;
    }

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
        UUID id = speaker.getUniqueId();
        List<PvPBot> crew = new ArrayList<>(bots);
        Crewed follow = new Crewed(crew);
        final Location[] lastAnchor = {null};
        final int[] ticks = {0};
        follow.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            ticks[0] += 20;
            crew.removeIf(b -> !b.isAlive());
            if (p == null || !p.isOnline() || p.isDead() || crew.isEmpty()
                    || (!behind && ticks[0] > FOLLOW_TICKS)) {
                follow.task.cancel();
                List<Crewed> list = followTasks.get(id);
                if (list != null) list.remove(follow);
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
        followTasks.computeIfAbsent(id, k -> new ArrayList<>()).add(follow);
    }

    private void cancelFollow(UUID speaker) {
        List<Crewed> list = followTasks.remove(speaker);
        if (list == null) return;
        for (Crewed c : list) if (c.task != null) c.task.cancel();
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
        if (order.commander()) {
            // The commander of each faction the speaker leads (or of the
            // one named: "red team commander ...").
            List<PvPBot> out = new ArrayList<>();
            for (PvPBot b : led) {
                String f = manager.getPlayerFaction(b.getUUID());
                if (f == null || !b.getUUID().equals(manager.getFactionCommander(f))) continue;
                if (order.subjectFaction() != null && !f.equalsIgnoreCase(order.subjectFaction())) continue;
                out.add(b);
            }
            return out;
        }
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
    // Voice orders work silently. The action-bar read-outs (what was
    // understood, what went wrong) are a debugging aid, off unless
    // voice-feedback: true is set in config.yml.
    private static void ok(Player speaker, String heard, String what) {
        feedback(speaker, Component.text("🎙 " + what, NamedTextColor.GREEN));
    }

    private static void fail(Player speaker, String heard, String why) {
        feedback(speaker, Component.text("🎙 " + why, NamedTextColor.RED));
    }

    private static void feedback(Player speaker, Component line) {
        if (speaker == null) return;
        if (!PvPBotPlugin.getInstance().getConfig().getBoolean("voice-feedback", false)) return;
        speaker.sendActionBar(line);
    }
}
