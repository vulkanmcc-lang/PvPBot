package com.pvpbot.voicelink;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

// Server -> client: extra words that can address the bots on this server
// (faction names like "red" in "red team attack blue"), so the client-side
// filter lets those orders through too.
// Wire format (v1): [byte version = 1][VarInt count]([VarInt length][UTF-8])*
public record VoiceWordsPayload(List<String> words) implements CustomPayload {
    public static final CustomPayload.Id<VoiceWordsPayload> ID =
            new CustomPayload.Id<>(Identifier.of("pvpbot", "voice_words"));

    public static final PacketCodec<PacketByteBuf, VoiceWordsPayload> CODEC =
            PacketCodec.of(VoiceWordsPayload::write, VoiceWordsPayload::read);

    private static final int MAX_WORDS = 256;
    private static final int MAX_WORD = 64;

    private void write(PacketByteBuf buf) {
        buf.writeByte(1);
        buf.writeVarInt(words.size());
        for (String w : words) buf.writeString(w, MAX_WORD);
    }

    private static VoiceWordsPayload read(PacketByteBuf buf) {
        int version = buf.readByte();
        int n = Math.min(buf.readVarInt(), MAX_WORDS);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(buf.readString(MAX_WORD));
        if (version != 1) out.clear();
        return new VoiceWordsPayload(out);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
