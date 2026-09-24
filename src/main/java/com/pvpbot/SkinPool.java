package com.pvpbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public final class SkinPool {
    private static final List<String[]> SKINS = new CopyOnWriteArrayList<>();

    private static final int MAX_SKINS = 64;

    private static boolean harvestFromPlayers = false;

    public static void setHarvestFromPlayers(boolean value) {
        harvestFromPlayers = value;
    }

    private SkinPool() {
    }

    public static void harvest(Player player) {
        if (!harvestFromPlayers) return;
        try {
            GameProfile profile = ((CraftPlayer) player).getHandle().getGameProfile();
            for (Property property : profile.properties().get("textures")) {
                add(property.value(), property.signature());
            }
        } catch (Throwable ignored) {
        }
    }

    public static void harvestOnline() {
        if (!harvestFromPlayers) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            harvest(player);
        }
    }

    public static void fetchAsync(Plugin plugin, List<String> usernames) {
        if (usernames == null || usernames.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int loaded = 0;
            for (String username : usernames) {
                if (username == null || username.isBlank()) continue;
                try {
                    String uuid = lookupUuid(username.trim());
                    if (uuid == null) continue;
                    if (fetchTextures(uuid)) loaded++;
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    plugin.getLogger().warning("[PvPBot] Skin lookup failed for "
                            + username + ": " + t.getMessage());
                }
            }
            int total = loaded;
            plugin.getLogger().info("[PvPBot] Loaded " + total + " skin(s) from Mojang");
        });
    }

    private static String lookupUuid(String username) throws Exception {
        JsonObject json = getJson("https://api.mojang.com/users/profiles/minecraft/" + username);
        if (json == null || !json.has("id")) return null;
        return json.get("id").getAsString();
    }

    private static boolean fetchTextures(String uuid) throws Exception {
        JsonObject json = getJson(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        if (json == null || !json.has("properties")) return false;

        JsonArray properties = json.getAsJsonArray("properties");
        for (int i = 0; i < properties.size(); i++) {
            JsonObject property = properties.get(i).getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) continue;
            String value = property.get("value").getAsString();
            String signature = property.has("signature")
                    ? property.get("signature").getAsString() : null;
            add(value, signature);
            return true;
        }
        return false;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "PvPBot");
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStreamReader reader =
                         new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } finally {
            connection.disconnect();
        }
    }

    public static GameProfile createProfile(UUID uuid, String name) {
        GameProfile profile = new GameProfile(uuid, name);
        String[] skin = skinFor(name);
        if (skin == null) return profile;
        return withTextures(profile, uuid, name, skin[0], skin[1]);
    }

    private static final String BLACK_SKIN_VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTYyMzAzMjA4MTQ3NiwKICAicHJvZmlsZUlkIiA6ICJkZGVkNTZlMWVmOGI0MGZlOGFkMTYyOTIwZjdhZWNkYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJEaXNjb3JkQXBwIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2VhY2NiMDdmMDUzNmRkODE3YzE1MjVhM2EzZjk3ZTQyODA5NWM5YjI5ODQ2MGJjN2EzYTNlMWI4YjlmYzcxZmEiCiAgICB9CiAgfQp9";
    private static final String BLACK_SKIN_SIGNATURE =
            "mxwj4Y2K3DI5cF+VNSWSntMa13cXV6SbuAVMgpsY2zrc3UWJrd9Mxy1qYefNfHL1bJMDrwZoyyTrlU3EDisoGPd1xckfOXIKThDQQVLHvhEqYjj5AAZY/9ljh0cxhDGMq/eT4Xs6vMH25jD/dCgGibcgvzeKoqk41j1v4qZXsn5tCSzXJLnVK/GjdjUSOnRs/PIjERRhuq0We9fmOEBzKA3FVHk6O1g+iIsSYDJaw2XIALcYOQlz4p0PYqctFaEGEPeM5TlZDgrSVdTEjYlENq4ktp7yIBARKh0DS5x/lnM1PY0UJ7JNLqvGczi5SyzlcoEhfqWInz97k/YHSPSMtXq3Ny57qCBnq34mXm4PBXjCjIbBo0YkL62CWHrF3HfFlTtwzkX/Ck9ehYZphXZX3d8ZS1rn+nxn/cnRa4bX8x1c+3vemZtUQpDPHfHho23qG22QtGv8kvnE+Si6WioxAkbGDx1uK0UafxeuV3KzqGX4P34InwMMB+DUxJR1ddEtPjbizCLtzCkomjXHBrhfA+TaoKzE2WAsUKKEgV17IV0YOGrkM1IOElmlyFKBCny/6Pbq473lkZH4kGRjvhLjpKeHlRuicUK4d1x/mb1Wn5rJnfOH36lUXZwrQcp4DZFnbrfoisoetelV0XA9l5FHBO86KYBnvZS9hJcM37Z28B4=";

    public static GameProfile createBlackProfile(UUID uuid, String name) {
        GameProfile profile = new GameProfile(uuid, name);
        return withTextures(profile, uuid, name, BLACK_SKIN_VALUE, BLACK_SKIN_SIGNATURE);
    }

    private static GameProfile withTextures(GameProfile profile, UUID uuid, String name,
                                              String value, String signature) {
        Property textures = signature == null
                ? new Property("textures", value)
                : new Property("textures", value, signature);

        try {
            profile.properties().removeAll("textures");
            profile.properties().put("textures", textures);
            return profile;
        } catch (UnsupportedOperationException immutableProfile) {
        } catch (Throwable t) {
            warnOnce("editing the profile", t);
            return profile;
        }

        try {
            PropertyMap map = buildPropertyMap(textures);
            java.lang.reflect.Constructor<GameProfile> ctor =
                    GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);
            return ctor.newInstance(uuid, name, map);
        } catch (Throwable t) {
            warnOnce("rebuilding the profile", t);
            return profile;
        }
    }

    private static PropertyMap buildPropertyMap(Property textures) throws Exception {
        Multimap<String, Property> contents = ArrayListMultimap.create();
        contents.put("textures", textures);

        try {
            return PropertyMap.class.getConstructor(Multimap.class).newInstance(contents);
        } catch (NoSuchMethodException noCopyCtor) {
        }

        PropertyMap map = PropertyMap.class.getConstructor().newInstance();
        try {
            map.put("textures", textures);
            return map;
        } catch (UnsupportedOperationException immutableEmptyMap) {
        }

        for (java.lang.reflect.Field field : PropertyMap.class.getDeclaredFields()) {
            if (Multimap.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(map, contents);
                return map;
            }
        }
        throw new IllegalStateException("no usable PropertyMap construction path");
    }

    private static boolean warned = false;

    private static void warnOnce(String stage, Throwable t) {
        if (warned) return;
        warned = true;
        Bukkit.getLogger().warning("[PvPBot] Could not attach skins while " + stage
                + " (" + t.getClass().getSimpleName() + ": " + t.getMessage()
                + "). Bots will use default skins.");
    }

    private static String[] skinFor(String name) {
        if (SKINS.isEmpty() || name == null) return null;
        int index = Math.floorMod(name.hashCode(), SKINS.size());
        return SKINS.get(index);
    }

    public static UUID offlineUuidFor(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static void add(String value, String signature) {
        if (value == null || value.isBlank()) return;
        for (String[] existing : SKINS) {
            if (existing[0].equals(value)) return;
        }
        if (SKINS.size() >= MAX_SKINS) SKINS.remove(0);
        SKINS.add(new String[]{value, signature});
    }

    public static int size() {
        return SKINS.size();
    }

    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add(SKINS.size() + " skin(s) cached");
        return out;
    }
}
