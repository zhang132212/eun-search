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

        EunSearchMod.LOGGER.info("[RegionScanner] ===== scan() 开始 =====");
        EunSearchMod.LOGGER.info("[RegionScanner] 维度={} 范围=({},{},{})~({},{},{}) 目标物品={}",
                dimension, minX, minY, minZ, maxX, maxY, maxZ, targetItems);

        Path regionDir = getRegionDir(server, dimension);
        EunSearchMod.LOGGER.info("[RegionScanner] 区域目录={} 存在={}", regionDir, Files.exists(regionDir));
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

        EunSearchMod.LOGGER.info("[RegionScanner] region范围=({}, {})~({}, {}) chunk范围=({}, {})~({}, {})",
                minRegionX, minRegionZ, maxRegionX, maxRegionZ,
                minChunkX, minChunkZ, maxChunkX, maxChunkZ);

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
        EunSearchMod.LOGGER.info("[RegionScanner] Pass1 完成: 容器数={} 槽位数={} 孤儿数={}",
                result.totalContainers, result.totalSlots, result.orphans.size());

        // Pass 2: if orphans exist, scan ALL chunks in expanded range (don't skip originals)
        if (!result.orphans.isEmpty()) {
            EunSearchMod.LOGGER.info("[RegionScanner] 检测到 {} 个孤儿(未配对双箱), 开始 Pass2 扩大范围扫描", result.orphans.size());
            for (var o : result.orphans) {
                EunSearchMod.LOGGER.info("[RegionScanner]   孤儿: pos=({},{},{}) id={} isLeft={} facing={} orphanIndex={}",
                        o.x, o.y, o.z, o.id, o.isLeft, o.facing, o.orphanIndex);
            }
            int exMinChunkX = minChunkX - 1, exMinChunkZ = minChunkZ - 1;
            int exMaxChunkX = maxChunkX + 1, exMaxChunkZ = maxChunkZ + 1;
            int exMinRegionX = exMinChunkX >> 5, exMinRegionZ = exMinChunkZ >> 5;
            int exMaxRegionX = exMaxChunkX >> 5, exMaxRegionZ = exMaxChunkZ >> 5;
            EunSearchMod.LOGGER.info("[RegionScanner] Pass2 region范围=({}, {})~({}, {}) chunk范围=({}, {})~({}, {})",
                    exMinRegionX, exMinRegionZ, exMaxRegionX, exMaxRegionZ,
                    exMinChunkX, exMinChunkZ, exMaxChunkX, exMaxChunkZ);
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
            EunSearchMod.LOGGER.info("[RegionScanner] Pass2 完成: 剩余孤儿数={}", result.orphans.size());
        }

        result.calculatePercentages();
        EunSearchMod.LOGGER.info("[RegionScanner] ===== scan() 结束: 容器数={} 槽位数={} 物品结果数={} =====",
                result.totalContainers, result.totalSlots, result.itemResults.size());
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
            EunSearchMod.LOGGER.info("[RegionScanner] 打开区域文件 {} ({} bytes, pass{})", regionFile.getFileName(), raf.length(), pass2 ? 2 : 1);

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
                        EunSearchMod.LOGGER.debug("[RegionScanner] chunk({},{}) 压缩类型={} 压缩大小={} 解压后大小={}",
                                chunkX, chunkZ, compressionType, length - 1, decompressed.length);

                        Path tempFile = Files.createTempFile("eun_search_chunk_", ".nbt");
                        try {
                            Files.write(tempFile, decompressed);
                            NbtCompound chunkNbt = NbtIo.read(tempFile);
                            if (chunkNbt == null) {
                                EunSearchMod.LOGGER.warn("[RegionScanner] chunk({},{}) NbtIo.read 返回 null!", chunkX, chunkZ);
                                continue;
                            }
                            EunSearchMod.LOGGER.debug("[RegionScanner] chunk({},{}) NBT解析成功, 字符串长度={}",
                                    chunkX, chunkZ, chunkNbt.toString().length());
                            processChunkNbt(chunkNbt, minX, minY, minZ, maxX, maxY, maxZ,
                                    targetItems, result, pass2);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }

                    } catch (Exception e) {
                        EunSearchMod.LOGGER.error("[RegionScanner] 读取chunk ({},{}) 失败: {}", chunkX, chunkZ, e.toString(), e);
                    }
                }
            }
        }
    }

    private static void processChunkNbt(NbtCompound chunkNbt,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            List<String> targetItems, ScanResult result, boolean pass2) {

        if (!chunkNbt.contains("block_entities")) {
            EunSearchMod.LOGGER.debug("[RegionScanner] chunk 无 block_entities 键, 跳过");
            return;
        }

        NbtList blockEntities = chunkNbt.getList("block_entities").orElse(new NbtList());
        EunSearchMod.LOGGER.debug("[RegionScanner] block_entities 数量={} (pass{})", blockEntities.size(), pass2 ? 2 : 1);

        for (int i = 0; i < blockEntities.size(); i++) {
            NbtCompound be = blockEntities.getCompound(i).orElse(null);
            if (be == null)
                continue;

            String id = be.getString("id").orElse("");
            int beX = be.getInt("x").orElse(0);
            int beY = be.getInt("y").orElse(0);
            int beZ = be.getInt("z").orElse(0);
            if (EXCLUDED_IDS.contains(id)) {
                EunSearchMod.LOGGER.debug("[RegionScanner] BE ({},{},{}) id={} 被排除", beX, beY, beZ, id);
                continue;
            }
            if (!CONTAINER_IDS.contains(id)) {
                EunSearchMod.LOGGER.debug("[RegionScanner] BE ({},{},{}) id={} 非容器, 跳过", beX, beY, beZ, id);
                continue;
            }
            EunSearchMod.LOGGER.debug("[RegionScanner] 容器BE ({},{},{}) id={} Items键存在={}", beX, beY, beZ, id, be.contains("Items"));

            int x = be.getInt("x").orElse(0);
            int y = be.getInt("y").orElse(0);
            int z = be.getInt("z").orElse(0);

            if (x < minX || x > maxX || y < minY || y > maxY
                    || z < minZ || z > maxZ) {
                EunSearchMod.LOGGER.debug("[RegionScanner] BE ({},{},{}) 超出扫描范围, 跳过", x, y, z);
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
                EunSearchMod.LOGGER.info("[RegionScanner] 箱子 ({},{},{}) type={} facing={}", x, y, z, chestType, chestFacing);
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
                    EunSearchMod.LOGGER.info("[RegionScanner] 右半箱 ({},{},{}) 期望左半箱 ({},{},{}) 同chunk内存在={}",
                            x, y, z, lx, y, lz, leftInChunk);
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
                    EunSearchMod.LOGGER.info("[RegionScanner] 左半箱 ({},{},{}) 期望右半箱 ({},{},{}) 找到partner={} (partner槽数={})",
                            x, y, z, px, y, pz, partnerItems != null, partnerItems != null ? partnerItems.size() : -1);
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
                        EunSearchMod.LOGGER.info("[RegionScanner] Pass2 匹配孤儿: 孤儿({},{},{}) 配对方=({},{},{}) facing={} isLeft={}",
                                orphan.x, orphan.y, orphan.z, x, y, z, orphan.facing, orphan.isLeft);
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
                            EunSearchMod.LOGGER.info("[RegionScanner] 配对方也是孤儿(索引{}), 合并容器: idx{} -> idx{}", partnerOi, partnerIdx, orphan.orphanIndex);
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
                        EunSearchMod.LOGGER.info("[RegionScanner] Pass2 孤儿处理完成, 剩余孤儿数={}", result.orphans.size());
                        break;
                    }
                }
                continue; // pass2: don't add to containers or do normal processing
            }
            // 注意: 潜影盒的 BE id 一律是 minecraft:shulker_box (所有颜色共用同一 BlockEntityType),
            // 颜色必须从 block_states palette 读取真实方块名
            if (id.contains("shulker_box")) {
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
            EunSearchMod.LOGGER.info("[RegionScanner] 处理容器 ({},{},{}) id={} type={} 物品槽数={} partner槽数={} 容量={}",
                    x, y, z, id, chestType, items.size(), partnerItems != null ? partnerItems.size() : -1, baseSlots);

            int[] totalCounts = new int[targetItems.size()];
            int[] directCounts = new int[targetItems.size()];
            int[] targetSlots = new int[targetItems.size()];
            List<int[]> shulkerSlotData = new ArrayList<>(); // {slotIndex, itemIndex, count}

            for (int j = 0; j < items.size(); j++) {
                NbtCompound entry = items.getCompound(j).orElse(null);
                if (entry == null)
                    continue;

                ItemData itemData = readItemFromSlot(entry);
                if (itemData == null) {
                    EunSearchMod.LOGGER.debug("[RegionScanner] 槽{} 物品读取失败(格式未知): {}", getSlot(entry, j), entry.toString().length() > 200 ? entry.toString().substring(0, 200) : entry);
                    continue;
                }

                boolean isShulker = itemData.id != null && itemData.id.contains("shulker_box");
                EunSearchMod.LOGGER.debug("[RegionScanner]   槽{}: id={} count={} isShulker={} hasComponents={}",
                        getSlot(entry, j), itemData.id, itemData.count, isShulker, itemData.components != null);

                if (isShulker) {
                    shulkerBoxesInContainer++;
                }

                if (isShulker && itemData.components != null) {
                    EunSearchMod.LOGGER.debug("[RegionScanner]   shulker组件键: {}", itemData.components.toString().length() > 300 ? itemData.components.toString().substring(0, 300) : itemData.components);
                    for (int t = 0; t < targetItems.size(); t++) {
                        int[] shulkerCount = countInShulkerBox(itemData.components, targetItems.get(t));
                        if (shulkerCount[1] > 0) {
                            EunSearchMod.LOGGER.info("[RegionScanner]   shulker内命中目标[{}] 槽数={} 数量={}", targetItems.get(t), shulkerCount[0], shulkerCount[1]);
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
                        EunSearchMod.LOGGER.info("[RegionScanner]   ★命中目标: 容器({},{},{}) 物品={} 目标={} count={} slot={}",
                                x, y, z, itemData.id, targetItems.get(t), itemData.count, getSlot(entry, j));
                        targetSlots[t]++;
                        totalCounts[t] += Math.max(1, itemData.count);
                        directCounts[t] += Math.max(1, itemData.count);
                    }
                }
            }
            if (partnerItems != null) {
                EunSearchMod.LOGGER.info("[RegionScanner] 读取partner ({},{},{}) 的 {} 个槽", partnerX, y, partnerZ, partnerItems.size());
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
                                EunSearchMod.LOGGER.info("[RegionScanner]   partner shulker内命中目标[{}] 槽数={} 数量={}", targetItems.get(t), sc[0], sc[1]);
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
                            EunSearchMod.LOGGER.info("[RegionScanner]   ★partner命中目标: 容器({},{},{}) 物品={} 目标={} count={} slot={}",
                                    x, y, z, itemData.id, targetItems.get(t), itemData.count, getSlot(entry, j));
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
                    info.blockId = id.contains("shulker_box") ? typeId : id;
                    info.facing = isChest ? chestFacing : readBlockStateProperty(chunkNbt, x, y, z, "facing", null);
                    info.chestType = isChest ? chestType : "single";
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
                    EunSearchMod.LOGGER.info("[RegionScanner] 记录容器结果: ({},{},{}) 类型={} 槽数={} 目标物品[{}] 数量={} 直接={} 双箱={}",
                            x, y, z, info.containerType, totalSlots, targetItems.get(t), totalCounts[t], directCounts[t], info.isDoubleChest);
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
                        EunSearchMod.LOGGER.info("[RegionScanner] 记录孤儿: ({},{},{}) isLeft={} facing={} 容器索引={}",
                                x, y, z, orphan.isLeft, chestFacing, orphan.orphanIndex);
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
        EunSearchMod.LOGGER.debug("[RegionScanner] findSection(y={}): 未找到sectionY={}", y, sectionY);
        return null;
    }

    private static String readBlockStateProperty(NbtCompound chunkNbt, int x, int y, int z, String property, String defaultValue) {
        try {
            NbtCompound section = findSection(chunkNbt, y);
            if (section == null || !section.contains("block_states")) {
                EunSearchMod.LOGGER.debug("[RegionScanner] readBlockStateProperty({},{},{},{}): 无section或block_states", x, y, z, property);
                return defaultValue;
            }
            NbtCompound bs = section.getCompound("block_states").orElse(null);
            if (bs == null || !bs.contains("palette")) {
                EunSearchMod.LOGGER.debug("[RegionScanner] readBlockStateProperty({},{},{},{}): 无palette", x, y, z, property);
                return defaultValue;
            }
            NbtList palette = bs.getList("palette").orElse(null);
            if (palette == null || palette.isEmpty())
                return defaultValue;
            long[] data = bs.getLongArray("data").orElse(new long[0]);
            int paletteIdx = 0;
            if (palette.size() > 1) {
                if (data.length == 0) {
                    EunSearchMod.LOGGER.debug("[RegionScanner] readBlockStateProperty({},{},{},{}): palette>1但无data", x, y, z, property);
                    return defaultValue;
                }
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
                EunSearchMod.LOGGER.debug("[RegionScanner] readBlockStateProperty({},{},{},{}): palette[{}]={}", x, y, z, property, paletteIdx, pc.toString().length() > 200 ? pc.toString().substring(0, 200) : pc);
                return defaultValue;
            }
            return defaultValue;
        } catch (Exception e) {
            EunSearchMod.LOGGER.debug("[RegionScanner] readBlockStateProperty({},{},{},{}) 异常: {}", x, y, z, property, e.toString());
            return defaultValue;
        }
    }

    private static String readChestType(NbtCompound chunkNbt, int x, int y, int z) {
        String t = readBlockStateProperty(chunkNbt, x, y, z, "type", "single");
        EunSearchMod.LOGGER.debug("[RegionScanner] readChestType({},{},{}) = {}", x, y, z, t);
        return t;
    }

    private static String readChestFacing(NbtCompound chunkNbt, int x, int y, int z) {
        String f = readBlockStateProperty(chunkNbt, x, y, z, "facing", "north");
        EunSearchMod.LOGGER.debug("[RegionScanner] readChestFacing({},{},{}) = {}", x, y, z, f);
        return f;
    }

    private static int[] countInShulkerBox(NbtCompound components, String normalizedItem) {
        int[] result = new int[] { 0, 0 };
        EunSearchMod.LOGGER.debug("[RegionScanner] countInShulkerBox: 目标={} 组件键={}", normalizedItem, components.toString().length() > 200 ? components.toString().substring(0, 200) : components);

        if (components.contains("minecraft:container")) {
            NbtElement containerTag = components.get("minecraft:container");
            if (containerTag != null) {
                if (containerTag.getType() == NbtElement.LIST_TYPE) {
                    NbtList containerItems = (NbtList) containerTag;
                    EunSearchMod.LOGGER.debug("[RegionScanner]   minecraft:container 是LIST, 条目数={}", containerItems.size());
                    for (int i = 0; i < containerItems.size(); i++) {
                        NbtCompound entry = containerItems.getCompound(i).orElse(null);
                        if (entry == null)
                            continue;
                        ItemData itemData = readItemFromSlot(entry);
                        if (itemData != null && itemData.id != null && matchesItem(itemData.id, normalizedItem)) {
                            EunSearchMod.LOGGER.info("[RegionScanner]   shulker内命中: {} x{}", itemData.id, Math.max(1, itemData.count));
                            result[0]++;
                            result[1] += Math.max(1, itemData.count);
                        }
                    }
                } else if (containerTag.getType() == NbtElement.COMPOUND_TYPE) {
                    NbtCompound containerCompound = (NbtCompound) containerTag;
                    if (containerCompound.contains("Items")) {
                        NbtList containerItems = containerCompound.getList("Items").orElse(new NbtList());
                        EunSearchMod.LOGGER.debug("[RegionScanner]   minecraft:container 是COMPOUND(含Items), 条目数={}", containerItems.size());
                        for (int i = 0; i < containerItems.size(); i++) {
                            NbtCompound entry = containerItems.getCompound(i).orElse(null);
                            if (entry == null)
                                continue;
                            ItemData itemData = readItemFromSlot(entry);
                            if (itemData != null && itemData.id != null && matchesItem(itemData.id, normalizedItem)) {
                                EunSearchMod.LOGGER.info("[RegionScanner]   shulker内命中: {} x{}", itemData.id, Math.max(1, itemData.count));
                                result[0]++;
                                result[1] += Math.max(1, itemData.count);
                            }
                        }
                    } else {
                        EunSearchMod.LOGGER.debug("[RegionScanner]   minecraft:container COMPOUND 无 Items 键, 键={}", containerCompound.toString().length() > 200 ? containerCompound.toString().substring(0, 200) : containerCompound);
                    }
                }
            }
        } else {
            EunSearchMod.LOGGER.debug("[RegionScanner]   组件中无 minecraft:container 键");
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
            EunSearchMod.LOGGER.debug("[RegionScanner] readItemFromSlot(新格式item): id={} count={} components={}",
                    data.id, data.count, data.components != null ? data.components.getKeys().toString() : "null");
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
            EunSearchMod.LOGGER.debug("[RegionScanner] readItemFromSlot(旧格式id): id={} count={} components={}",
                    data.id, data.count, data.components != null ? "有" : "无");
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
                int max = item.getMaxCount();
                EunSearchMod.LOGGER.debug("[RegionScanner] getMaxStackSize: {} -> {}", itemId, max);
                return max;
            }
            EunSearchMod.LOGGER.debug("[RegionScanner] getMaxStackSize: Identifier解析失败: {}", itemId);
        } catch (Exception e) {
            EunSearchMod.LOGGER.debug("[RegionScanner] getMaxStackSize({}) 异常: {}", itemId, e.toString());
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
        // 先按位置精确读取该方块的 Name (通过 block_states.data 计算 palette 索引)
        String exact = readBlockNameAt(chunkNbt, x, y, z);
        if (exact != null && exact.contains("shulker_box")) {
            EunSearchMod.LOGGER.info("[RegionScanner] readShulkerColor({},{},{}) 精确读取: {}", x, y, z, exact);
            return exact;
        }
        EunSearchMod.LOGGER.info("[RegionScanner] readShulkerColor({},{},{}) 精确读取失败({}), 回退heuristic", x, y, z, exact);
        try {
            if (!chunkNbt.contains("sections"))
                return "minecraft:shulker_box";
            NbtList sections = chunkNbt.getList("sections").orElse(null);
            if (sections == null)
                return "minecraft:shulker_box";
            // 注意: sections 列表下标不是 Y 值 (1.18+ 世界最低 sectionY 为 -4), 必须按 Y 匹配
            int sectionY = y >> 4;
            NbtCompound section = null;
            for (int i = 0; i < sections.size(); i++) {
                NbtCompound sec = sections.getCompound(i).orElse(null);
                if (sec != null && sec.getInt("Y").orElse(Integer.MIN_VALUE) == sectionY) {
                    section = sec;
                    break;
                }
            }
            if (section == null) {
                EunSearchMod.LOGGER.debug("[RegionScanner] readShulkerColor({},{},{}): 未找到sectionY={} (sections数={})", x, y, z, sectionY, sections.size());
                return "minecraft:shulker_box";
            }
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
                return parsePaletteName(palette.get(0));
            }

            // find the first shulker_box entry as heuristic
            for (int i = 0; i < palette.size(); i++) {
                String name = parsePaletteName(palette.get(i));
                if (name != null && name.contains("shulker_box")) {
                    EunSearchMod.LOGGER.info("[RegionScanner] readShulkerColor({},{},{}) 回退识别到: {}", x, y, z, name);
                    return name;
                }
            }
            EunSearchMod.LOGGER.info("[RegionScanner] readShulkerColor({},{},{}) palette中未找到shulker, 返回默认", x, y, z);
            return "minecraft:shulker_box";
        } catch (Exception e) {
            EunSearchMod.LOGGER.debug("[RegionScanner] readShulkerColor({},{},{}) 异常: {}", x, y, z, e.toString());
            return "minecraft:shulker_box";
        }
    }

    /** 通过 block_states.data 精确计算 (x,y,z) 位置的 palette 索引, 读取其方块名 */
    private static String readBlockNameAt(NbtCompound chunkNbt, int x, int y, int z) {
        try {
            NbtCompound section = findSection(chunkNbt, y);
            if (section == null || !section.contains("block_states"))
                return null;
            NbtCompound bs = section.getCompound("block_states").orElse(null);
            if (bs == null || !bs.contains("palette"))
                return null;
            NbtList palette = bs.getList("palette").orElse(null);
            if (palette == null || palette.isEmpty())
                return null;
            long[] data = bs.getLongArray("data").orElse(new long[0]);
            int paletteIdx = 0;
            if (palette.size() > 1) {
                if (data.length == 0)
                    return null;
                int bits = Math.max(4, 64 - Long.numberOfLeadingZeros(palette.size() - 1));
                int entriesPerLong = 64 / bits;
                int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                int longIndex = idx / entriesPerLong;
                int bitOffset = (idx % entriesPerLong) * bits;
                if (longIndex >= data.length)
                    return null;
                paletteIdx = (int) ((data[longIndex] >>> bitOffset) & ((1L << bits) - 1));
                if (paletteIdx < 0 || paletteIdx >= palette.size())
                    return null;
            }
            return parsePaletteName(palette.get(paletteIdx));
        } catch (Exception e) {
            EunSearchMod.LOGGER.debug("[RegionScanner] readBlockNameAt({},{},{}) 异常: {}", x, y, z, e.toString());
            return null;
        }
    }

    /** 从 palette 条目解析方块名: 支持 NbtCompound {Name:...} 与 NbtString "minecraft:xxx[...]" 两种格式 */
    private static String parsePaletteName(NbtElement pe) {
        try {
            if (pe instanceof NbtCompound pc) {
                String n = pc.getString("Name").orElse("");
                if (!n.isEmpty()) return n;
                return null;
            }
            if (pe instanceof net.minecraft.nbt.NbtString ps) {
                String s = ps.asString().orElse("");
                int bracket = s.indexOf('[');
                if (bracket >= 0) s = s.substring(0, bracket);
                if (s.isEmpty()) return null;
                return s;
            }
        } catch (Exception e) {
            EunSearchMod.LOGGER.debug("[RegionScanner] parsePaletteName 异常: {}", e.toString());
        }
        return null;
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
        EunSearchMod.LOGGER.info("[RegionScanner] ===== scanAllItems() 开始: 维度={} 范围=({},{},{})~({},{},{}) =====",
                dimension, minX, minY, minZ, maxX, maxY, maxZ);
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
        public String facing;      // 容器实际朝向 (north/south/east/west), 无朝向属性时为 null
        public String chestType = "single"; // chest/trapped_chest 专用: single/left/right
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
