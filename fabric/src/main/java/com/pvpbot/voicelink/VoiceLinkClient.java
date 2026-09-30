package com.pvpbot.voicelink;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class VoiceLinkClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("pvpbot_voicelink");

    // Words that address the bots. Only utterances containing one of these
    // (or a faction name the server sent us) ever leave this PC - everything
    // else you say on voice chat is recognised locally and thrown away.
    private static final Set<String> ADDRESS_WORDS = words(
            "everyone", "everybody", "all", "bots", "team", "guys", "squad", "army",
            "boys", "lads", "yall", "troops", "crew", "gang", "fellas", "every", "commander",
            "commanders", "comander");

    // Words that can open an unaddressed order ("focus Steve", "follow me").
    // The server sends its full list on join; this is the fallback.
    private static final Set<String> DEFAULT_STARTERS = words(
            "kill", "attack", "focus", "get", "take", "target", "push", "rush", "dont", "all",
            "stop", "hold", "nobody", "calm", "save", "come", "follow", "group", "meet", "regroup",
            "stay", "move", "lets", "keep", "go", "watch", "behind", "theyre", "wait", "we",
            "mine", "dig", "excavate", "destroy", "blow", "demolish", "level", "flatten", "break",
            "fall", "form", "line", "at", "dismissed", "quit", "pillar", "tower", "climb", "reach",
            "bow", "bows", "shoot", "snipe", "fire", "use", "put", "remove", "equip", "wear",
            "armor", "armour", "gear", "suit", "strip", "every", "make", "build", "bridge", "path",
            "pave", "tunnel", "look", "face", "turn", "eyes", "tower", "best", "worst", "bad",
            "good", "strongest", "weakest", "guard", "camp", "scatter", "split", "spread", "run",
            "disperse", "fan");

    private static final Set<String> LEAD_IN = words(
            "ok", "okay", "hey", "yo", "alright", "right", "so", "and", "uh", "um", "now", "oi");

    // Tolerates duplicates (Set.of throws on them at class load, which kills
    // the whole mod before voice chat can even start).
    private static Set<String> words(String... ws) {
        return Set.copyOf(new java.util.LinkedHashSet<>(Arrays.asList(ws)));
    }

    private static VoiceLinkConfig config;
    private static SpeechEngine engine;
    private static volatile Set<String> serverAddress = Set.of();
    private static volatile Set<String> serverStarters = Set.of();
    private static volatile boolean downloading = false;

    @Override
    public void onInitializeClient() {
        config = VoiceLinkConfig.load();
        engine = new SpeechEngine(VoiceLinkClient::onUtterance);

        PayloadTypeRegistry.playC2S().register(VoiceCommandPayload.ID, VoiceCommandPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VoiceWordsPayload.ID, VoiceWordsPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(VoiceWordsPayload.ID, (payload, context) -> {
            Set<String> address = new HashSet<>();
            Set<String> starters = new HashSet<>();
            for (String w : payload.words()) {
                boolean isAddress = w.startsWith("@");
                for (String part : w.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                    if (part.isEmpty()) continue;
                    (isAddress ? address : starters).add(part);
                }
            }
            serverAddress = Set.copyOf(address);
            serverStarters = Set.copyOf(starters);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            serverAddress = Set.of();
            serverStarters = Set.of();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(VoiceLinkClient::announceOnJoin));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("voicelink")
                        .executes(VoiceLinkClient::status)
                        .then(ClientCommandManager.literal("status").executes(VoiceLinkClient::status))
                        .then(ClientCommandManager.literal("on").executes(ctx -> setEnabled(ctx, true)))
                        .then(ClientCommandManager.literal("off").executes(ctx -> setEnabled(ctx, false)))
                        .then(ClientCommandManager.literal("setup").executes(VoiceLinkClient::setup))));

        if (config.enabled) startEngineAsync();
    }

    // ---------------------------------------------------------------------
    // Audio in, orders out
    // ---------------------------------------------------------------------

    static void onMicrophoneFrame(short[] pcm) {
        if (config != null && config.enabled && engine != null) engine.offer(pcm);
    }

    private static void onUtterance(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (!addressesBots(text)) return;
            if (client.player == null) return;
            if (!ClientPlayNetworking.canSend(VoiceCommandPayload.ID)) {
                if (config.showHeard) {
                    overlay(Text.literal("🎙 \"" + text + "\" - this server doesn't have PvPBot voice commands")
                            .formatted(Formatting.GRAY));
                }
                return;
            }
            String clipped = text.length() > VoiceCommandPayload.MAX_LENGTH
                    ? text.substring(0, VoiceCommandPayload.MAX_LENGTH) : text;
            ClientPlayNetworking.send(new VoiceCommandPayload(clipped));
        });
    }

    // Could this be an order? Either the bots are addressed somewhere in it
    // ("okay everyone kill Steve", "calm down guys"), or it opens with a
    // command word ("focus Steve", "follow me"), or it's the one pattern that
    // puts the name first ("Steve is our target"). Anything else stays here.
    static boolean addressesBots(String text) {
        List<String> tokens = Arrays.stream(text.toLowerCase(Locale.ROOT)
                .replace("'", "").split("[^a-z0-9]+")).filter(t -> !t.isEmpty()).toList();
        Set<String> address = serverAddress;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (ADDRESS_WORDS.contains(t) || address.contains(t)) return true;
            if (t.equals("you") && i + 1 < tokens.size() && tokens.get(i + 1).equals("all")) return true;
        }
        int first = 0;
        while (first < tokens.size() && LEAD_IN.contains(tokens.get(first))) first++;
        if (first >= tokens.size()) return false;
        Set<String> starters = serverStarters.isEmpty() ? DEFAULT_STARTERS : serverStarters;
        // A command word first ("focus Steve"), or right after a bot's name
        // of up to three words ("Nexar Void come here").
        for (int i = first; i < Math.min(tokens.size(), first + 4); i++) {
            if (starters.contains(tokens.get(i))) return true;
        }
        return tokens.size() <= 8 && tokens.contains("target");
    }

    // ---------------------------------------------------------------------
    // Engine / model lifecycle
    // ---------------------------------------------------------------------

    private static void startEngineAsync() {
        Thread t = new Thread(() -> engine.load(config.modelDir()), "PvPBot-VoiceLink-Load");
        t.setDaemon(true);
        t.start();
    }

    private static void announceOnJoin() {
        if (!config.enabled) return;
        if (!ClientPlayNetworking.canSend(VoiceCommandPayload.ID)) return;
        if (!engine.ready()) {
            chat(Text.literal("[Voice Link] This server takes PvPBot voice orders. ")
                    .formatted(Formatting.GOLD)
                    .append(Text.literal(engine.status()).formatted(Formatting.GRAY)));
        } else {
            chat(Text.literal("[Voice Link] Voice orders active - try \"everyone kill <player>\".")
                    .formatted(Formatting.GREEN));
        }
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        src.sendFeedback(Text.literal("PvPBot Voice Link").formatted(Formatting.GOLD));
        src.sendFeedback(Text.literal(" enabled: " + config.enabled).formatted(Formatting.GRAY));
        src.sendFeedback(Text.literal(" speech: " + (downloading ? "downloading model..." : engine.status()))
                .formatted(Formatting.GRAY));
        src.sendFeedback(Text.literal(" server: " + (ClientPlayNetworking.canSend(VoiceCommandPayload.ID)
                ? "accepts voice orders" : "no PvPBot voice support")).formatted(Formatting.GRAY));
        src.sendFeedback(Text.literal(" say e.g. \"everyone kill Steve\", \"everyone stop\", "
                + "\"everyone come here\", \"red team attack blue\"").formatted(Formatting.DARK_GRAY));
        return 1;
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        config.enabled = on;
        config.save();
        if (on) {
            startEngineAsync();
            ctx.getSource().sendFeedback(Text.literal("Voice Link on.").formatted(Formatting.GREEN));
        } else {
            Thread t = new Thread(engine::stop, "PvPBot-VoiceLink-Stop");
            t.setDaemon(true);
            t.start();
            ctx.getSource().sendFeedback(Text.literal("Voice Link off - nothing you say is processed.")
                    .formatted(Formatting.YELLOW));
        }
        return 1;
    }

    private static int setup(CommandContext<FabricClientCommandSource> ctx) {
        if (downloading) {
            ctx.getSource().sendFeedback(Text.literal("Already downloading.").formatted(Formatting.YELLOW));
            return 0;
        }
        downloading = true;
        ctx.getSource().sendFeedback(Text.literal("Downloading the offline speech model (~40 MB) from "
                + config.modelUrl).formatted(Formatting.GRAY));
        Thread t = new Thread(() -> {
            try {
                ModelDownloader.download(config.modelUrl, config.modelDir(),
                        msg -> MinecraftClient.getInstance().execute(() ->
                                overlay(Text.literal(msg).formatted(Formatting.GRAY))));
                if (config.enabled) engine.load(config.modelDir());
                MinecraftClient.getInstance().execute(() -> chat(Text.literal(
                        "[Voice Link] Speech model ready - " + engine.status()).formatted(Formatting.GREEN)));
            } catch (Exception e) {
                LOG.error("Model download failed", e);
                MinecraftClient.getInstance().execute(() -> chat(Text.literal(
                        "[Voice Link] Download failed: " + e.getMessage()).formatted(Formatting.RED)));
            } finally {
                downloading = false;
            }
        }, "PvPBot-VoiceLink-Download");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    private static void chat(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) client.inGameHud.getChatHud().addMessage(text);
    }

    private static void overlay(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud != null) client.inGameHud.setOverlayMessage(text, false);
    }
}
