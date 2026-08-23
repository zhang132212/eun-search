package com.eunsearch.client.render;

import com.eunsearch.client.config.ClientConfig;
import com.eunsearch.client.search.SearchManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class RangeHud {
    private RangeHud() {}

    public static void init() {
        HudElementRegistry.addLast(Identifier.parse("eun_search_client:range_hud"),
                (guiGraphics, deltaTracker) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player == null) return;
                    int color = ClientConfig.get().showRangeOverlay ? 0xFFFFFFFF : 0x80FFFFFF;
                    String text = SearchManager.INSTANCE.isSearching()
                            ? "[EunSearch] range " + ClientConfig.get().scanRangeBlocks + " blocks | servers " + (com.eunsearch.client.network.ServuxEntitySync.getInstance().isServuxServer() ? "Servux" : "-") + " | hits " + SearchManager.INSTANCE.getHits().size()
                            : "[EunSearch] range " + ClientConfig.get().scanRangeBlocks + " blocks";
                    guiGraphics.text(mc.font, text, 2, 2, color);
                });
    }
}
