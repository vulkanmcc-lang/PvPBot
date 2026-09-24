package com.pvpbot.ai;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;

public final class ShulkerIO {
    private ShulkerIO() {
    }

    public static boolean write(Block block, ItemStack[] contents) {
        if (block == null || contents == null) return false;

        try {
            BlockState st = block.getState();
            if (!(st instanceof Container c)) return false;

            try {
                c.getSnapshotInventory().setContents(fit(contents,
                        c.getSnapshotInventory().getSize()));
            } catch (Throwable t) {
                c.getInventory().setContents(fit(contents, c.getInventory().getSize()));
                return true;
            }
            c.update(true, false);

            if (!isEmpty(block)) return true;

            BlockState live = block.getState();
            if (live instanceof Container lc) {
                lc.getInventory().setContents(fit(contents, lc.getInventory().getSize()));
                return !isEmpty(block);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static ItemStack add(Block block, ItemStack stack) {
        if (block == null || stack == null) return stack;
        try {
            BlockState st = block.getState();
            if (!(st instanceof Container c)) return stack;

            java.util.HashMap<Integer, ItemStack> left;
            try {
                left = c.getSnapshotInventory().addItem(stack);
                c.update(true, false);
            } catch (Throwable t) {
                left = c.getInventory().addItem(stack);
            }
            return left.isEmpty() ? null : left.values().iterator().next();
        } catch (Throwable ignored) {
            return stack;
        }
    }

    public static ItemStack[] read(Block block) {
        if (block == null) return null;
        try {
            BlockState st = block.getState();
            if (!(st instanceof Container c)) return null;

            ItemStack[] src = c.getInventory().getContents();
            ItemStack[] copy = new ItemStack[src.length];
            for (int i = 0; i < src.length; i++) {
                copy[i] = src[i] == null ? null : src[i].clone();
            }
            return copy;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isEmpty(Block block) {
        try {
            BlockState st = block.getState();
            if (!(st instanceof Container c)) return true;
            for (ItemStack s : c.getInventory().getContents()) {
                if (s != null && !s.getType().isAir()) return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean openVisual(Block block) {
        if (block == null) return false;
        try {
            BlockState st = block.getState();
            if (st instanceof org.bukkit.block.Lidded lidded) {
                lidded.open();
                return true;
            }
            org.bukkit.block.data.BlockData bd = block.getBlockData();
            if (bd instanceof org.bukkit.block.data.Openable openable) {
                openable.setOpen(true);
                block.setBlockData(bd, true);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean closeVisual(Block block) {
        if (block == null) return false;
        try {
            BlockState st = block.getState();
            if (st instanceof org.bukkit.block.Lidded lidded) {
                lidded.close();
                return true;
            }
            org.bukkit.block.data.BlockData bd = block.getBlockData();
            if (bd instanceof org.bukkit.block.data.Openable openable) {
                openable.setOpen(false);
                block.setBlockData(bd, true);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ItemStack[] fit(ItemStack[] contents, int size) {
        if (contents.length == size) return contents;
        ItemStack[] out = new ItemStack[size];
        System.arraycopy(contents, 0, out, 0, Math.min(size, contents.length));
        return out;
    }
}
