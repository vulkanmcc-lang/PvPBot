package com.pvpbot.voicelink;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

// Simple Voice Chat plugin entrypoint ("voicechat" in fabric.mod.json).
// ClientSoundEvent fires with the player's own raw microphone audio every
// time voice chat is about to send a frame - i.e. only while they're
// actually talking (push-to-talk held or voice activation triggered).
public final class VoiceLinkVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() {
        return "pvpbot_voicelink";
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceLinkClient.LOG.info("PvPBot Voice Link hooked into Simple Voice Chat");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::onClientSound);
    }

    private void onClientSound(ClientSoundEvent event) {
        VoiceLinkClient.onMicrophoneFrame(event.getRawAudio());
    }
}
