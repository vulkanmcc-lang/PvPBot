package com.pvpbot.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

public final class PvPBotVoiceChat implements VoicechatPlugin {

    private static VoicechatServerApi api;

    @Override
    public String getPluginId() {
        return "pvpbot";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        if (voicechatApi instanceof VoicechatServerApi serverApi) {
            api = serverApi;
        }
    }

    public static void register(JavaPlugin plugin) {
        BukkitVoicechatService service =
                plugin.getServer()
                        .getServicesManager()
                        .load(BukkitVoicechatService.class);

        if (service == null) {
            plugin.getLogger().warning(
                    "Simple Voice Chat service was not found!"
            );
            return;
        }

        service.registerPlugin(new PvPBotVoiceChat());

        plugin.getLogger().info(
                "Registered PvPBot with Simple Voice Chat."
        );
    }

    public static VoicechatConnection getConnection(UUID uuid) {
        if (api == null) {
            return null;
        }

        return api.getConnectionOf(uuid);
    }

    public static void connectBot(JavaPlugin plugin, UUID uuid, String name) {
        new BukkitRunnable() {
            int attempts = 0;

            @Override
            public void run() {
                attempts++;

                VoicechatConnection connection = getConnection(uuid);

                if (connection != null) {
                    connection.setConnected(true);

                    plugin.getLogger().info(
                            "[PvPBot] SVC connection established for " + name
                    );

                    cancel();
                    return;
                }

                if (attempts >= 20) {
                    plugin.getLogger().warning(
                            "[PvPBot] SVC connection was not created for " + name
                    );
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}