package com.eunsearch.client;

import com.eunsearch.client.config.ClientConfig;
import com.eunsearch.client.gui.SettingsScreen;
import com.eunsearch.client.network.ServuxEntitySync;
import com.eunsearch.client.render.RangeHud;
import com.eunsearch.client.search.SearchManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class EunSearchClient implements ClientModInitializer {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("eun_search_client:general"));
    private static KeyMapping searchKey;
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ServuxEntitySync.getInstance().init();
        RangeHud.init();
        searchKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.eun_search_client.search", InputConstants.Type.KEYSYM, 74, CATEGORY));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.eun_search_client.settings", InputConstants.Type.KEYSYM, 73, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean settingsPressed = settingsKey.consumeClick();
            boolean searchPressed = searchKey.consumeClick();
            if ((settingsPressed && searchKey.isDown()) || (searchPressed && settingsKey.isDown())) {
                openSettings();
            } else if (searchPressed) {
                handleSearchHotkey();
            }
            ServuxEntitySync.getInstance().tick();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("esearch")
                    .then(ClientCommands.argument("item", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String query = StringArgumentType.getString(ctx, "item");
                                SearchManager.INSTANCE.startSearch(query);
                                Minecraft mc = Minecraft.getInstance();
                                if (mc.player != null) {
                                    mc.player.sendSystemMessage(Component.literal("[EunSearch] Scanning for " + query + " in loaded chunks..."));
                                }
                                return 1;
                            })));
            dispatcher.register(ClientCommands.literal("esettings")
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.gui.screen() == null) {
                            mc.gui.setScreen(new SettingsScreen(null));
                        }
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("eclear")
                    .executes(ctx -> {
                        SearchManager.INSTANCE.clear();
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.literal("[EunSearch] Cleared results"));
                        }
                        return 1;
                    }));
        });
    }
    private static void openSettings() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null) {
            mc.gui.setScreen(new SettingsScreen(null));
        }
    }

    private static void handleSearchHotkey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        var stack = mc.player.getMainHandItem();
        if (stack.isEmpty()) {
            mc.player.sendSystemMessage(Component.literal("[EunSearch] 请在主手拿要查找的物品，再按快捷键。"));
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        SearchManager.INSTANCE.startSearch(itemId);
        mc.player.sendSystemMessage(Component.literal("[EunSearch] 正在查找手中物品: " + itemId));
    }
}
