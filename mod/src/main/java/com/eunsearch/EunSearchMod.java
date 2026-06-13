package com.eunsearch;

import com.eunsearch.bot.BotTCPServer;
import com.eunsearch.command.ScanCommand;
import com.eunsearch.config.ModConfig;
import com.eunsearch.quick.QuickModeHandler;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class EunSearchMod implements DedicatedServerModInitializer {

    public static final String MOD_ID = "eun_search";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static EunSearchMod instance;
    private final java.util.concurrent.ConcurrentMap<Integer, BotTCPServer> tcpServers = new java.util.concurrent.ConcurrentHashMap<>();
    private ModConfig config;
    private MinecraftServer server;
    private ScheduledExecutorService watcherExecutor;
    private volatile boolean watching = false;

    @Override
    public void onInitializeServer() {
        instance = this;

        config = ModConfig.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ScanCommand.register(dispatcher);
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (QuickModeHandler.isInQuickMode((net.minecraft.server.network.ServerPlayerEntity) player)) {
                QuickModeHandler.onLeftClick((net.minecraft.server.network.ServerPlayerEntity) player, pos);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (QuickModeHandler.isInQuickMode((net.minecraft.server.network.ServerPlayerEntity) player)) {
                QuickModeHandler.onRightClick((net.minecraft.server.network.ServerPlayerEntity) player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.server = server;
            startConfigWatcher();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            stopConfigWatcher();
            for (var s : tcpServers.values()) s.stop();
            tcpServers.clear();
            ScanCommand.cleanupAllMarkers();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ScanCommand.cleanupJoinMarkers(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ScanCommand.cleanupPlayerMarkers(handler.getPlayer().getUuid());
            QuickModeHandler.removePlayer(handler.getPlayer().getUuid());
        });

        LOGGER.info("[EunSearch] 模组初始化完成");
    }

    public static EunSearchMod getInstance() {
        return instance;
    }

    public ModConfig getConfig() {
        return config;
    }

    public void saveConfig() {
        config.save();
    }

    public void reloadConfig() {
        config.reload();
    }

    public MinecraftServer getServer() {
        return server;
    }

    public BotTCPServer getBotTCPServer() {
        return null; // no default, use /scanTcp to register ports
    }

    public void broadcastCommand(String action, String player, String tagOrName, String itemId, int count) {
        if (tagOrName != null && !tagOrName.isEmpty()) {
            // Try tag first, then bot name
            for (var s : tcpServers.entrySet()) {
                if (tagOrName.equals(s.getValue().getDefaultTag()) || tagOrName.equals(s.getValue().getBotName())) {
                    s.getValue().sendBotCommand(action, player, tagOrName, itemId, count);
                    return;
                }
            }
        }
        LOGGER.warn("[EunSearch] 未找到tag/bot={}的TCP端口", tagOrName);
    }

    public BotTCPServer getTcpServer(int port) {
        return tcpServers.get(port);
    }

    public java.util.Map<Integer, BotTCPServer> getTcpServers() {
        return tcpServers;
    }

    public java.util.Collection<String> getBotNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (var s : tcpServers.values()) {
            String n = s.getBotName();
            if (n != null) names.add(n);
        }
        return names;
    }

    public void startTcp(int port, String tag) {
        var existing = tcpServers.get(port);
        if (existing != null) existing.stop();
        var s = new BotTCPServer(port, tag);
        s.start();
        tcpServers.put(port, s);
        LOGGER.info("[EunSearch] TCP端口 {} 已启动 (tag={})", port, tag == null ? "default" : tag);
    }

    public void stopTcp(int port) {
        var s = tcpServers.remove(port);
        if (s != null) { s.stop(); LOGGER.info("[EunSearch] TCP端口 {} 已关闭", port); }
    }

    private void startConfigWatcher() {
        if (watching) return;
        watching = true;

        watcherExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EunSearch-ConfigWatcher");
            t.setDaemon(true);
            return t;
        });

        watcherExecutor.execute(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                Path configDir = Path.of("config");
                Files.createDirectories(configDir);
                configDir.register(watchService, ENTRY_MODIFY);

                LOGGER.info("[EunSearch] 配置文件监听已启动: {}", configDir.resolve("eun_search.json"));

                while (watching) {
                    WatchKey key;
                    try {
                        key = watchService.poll(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (key == null) continue;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.toString().equals("eun_search.json")) {
                            debounceReload();
                        }
                    }
                    key.reset();
                }
            } catch (IOException e) {
                LOGGER.error("[EunSearch] 配置文件监听异常", e);
            } catch (ClosedWatchServiceException ignored) {
            }
        });
    }

    private void debounceReload() {
        watcherExecutor.schedule(() -> {
            try {
                reloadConfig();
            } catch (Exception e) {
                LOGGER.error("[EunSearch] 热重载配置文件失败", e);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void stopConfigWatcher() {
        watching = false;
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
    }
}
