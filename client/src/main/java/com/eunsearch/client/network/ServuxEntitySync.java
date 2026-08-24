package com.eunsearch.client.network;

import com.eunsearch.client.search.SearchManager;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;

public class ServuxEntitySync {
    private static final ServuxEntitySync INSTANCE = new ServuxEntitySync();
    private final Set<BlockPos> requested = new LinkedHashSet<>();

    private ServuxEntitySync() {}

    public static ServuxEntitySync getInstance() {
        return INSTANCE;
    }

    public void init() {
        // MiniHUD already owns the servux:entity_data channel and the metadata
        // registration. We just schedule requests through its public tracker.
    }

    public boolean isServuxServer() {
        return fi.dy.masa.minihud.data.EntityDataManager.getInstance().hasServuxServer();
    }

    public boolean isRegistered() {
        return this.isServuxServer();
    }

    public int getPendingCount() {
        return fi.dy.masa.minihud.data.EntityDataManager.getInstance().getPendingBlockEntitiesCount();
    }

    public void requestBlockEntity(BlockPos pos) {
        if (pos == null) return;
        BlockPos immutable = pos.immutable();
        if (this.requested.add(immutable)) {
            fi.dy.masa.minihud.data.EntityDataManager.getInstance().getRequestTracker().schedulePendingBlockEntity(immutable);
        }
    }

    public void tick() {
        if (requested.isEmpty()) return;
        if (!isServuxServer()) return;

        var cache = fi.dy.masa.minihud.data.EntityDataManager.getInstance().getCache();
        var iter = requested.iterator();
        while (iter.hasNext()) {
            BlockPos pos = iter.next();
            var pair = cache.getBlockEntityPairFromCache(pos);
            if (pair != null && pair.data() != null) {
                iter.remove();
                SearchManager.INSTANCE.onBlockEntityNbt(pos, pair.data());
            }
        }
    }
}
