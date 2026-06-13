package com.eunsearch.api;

import com.eunsearch.EunSearchMod;
import com.eunsearch.config.ScanEntry;
import com.eunsearch.scan.RegionScanner;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Public API for bot mods to query EunSearch scan areas and results.
 * All methods are server-thread-safe.
 */
public class EunSearchAPI {

    /** A container found by scanning. */
    public static class ContainerResult {
        public final int x, y, z;
        public final String itemId;
        public final int count;
        public final int directCount;
        public final String containerType;
        public final java.util.List<int[]> shulkerSlots;
        public final boolean isDoubleChest;
        public final int partnerX, partnerZ;

        public ContainerResult(int x, int y, int z, String itemId, int count, int directCount, String containerType, java.util.List<int[]> shulkerSlots, boolean isDoubleChest, int partnerX, int partnerZ) {
            this.x = x; this.y = y; this.z = z;
            this.itemId = itemId; this.count = count;
            this.directCount = directCount;
            this.containerType = containerType;
            this.shulkerSlots = shulkerSlots;
            this.isDoubleChest = isDoubleChest;
            this.partnerX = partnerX; this.partnerZ = partnerZ;
        }
    }

    /** Result of a targeted scan. */
    public static class ScanResult {
        public final String tag;
        public final List<ContainerResult> containers = new ArrayList<>();
        public final int totalContainers;
        public final int totalSlots;
        public final long elapsedMs;

        ScanResult(String tag, int totalContainers, int totalSlots, long elapsedMs) {
            this.tag = tag; this.totalContainers = totalContainers; this.totalSlots = totalSlots;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * Get all configured scan entries (tags + coordinates).
     */
    public static List<ScanEntry> getScanEntries() {
        return EunSearchMod.getInstance().getConfig().getScans();
    }

    /**
     * Find a scan entry by tag (case-insensitive).
     */
    public static ScanEntry getScanEntry(String tag) {
        return EunSearchMod.getInstance().getConfig().findScanByTag(tag);
    }

    /**
     * Scan a configured area for specific items.
     * Returns container positions + counts for matched items.
     */
    public static ScanResult scanForItems(MinecraftServer server, String tag, List<String> itemIds) {
        ScanEntry entry = getScanEntry(tag);
        if (entry == null) return null;

        long start = System.currentTimeMillis();
        try {
            var raw = RegionScanner.scan(server, entry.dimension,
                    entry.minX(), entry.minY(), entry.minZ(),
                    entry.maxX(), entry.maxY(), entry.maxZ(),
                    itemIds);

            ScanResult result = new ScanResult(tag, raw.totalContainers, raw.totalSlots,
                    System.currentTimeMillis() - start);

            for (var ci : raw.containers) {
                if (ci.targetCount > 0 && ci.itemIndex < itemIds.size()) {
                    result.containers.add(new ContainerResult(
                            ci.x, ci.y, ci.z,
                            itemIds.get(ci.itemIndex), ci.targetCount, ci.directCount,
                            ci.containerType,
                            new ArrayList<>(ci.shulkerSlots),
                            ci.isDoubleChest, ci.partnerX, ci.partnerZ));
                }
            }
            return result;
        } catch (Exception e) {
            EunSearchMod.LOGGER.error("[EunSearchAPI] 扫描 '{}' 失败", tag, e);
            return null;
        }
    }

    /**
     * Scan for a single item. Convenience wrapper around scanForItems.
     */
    public static ScanResult scanForItem(MinecraftServer server, String tag, String itemId) {
        return scanForItems(server, tag, List.of(itemId));
    }

    /**
     * Find all containers in a scan area that contain the target item.
     * Shortcut: uses the first matching scan tag.
     */
    public static List<ContainerResult> searchItem(MinecraftServer server, String tag, String itemId) {
        ScanResult sr = scanForItem(server, tag, itemId);
        return sr != null ? sr.containers : List.of();
    }
}
