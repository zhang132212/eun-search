package com.eunsearch.scan;

import com.eunsearch.EunSearchMod;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public class RegionScanner {

    private static final Set<String> EXCLUDED_IDS = Set.of(
            "minecraft:ender_chest");

    private static final int SECTOR_SIZE = 4096;
    private static final int REGION_WIDTH = 32;
    private static final Set<String> CONTAINER_IDS = Set.of(
            "minecraft:chest", "minecraft:trapped_chest",
            "minecraft:barrel", "minecraft:hopper",
            "minecraft:dispenser", "minecraft:dropper",
            "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "minecraft:brewing_stand",
            "minecraft:shulker_box",
            "minecraft:white_shulker_box", "minecraft:orange_shulker_box",
            "minecraft:magenta_shulker_box", "minecraft:light_blue_shulker_box",
            "minecraft:yellow_shulker_box", "minecraft:lime_shulker_box",
            "minecraft:pink_shulker_box", "minecraft:gray_shulker_box",
            "minecraft:light_gray_shulker_box", "minecraft:cyan_shulker_box",
            "minecraft:purple_shulker_box", "minecraft:blue_shulker_box",
            "minecraft:brown_shulker_box", "minecraft:green_shulker_box",
            "minecraft:red_shulker_box", "minecraft:black_shulker_box",
            "minecraft:decorated_pot");

    public static ScanResult scan(MinecraftServer server, String dimension,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            List<String> targetItems) throws IOException {

        Path regionDir = getRegionDir(server, dimension);
        if (!Files.exists(regionDir)) {
            throw new IOException("区域文件目录不存在: " + regionDir);
        }

        int minRegionX = minX >> 9;
        int minRegionZ = minZ >> 9;
        int maxRegionX = maxX >> 9;
        int maxRegionZ = maxZ >> 9;

        int minChunkX = minX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkX = maxX >> 4;
        int maxChunkZ = maxZ >> 4;

        ScanResult result = new ScanResult();
        result.items = targetItems;

        // Pass 1: scan original region
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                String fileName = "r." + rx + "." + rz + ".mca";
                Path regionFile = regionDir.resolve(fileName);
                if (!Files.exists(regionFile))
                    continue;

                scanRegionFile(regionFile, rx, rz,
                        minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                        minX, minY, minZ, maxX, maxY, maxZ,
                        targetItems, result, false);
            }
        }

        // Pass 2: if orphans exist, scan ALL chunks in expanded range (don't skip originals)
        if (!result.orphans.isEmpty()) {
            int exMinChunkX = minChunkX - 1, exMinChunkZ = minChunkZ - 1;
            int exMaxChunkX = maxChunkX + 1, exMaxChunkZ = maxChunkZ + 1;
            int exMinRegionX = exMinChunkX >> 5, exMinRegionZ = exMinChunkZ >> 5;
            int exMaxRegionX = exMaxChunkX >> 5, exMaxRegionZ = exMaxChunkZ >> 5;
            for (int rx = exMinRegionX; rx <= exMaxRegionX; rx++) {
                for (int rz = exMinRegionZ; rz <= exMaxRegionZ; rz++) {
                    String fileName = "r." + rx + "." + rz + ".mca";
                    Path regionFile = regionDir.resolve(fileName);
                    if (!Files.exists(regionFile))
                        continue;
                scanRegionFile(regionFile, rx, rz,
                        exMinChunkX, exMinChunkZ, exMaxChunkX, exMaxChunkZ,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        targetItems, result, true);
                }
            }
        }

        result.calculatePercentages();
        return result;
    }

    private static void scanRegionFile(Path regionFile, int regionX, int regionZ,
            int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            List<String> targetItems, ScanResult result, boolean pass2) throws IOException {

        byte[] header = new byte[SECTOR_SIZE * 2];

        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (raf.length() < header.length)
                return;
            raf.readFully(header);

            for (int cz = 0; cz < REGION_WIDTH; cz++) {
                for (int cx = 0; cx < REGION_WIDTH; cx++) {
                    int chunkX = (regionX << 5) + cx;
                    int chunkZ = (regionZ << 5) + cz;

                    if (chunkX < minChunkX || chunkX > maxChunkX
                            || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
                        continue;
                    }

                    int entryIndex = cx + cz * REGION_WIDTH;
                    int locationOffset = entryIndex * 4;

                    int offset = ((header[locationOffset] & 0xFF) << 16)
                            | ((header[locationOffset + 1] & 0xFF) << 8)
                            | (header[locationOffset + 2] & 0xFF);
                    int sectorCount = header[locationOffset + 3] & 0xFF;

                    if (offset == 0 || sectorCount == 0)
                        continue;

                    try {
                        raf.seek((long) offset * SECTOR_SIZE);
                        int length = raf.readInt();
                        if (length <= 0 || length > SECTOR_SIZE * sectorCount)
                            continue;

                        byte compressionType = raf.readByte();
                        byte[] compressed = new byte[length - 1];
                        raf.readFully(compressed);

                        byte[] decompressed = decompress(compressed, compressionType);
                        if (decompressed == null)
                            continue;

                        Path tempFile = Files.createTempFile("eun_search_chunk_", ".nbt");
                        try {
                            Files.write(tempFile, decompressed);
                            NbtCompound chunkNbt = NbtIo.read(tempFile);
                            processChunkNbt(chunkNbt, minX, minY, minZ, maxX, maxY, maxZ,
                                    targetItems, result, pass2);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }

                    } catch (Exception e) {
                        EunSearchMod.LOGGER.debug("[EunSearch] 读取chunk ({},{}) 失败: {}",
                                chunkX, chunkZ, e.getMessage());
                    }
                }
            }
        }
    }

    private static void processChunkNbt(NbtCompound chunkNbt,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            List<String> targetItems, ScanResult result, boolean pass2) {

        if (!chunkNbt.contains("block_entities"))
            return;

        NbtList blockEntities = chunkNbt.getList("block_entities").orElse(new NbtList());

        for (int i = 0; i < blockEntities.size(); i++) {
            NbtCompound be = blockEntities.getCompound(i).orElse(null);
            if (be == null)
                continue;

            String id = be.getString("id").orElse("");
            if (EXCLUDED_IDS.contains(id))
                continue;
            if (!CONTAINER_IDS.contains(id))
                continue;

            int x = be.getInt("x").orElse(0);
            int y = be.getInt("y").orElse(0);
            int z = be.getInt("z").orElse(0);

            if (x < minX || x > maxX || y < minY || y > maxY
                    || z < minZ || z > maxZ) {
                continue;
            }

            result.allContainerPositions.add(new BlockPos(x, y, z));
            String typeId = id;

            // 双箱子: 读 block state 的 type 属性
            NbtList partnerItems = null;
            int partnerX = 0, partnerZ = 0;
            int a1SlotOffset = 0;
            int a2SlotOffset = 0;
            boolean isChest = id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest");
            String chestType = "single";
            String chestFacing = "north";
            if (isChest) {
                chestType = readChestType(chunkNbt, x, y, z);
                chestFacing = readChestFacing(chunkNbt, x, y, z);
                a1SlotOffset = chestType.equals("left") ? 27 : 0;
                if (chestType.equals("right")) {
                    result.containerTypeMap.put(x + "," + y + "," + z, typeId);
                    String rFacing = chestFacing;
                    int lx = x, lz = z;
                    switch (rFacing) {
                        case "north" -> lx = x - 1;
                        case "south" -> lx = x + 1;
                        case "east" -> lz = z - 1;
                        case "west" -> lz = z + 1;
                    }
                    boolean leftInChunk = false;
                    for (int k = 0; k < blockEntities.size(); k++) {
                        NbtCompound other = blockEntities.getCompound(k).orElse(null);
                        if (other != null && other.getString("id").orElse("").equals(id)
                                && other.getInt("x").orElse(0) == lx && other.getInt("y").orElse(0) == y
                                && other.getInt("z").orElse(0) == lz) {
                            leftInChunk = true; break;
                        }
                    }
                    if (leftInChunk) continue;
                }
                if (chestType.equals("left")) {
                    int px = x, pz = z;
                    switch (chestFacing) {
                        case "north" -> px = x + 1;
                        case "south" -> px = x - 1;
                        case "east" -> pz = z + 1;
                        case "west" -> pz = z - 1;
                    }
                    for (int k = 0; k < blockEntities.size(); k++) {
                        NbtCompound other = blockEntities.getCompound(k).orElse(null);
                        if (other == null || !other.getString("id").orElse("").equals(id))
                            continue;
                        if (other.getInt("x").orElse(0) == px && other.getInt("y").orElse(0) == y
                                && other.getInt("z").orElse(0) == pz) {
                            partnerItems = other.getList("Items").orElse(new NbtList());
                            partnerX = px;
                            partnerZ = pz;
                            a1SlotOffset = 27;
                            a2SlotOffset = 0;
                            break;
                        }
                    }
                }
            }

            // Pass 2: match orphans only
            if (pass2) {
                if (!(id.equals("minecraft:chest") || id.equals("minecraft:trapped_chest"))) continue;
                // For each orphan, check if this block entity is the partner
                for (int oi = 0; oi < result.orphans.size(); oi++) {
                    var orphan = result.orphans.get(oi);
                    boolean match = false;
                    if (orphan.isLeft) {
                        // Left orphan's partner is at clockwise direction
                        switch (orphan.facing) {
                            case "north" -> match = (x == orphan.x + 1 && z == orphan.z);
                            case "south" -> match = (x == orphan.x - 1 && z == orphan.z);
                            case "east" -> match = (x == orphan.x && z == orphan.z + 1);
                            case "west" -> match = (x == orphan.x && z == orphan.z - 1);
                        }
                    } else {
                        // Right orphan's partner is at counter-clockwise direction
                        switch (orphan.facing) {
                            case "north" -> match = (x == orphan.x - 1 && z == orphan.z);
                            case "south" -> match = (x == orphan.x + 1 && z == orphan.z);
                            case "east" -> match = (x == orphan.x && z == orphan.z - 1);
                            case "west" -> match = (x == orphan.x && z == orphan.z + 1);
                        }
                    }
                    if (match && y == orphan.y && id.equals(orphan.id)) {
                        var container = result.containers.get(orphan.orphanIndex);

                        // Check if partner is also an orphan (other half of same double chest)
                        int partnerOi = -1;
                        for (int k = 0; k < result.orphans.size(); k++) {
                            var o2 = result.orphans.get(k);
                            if (o2.x == x && o2.y == y && o2.z == z && o2.id.equals(id)) {
                                partnerOi = k; break;
                            }
                        }
                        if (partnerOi >= 0) {
                            // Merge the other orphan's container into this one
                            int partnerIdx = result.orphans.get(partnerOi).orphanIndex;
                            var partnerContainer = result.containers.get(partnerIdx);
                            container.targetCount += partnerContainer.targetCount;
                            container.directCount += partnerContainer.directCount;
                            container.targetSlots += partnerContainer.targetSlots;
                            container.totalSlots = Math.max(container.totalSlots, 54);
                            container.shulkerSlots.addAll(partnerContainer.shulkerSlots);

                            // Remove partner container and orphan
                            result.containers.remove(partnerIdx);
                            // Adjust orphanIndex for remaining orphans
                            for (var o : result.orphans) {
                                if (o.orphanIndex > partnerIdx) o.orphanIndex--;
                            }
                            if (orphan.orphanIndex > partnerIdx) orphan.orphanIndex--;
                            result.orphans.remove(partnerOi);
                            if (partnerOi < oi) oi--;
                        }

                        container.isDoubleChest = true;
                        container.partnerX = x;
                        container.partnerZ = z;
                        result.orphans.remove(oi);
                        break;
                    }
                }
                continue; // pass2: don't add to containers or do normal processing
            }
            if (id.equals("minecraft:shulker_box")) {
                typeId = readShulkerColor(chunkNbt, x, y, z);
            }
            result.containerTypeMap.put(x + "," + y + "," + z, typeId);

            NbtList items = be.contains("Items") ? be.getList("Items").orElse(new NbtList()) : new NbtList();
            if (items.isEmpty() && partnerItems == null)
                continue;
            if (items.isEmpty() && partnerItems != null && partnerItems.isEmpty())
                continue;

            int baseSlots = getContainerCapacity(id, be);

            int shulkerBoxesInContainer = 0;
            if (isChest) EunSearchMod.LOGGER.warn("[EunSearch] Processing chest({},{},{}) type={} a1={} a2={} pNull={}", x, y, z, chestType, a1SlotOffset, a2SlotOffset, partnerItems == null);

            int[] totalCounts = new int[targetItems.size()];
            int[] directCounts = new int[targetItems.size()];
            int[] targetSlots = new int[targetItems.size()];
            List<int[]> shulkerSlotData = new ArrayList<>(); // {slotIndex, itemIndex, count}

            for (int j = 0; j < items.size(); j++) {
                NbtCompound entry = items.getCompound(j).orElse(null);
                if (entry == null)
                    continue;

                ItemData itemData = readItemFromSlot(entry);
                if (itemData == null)
                    continue;

                boolean isShulker = itemData.id != null && itemData.id.contains("shulker_box");

                if (isShulker) {
                    shulkerBoxesInContainer++;
                }

                if (isShulker && itemData.components != null) {
                    for (int t = 0; t < targetItems.size(); t++) {
                        int[] shulkerCount = countInShulkerBox(itemData.components, targetItems.get(t));
                        if (shulkerCount[1] > 0) {
                            int actualSlot = getSlot(entry, j) + a1SlotOffset;
                            targetSlots[t] += shulkerCount[0];
                            totalCounts[t] += shulkerCount[1];
                            shulkerSlotData.add(new int[] { actualSlot, t, shulkerCount[1] });
                            result.shulkerContributions.add(new int[] { x, y, z, t });
                        }
                    }
                }

                for (int t = 0; t < targetItems.size(); t++) {
                    if (itemData.id != null && matchesItem(itemData.id, targetItems.get(t))) {
                        targetSlots[t]++;
                        totalCounts[t] += Math.max(1, itemData.count);
                        directCounts[t] += Math.max(1, itemData.count);
                    }
                }
            }
            if (partnerItems != null) {
                for (int j = 0; j < partnerItems.size(); j++) {
                    NbtCompound entry = partnerItems.getCompound(j).orElse(null);
                    if (entry == null)
                        continue;
                    ItemData itemData = readItemFromSlot(entry);
                    if (itemData == null)
                        continue;
                    boolean isShulker = itemData.id != null && itemData.id.contains("shulker_box");
                    if (isShulker)
                        shulkerBoxesInContainer++;
                    if (isShulker && itemData.components != null) {
                        for (int t = 0; t < targetItems.size(); t++) {
                            int[] sc = countInShulkerBox(itemData.components, targetItems.get(t));
                            if (sc[1] > 0) {
                                int slot = getSlot(entry, j) + a2SlotOffset;
                                targetSlots[t] += sc[0];
                                totalCounts[t] += sc[1];
                                shulkerSlotData.add(new int[] { slot, t, sc[1] });
                                result.shulkerContributions.add(new int[] { x, y, z, t });
                            }
                        }
                    }
                    for (int t = 0; t < targetItems.size(); t++) {
                        if (itemData.id != null && matchesItem(itemData.id, targetItems.get(t))) {
                            targetSlots[t]++;
                            totalCounts[t] += Math.max(1, itemData.count);
                            directCounts[t] += Math.max(1, itemData.count);
                        }
                    }
                }
            }
            int totalSlots = baseSlots - shulkerBoxesInContainer + shulkerBoxesInContainer * 27;
            if (totalSlots <= 0)
                continue;

            result.totalContainers++;
            result.totalSlots += totalSlots;

            for (int t = 0; t < targetItems.size(); t++) {
                if (totalCounts[t] > 0) {
                    PerItemResult pir = result.getOrCreateItemResult(t, targetItems);
                    pir.totalCount += totalCounts[t];
                    pir.slotsWithTarget += targetSlots[t];

                    ContainerInfo info = new ContainerInfo();
                    info.x = x;
                    info.y = y;
                    info.z = z;
                    info.containerType = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    info.blockId = id.equals("minecraft:shulker_box") ? readShulkerColor(chunkNbt, x, y, z) : id;
                    info.totalSlots = totalSlots;
                    info.targetSlots = targetSlots[t];
                    info.targetCount = totalCounts[t];
                    info.directCount = directCounts[t];
                    info.itemIndex = t;
                    if (partnerItems != null) {
                        info.isDoubleChest = true;
                        info.partnerX = partnerX;
                        info.partnerZ = partnerZ;
                    }
                    for (int[] sd : shulkerSlotData) {
                        if (sd[1] == t)
                            info.shulkerSlots.add(new int[] { sd[0], sd[1], sd[2] });
                    }
                    result.containers.add(info);
                    if (isChest && !info.shulkerSlots.isEmpty()) {
                        StringBuilder sb2 = new StringBuilder();
                        for (int[] s : info.shulkerSlots) sb2.append(s[0]).append(":").append(s[2]).append(" ");
                        EunSearchMod.LOGGER.warn("[EunSearch] ContainerInfo({},{},{}) type={} isDb={} slots=[{}]", x, y, z, chestType, info.isDoubleChest, sb2.toString().trim());
                    }
                    // Record orphan in pass1
                    if (!pass2 && isChest && partnerItems == null) {
                        var orphan = new OrphanInfo();
                        orphan.orphanIndex = result.containers.size() - 1;
                        orphan.x = x; orphan.y = y; orphan.z = z;
                        orphan.id = id;
                        orphan.isLeft = chestType.equals("left");
                        orphan.facing = chestFacing;
                        result.orphans.add(orphan);
                    }
                }
            }
        }
    }

    /** 直接读 block state palette 获取 chest type: "left"/"right"/"single" */
    private static NbtCompound findSection(NbtCompound chunkNbt, int y) {
        if (!chunkNbt.contains("sections"))
            return null;
        NbtList sections = chunkNbt.getList("sections").orElse(null);
        if (sections == null)
            return null;
        int sectionY = y >> 4;
        for (int i = 0; i < sections.size(); i++) {
            NbtCompound sec = sections.getCompound(i).orElse(null);
            if (sec != null && sec.getInt("Y").orElse(Integer.MIN_VALUE) == sectionY)
                return sec;
        }
        return null;
    }

    private static String readBlockStateProperty(NbtCompound chunkNbt, int x, int y, int z, String property, String defaultValue) {
        try {
            NbtCompound section = findSection(chunkNbt, y);
            if (section == null || !section.contains("block_states"))
                return defaultValue;
            NbtCompound bs = section.getCompound("block_states").orElse(null);
            if (bs == null || !bs.contains("palette"))
                return defaultValue;
            NbtList palette = bs.getList("palette").orElse(null);
            if (palette == null || palette.isEmpty())
                return defaultValue;
            long[] data = bs.getLongArray("data").orElse(new long[0]);
            int paletteIdx = 0;
            if (palette.size() > 1) {
                if (data.length == 0)
                    return defaultValue;
                int bits = Math.max(4, 64 - Long.numberOfLeadingZeros(palette.size() - 1));
                int entriesPerLong = 64 / bits;
                int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                int longIndex = idx / entriesPerLong;
                int bitOffset = (idx % entriesPerLong) * bits;
                if (longIndex >= data.length)
                    return defaultValue;
                paletteIdx = (int) ((data[longIndex] >>> bitOffset) & ((1L << bits) - 1));
                if (paletteIdx < 0 || paletteIdx >= palette.size())
                    return defaultValue;
            }
            NbtElement pe = palette.get(paletteIdx);
            if (pe instanceof net.minecraft.nbt.NbtString ps) {
                String s = ps.asString().orElse("");
                int pi = s.indexOf(property + "=");
                if (pi >= 0) {
                    String sub = s.substring(pi + property.length() + 1);
                    int end = sub.indexOf(',');
                    if (end < 0) end = sub.indexOf(']');
                    if (end < 0) end = sub.length();
                    return sub.substring(0, end);
                }
                return defaultValue;
            }
            if (pe instanceof NbtCompound pc) {
                if (pc.contains("Properties")) {
                    NbtCompound props = pc.getCompound("Properties").orElse(null);
                    if (props != null && props.contains(property)) {
                        return props.getString(property).orElse(defaultValue);
                    }
                }
                return defaultValue;
            }
            return defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String readChestType(NbtCompound chunkNbt, int x, int y, int z) {
        return readBlockStateProperty(chunkNbt, x, y, z, "type", "single");
    }

    private static String readChestFacing(NbtCompound chunkNbt, int x, int y, int z) {
        return readBlockStateProperty(chunkNbt, x, y, z, "facing", "north");
    }

    private static int[] countInShulkerBox(NbtCompound components, String normalizedItem) {
        int[] result = new int[] { 0, 0 };

        if (components.contains("minecraft:container")) {
            NbtElement containerTag = components.get("minecraft:container");
            if (containerTag != null) {
                if (containerTag.getType() == NbtElement.LIST_TYPE) {
                    NbtList containerItems = (NbtList) containerTag;
                    for (int i = 0; i < containerItems.size(); i++) {
                        NbtCompound entry = containerItems.getCompound(i).orElse(null);
                        if (entry == null)
                            continue;
                        ItemData itemData = readItemFromSlot(entry);
                        if (itemData != null && itemData.id != null && matchesItem(itemData.id, normalizedItem)) {
                            result[0]++;
                            result[1] += Math.max(1, itemData.count);
                        }
                    }
                } else if (containerTag.getType() == NbtElement.COMPOUND_TYPE) {
                    NbtCompound containerCompound = (NbtCompound) containerTag;
                    if (containerCompound.contains("Items")) {
                        NbtList containerItems = containerCompound.getList("Items").orElse(new NbtList());
                        for (int i = 0; i < containerItems.size(); i++) {
                            NbtCompound entry = containerItems.getCompound(i).orElse(null);
                            if (entry == null)
                                continue;
                            ItemData itemData = readItemFromSlot(entry);
                            if (itemData != null && itemData.id != null && matchesItem(itemData.id, normalizedItem)) {
                                result[0]++;
                                result[1] += Math.max(1, itemData.count);
                            }
                        }
                    }
                }
            }
        }

        if (components.contains("Items")) {
            NbtList items = components.getList("Items").orElse(new NbtList());
            for (int i = 0; i < items.size(); i++) {
                NbtCompound entry = items.getCompound(i).orElse(null);
                if (entry == null)
                    continue;
                ItemData itemData = readItemFromSlot(entry);
                if (itemData != null && itemData.id != null && matchesItem(itemData.id, normalizedItem)) {
                    result[0]++;
                    result[1] += Math.max(1, itemData.count);
                }
            }
        }

        return result;
    }

    private static ItemData readItemFromSlot(NbtCompound entry) {
        if (entry.contains("item")) {
            NbtCompound itemTag = entry.getCompound("item").orElse(null);
            if (itemTag == null)
                return null;
            String id = itemTag.getString("id").orElse("");
            if (id.isEmpty())
                return null;
            ItemData data = new ItemData();
            data.id = id;
            data.count = getCount(itemTag);
            if (itemTag.contains("components")) {
                NbtElement compTag = itemTag.get("components");
                if (compTag instanceof NbtCompound comp) {
                    data.components = comp;
                }
            }
            return data;
        }

        if (entry.contains("id")) {
            String id = entry.getString("id").orElse("");
            if (id.isEmpty())
                return null;
            ItemData data = new ItemData();
            data.id = id;
            data.count = getCount(entry);
            // flat format: components at top level
            if (entry.contains("components")) {
                NbtElement ct = entry.get("components");
                if (ct instanceof NbtCompound comp)
                    data.components = comp;
            }
            // legacy: tag.BlockEntityTag
            if (entry.contains("tag")) {
                NbtCompound tag = entry.getCompound("tag").orElse(null);
                if (tag != null && tag.contains("BlockEntityTag")) {
                    NbtElement bet = tag.get("BlockEntityTag");
                    if (bet instanceof NbtCompound betc) {
                        data.components = betc;
                    }
                }
            }
            return data;
        }

        return null;
    }

    private static int getCount(NbtCompound tag) {
        if (tag.contains("count")) {
            NbtElement elem = tag.get("count");
            if (elem instanceof AbstractNbtNumber num)
                return num.intValue();
        }
        if (tag.contains("Count")) {
            NbtElement elem = tag.get("Count");
            if (elem instanceof AbstractNbtNumber num)
                return num.intValue();
        }
        return 1;
    }

    private static int getSlot(NbtCompound entry, int defaultSlot) {
        if (entry.contains("slot")) {
            NbtElement elem = entry.get("slot");
            if (elem instanceof AbstractNbtNumber num)
                return num.intValue();
        }
        if (entry.contains("Slot")) {
            NbtElement elem = entry.get("Slot");
            if (elem instanceof AbstractNbtNumber num)
                return num.intValue();
        }
        return defaultSlot;
    }

    private static int getMaxStackSize(String itemId) {
        try {
            Identifier id = Identifier.tryParse(itemId);
            if (id != null) {
                var item = Registries.ITEM.get(id);
                return item.getMaxCount();
            }
        } catch (Exception ignored) {
        }
        return 64;
    }

    private static void offsetShulkerSlots(ContainerInfo ci, int offset) {
        for (int[] s : ci.shulkerSlots) s[0] += offset;
    }

    private static int getContainerCapacity(String id, NbtCompound be) {
        switch (id) {
            case "minecraft:chest":
            case "minecraft:trapped_chest":
                if (be.contains("Items")) {
                    NbtList items = be.getList("Items").orElse(new NbtList());
                    int maxSlot = -1;
                    for (int i = 0; i < items.size(); i++) {
                        NbtCompound entry = items.getCompound(i).orElse(null);
                        if (entry == null)
                            continue;
                        int slot = getSlot(entry, i);
                        if (slot > maxSlot)
                            maxSlot = slot;
                    }
                    if (maxSlot > 26)
                        return 54;
                }
                return 27;
            case "minecraft:barrel":
                return 27;
            case "minecraft:hopper":
                return 5;
            case "minecraft:dispenser":
            case "minecraft:dropper":
                return 9;
            case "minecraft:furnace":
            case "minecraft:blast_furnace":
            case "minecraft:smoker":
                return 2;
            case "minecraft:brewing_stand":
                return 5;
            case "minecraft:decorated_pot":
                return 1;
            default:
                if (id.contains("shulker_box"))
                    return 27;
                return 27;
        }
    }

    private static boolean matchesItem(String itemId, String target) {
        if (itemId.equalsIgnoreCase(target))
            return true;
        String shortId = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String shortTarget = target.contains(":") ? target.substring(target.indexOf(':') + 1) : target;
        return shortId.equalsIgnoreCase(shortTarget);
    }

    private static String readShulkerColor(NbtCompound chunkNbt, int x, int y, int z) {
        try {
            if (!chunkNbt.contains("sections"))
                return "minecraft:shulker_box";
            NbtList sections = chunkNbt.getList("sections").orElse(null);
            if (sections == null)
                return "minecraft:shulker_box";
            int si = y >> 4;
            if (si < 0 || si >= sections.size())
                return "minecraft:shulker_box";
            NbtCompound section = sections.getCompound(si).orElse(null);
            if (section == null)
                return "minecraft:shulker_box";
            if (!section.contains("block_states"))
                return "minecraft:shulker_box";
            NbtCompound bs = section.getCompound("block_states").orElse(null);
            if (bs == null)
                return "minecraft:shulker_box";
            NbtList palette = bs.getList("palette").orElse(null);
            if (palette == null || palette.isEmpty())
                return "minecraft:shulker_box";

            // single palette entry → every block is that type
            if (palette.size() == 1) {
                NbtCompound entry = palette.getCompound(0).orElse(null);
                if (entry != null)
                    return entry.getString("Name").orElse("minecraft:shulker_box");
                return "minecraft:shulker_box";
            }

            // find the first shulker_box entry as heuristic
            for (int i = 0; i < palette.size(); i++) {
                NbtCompound entry = palette.getCompound(i).orElse(null);
                if (entry == null)
                    continue;
                String name = entry.getString("Name").orElse("");
                if (name.contains("shulker_box"))
                    return name;
            }
            return "minecraft:shulker_box";
        } catch (Exception e) {
            return "minecraft:shulker_box";
        }
    }

    private static String normalizeItemId(String item) {
        if (item == null || item.isEmpty())
            return item;
        if (!item.contains(":"))
            return "minecraft:" + item.toLowerCase(Locale.ROOT);
        return item.toLowerCase(Locale.ROOT);
    }

    private static byte[] decompress(byte[] data, byte compressionType) throws IOException {
        if (compressionType == 1) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
                gz.transferTo(out);
            }
            return out.toByteArray();
        } else if (compressionType == 2) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(data))) {
                inf.transferTo(out);
            }
            return out.toByteArray();
        } else if (compressionType == 3) {
            return data;
        }
        return null;
    }

    private static Path getRegionDir(MinecraftServer server, String dimension) throws IOException {
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);

        return switch (dimension) {
            case "minecraft:the_nether" -> {
                Path legacy = worldDir.resolve("DIM-1").resolve("region");
                if (Files.exists(legacy)) {
                    yield legacy;
                }
                Path modern = worldDir.resolve("dimensions").resolve("minecraft").resolve("the_nether")
                        .resolve("region");
                if (Files.exists(modern)) {
                    yield modern;
                }
                yield legacy;
            }
            case "minecraft:the_end" -> {
                Path legacy = worldDir.resolve("DIM1").resolve("region");
                if (Files.exists(legacy)) {
                    yield legacy;
                }
                Path modern = worldDir.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region");
                if (Files.exists(modern)) {
                    yield modern;
                }
                yield legacy;
            }
            default -> {
                Path legacy = worldDir.resolve("region");
                if (Files.exists(legacy)) {
                    yield legacy;
                }
                Path modern = worldDir.resolve("dimensions").resolve("minecraft").resolve("overworld")
                        .resolve("region");
                if (Files.exists(modern)) {
                    yield modern;
                }
                yield legacy;
            }
        };
    }

    // --- All-items scan ---

    public static Map<String, int[]> scanAllItems(MinecraftServer server, String dimension,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int[] outTotals) throws IOException {
        Path regionDir = getRegionDir(server, dimension);
        if (!Files.exists(regionDir)) {
            throw new IOException("区域文件目录不存在: " + regionDir);
        }

        int minRegionX = minX >> 9;
        int minRegionZ = minZ >> 9;
        int maxRegionX = maxX >> 9;
        int maxRegionZ = maxZ >> 9;
        int minChunkX = minX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkX = maxX >> 4;
        int maxChunkZ = maxZ >> 4;

        Map<String, int[]> allItems = new LinkedHashMap<>();
        outTotals[0] = 0;
        outTotals[1] = 0;

        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                String fileName = "r." + rx + "." + rz + ".mca";
                Path regionFile = regionDir.resolve(fileName);
                if (!Files.exists(regionFile))
                    continue;
                scanRegionFileAll(regionFile, rx, rz,
                        minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                        minX, minY, minZ, maxX, maxY, maxZ,
                        allItems, outTotals);
            }
        }
        return allItems;
    }

    private static void scanRegionFileAll(Path regionFile, int regionX, int regionZ,
            int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Map<String, int[]> allItems, int[] outTotals) throws IOException {
        byte[] header = new byte[SECTOR_SIZE * 2];
        try (RandomAccessFile raf = new RandomAccessFile(regionFile.toFile(), "r")) {
            if (raf.length() < header.length)
                return;
            raf.readFully(header);
            for (int cz = 0; cz < REGION_WIDTH; cz++) {
                for (int cx = 0; cx < REGION_WIDTH; cx++) {
                    int chunkX = (regionX << 5) + cx;
                    int chunkZ = (regionZ << 5) + cz;
                    if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ)
                        continue;
                    int entryIndex = cx + cz * REGION_WIDTH;
                    int locationOffset = entryIndex * 4;
                    int offset = ((header[locationOffset] & 0xFF) << 16)
                            | ((header[locationOffset + 1] & 0xFF) << 8)
                            | (header[locationOffset + 2] & 0xFF);
                    int sectorCount = header[locationOffset + 3] & 0xFF;
                    if (offset == 0 || sectorCount == 0)
                        continue;
                    try {
                        raf.seek((long) offset * SECTOR_SIZE);
                        int length = raf.readInt();
                        if (length <= 0 || length > SECTOR_SIZE * sectorCount)
                            continue;
                        byte compressionType = raf.readByte();
                        byte[] compressed = new byte[length - 1];
                        raf.readFully(compressed);
                        byte[] decompressed = decompress(compressed, compressionType);
                        if (decompressed == null)
                            continue;
                        Path tempFile = Files.createTempFile("eun_search_chunk_", ".nbt");
                        try {
                            Files.write(tempFile, decompressed);
                            NbtCompound chunkNbt = NbtIo.read(tempFile);
                            processChunkNbtAll(chunkNbt, minX, minY, minZ, maxX, maxY, maxZ, allItems, outTotals);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    } catch (Exception e) {
                        EunSearchMod.LOGGER.debug("[EunSearch] 读取chunk ({},{}) 失败: {}", chunkX, chunkZ, e.getMessage());
                    }
                }
            }
        }
    }

    private static void processChunkNbtAll(NbtCompound chunkNbt,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            Map<String, int[]> allItems, int[] outTotals) {
        if (!chunkNbt.contains("block_entities"))
            return;
        NbtList blockEntities = chunkNbt.getList("block_entities").orElse(new NbtList());
        for (int i = 0; i < blockEntities.size(); i++) {
            NbtCompound be = blockEntities.getCompound(i).orElse(null);
            if (be == null)
                continue;
            String id = be.getString("id").orElse("");
            if (EXCLUDED_IDS.contains(id))
                continue;
            if (!CONTAINER_IDS.contains(id))
                continue;
            int x = be.getInt("x").orElse(0);
            int y = be.getInt("y").orElse(0);
            int z = be.getInt("z").orElse(0);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ)
                continue;
            if (!be.contains("Items"))
                continue;
            NbtList items = be.getList("Items").orElse(new NbtList());
            if (items.isEmpty())
                continue;
            int baseSlots = getContainerCapacity(id, be);
            int shulkerBoxes = 0;
            Map<String, int[]> localCounts = new LinkedHashMap<>();

            for (int j = 0; j < items.size(); j++) {
                NbtCompound entry = items.getCompound(j).orElse(null);
                if (entry == null)
                    continue;
                ItemData itemData = readItemFromSlot(entry);
                if (itemData == null || itemData.id == null)
                    continue;

                if (itemData.id.contains("shulker_box")) {
                    shulkerBoxes++;
                    if (itemData.components != null) {
                        aggregateShulkerAll(itemData.components, localCounts);
                    }
                }

                localCounts.merge(itemData.id, new int[] { Math.max(1, itemData.count), 1 },
                        (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] });
            }

            int totalSlots = baseSlots - shulkerBoxes + shulkerBoxes * 27;
            if (totalSlots <= 0)
                continue;

            outTotals[0]++;
            outTotals[1] += totalSlots;

            for (Map.Entry<String, int[]> e : localCounts.entrySet()) {
                allItems.merge(e.getKey(), e.getValue(),
                        (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] });
            }
        }
    }

    private static void aggregateShulkerAll(NbtCompound components, Map<String, int[]> map) {
        if (components.contains("minecraft:container")) {
            NbtElement ct = components.get("minecraft:container");
            if (ct instanceof NbtList containerList) {
                for (int i = 0; i < containerList.size(); i++) {
                    NbtCompound entry = containerList.getCompound(i).orElse(null);
                    if (entry == null)
                        continue;
                    ItemData d = readItemFromSlot(entry);
                    if (d != null && d.id != null) {
                        map.merge(d.id, new int[] { Math.max(1, d.count), 1 },
                                (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] });
                    }
                }
            } else if (ct instanceof NbtCompound cc) {
                if (cc.contains("Items")) {
                    NbtList items = cc.getList("Items").orElse(new NbtList());
                    for (int i = 0; i < items.size(); i++) {
                        NbtCompound entry = items.getCompound(i).orElse(null);
                        if (entry == null)
                            continue;
                        ItemData d = readItemFromSlot(entry);
                        if (d != null && d.id != null) {
                            map.merge(d.id, new int[] { Math.max(1, d.count), 1 },
                                    (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] });
                        }
                    }
                }
            }
        }
        if (components.contains("Items")) {
            NbtList items = components.getList("Items").orElse(new NbtList());
            for (int i = 0; i < items.size(); i++) {
                NbtCompound entry = items.getCompound(i).orElse(null);
                if (entry == null)
                    continue;
                ItemData d = readItemFromSlot(entry);
                if (d != null && d.id != null) {
                    map.merge(d.id, new int[] { Math.max(1, d.count), 1 },
                            (a, b) -> new int[] { a[0] + b[0], a[1] + b[1] });
                }
            }
        }
    }

    // --- Result classes ---

    public static class OrphanInfo {
        public int orphanIndex; // index in containers list (to merge back)
        public int x, y, z;
        public String id;
        public boolean isLeft; // true=left half missing partner, false=right half standalone
        public String facing;
    }

    public static class ScanResult {
        public List<String> items = new ArrayList<>();
        public List<PerItemResult> itemResults = new ArrayList<>();
        public List<ContainerInfo> containers = new ArrayList<>();
        public int totalContainers = 0;
        public int totalSlots = 0;
        public List<int[]> shulkerContributions = new ArrayList<>();
        public List<OrphanInfo> orphans = new ArrayList<>();
        public java.util.Set<BlockPos> allContainerPositions = new java.util.HashSet<>();
        public java.util.Map<String, String> containerTypeMap = new java.util.HashMap<>();

        PerItemResult getOrCreateItemResult(int index, List<String> targetItems) {
            while (itemResults.size() <= index) {
                int fillIndex = itemResults.size();
                PerItemResult pir = new PerItemResult();
                pir.itemId = normalizeItemId(targetItems.get(fillIndex));
                pir.maxStackSize = getMaxStackSize(pir.itemId);
                itemResults.add(pir);
            }
            return itemResults.get(index);
        }

        void calculatePercentages() {
            for (PerItemResult pir : itemResults) {
                long cap = (long) totalSlots * pir.maxStackSize;
                pir.percentage = cap > 0 ? (pir.totalCount * 100.0 / cap) : 0.0;
            }
            for (ContainerInfo ci : containers) {
                if (ci.itemIndex < itemResults.size()) {
                    PerItemResult pir = itemResults.get(ci.itemIndex);
                    long cap = (long) ci.totalSlots * pir.maxStackSize;
                    ci.targetPercentage = cap > 0 ? (ci.targetCount * 100.0 / cap) : 0.0;
                }
            }
        }
    }

    public static class PerItemResult {
        public String itemId;
        public int totalCount = 0;
        public int slotsWithTarget = 0;
        public int maxStackSize = 64;
        public double percentage = 0.0;
    }

    public static class ContainerInfo {
        public int x, y, z;
        public String containerType;
        public String blockId;
        public int totalSlots = 0;
        public int targetSlots = 0;
        public int targetCount = 0;
        public int directCount = 0;
        public double targetPercentage = 0.0;
        public int itemIndex = 0;
        public boolean isDoubleChest = false;
        public int partnerX, partnerZ;
        /**
         * Per-shulker-slot info: each int[]{slotIndex, itemIndex, count} for slots containing the
         * target item
         */
        public List<int[]> shulkerSlots = new ArrayList<>();
    }

    private static class ItemData {
        String id;
        int count;
        NbtCompound components;
    }
}
