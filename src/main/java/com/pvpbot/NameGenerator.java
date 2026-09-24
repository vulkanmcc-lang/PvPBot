package com.pvpbot;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class NameGenerator {
    private static final List<String> PREFIXES = List.of(
            "Shadow", "Frost", "Neon", "Crimson", "Storm", "Iron", "Blaze", "Dark", "Ghost", "Cyber",
            "Void", "Flame", "Ice", "Steel", "Cosmic", "Pixel", "Nova", "Rogue", "Zen", "Turbo",
            "Mystic", "Razor", "Phantom", "Quantum", "Solar", "Lunar", "Venom", "Titan", "Crystal",
            "Ocean", "Desert", "Jungle", "Glacier", "Meteor", "Coral", "Tornado", "Lava", "Toxic",
            "Mist", "Dusk", "Dawn", "Twilight", "Star", "Moon", "Sun", "Cloud", "Snow", "Wind",
            "Earth", "Fire", "Water", "Prism", "Echo", "Nexus", "Apex", "Zero", "Alpha", "Omega"
    );

    private static final List<String> SUFFIXES = List.of(
            "Viper", "Blade", "Wolf", "Tide", "Chaser", "Phantom", "Fury", "Fox", "Strike", "Hawk",
            "Bolt", "Ember", "Rider", "Punk", "Walker", "Knight", "Breaker", "Nerve", "Ray", "Demon",
            "Star", "Agent", "Master", "Fist", "Owl", "Edge", "Ace", "Pulse", "Leap", "Flare",
            "Eclipse", "Force", "Mage", "King", "Breeze", "Eagle", "Cat", "Ash", "Peak", "Shower",
            "Reef", "Spin", "Flow", "Runner", "Fall", "Break", "Zone", "Dust", "Beam", "Rise",
            "Ninja", "Drift", "Gust", "Shake", "Storm", "Glow", "Vault", "Point", "Hunter", "God"
    );

    private static final Set<String> usedNames = Collections.synchronizedSet(new HashSet<>());

    public enum NameStyle { CLEAN, ALT, VOID }

    private static final String MASH_DIGITS =
            "000011122222333889999997" + "456";
    private static final String MASH_LETTERS =
            "uuuuuuuaaaaawwwwiiiimmmyyyqqqhhhxxxsssccckkeeennddffjjoorrtt";

    private static final int MASH_MIN_LENGTH = 11;
    private static final int MASH_MAX_LENGTH = 16;

    private static String mashName(ThreadLocalRandom random) {
        int target = random.nextInt(MASH_MIN_LENGTH, MASH_MAX_LENGTH + 1);
        StringBuilder sb = new StringBuilder(target);

        boolean digits = random.nextDouble() < 0.6;

        while (sb.length() < target) {
            int runLength = digits
                    ? random.nextInt(1, 5)
                    : random.nextInt(1, 6);
            runLength = Math.min(runLength, target - sb.length());

            String pool = digits ? MASH_DIGITS : MASH_LETTERS;
            for (int i = 0; i < runLength; i++) {
                sb.append(pool.charAt(random.nextInt(pool.length())));
            }
            digits = !digits;
        }
        return sb.toString();
    }

    public static String getRandomName(NameStyle style) {
        if (style == NameStyle.VOID) return getRandomVoidName();
        return style == NameStyle.ALT ? getRandomAltName() : getRandomName();
    }

    private static String getRandomVoidName() {
        pruneUsedNames();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        String name;
        int attempts = 0;
        do {
            name = mashName(random);

            if (++attempts > 50) {
                name = name.substring(0, Math.min(name.length(), 12))
                        + random.nextInt(1000, 9999);
                if (name.length() > 16) name = name.substring(0, 16);
                break;
            }
        } while (usedNames.contains(name));

        usedNames.add(name);
        return name;
    }

    public static String getRandomAltName() {
        pruneUsedNames();

        ThreadLocalRandom random = ThreadLocalRandom.current();
        String name;
        int attempts = 0;
        do {
            name = mashName(random);

            if (++attempts > 50) {
                name = name.substring(0, Math.min(name.length(), 12))
                        + random.nextInt(1000, 9999);
                if (name.length() > 16) name = name.substring(0, 16);
                break;
            }
        } while (usedNames.contains(name));

        usedNames.add(name);
        return name;
    }

    public static String getRandomName() {
        pruneUsedNames();

        String name;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        do {
            String prefix = PREFIXES.get(random.nextInt(PREFIXES.size()));
            String suffix = SUFFIXES.get(random.nextInt(SUFFIXES.size()));

            name = prefix + suffix;

            if (random.nextDouble() < 0.3) {
                name += random.nextInt(10, 999);
            }

            if (name.length() > 16) {
                name = name.substring(0, 16);
            }
        } while (usedNames.contains(name));

        usedNames.add(name);
        return name;
    }

    private static void pruneUsedNames() {
        if (usedNames.size() <= 1000) return;

        java.util.Set<String> live = new java.util.HashSet<>();
        try {
            com.pvpbot.PvPBotPlugin plugin = com.pvpbot.PvPBotPlugin.getInstance();
            if (plugin != null && plugin.getBotManager() != null) {
                for (PvPBot bot : plugin.getBotManager().getBots().values()) {
                    if (bot != null && bot.getName() != null) live.add(bot.getName());
                }
            }
        } catch (Throwable t) {
            return;
        }

        synchronized (usedNames) {
            usedNames.retainAll(live);
        }
    }

    public static void reserveName(String name) {
        if (name != null && !name.isBlank()) usedNames.add(name);
    }

    public static void releaseName(String name) {
        usedNames.remove(name);
    }
}
