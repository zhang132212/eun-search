package com.eunsearch.client;

import com.eunsearch.client.config.ClientConfig;
import com.eunsearch.client.gui.SettingsScreen;
import com.eunsearch.client.network.ServuxEntitySync;
import com.eunsearch.client.render.RangeHud;
import com.eunsearch.client.search.SearchManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class EunSearchClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ServuxEntitySync.getInstance().init();
        RangeHud.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> ServuxEntitySync.getInstance().tick());

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
}
