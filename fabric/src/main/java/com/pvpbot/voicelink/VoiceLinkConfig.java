package com.pvpbot.voicelink;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

// config/pvpbot-voicelink/voicelink.properties
final class VoiceLinkConfig {
    static final String DEFAULT_MODEL = "vosk-model-small-en-us-0.15";
    static final String DEFAULT_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    boolean enabled = true;
    boolean showHeard = true;
    String model = DEFAULT_MODEL;
    String modelUrl = DEFAULT_MODEL_URL;

    static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("pvpbot-voicelink");
    }

    private static Path file() {
        return dir().resolve("voicelink.properties");
    }

    Path modelDir() {
        return dir().resolve("models").resolve(model);
    }

    static VoiceLinkConfig load() {
        VoiceLinkConfig c = new VoiceLinkConfig();
        Path f = file();
        if (Files.exists(f)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
                c.enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
                c.showHeard = Boolean.parseBoolean(p.getProperty("showHeard", "true"));
                c.model = p.getProperty("model", DEFAULT_MODEL).trim();
                c.modelUrl = p.getProperty("modelUrl", DEFAULT_MODEL_URL).trim();
            } catch (IOException e) {
                VoiceLinkClient.LOG.warn("Couldn't read {}: {}", f, e.toString());
            }
        }
        c.save();
        return c;
    }

    void save() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("showHeard", Boolean.toString(showHeard));
        p.setProperty("model", model);
        p.setProperty("modelUrl", modelUrl);
        try {
            Files.createDirectories(dir());
            try (Writer w = Files.newBufferedWriter(file())) {
                p.store(w, "PvPBot Voice Link - model is a folder name under models/, "
                        + "modelUrl is where /voicelink setup downloads it from (any Vosk model zip)");
            }
        } catch (IOException e) {
            VoiceLinkClient.LOG.warn("Couldn't save voice link config: {}", e.toString());
        }
    }
}
