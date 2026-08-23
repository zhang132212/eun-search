package com.eunsearch.client.network;

import com.eunsearch.client.search.SearchManager;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.minecraft.core.BlockPos;

public interface SearchResultSink {
    static void acceptBlockEntity(BlockPos pos, CompoundData nbt) {
        SearchManager.INSTANCE.onBlockEntityNbt(pos, nbt);
    }
}
