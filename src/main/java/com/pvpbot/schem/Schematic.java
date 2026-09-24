package com.pvpbot.schem;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class Schematic {
    public final String name;
    public final int width, height, length;
    public final int offsetX, offsetY, offsetZ;

    public final BlockData[] palette;

    public final int[] blocks;

    public final int unknownStates;

    private Map<Material, Integer> materialCache;

    private Schematic(String name, int width, int height, int length,
                      int offsetX, int offsetY, int offsetZ,
                      BlockData[] palette, int[] blocks, int unknownStates) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.length = length;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.palette = palette;
        this.blocks = blocks;
        this.unknownStates = unknownStates;
    }

    public int volume() {
        return width * height * length;
    }

    public BlockData at(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) return null;
        int idx = blocks[(y * length + z) * width + x];
        return (idx >= 0 && idx < palette.length) ? palette[idx] : null;
    }

    public Map<Material, Integer> materials() {
        if (materialCache != null) return materialCache;

        Map<Material, Integer> counts = new LinkedHashMap<>();
        int[] perIndex = new int[palette.length];
        for (int idx : blocks) {
            if (idx >= 0 && idx < perIndex.length) perIndex[idx]++;
        }
        for (int i = 0; i < palette.length; i++) {
            if (perIndex[i] == 0 || palette[i] == null) continue;
            Material m = palette[i].getMaterial();
            if (m.isAir()) continue;
            counts.merge(m, perIndex[i], Integer::sum);
        }
        materialCache = counts;
        return counts;
    }

    public static Schematic load(File file) throws IOException {
        Map<String, Object> root = Nbt.read(file);

        Map<String, Object> inner = Nbt.getCompound(root, "Schematic");
        if (inner != null) root = inner;

        int width = Nbt.getInt(root, "Width", 0) & 0xFFFF;
        int height = Nbt.getInt(root, "Height", 0) & 0xFFFF;
        int length = Nbt.getInt(root, "Length", 0) & 0xFFFF;
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IOException("bad dimensions " + width + "x" + height + "x" + length);
        }

        long volume = (long) width * height * length;
        if (volume > 4_000_000L) {
            throw new IOException("schematic is " + volume + " blocks; the cap is 4,000,000");
        }

        Map<String, Object> paletteTag = Nbt.getCompound(root, "Palette");
        byte[] data = Nbt.getBytes(root, "BlockData");

        Map<String, Object> blocksTag = Nbt.getCompound(root, "Blocks");
        if (blocksTag != null) {
            if (paletteTag == null) paletteTag = Nbt.getCompound(blocksTag, "Palette");
            if (data == null) data = Nbt.getBytes(blocksTag, "Data");
        }

        if (paletteTag == null) throw new IOException("no Palette — is this a Sponge schematic?");
        if (data == null) throw new IOException("no BlockData");

        int max = -1;
        for (Object v : paletteTag.values()) {
            if (v instanceof Number n) max = Math.max(max, n.intValue());
        }
        if (max < 0) throw new IOException("palette is empty");

        BlockData[] palette = new BlockData[max + 1];
        int unknown = 0;
        BlockData air = Bukkit.createBlockData(Material.AIR);

        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            if (!(e.getValue() instanceof Number n)) continue;
            int idx = n.intValue();
            if (idx < 0 || idx > max) continue;
            try {
                palette[idx] = Bukkit.createBlockData(e.getKey());
            } catch (Throwable t) {
                palette[idx] = air;
                unknown++;
            }
        }
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) palette[i] = air;
        }

        int[] blocks = decodeVarints(data, (int) volume);

        int[] off = Nbt.getInts(root, "Offset");
        int ox = (off != null && off.length >= 3) ? off[0] : 0;
        int oy = (off != null && off.length >= 3) ? off[1] : 0;
        int oz = (off != null && off.length >= 3) ? off[2] : 0;

        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);

        return new Schematic(name, width, height, length, ox, oy, oz, palette, blocks, unknown);
    }

    private static int[] decodeVarints(byte[] data, int expected) throws IOException {
        int[] out = new int[expected];
        int index = 0;
        int i = 0;

        while (index < expected) {
            if (i >= data.length) {
                throw new IOException("BlockData ended after " + index + " of " + expected + " blocks");
            }
            int value = 0;
            int shift = 0;
            while (true) {
                if (i >= data.length) throw new IOException("truncated varint in BlockData");
                if (shift > 28) throw new IOException("varint too long in BlockData");
                byte b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            out[index++] = value;
        }
        return out;
    }
}
