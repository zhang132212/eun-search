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
    private static volatile boolean debugLogging = false;

    /** 切换 mod 的 log4j 日志级别: 开=DEBUG(全量日志), 关=INFO */
    public static void setDebugLogging(boolean enable) {
        debugLogging = enable;
        try {
            var l4j = org.apache.logging.log4j.LogManager.getLogger(MOD_ID);
            if (l4j instanceof org.apache.logging.log4j.core.Logger coreLogger) {
                coreLogger.setLevel(enable ? org.apache.logging.log4j.Level.DEBUG : org.apache.logging.log4j.Level.INFO);
                LOGGER.info("[EunSearch] 调试日志已{} (log4j级别={})", enable ? "开启" : "关闭", coreLogger.getLevel());
            } else {
                LOGGER.info("[EunSearch] 无法切换级别(非log4j核心), 当前debug={}", enable);
            }
        } catch (Exception e) {
            LOGGER.warn("[EunSearch] 切换调试日志级别失败", e);
        }
    }

    public static boolean isDebugLogging() {
        return debugLogging;
    }

    @Override
    public void onInitializeServer() {
        LOGGER.info("[EunSearch] ===== onInitializeServer 开始 (mod 1.0.14-DEBUG) =====");
        instance = this;

        LOGGER.info("[EunSearch] 加载配置...");
        config = ModConfig.load();
        LOGGER.info("[EunSearch] 配置加载完成: {} 个扫描条目", config.getScans().size());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LOGGER.info("[EunSearch] 注册命令回调触发 (environment={})", environment);
            ScanCommand.register(dispatcher);
            LOGGER.info("[EunSearch] /scan 等命令注册完成");
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (QuickModeHandler.isInQuickMode((net.minecraft.server.network.ServerPlayerEntity) player)) {
                LOGGER.info("[EunSearch] 左键事件 (快速模式): player={} pos=({},{},{})", player.getName().getString(), pos.getX(), pos.getY(), pos.getZ());
                QuickModeHandler.onLeftClick((net.minecraft.server.network.ServerPlayerEntity) player, pos);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (QuickModeHandler.isInQuickMode((net.minecraft.server.network.ServerPlayerEntity) player)) {
                LOGGER.info("[EunSearch] 右键事件 (快速模式): player={}", player.getName().getString());
                QuickModeHandler.onRightClick((net.minecraft.server.network.ServerPlayerEntity) player);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[EunSearch] SERVER_STARTED: 服务器启动完成");
            this.server = server;
            startConfigWatcher();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[EunSearch] SERVER_STOPPING: 停止配置监听与TCP, 清理标记 (tcp端口数={})", tcpServers.size());
            stopConfigWatcher();
            for (var s : tcpServers.values()) s.stop();
            tcpServers.clear();
            ScanCommand.cleanupAllMarkers();
            LOGGER.info("[EunSearch] 停止清理完成");
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            String name = handler.getPlayer() != null ? handler.getPlayer().getName().getString() : "?";
            LOGGER.info("[EunSearch] 玩家加入: {} (UUID={})", name, handler.getPlayer() != null ? handler.getPlayer().getUuid() : "?");
            ScanCommand.cleanupJoinMarkers(server);
            LOGGER.info("[EunSearch] 玩家加入清理标记完成: {}", name);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            String name = handler.getPlayer() != null ? handler.getPlayer().getName().getString() : "?";
            LOGGER.info("[EunSearch] 玩家断开: {} (UUID={})", name, handler.getPlayer() != null ? handler.getPlayer().getUuid() : "?");
            ScanCommand.cleanupPlayerMarkers(handler.getPlayer().getUuid());
            QuickModeHandler.removePlayer(handler.getPlayer().getUuid());
        });

        LOGGER.info("[EunSearch] ===== onInitializeServer 完成 =====");
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
        LOGGER.info("[EunSearch] broadcastCommand: action={} player={} tagOrName={} itemId={} count={} (tcp端口数={})",
                action, player, tagOrName, itemId, count, tcpServers.size());
        if (tagOrName != null && !tagOrName.isEmpty()) {
            // Try tag first, then bot name
            for (var s : tcpServers.entrySet()) {
                if (tagOrName.equals(s.getValue().getDefaultTag()) || tagOrName.equals(s.getValue().getBotName())) {
                    LOGGER.info("[EunSearch] broadcastCommand: 命中端口 {} (tag={}, bot={})", s.getKey(), s.getValue().getDefaultTag(), s.getValue().getBotName());
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
        LOGGER.info("[EunSearch] startTcp: port={} tag={}", port, tag);
        var existing = tcpServers.get(port);
        if (existing != null) existing.stop();
        var s = new BotTCPServer(port, tag);
        s.start();
        tcpServers.put(port, s);
        LOGGER.info("[EunSearch] TCP端口 {} 已启动 (tag={}), 当前端口数={}", port, tag == null ? "default" : tag, tcpServers.size());
    }

    public void stopTcp(int port) {
        LOGGER.info("[EunSearch] stopTcp: port={}", port);
        var s = tcpServers.remove(port);
        if (s != null) { s.stop(); LOGGER.info("[EunSearch] TCP端口 {} 已关闭", port); }
        else LOGGER.warn("[EunSearch] stopTcp: 端口 {} 未在运行", port);
    }

    private void startConfigWatcher() {
        LOGGER.info("[EunSearch] startConfigWatcher: watching={}", watching);
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
        LOGGER.info("[EunSearch] 检测到配置文件变更, 1秒后热重载");
        watcherExecutor.schedule(() -> {
            try {
                reloadConfig();
            } catch (Exception e) {
                LOGGER.error("[EunSearch] 热重载配置文件失败", e);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private void stopConfigWatcher() {
        LOGGER.info("[EunSearch] stopConfigWatcher: watching={}", watching);
        watching = false;
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
    }
}
