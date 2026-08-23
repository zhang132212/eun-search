package com.eunsearch.client.search;

import com.eunsearch.client.config.ClientConfig;
import com.eunsearch.client.network.SearchResultSink;
import com.eunsearch.client.network.ServuxEntitySync;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SearchManager {
    public static final SearchManager INSTANCE = new SearchManager();

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
            "minecraft:decorated_pot"
    );

    private String activeQuery = "";
    private final Map<BlockPos, Integer> hits = new LinkedHashMap<>();
    private long lastClearTime;

    private SearchManager() {}

    public void startSearch(String itemQuery) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        this.activeQuery = normalizeItem(itemQuery);
        this.hits.clear();
        this.lastClearTime = System.currentTimeMillis();

        int radius = Math.max(4, ClientConfig.get().scanRangeBlocks);
        BlockPos center = mc.player.blockPosition();
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) continue;
                    String beId = getBlockEntityId(chunk, pos);
                    if (CONTAINER_IDS.contains(beId)) {
                        ServuxEntitySync.getInstance().requestBlockEntity(pos);
                    }
                }
            }
        }
    }

    private String getBlockEntityId(LevelChunk chunk, BlockPos pos) {
        // Block Entity NBT isn't available until requested; use block id as fallback.
        String blockId = chunk.getBlockState(pos).getBlock().toString();
        // Minecraft Block.toString() is not a registry id, use registry name via BuiltInRegistries
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock());
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString();
    }

    public void onBlockEntityNbt(BlockPos pos, CompoundData data) {
        if (activeQuery.isEmpty()) return;
        CompoundTag nbt = DataConverterNbt.toVanillaCompound(data);
        String beId = nbt.getStringOr("id", "");
        if (!CONTAINER_IDS.contains(beId)) return;

        int count = countItemInContainer(nbt, activeQuery);
        if (count > 0) {
            hits.put(pos.immutable(), count);
        }
    }

    private int countItemInContainer(CompoundTag nbt, String query) {
        int total = 0;
        if (nbt.contains("Items")) {
            ListTag items = nbt.getListOrEmpty("Items");
            for (Tag tag : items) {
                if (tag instanceof CompoundTag item) {
                    total += countInItem(item, query);
                }
            }
        }
        return total;
    }

    private int countInItem(CompoundTag item, String query) {
        String id = item.getStringOr("id", "");
        if (id.isEmpty()) return 0;
        int count = item.getIntOr("count", 0);
        if (count <= 0) count = item.getIntOr("Count", 0);
        if (id.equals(query)) {
            return Math.max(1, count);
        }
        if (item.contains("components")) {
            CompoundTag components = item.getCompoundOrEmpty("components");
            if (components.contains("minecraft:container")) {
                return countInContainerComponent(components.get("minecraft:container"), query);
            }
        }
        return 0;
    }

    private int countInContainerComponent(Tag component, String query) {
        if (component instanceof ListTag list) {
            int total = 0;
            for (Tag t : list) {
                if (t instanceof CompoundTag c) total += countInItem(c, query);
            }
            return total;
        }
        if (component instanceof CompoundTag c && c.contains("Items")) {
            int total = 0;
            ListTag items = c.getListOrEmpty("Items");
            for (Tag t : items) {
                if (t instanceof CompoundTag i) total += countInItem(i, query);
            }
            return total;
        }
        return 0;
    }

    private String normalizeItem(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return s;
        if (!s.contains(":")) {
            return "minecraft:" + s;
        }
        return s;
    }

    public boolean isSearching() {
        return !activeQuery.isEmpty();
    }

    public Map<BlockPos, Integer> getHits() {
        return this.hits;
    }

    public void clear() {
        this.activeQuery = "";
        this.hits.clear();
    }
}
