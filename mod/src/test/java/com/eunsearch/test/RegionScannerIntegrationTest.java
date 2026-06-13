package com.eunsearch.test;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class RegionScannerIntegrationTest {

    private static final int SECTOR_SIZE = 4096;
    private static final int REGION_WIDTH = 32;

    @Test
    void testCreateAndReadRegionFile() throws Exception {
        Path tmpDir = Files.createTempDirectory("eun_search_region_test");
        Path regionDir = tmpDir.resolve("region");
        Files.createDirectories(regionDir);
        Path regionFile = regionDir.resolve("r.0.0.mca");

        NbtCompound chunkNbt = createTestChunkNbt(5, 5);
        byte[] compressed = compressNbt(chunkNbt);
        writeRegionFile(regionFile, 0, 0, new byte[][]{compressed});

        assertTrue(Files.exists(regionFile));
        assertTrue(Files.size(regionFile) > 0);

        NbtCompound readBack = readChunkFromRegion(regionFile, 0, 0);
        assertNotNull(readBack, "chunk NBT should be readable");

        assertTrue(readBack.contains("block_entities"), "should have block_entities");
        NbtList entities = readBack.getList("block_entities").orElse(new NbtList());
        assertEquals(2, entities.size(), "should have 2 block entities");

        NbtCompound chest = entities.getCompound(0).orElse(null);
        assertNotNull(chest);
        assertEquals("minecraft:chest", chest.getString("id").orElse(""), "first entity should be chest");

        assertTrue(chest.contains("Items"), "chest should have Items");
        NbtList items = chest.getList("Items").orElse(new NbtList());
        assertTrue(items.size() > 0, "chest should have at least one item");

        NbtCompound firstItem = items.getCompound(0).orElse(null);
        assertNotNull(firstItem);
        assertEquals("minecraft:diamond", firstItem.getString("id").orElse(""), "first item should be diamond");

        cleanup(tmpDir);
    }

    @Test
    void testNbtContainsAndGetters() {
        NbtCompound cmp = new NbtCompound();
        cmp.putString("id", "minecraft:chest");
        cmp.putInt("x", 10);
        cmp.putInt("y", 64);
        cmp.putInt("z", 20);

        assertTrue(cmp.contains("id"));
        assertFalse(cmp.contains("nonexistent"));
        assertEquals("minecraft:chest", cmp.getString("id").orElse(""));
        assertEquals(10, cmp.getInt("x").orElse(-1));
        assertEquals(64, cmp.getInt("y").orElse(-1));
    }

    @Test
    void testNbtListCompoundAccess() {
        NbtList list = new NbtList();
        NbtCompound a = new NbtCompound();
        a.putString("id", "minecraft:diamond");
        a.putByte("Count", (byte) 64);
        list.add(a);

        NbtCompound b = new NbtCompound();
        b.putString("id", "minecraft:iron_ingot");
        b.putByte("Count", (byte) 32);
        list.add(b);

        assertEquals(2, list.size());
        NbtCompound first = list.getCompound(0).orElse(null);
        assertNotNull(first);
        assertEquals("minecraft:diamond", first.getString("id").orElse(""));
        assertEquals(64, (int) first.getByte("Count").orElse((byte) 0));
    }

    @Test
    void testContainerItemsDetection() {
        NbtCompound be = new NbtCompound();
        be.putString("id", "minecraft:barrel");
        be.putInt("x", 0);
        be.putInt("y", 64);
        be.putInt("z", 0);

        NbtList items = new NbtList();
        NbtCompound slot = new NbtCompound();
        slot.putString("id", "minecraft:carrot");
        slot.putByte("Count", (byte) 32);
        slot.putByte("Slot", (byte) 0);
        items.add(slot);
        be.put("Items", items);

        assertTrue(be.contains("Items"));
        NbtList read = be.getList("Items").orElse(new NbtList());
        assertEquals(1, read.size());

        NbtCompound item = read.getCompound(0).orElse(null);
        assertNotNull(item);
        assertEquals("minecraft:carrot", item.getString("id").orElse(""));
    }

    @Test
    void testNewItemFormat() {
        NbtCompound entry = new NbtCompound();
        entry.putByte("slot", (byte) 0);
        NbtCompound itemTag = new NbtCompound();
        itemTag.putString("id", "minecraft:diamond");
        itemTag.putInt("count", 64);
        NbtCompound components = new NbtCompound();
        components.putString("minecraft:custom_data", "test");
        itemTag.put("components", components);
        entry.put("item", itemTag);

        assertTrue(entry.contains("item"));
        NbtCompound read = entry.getCompound("item").orElse(null);
        assertNotNull(read);
        assertEquals("minecraft:diamond", read.getString("id").orElse(""));
        assertEquals(64, read.getInt("count").orElse(-1));
        assertTrue(read.contains("components"));
    }

    @Test
    void testOldItemFormat() {
        NbtCompound entry = new NbtCompound();
        entry.putString("id", "minecraft:apple");
        entry.putByte("Count", (byte) 16);
        entry.putByte("Slot", (byte) 4);

        assertTrue(entry.contains("id"));
        assertEquals("minecraft:apple", entry.getString("id").orElse(""));
        assertEquals(16, (int) entry.getByte("Count").orElse((byte) 0));
    }

    @Test
    void testShulkerBoxNested() {
        NbtCompound be = new NbtCompound();
        be.putString("id", "minecraft:red_shulker_box");
        be.putInt("x", 10);
        be.putInt("y", 64);
        be.putInt("z", 10);

        NbtList items = new NbtList();
        NbtCompound slot = new NbtCompound();
        slot.putString("id", "minecraft:carrot");
        slot.putByte("Count", (byte) 10);
        items.add(slot);
        be.put("Items", items);

        assertTrue(be.contains("Items"));
        NbtList readItems = be.getList("Items").orElse(new NbtList());
        assertEquals(1, readItems.size());

        NbtCompound carrot = readItems.getCompound(0).orElse(null);
        assertNotNull(carrot);
        assertEquals("minecraft:carrot", carrot.getString("id").orElse(""));
    }

    @Test
    void testScanEntryLogic() {
        com.eunsearch.config.ScanEntry e = new com.eunsearch.config.ScanEntry(
                "test", "minecraft:stone", -50, 0, -50, 50, 128, 50, "minecraft:overworld");

        assertTrue(e.coversPosition(0, 64, 0), "covers center");
        assertTrue(e.coversPosition(-50, 0, -50), "covers min corner");
        assertTrue(e.coversPosition(50, 128, 50), "covers max corner");
        assertFalse(e.coversPosition(-51, 0, 0), "outside x1");
        assertFalse(e.coversPosition(0, -1, 0), "outside y1");
        assertFalse(e.coversPosition(0, 0, 51), "outside z2");
    }

    // --- Helpers ---

    static NbtCompound createTestChunkNbt(int cx, int cz) {
        NbtCompound root = new NbtCompound();
        root.putInt("xPos", cx);
        root.putInt("zPos", cz);

        NbtList blockEntities = new NbtList();

        NbtCompound chest = new NbtCompound();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", cx * 16 + 5);
        chest.putInt("y", 64);
        chest.putInt("z", cz * 16 + 5);
        NbtList chestItems = new NbtList();
        NbtCompound item1 = new NbtCompound();
        item1.putString("id", "minecraft:diamond");
        item1.putByte("Count", (byte) 32);
        item1.putByte("Slot", (byte) 0);
        chestItems.add(item1);
        chest.put("Items", chestItems);
        blockEntities.add(chest);

        NbtCompound barrel = new NbtCompound();
        barrel.putString("id", "minecraft:barrel");
        barrel.putInt("x", cx * 16 + 10);
        barrel.putInt("y", 64);
        barrel.putInt("z", cz * 16 + 5);
        NbtList barrelItems = new NbtList();
        NbtCompound item2 = new NbtCompound();
        item2.putString("id", "minecraft:iron_ingot");
        item2.putByte("Count", (byte) 16);
        item2.putByte("Slot", (byte) 0);
        barrelItems.add(item2);
        barrel.put("Items", barrelItems);
        blockEntities.add(barrel);

        root.put("block_entities", blockEntities);
        return root;
    }

    static byte[] compressNbt(NbtCompound nbt) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(baos))) {
            NbtIo.write(nbt, dos);
        }
        return baos.toByteArray();
    }

    static void writeRegionFile(Path path, int rx, int rz, byte[][] chunkData) throws IOException {
        byte[] header = new byte[SECTOR_SIZE * 2];
        int[] chunkOffsets = new int[chunkData.length];
        int currentSector = 2;

        for (int i = 0; i < chunkData.length; i++) {
            int dataLen = chunkData[i].length + 1;
            int sectors = (dataLen + 4 + SECTOR_SIZE - 1) / SECTOR_SIZE;
            chunkOffsets[i] = currentSector;
            int entryPos = i * 4;
            header[entryPos] = (byte) (currentSector >> 16);
            header[entryPos + 1] = (byte) (currentSector >> 8);
            header[entryPos + 2] = (byte) currentSector;
            header[entryPos + 3] = (byte) sectors;
            currentSector += sectors;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.write(header);
            for (int i = 0; i < chunkData.length; i++) {
                raf.seek((long) chunkOffsets[i] * SECTOR_SIZE);
                raf.writeInt(chunkData[i].length + 1);
                raf.writeByte(1);
                raf.write(chunkData[i]);
            }
        }
    }

    static NbtCompound readChunkFromRegion(Path regionFile, int cx, int cz) throws IOException {
        byte[] header = new byte[SECTOR_SIZE * 2];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            raf.readFully(header);
            int entryIndex = (cx + cz * REGION_WIDTH) * 4;
            int offset = ((header[entryIndex] & 0xFF) << 16)
                    | ((header[entryIndex + 1] & 0xFF) << 8)
                    | (header[entryIndex + 2] & 0xFF);
            if (offset == 0) return null;

            raf.seek((long) offset * SECTOR_SIZE);
            int length = raf.readInt();
            byte compressionType = raf.readByte();
            byte[] compressed = new byte[length - 1];
            raf.readFully(compressed);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // compression type 1 = GZip, 2 = Zlib
            if (compressionType == 1) {
                try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    gz.transferTo(out);
                }
            } else if (compressionType == 2) {
                try (java.util.zip.InflaterInputStream inf = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(compressed))) {
                    inf.transferTo(out);
                }
            } else {
                out.write(compressed);
            }

            Path tmp = Files.createTempFile("eun_search_test_", ".nbt");
            try {
                Files.write(tmp, out.toByteArray());
                return NbtIo.read(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    static void cleanup(Path dir) {
        try {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
