package com.eunsearch.scan;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStateScanTest {
    @Test
    void negativeHeightUsesSectionYAndActualPaletteIndex() throws Exception {
        NbtCompound chunk = chunk(-4, palette(false), 15, 15, 15, 2);
        assertEquals("minecraft:blue_shulker_box", color(chunk, -1, -49, -1));
        assertEquals("minecraft:red_shulker_box", color(chunk, 0, -64, 0));
    }

    @Test
    void stringPaletteKeepsTheSelectedColorWithoutProperties() throws Exception {
        NbtCompound chunk = chunk(4, palette(true), 1, 2, 3, 2);
        assertEquals("minecraft:blue_shulker_box", color(chunk, 1, 66, 3));
    }

    @Test
    void singleEntryPaletteDoesNotRequirePackedData() throws Exception {
        NbtList palette = new NbtList();
        palette.add(NbtString.of("minecraft:green_shulker_box[facing=up]"));
        assertEquals("minecraft:green_shulker_box", color(chunk(20, palette, 0, 0, 0, 0), 0, 320, 0));
    }

    @Test
    void missingOrUnrelatedStateFallsBackToUncoloredShulker() throws Exception {
        assertEquals("minecraft:shulker_box", color(new NbtCompound(), 0, 64, 0));
        NbtList palette = new NbtList();
        palette.add(NbtString.of("minecraft:stone"));
        assertEquals("minecraft:shulker_box", color(chunk(4, palette, 0, 0, 0, 0), 0, 64, 0));
    }

    @Test
    void chestPropertiesStillUseTheExactPackedState() throws Exception {
        NbtList palette = new NbtList();
        palette.add(NbtString.of("minecraft:air"));
        palette.add(NbtString.of("minecraft:chest[facing=south,type=right,waterlogged=false]"));
        NbtCompound chunk = chunk(-1, palette, 3, 15, 5, 1);
        Method method = RegionScanner.class.getDeclaredMethod("readChestType", NbtCompound.class, int.class, int.class, int.class);
        method.setAccessible(true);
        assertEquals("right", method.invoke(null, chunk, 3, -1, 5));
    }

    private static NbtList palette(boolean strings) {
        NbtList palette = new NbtList();
        for (String id : new String[] { "minecraft:red_shulker_box", "minecraft:air", "minecraft:blue_shulker_box" }) {
            NbtElement entry;
            if (strings) {
                entry = NbtString.of(id + "[facing=up]");
            } else {
                NbtCompound compound = new NbtCompound();
                compound.putString("Name", id);
                entry = compound;
            }
            palette.add(entry);
        }
        return palette;
    }

    private static NbtCompound chunk(int sectionY, NbtList palette, int x, int y, int z, int selected) {
        NbtCompound states = new NbtCompound();
        states.put("palette", palette);
        if (palette.size() > 1) {
            long[] data = new long[256];
            int index = y * 256 + z * 16 + x;
            data[index / 16] = (long) selected << ((index % 16) * 4);
            states.putLongArray("data", data);
        }
        NbtCompound section = new NbtCompound();
        section.putByte("Y", (byte) sectionY);
        section.put("block_states", states);
        NbtList sections = new NbtList();
        sections.add(section);
        NbtCompound chunk = new NbtCompound();
        chunk.put("sections", sections);
        return chunk;
    }

    private static String color(NbtCompound chunk, int x, int y, int z) throws Exception {
        Method method = RegionScanner.class.getDeclaredMethod("readShulkerColor", NbtCompound.class, int.class, int.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, chunk, x, y, z);
    }
}
