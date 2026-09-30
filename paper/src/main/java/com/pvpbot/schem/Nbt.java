package com.pvpbot.schem;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Nbt {
    private static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4,
            FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9,
            COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    private static final int MAX_DEPTH = 64;

    private Nbt() {
    }

    public static Map<String, Object> read(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath());
        if (raw.length < 3) throw new IOException("file is empty or truncated");

        boolean gzipped = (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
        InputStream base = new ByteArrayInputStream(raw);
        if (gzipped) base = new GZIPInputStream(base);

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(base))) {
            int type = in.readByte() & 0xFF;
            if (type != COMPOUND) {
                throw new IOException("root tag is type " + type + ", expected a compound");
            }
            in.readUTF();
            return readCompound(in, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        Map<String, Object> out = new HashMap<>();
        while (true) {
            int type = in.readByte() & 0xFF;
            if (type == END) return out;
            String name = in.readUTF();
            out.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested deeper than " + MAX_DEPTH);
        switch (type) {
            case BYTE:    return in.readByte();
            case SHORT:   return in.readShort();
            case INT:     return in.readInt();
            case LONG:    return in.readLong();
            case FLOAT:   return in.readFloat();
            case DOUBLE:  return in.readDouble();
            case STRING:  return in.readUTF();
            case COMPOUND: return readCompound(in, depth);
            case BYTE_ARRAY: {
                int len = checkLength(in.readInt());
                byte[] a = new byte[len];
                in.readFully(a);
                return a;
            }
            case INT_ARRAY: {
                int len = checkLength(in.readInt());
                int[] a = new int[len];
                for (int i = 0; i < len; i++) a[i] = in.readInt();
                return a;
            }
            case LONG_ARRAY: {
                int len = checkLength(in.readInt());
                long[] a = new long[len];
                for (int i = 0; i < len; i++) a[i] = in.readLong();
                return a;
            }
            case LIST: {
                int elem = in.readByte() & 0xFF;
                int len = checkLength(in.readInt());
                List<Object> list = new ArrayList<>(Math.min(len, 1024));

                for (int i = 0; i < len && elem != END; i++) {
                    list.add(readPayload(in, elem, depth + 1));
                }
                return list;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static int checkLength(int len) throws IOException {
        if (len < 0 || len > 64 * 1024 * 1024) {
            throw new IOException("implausible NBT array length: " + len);
        }
        return len;
    }

    public static int getInt(Map<String, Object> tag, String key, int fallback) {
        Object v = tag.get(key);
        return (v instanceof Number n) ? n.intValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getCompound(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return (v instanceof Map) ? (Map<String, Object>) v : null;
    }

    public static byte[] getBytes(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return (v instanceof byte[] b) ? b : null;
    }

    public static int[] getInts(Map<String, Object> tag, String key) {
        Object v = tag.get(key);
        return (v instanceof int[] a) ? a : null;
    }
}
