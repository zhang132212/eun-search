package com.eunsearch.mixin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Mixin(value = RegistrySyncManager.class, remap = false)
public class RegistrySyncBypass {
    private static final Logger LOGGER = LoggerFactory.getLogger("eun_search-bypass");
    private static final Gson GSON = new Gson();
    private static Set<String> whitelist = new HashSet<>();
    private static boolean loaded = false;

    private static void loadWhitelist() {
        if (loaded) return;
        loaded = true;
        Path p = Path.of("config", "eun_search_registry_bypass.json");
        if (Files.exists(p)) {
            try {
                String json = Files.readString(p);
                whitelist = GSON.fromJson(json, new TypeToken<Set<String>>(){}.getType());
                LOGGER.info("[RegistryBypass] 已加载白名单: {} 个玩家", whitelist.size());
            } catch (IOException e) {
                LOGGER.error("[RegistryBypass] 读取白名单失败", e);
            }
        } else {
            // Default: add common bot names
            whitelist.add("QQScanBot");
            whitelist.add("zhang1323");
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, GSON.toJson(whitelist));
                LOGGER.info("[RegistryBypass] 已创建默认白名单: {}", whitelist);
            } catch (IOException ignored) {}
        }
    }

    @Inject(method = "configureClient", at = @At("HEAD"), cancellable = true)
    private static void bypassRegistrySync(ServerConfigurationNetworkHandler handler, MinecraftServer server, CallbackInfo ci) {
        loadWhitelist();
        String playerName = handler.getDebugProfile().name();
        if (whitelist.contains(playerName)) {
            LOGGER.info("[RegistryBypass] 跳过 {} 的注册表检查", playerName);
            ci.cancel();
        }
    }
}
