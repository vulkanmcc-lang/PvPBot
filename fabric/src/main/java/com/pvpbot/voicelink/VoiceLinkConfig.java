package com.pvpbot.voicelink;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

// config/pvpbot-voicelink/voicelink.properties
final class VoiceLinkConfig {
    // Offline Vosk models, smallest to most accurate. Vosk runs on the CPU
    // (its GPU mode is NVIDIA-only), so a fast desktop CPU handles even the
    // big ones in real time; they mostly cost RAM and download size.
    enum ModelChoice {
        SMALL("vosk-model-small-en-us-0.15", "40 MB", "fast, least accurate"),
        MEDIUM("vosk-model-en-us-0.22-lgraph", "128 MB", "noticeably better than small"),
        LARGE("vosk-model-en-us-0.22", "1.8 GB", "most accurate for clear speech"),
        GIGASPEECH("vosk-model-en-us-0.42-gigaspeech", "2.3 GB", "trained on podcasts/YouTube - best with casual speech");

        final String folder;
        final String size;
        final String blurb;

        ModelChoice(String folder, String size, String blurb) {
            this.folder = folder;
            this.size = size;
            this.blurb = blurb;
        }

        String url() {
            return "https://alphacephei.com/vosk/models/" + folder + ".zip";
        }

        static ModelChoice byName(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        static ModelChoice byFolder(String folder) {
            for (ModelChoice c : values()) if (c.folder.equals(folder)) return c;
            return null;
        }
    }

    static final ModelChoice DEFAULT_CHOICE = ModelChoice.LARGE;
    private static final int CONFIG_VERSION = 2;

    boolean enabled = true;
    String model = DEFAULT_CHOICE.folder;
    String modelUrl = DEFAULT_CHOICE.url();

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbot-voicelink");
    }

    static Path modelsDir() {
        return dir().resolve("models");
    }

    private static Path file() {
        return dir().resolve("voicelink.properties");
    }

    Path modelDir() {
        return modelsDir().resolve(model);
    }

    void choose(ModelChoice c) {
        model = c.folder;
        modelUrl = c.url();
        save();
    }

    String sizeNote() {
        ModelChoice c = ModelChoice.byFolder(model);
        return c == null ? "" : " (~" + c.size + ")";
    }

    static VoiceLinkConfig load() {
        VoiceLinkConfig c = new VoiceLinkConfig();
        Path f = file();
        if (Files.exists(f)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
                c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
                c.model = p.getProperty("model", DEFAULT_CHOICE.folder).trim();
                c.modelUrl = p.getProperty("modelUrl", DEFAULT_CHOICE.url()).trim();
                int version = Integer.parseInt(p.getProperty("configVersion", "1").trim());
                // Older configs just carried the old default (the small
                // model): move them up to the new default. A model picked on
                // purpose since then is left alone.
                if (version < 2 && c.model.equals(ModelChoice.SMALL.folder)) {
                    c.model = DEFAULT_CHOICE.folder;
                    c.modelUrl = DEFAULT_CHOICE.url();
                }
            } catch (IOException | NumberFormatException e) {
                VoiceLinkClient.LOG.warn("Couldn't read {}: {}", f, e.toString());
            }
        }
        c.save();
        return c;
    }

    void save() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("model", model);
        p.setProperty("modelUrl", modelUrl);
        p.setProperty("configVersion", Integer.toString(CONFIG_VERSION));
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "PvPBot Voice Link - model is a folder name under models/, "
                        + "modelUrl is where /voicelink setup downloads it from (any Vosk model zip). "
                        + "Easiest: /voicelink model small|medium|large|gigaspeech");
            }
        } catch (IOException e) {
            VoiceLinkClient.LOG.warn("Couldn't save voice link config: {}", e.toString());
        }
    }
}
