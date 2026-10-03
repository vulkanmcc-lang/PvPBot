package com.pvpbot.voicelink;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

// Client -> server: something the player said that looks like a bot order.
// Wire format (v1), mirrored by com.pvpbot.voice.VoiceLink on the plugin:
//   [byte version = 1][VarInt length][UTF-8 transcript]
public record VoiceCommandPayload(String transcript) implements CustomPayload {
    public static final int VERSION = 1;
    public static final int MAX_LENGTH = 256;

    public static final CustomPayload.Id<VoiceCommandPayload> ID =
            new CustomPayload.Id<>(Identifier.of("pvpbot", "voice"));

    public static final PacketCodec<PacketByteBuf, VoiceCommandPayload> CODEC =
            PacketCodec.of(VoiceCommandPayload::write, VoiceCommandPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeByte(VERSION);
        buf.writeString(transcript, MAX_LENGTH);
    }

    private static VoiceCommandPayload read(PacketByteBuf buf) {
        buf.readByte();
        return new VoiceCommandPayload(buf.readString(MAX_LENGTH));
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
