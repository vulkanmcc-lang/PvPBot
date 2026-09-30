package com.pvpbot.voicelink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Offline speech-to-text for the player's own microphone.
//
// Simple Voice Chat hands us 20 ms frames of 48 kHz mono PCM on its audio
// thread; we just queue them (never block voice chat) and a dedicated
// worker thread downsamples to the 16 kHz Vosk expects and feeds the
// recogniser. An utterance ends either when Vosk hears a pause, or when the
// mic simply stops sending (push-to-talk released) for a moment - then we
// flush whatever was said so far.
final class SpeechEngine {
    private static final float VOSK_RATE = 16000f;
    private static final int SVC_RATE = 48000;
    private static final int DOWNSAMPLE = SVC_RATE / (int) VOSK_RATE;
    private static final long SILENCE_FLUSH_MS = 650;

    private final BlockingQueue<short[]> frames = new ArrayBlockingQueue<>(600);
    private final Consumer<String> onUtterance;

    private volatile Model model;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile String status = "no speech model loaded";

    SpeechEngine(Consumer<String> onUtterance) {
        this.onUtterance = onUtterance;
    }

    String status() {
        return status;
    }

    boolean ready() {
        return model != null && running;
    }

    // Called from Simple Voice Chat's audio thread.
    void offer(short[] frame) {
        if (!running || frame == null || frame.length == 0) return;
        frames.offer(frame.clone()); // dropped if we're somehow 12 s behind
    }

    synchronized void load(Path modelDir) {
        stop();
        if (!Files.isDirectory(modelDir)) {
            status = "speech model missing - run /voicelink setup";
            return;
        }
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(modelDir.toString());
        } catch (Throwable t) {
            model = null;
            status = "couldn't load speech model: " + t;
            VoiceLinkClient.LOG.error("Vosk model load failed", t);
            return;
        }
        running = true;
        frames.clear();
        Thread t = new Thread(this::run, "PvPBot-VoiceLink-STT");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        worker = t;
        t.start();
        status = "listening (model: " + modelDir.getFileName() + ")";
    }

    synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Model m = model;
        model = null;
        if (m != null) {
            try {
                m.close();
            } catch (Throwable ignored) {
            }
        }
        frames.clear();
        status = "stopped";
    }

    private void run() {
        Model m = model;
        if (m == null) return;
        try (Recognizer rec = new Recognizer(m, VOSK_RATE)) {
            boolean fed = false;
            long lastAudio = 0;
            short[] down = new short[0];

            while (running) {
                short[] frame;
                try {
                    frame = frames.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }

                if (frame == null) {
                    if (fed && System.currentTimeMillis() - lastAudio > SILENCE_FLUSH_MS) {
                        emit(rec.getFinalResult());
                        fed = false;
                    }
                    continue;
                }

                int outLen = frame.length / DOWNSAMPLE;
                if (down.length != outLen) down = new short[outLen];
                // Average each group of three samples: a cheap low-pass that
                // keeps the 48k -> 16k decimation from aliasing badly.
                for (int i = 0, j = 0; i < outLen; i++, j += DOWNSAMPLE) {
                    int sum = 0;
                    for (int k = 0; k < DOWNSAMPLE; k++) sum += frame[j + k];
                    down[i] = (short) (sum / DOWNSAMPLE);
                }

                fed = true;
                lastAudio = System.currentTimeMillis();
                if (rec.acceptWaveForm(down, outLen)) {
                    emit(rec.getResult());
                    fed = false;
                }
            }
        } catch (Throwable t) {
            status = "speech recogniser crashed: " + t;
            VoiceLinkClient.LOG.error("Voice link recogniser stopped", t);
            running = false;
        }
    }

    private void emit(String json) {
        String text = textOf(json);
        if (text != null && !text.isBlank()) onUtterance.accept(text.trim());
    }

    private static String textOf(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return o.has("text") ? o.get("text").getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
