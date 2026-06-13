package com.eunsearch.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.eunsearch.EunSearchMod;
import com.eunsearch.config.ScanEntry;
import com.eunsearch.quick.QuickModeHandler;
import com.eunsearch.render.ItemNameMap;
import com.eunsearch.scan.RegionScanner;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.AffineTransformation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ScanCommand {

    private static final java.util.Set<java.util.UUID> LOG_ENABLED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<Integer, MarkerEntry> MARKERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, java.util.List<Integer>> PLAYER_MARKER_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> GREEN_MARKER = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile ScheduledExecutorService MARKER_TIMER;
    private static final String MARKER_TAG = "EunSearchMarker";
    private static final int MARKER_SECONDS = 10;

    private static class MarkerEntry {
        final net.minecraft.entity.Entity entity;
        final java.util.UUID owner;
        int secondsRemaining;
        MarkerEntry(net.minecraft.entity.Entity entity, java.util.UUID owner, int seconds) {
            this.entity = entity; this.owner = owner; this.secondsRemaining = seconds;
        }
    }

    private static final SuggestionProvider<ServerCommandSource> TAG_SUGGESTIONS = (ctx, builder) -> {
        for (ScanEntry e : EunSearchMod.getInstance().getConfig().getScans()) {
            builder.suggest(e.tag);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> SEARCH_TAG_SUGGESTIONS = (ctx, builder) -> {
        for (ScanEntry e : EunSearchMod.getInstance().getConfig().getScans()) {
            String t = e.tag;
            if (t.matches(".*[^\\x00-\\x7F].*")) builder.suggest("\"" + t + "\"");
            else builder.suggest(t);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> BOT_NAME_SUGGESTIONS = (ctx, builder) -> {
        for (String n : EunSearchMod.getInstance().getBotNames()) {
            builder.suggest(n);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<ServerCommandSource> ITEM_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
        String match = remaining;
        String prefix = "";
        int lastComma = remaining.lastIndexOf(',');
        if (lastComma >= 0) {
            prefix = remaining.substring(0, lastComma + 1);
            match = remaining.substring(lastComma + 1).trim();
        }
        for (var entry : ItemNameMap.getAllEntries()) {
            String id = entry.getKey();
            String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String cn = entry.getValue();
            if (shortId.toLowerCase(java.util.Locale.ROOT).startsWith(match)) {
                builder.suggest(prefix + shortId);
            }
            if (cn.startsWith(match)) {
                builder.suggest(prefix + cn);
            }
        }
        return builder.buildFuture();
    };

    public static void cleanupPlayerMarkers(java.util.UUID uuid) {
        var ids = PLAYER_MARKER_IDS.remove(uuid);
        if (ids != null) {
            for (int id : ids) {
                var entry = MARKERS.remove(id);
                if (entry != null && !entry.entity.isRemoved()) entry.entity.discard();
            }
        }
    }

    public static void cleanupAllMarkers() {
        for (var entry : MARKERS.values()) if (!entry.entity.isRemoved()) entry.entity.discard();
        MARKERS.clear(); PLAYER_MARKER_IDS.clear();
        if (MARKER_TIMER != null) { MARKER_TIMER.shutdownNow(); MARKER_TIMER = null; }
    }

    public static void cleanupJoinMarkers(MinecraftServer server) {
        for (var entry : MARKERS.values()) {
            if (!entry.entity.isRemoved()) {
                entry.entity.discard();
            }
        }
        MARKERS.clear(); PLAYER_MARKER_IDS.clear();
    }

    private static void ensureTimerRunning(MinecraftServer server) {
        if (MARKER_TIMER != null) return;
        MARKER_TIMER = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "EunSearch-MarkerTimer"); t.setDaemon(true); return t; });
        MARKER_TIMER.scheduleAtFixedRate(() -> {
            server.execute(() -> {
                var it = MARKERS.values().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    entry.secondsRemaining--;
                    if (entry.secondsRemaining <= 0) {
                        if (!entry.entity.isRemoved()) entry.entity.discard();
                        PLAYER_MARKER_IDS.computeIfPresent(entry.owner, (k, v) -> { v.remove(Integer.valueOf(entry.entity.getId())); return v.isEmpty() ? null : v; });
                        it.remove();
                    }
                }
                if (MARKERS.isEmpty() && MARKER_TIMER != null) { MARKER_TIMER.shutdown(); MARKER_TIMER = null; }
            });
        }, 1, 1, TimeUnit.SECONDS);
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("scan")
            .executes(ScanCommand::showHelp)
            .then(literal("list").executes(ScanCommand::listScans))
            .then(literal("all")
                .then(argument("x1", IntegerArgumentType.integer()).then(argument("y1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
                    .then(literal("to").then(argument("x2", IntegerArgumentType.integer()).then(argument("y2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer())
                        .then(argument("tag", StringArgumentType.greedyString()).suggests(TAG_SUGGESTIONS).executes(ScanCommand::addAllScan))))))))))
            .then(literal("remove").then(argument("tag", StringArgumentType.greedyString()).suggests(TAG_SUGGESTIONS).executes(ScanCommand::removeScan)))
            .then(literal("config")
                .then(literal("reload").executes(ScanCommand::reloadConfig)))
            .then(literal("run").then(argument("tag", StringArgumentType.greedyString()).suggests(TAG_SUGGESTIONS).executes(ScanCommand::runScan)))
            .then(literal("search")
                .then(argument("tag", StringArgumentType.string()).suggests(SEARCH_TAG_SUGGESTIONS)
                    .then(argument("item", StringArgumentType.greedyString()).suggests(ITEM_SUGGESTIONS).executes(ScanCommand::searchContainer))))
            .then(literal("markClear").executes(ScanCommand::clearAllMarks))
            .then(literal("quick").executes(ScanCommand::toggleQuickMode))
            .then(literal("log").executes(ScanCommand::toggleLog))
            .then(buildRangeCommand())
        );

        d.register(literal("botSearchAll")
            .then(argument("tag", StringArgumentType.string()).suggests(SEARCH_TAG_SUGGESTIONS)
                .then(argument("item", StringArgumentType.greedyString()).suggests(ITEM_SUGGESTIONS).executes(ScanCommand::botFetchAll)))
        );
        d.register(literal("botSearchChest")
            .then(argument("tag", StringArgumentType.string()).suggests(SEARCH_TAG_SUGGESTIONS)
                .then(argument("item", StringArgumentType.greedyString()).suggests(ITEM_SUGGESTIONS).executes(ScanCommand::botFetchChest)))
        );
        d.register(literal("botStop")
            .executes(ScanCommand::botStopHelp)
            .then(argument("bot", StringArgumentType.string()).suggests(BOT_NAME_SUGGESTIONS)
                .executes(ScanCommand::botStop))
        );

        d.register(literal("scanTcp")
            .then(argument("tag", StringArgumentType.string()).suggests(SEARCH_TAG_SUGGESTIONS)
                .then(argument("port", IntegerArgumentType.integer(1025, 65535))
                    .executes(ScanCommand::openTcp)))
            .then(literal("list").executes(ScanCommand::listTcp))
            .then(literal("remove").then(argument("port", IntegerArgumentType.integer(1025, 65535))
                .executes(ScanCommand::removeTcp)))
        );

        d.register(literal("eunlook")
            .then(argument("x1", IntegerArgumentType.integer())
                .then(argument("y1", IntegerArgumentType.integer())
                    .then(argument("z1", IntegerArgumentType.integer())
                        .then(argument("x2", IntegerArgumentType.integer())
                            .then(argument("z2", IntegerArgumentType.integer())
                                .then(argument("type", StringArgumentType.word())
                                    .executes(ScanCommand::eunLook))))))));
    }

    private static int botStopHelp(CommandContext<ServerCommandSource> ctx) {
        ctx.getSource().sendFeedback(() -> Text.literal("§7用法: /botStop <标签>"), false);
        return 1;
    }

    private static int botStop(CommandContext<ServerCommandSource> ctx) {
        String playerName = ctx.getSource().getName();
        String botName = StringArgumentType.getString(ctx, "bot");
        EunSearchMod.getInstance().broadcastCommand("stop", playerName, botName, "", 0);
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 已发送停止指令 (bot=" + botName + ")"), false);
        return 1;
    }

    private static int openTcp(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        int port = IntegerArgumentType.getInteger(ctx, "port");
        EunSearchMod.getInstance().startTcp(port, tag);
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] TCP端口 " + port + " 已启动 (tag=" + tag + ")"), false);
        return 1;
    }

    private static int listTcp(CommandContext<ServerCommandSource> ctx) {
        var servers = EunSearchMod.getInstance().getTcpServers();
        StringBuilder sb = new StringBuilder("§a[EunSearch] TCP端口列表:\n");
        for (var e : servers.entrySet()) {
            String tag = e.getValue().getDefaultTag();
            sb.append("§7端口:").append(e.getKey()).append(" §e").append(tag == null ? "默认" : tag).append("\n");
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int removeTcp(CommandContext<ServerCommandSource> ctx) {
        int port = IntegerArgumentType.getInteger(ctx, "port");
        EunSearchMod.getInstance().stopTcp(port);
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] TCP端口 " + port + " 已关闭"), false);
        return 1;
    }

    private static int eunLook(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
            int x1 = IntegerArgumentType.getInteger(ctx, "x1");
            int y1 = IntegerArgumentType.getInteger(ctx, "y1");
            int z1 = IntegerArgumentType.getInteger(ctx, "z1");
            int x2 = IntegerArgumentType.getInteger(ctx, "x2");
            int z2 = IntegerArgumentType.getInteger(ctx, "z2");
            // Rotate view
            double cx = (x1 + x2 + 1) / 2.0;
            double cy = y1 - 0.5;
            double cz = (z1 + z2 + 1) / 2.0;
            double dx = cx - player.getX();
            double dy = cy - player.getEyeY();
            double dz2 = cz - player.getZ();
            double horiz = Math.sqrt(dx * dx + dz2 * dz2);
            float yaw = (float)(Math.atan2(dz2, dx) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float)(-(Math.atan2(dy, horiz) * 180.0 / Math.PI));
            ServerWorld world = ctx.getSource().getWorld();
            player.teleport(world, player.getX(), player.getY(), player.getZ(),
                java.util.Set.of(), yaw, pitch, false);

            // Remove previous green marker
            Integer oldId = GREEN_MARKER.remove(player.getUuid());
            if (oldId != null) {
                var oldEntry = MARKERS.remove(oldId);
                if (oldEntry != null && !oldEntry.entity.isRemoved()) oldEntry.entity.discard();
                PLAYER_MARKER_IDS.computeIfPresent(player.getUuid(), (k, v) -> { v.remove(oldId); return v.isEmpty() ? null : v; });
            }

            // Create green glowing marker
            int mnX = Math.min(x1, x2), mxX = Math.max(x1, x2);
            int mnZ = Math.min(z1, z2), mxZ = Math.max(z1, z2);
            String t = StringArgumentType.getString(ctx, "type");
            var bs = switch (t) {
                case "chest" -> Blocks.CHEST.getDefaultState();
                case "trapped_chest" -> Blocks.TRAPPED_CHEST.getDefaultState();
                case "barrel" -> Blocks.BARREL.getDefaultState();
                case "hopper" -> Blocks.HOPPER.getDefaultState();
                case "dispenser" -> Blocks.DISPENSER.getDefaultState();
                case "dropper" -> Blocks.DROPPER.getDefaultState();
                case "furnace" -> Blocks.FURNACE.getDefaultState();
                case "blast_furnace" -> Blocks.BLAST_FURNACE.getDefaultState();
                case "smoker" -> Blocks.SMOKER.getDefaultState();
                case "brewing_stand" -> Blocks.BREWING_STAND.getDefaultState();
                default -> Blocks.CHEST.getDefaultState();
            };
            if (t.contains("shulker_box")) bs = Blocks.SHULKER_BOX.getDefaultState();
            var entity = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
            entity.setPosition(mnX, y1, mnZ);
            entity.setBlockState(bs);
            entity.setTransformation(new AffineTransformation(new Matrix4f().scale(mxX - mnX + 1, 1.0f, mxZ - mnZ + 1)));
            entity.setGlowing(true);
            entity.addCommandTag(MARKER_TAG);
            world.spawnEntity(entity);

            // Green team for glow color
            var sb = world.getServer().getScoreboard();
            var team = sb.getTeam("eun_green");
            if (team == null) {
                team = sb.addTeam("eun_green");
                team.setColor(net.minecraft.util.Formatting.GREEN);
            }
            sb.addScoreHolderToTeam(entity.getUuidAsString(), team);

            var entry = new MarkerEntry(entity, player.getUuid(), MARKER_SECONDS);
            MARKERS.put(entity.getId(), entry);
            GREEN_MARKER.put(player.getUuid(), entity.getId());
            PLAYER_MARKER_IDS.computeIfAbsent(player.getUuid(), k -> new ArrayList<>()).add(entity.getId());
            ensureTimerRunning(world.getServer());
        } catch (Exception ignored) {}
        return 1;
    }

    private static int botFetchAll(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        String rawItem = StringArgumentType.getString(ctx, "item");
        String playerName = ctx.getSource().getName();
        String itemName = rawItem;
        int count = 1;
        int lastSpace = rawItem.lastIndexOf(' ');
        if (lastSpace > 0) {
            String lastPart = rawItem.substring(lastSpace + 1);
            try {
                count = Integer.parseInt(lastPart);
                itemName = rawItem.substring(0, lastSpace);
            } catch (NumberFormatException ignored) {}
        }
        String itemId = ItemNameMap.getItemId(itemName);
        int maxStack = 64;
        try {
            Identifier id = Identifier.tryParse(itemId);
            if (id != null) { maxStack = Registries.ITEM.get(id).getMaxCount(); }
        } catch (Exception ignored) {}
        int maxCount = maxStack * 27;
        final int fCount = Math.min(count, maxCount);
        final boolean capped = count > maxCount;
        final String fItemName = itemName;
        EunSearchMod.getInstance().broadcastCommand("fetchAll", playerName, tag, itemId, fCount);
        ctx.getSource().sendFeedback(() -> Text.literal(true ?
                "§a已发送取物: " + fItemName + " x" + fCount + (capped ? "(上限" + maxCount + ")" : "") :
                "§a已广播至所有TCP端口"), false);
        return 1;
    }

    private static int botFetchChest(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        String rawItem = StringArgumentType.getString(ctx, "item");
        String playerName = ctx.getSource().getName();
        String itemName = rawItem;
        int count = 1;
        int lastSpace = rawItem.lastIndexOf(' ');
        if (lastSpace > 0) {
            String lastPart = rawItem.substring(lastSpace + 1);
            try {
                count = Integer.parseInt(lastPart);
                itemName = rawItem.substring(0, lastSpace);
            } catch (NumberFormatException ignored) {}
        }
        String itemId = ItemNameMap.getItemId(itemName);
        int maxStack = 64;
        try {
            Identifier id = Identifier.tryParse(itemId);
            if (id != null) { maxStack = Registries.ITEM.get(id).getMaxCount(); }
        } catch (Exception ignored) {}
        int maxCount = maxStack * 27;
        final int fCount = Math.min(count, maxCount);
        final boolean capped = count > maxCount;
        final String fItemName = itemName;
        EunSearchMod.getInstance().broadcastCommand("fetchChest", playerName, tag, itemId, fCount);
        ctx.getSource().sendFeedback(() -> Text.literal(true ?
                "§a已发送取物(仅箱子): " + fItemName + " x" + fCount + (capped ? "(上限" + maxCount + ")" : "") :
                "§a已广播至所有TCP端口"), false);
        return 1;
    }

    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        String sb = "\n§f===== §aEunSearch 指令帮助§f =====\n" +
                "§7/scan list §f- 列出所有扫描配置\n" +
                "§7/scan all x1 y1 z1 to x2 y2 z2 <标签> §f- 添加扫描区域\n" +
                "§7/scan remove <标签> §f- 删除扫描\n" +
                "§7/scan config reload §f- 热重载配置\n" +
                "§7/scan run <标签> §f- 手动运行扫描\n" +
                "§7/scan search <标签> <物品> §f- 搜索容器并标记\n" +
                "§7/scan markClear §f- 清除容器标记\n" +
                "§7/scan quick §f- 快速配置坐标\n" +
                "§7/botSearchAll <标签> <物品> [数量] §f- 向bot发送取物指令\n" +
                "§7/botSearchChest <标签> <物品> [数量] §f- 向bot发送取物指令(仅箱子)\n" +
                "§7/botStop <标签> §f- 停止bot当前任务\n" +
                "§f===== §7物品支持中文名 + Tab补全§f =====\n";
        ctx.getSource().sendFeedback(() -> Text.literal(sb), false);
        return 1;
    }

    private static int addAllScan(CommandContext<ServerCommandSource> ctx) {
        int x1 = IntegerArgumentType.getInteger(ctx, "rx1"), y1 = IntegerArgumentType.getInteger(ctx, "ry1"), z1 = IntegerArgumentType.getInteger(ctx, "rz1");
        int x2 = IntegerArgumentType.getInteger(ctx, "rx2"), y2 = IntegerArgumentType.getInteger(ctx, "ry2"), z2 = IntegerArgumentType.getInteger(ctx, "rz2");
        String tag = StringArgumentType.getString(ctx, "tag");
        String dim = ctx.getSource().getWorld().getRegistryKey().getValue().toString();
        ScanEntry e = new ScanEntry(tag, "*", x1, y1, z1, x2, y2, z2, dim);
        e.allItems = true; e.item = null;
        EunSearchMod.getInstance().getConfig().addScan(e); EunSearchMod.getInstance().saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 已保存全物品扫描 §e" + tag), false);
        return 1;
    }

    private static int removeScan(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        boolean ok = EunSearchMod.getInstance().getConfig().removeScan(tag); EunSearchMod.getInstance().saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal(true ? "§a[EunSearch] 已删除 §e" + tag : "§c[EunSearch] 未找到 §e" + tag), false);
        return 1;
    }

    private static int listScans(CommandContext<ServerCommandSource> ctx) {
        var scans = EunSearchMod.getInstance().getConfig().getScans();
        StringBuilder sb = new StringBuilder();
        if (scans.isEmpty()) { sb.append("§7暂无扫描配置"); ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false); return 1; }
        sb.append("§a扫描列表 (§e").append(scans.size()).append("§a):\n");
        for (ScanEntry s : scans) {
            boolean isAll = s.allItems;
            String items = isAll ? "§b全物品" : String.join(",", s.getItems());
            sb.append("§7- §e#").append(s.tag).append("§7|").append(items).append("§7|(").append(s.x1).append(",").append(s.y1).append(",").append(s.z1).append(")~(").append(s.x2).append(",").append(s.y2).append(",").append(s.z2).append(")§7|维:").append(s.dimension.contains(":") ? s.dimension.substring(s.dimension.lastIndexOf(':') + 1) : s.dimension).append("\n");
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int runScan(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        ScanEntry s = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
        if (s == null) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 未找到"), false); return 0; }
        try {
            long start = System.currentTimeMillis();
            var r = RegionScanner.scan(ctx.getSource().getServer(), s.dimension, s.minX(), s.minY(), s.minZ(), s.maxX(), s.maxY(), s.maxZ(), s.getItems());
            long el = System.currentTimeMillis() - start;
            StringBuilder sb = new StringBuilder("§a[EunSearch] §e" + tag + "§a (§7" + el + "ms§a)\n容器:" + r.totalContainers + " 格:" + r.totalSlots + "\n");
            for (var p : r.itemResults) { String sn = p.itemId.contains(":") ? p.itemId.substring(p.itemId.indexOf(':') + 1) : p.itemId; sb.append("§7- §e").append(sn).append("§7: ").append(String.format("%,d", p.totalCount)).append("个 (").append(String.format("%.2f%%", p.percentage)).append(")\n"); }
            ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        } catch (Exception e) { EunSearchMod.LOGGER.error("扫描失败", e); }
        return 1;
    }

    private static int toggleLog(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
            java.util.UUID uuid = player.getUuid();
            if (LOG_ENABLED.contains(uuid)) { LOG_ENABLED.remove(uuid); ctx.getSource().sendFeedback(() -> Text.literal("§7[EunSearch] 槽位日志已关闭"), false); }
            else { LOG_ENABLED.add(uuid); ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 槽位日志已开启"), false); }
        } catch (Exception e) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 仅玩家"), false); }
        return 1;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> buildRangeCommand() {
        var setCmd = argument("x1", IntegerArgumentType.integer())
            .then(argument("y1", IntegerArgumentType.integer())
                .then(argument("z1", IntegerArgumentType.integer())
                    .then(literal("to")
                        .then(argument("x2", IntegerArgumentType.integer())
                            .then(argument("y2", IntegerArgumentType.integer())
                                .then(argument("z2", IntegerArgumentType.integer())
                                    .executes(ScanCommand::setRange)))))));
        var removeCmd = argument("rx1", IntegerArgumentType.integer())
            .then(argument("ry1", IntegerArgumentType.integer())
                .then(argument("rz1", IntegerArgumentType.integer())
                    .then(literal("to")
                        .then(argument("rx2", IntegerArgumentType.integer())
                            .then(argument("ry2", IntegerArgumentType.integer())
                                .then(argument("rz2", IntegerArgumentType.integer())
                                    .executes(ScanCommand::removeRange)))))));
        return literal("range")
            .then(argument("tag", StringArgumentType.string()).suggests(SEARCH_TAG_SUGGESTIONS)
                .then(literal("set").then(setCmd))
                .then(literal("list").executes(ScanCommand::listRanges))
                .then(literal("remove").then(removeCmd)));
    }

    private static int setRange(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        ScanEntry entry = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
        if (entry == null) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 未找到扫描标签 " + tag), false); return 0; }
        int x1 = IntegerArgumentType.getInteger(ctx, "x1"), y1 = IntegerArgumentType.getInteger(ctx, "y1"), z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2"), y2 = IntegerArgumentType.getInteger(ctx, "y2"), z2 = IntegerArgumentType.getInteger(ctx, "z2");
        entry.ranges.add(new int[]{x1, y1, z1, x2, y2, z2});
        EunSearchMod.getInstance().saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 已为 " + tag + " 添加寻路范围 (" + x1 + "," + y1 + "," + z1 + ")~(" + x2 + "," + y2 + "," + z2 + ")"), false);
        return 1;
    }

    private static int listRanges(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        ScanEntry entry = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
        if (entry == null) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 未找到扫描标签 " + tag), false); return 0; }
        if (entry.ranges.isEmpty()) { ctx.getSource().sendFeedback(() -> Text.literal("§7[EunSearch] " + tag + " 无预设寻路范围"), false); return 1; }
        StringBuilder sb = new StringBuilder("§a[EunSearch] " + tag + " 寻路范围:\n");
        int idx = 1;
        for (int[] r : entry.ranges) {
            sb.append("§7#").append(idx).append(" §e(").append(r[0]).append(",").append(r[1]).append(",").append(r[2]).append(")~(").append(r[3]).append(",").append(r[4]).append(",").append(r[5]).append(")\n");
            idx++;
        }
        ctx.getSource().sendFeedback(() -> Text.literal(sb.toString()), false);
        return 1;
    }

    private static int removeRange(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        ScanEntry entry = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
        if (entry == null) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 未找到扫描标签 " + tag), false); return 0; }
        int x1 = IntegerArgumentType.getInteger(ctx, "x1"), y1 = IntegerArgumentType.getInteger(ctx, "y1"), z1 = IntegerArgumentType.getInteger(ctx, "z1");
        int x2 = IntegerArgumentType.getInteger(ctx, "x2"), y2 = IntegerArgumentType.getInteger(ctx, "y2"), z2 = IntegerArgumentType.getInteger(ctx, "z2");
        boolean removed = entry.ranges.removeIf(r -> r[0]==x1&&r[1]==y1&&r[2]==z1&&r[3]==x2&&r[4]==y2&&r[5]==z2);
        if (removed) EunSearchMod.getInstance().saveConfig();
        ctx.getSource().sendFeedback(() -> Text.literal(removed ? "§a[EunSearch] 已删除寻路范围" : "§c[EunSearch] 未找到匹配的寻路范围"), false);
        return 1;
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> ctx) { EunSearchMod.getInstance().reloadConfig(); ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 已重载"), false); return 1; }
    private static int toggleQuickMode(CommandContext<ServerCommandSource> ctx) { try { QuickModeHandler.toggle(ctx.getSource().getPlayerOrThrow()); } catch (Exception e) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 仅玩家"), false); } return 1; }

    private static int clearAllMarks(CommandContext<ServerCommandSource> ctx) {
        int count = MARKERS.size();
        for (var entry : MARKERS.values()) if (!entry.entity.isRemoved()) entry.entity.discard();
        MARKERS.clear(); PLAYER_MARKER_IDS.clear();
        ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 已清除 §e" + count + "§a 个标记"), false);
        return 1;
    }

    private static int searchContainer(CommandContext<ServerCommandSource> ctx) {
        String tag = StringArgumentType.getString(ctx, "tag");
        String rawItem = StringArgumentType.getString(ctx, "item");
        ScanEntry scan = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
        if (scan == null) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 未找到 §e" + tag), false); return 0; }
        String targetItem = ItemNameMap.getItemId(rawItem);
        ServerPlayerEntity player;
        try { player = ctx.getSource().getPlayerOrThrow(); } catch (Exception e) { ctx.getSource().sendFeedback(() -> Text.literal("§c[EunSearch] 仅玩家"), false); return 0; }
        try {
            var result = RegionScanner.scan(ctx.getSource().getServer(), scan.dimension, scan.minX(), scan.minY(), scan.minZ(), scan.maxX(), scan.maxY(), scan.maxZ(), List.of(targetItem));
            List<BlockPos> found = new ArrayList<>();
            List<BlockPos[]> groups = new ArrayList<>();
            java.util.Map<BlockPos, String> posToBlockId = new java.util.HashMap<>();
            java.util.Map<BlockPos, RegionScanner.ContainerInfo> posToInfo = new java.util.HashMap<>();
            int totalItemCount = 0;
            for (var ci : result.containers) {
                if (ci.targetCount <= 0) continue;
                BlockPos pos = new BlockPos(ci.x, ci.y, ci.z);
                found.add(pos);
                posToBlockId.put(pos, ci.blockId != null ? ci.blockId : "minecraft:chest");
                posToInfo.put(pos, ci);
                totalItemCount += ci.targetCount;
                var group = new java.util.LinkedHashSet<BlockPos>(); group.add(pos);
                if (ci.isDoubleChest) {
                    group.add(new BlockPos(ci.partnerX, ci.y, ci.partnerZ));
                } else if (ci.containerType.equals("chest") || ci.containerType.equals("trapped_chest")) {
                    for (BlockPos nb : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                        if (found.contains(nb)) { group.add(nb); break; }
                    }
                }
                groups.add(group.toArray(new BlockPos[0]));
            }
            if (found.isEmpty()) { ctx.getSource().sendFeedback(() -> Text.literal("§e[EunSearch] 未找到含有 §7" + rawItem + "§e 的容器"), false); return 0; }
            groups = mergeGroups(groups);
            startEdgeMarking((ServerWorld) ctx.getSource().getWorld(), groups, player, posToBlockId);
            final int fTotal = totalItemCount;
            ctx.getSource().sendFeedback(() -> Text.literal("§a[EunSearch] 找到 §e" + found.size() + "§a 个容器, 共 §e" + String.format("%,d", fTotal) + "§a 个 §e" + rawItem + "§a, 标记10秒"), false);
            sendContainerDetails(player, groups, posToInfo, rawItem);
        } catch (Exception e) { EunSearchMod.LOGGER.error("搜索失败", e); }
        return 1;
    }

    private static List<BlockPos[]> mergeGroups(List<BlockPos[]> groups) {
        if (groups.size() <= 1) return groups;
        var sets = new ArrayList<java.util.Set<BlockPos>>();
        for (var g : groups) sets.add(new java.util.LinkedHashSet<>(java.util.Arrays.asList(g)));
        boolean ch = true;
        while (ch) {
            ch = false;
            outer: for (int i = 0; i < sets.size(); i++)
                for (int j = i + 1; j < sets.size(); j++)
                    if (!java.util.Collections.disjoint(sets.get(i), sets.get(j))) { sets.get(i).addAll(sets.get(j)); sets.remove(j); ch = true; break outer; }
        }
        List<BlockPos[]> r = new ArrayList<>();
        for (var s : sets) r.add(s.toArray(new BlockPos[0]));
        return r;
    }

    private static void startEdgeMarking(ServerWorld world, List<BlockPos[]> groups, ServerPlayerEntity player,
                                          java.util.Map<BlockPos, String> posToBlockId) {
        if (groups.isEmpty()) return;
        cleanupPlayerMarkers(player.getUuid());

        for (BlockPos[] group : groups) {
            int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
            int y = group[0].getY();
            for (BlockPos p : group) { if (p.getX() < mnX) mnX = p.getX(); if (p.getX() > mxX) mxX = p.getX(); if (p.getZ() < mnZ) mnZ = p.getZ(); if (p.getZ() > mxZ) mxZ = p.getZ(); }

            String bid = posToBlockId.getOrDefault(group[0], "minecraft:chest");
            var bs = switch (bid) {
                case "minecraft:chest" -> Blocks.CHEST.getDefaultState();
                case "minecraft:trapped_chest" -> Blocks.TRAPPED_CHEST.getDefaultState();
                case "minecraft:barrel" -> Blocks.BARREL.getDefaultState();
                case "minecraft:hopper" -> Blocks.HOPPER.getDefaultState();
                case "minecraft:dispenser" -> Blocks.DISPENSER.getDefaultState();
                case "minecraft:dropper" -> Blocks.DROPPER.getDefaultState();
                case "minecraft:furnace" -> Blocks.FURNACE.getDefaultState();
                case "minecraft:blast_furnace" -> Blocks.BLAST_FURNACE.getDefaultState();
                case "minecraft:smoker" -> Blocks.SMOKER.getDefaultState();
                case "minecraft:brewing_stand" -> Blocks.BREWING_STAND.getDefaultState();
                case "minecraft:decorated_pot" -> Blocks.DECORATED_POT.getDefaultState();
                default -> Blocks.CHEST.getDefaultState();
            };
            if (bid.contains("shulker_box")) bs = Blocks.SHULKER_BOX.getDefaultState();

            var entity = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);
            entity.setPosition(mnX, y, mnZ);
            entity.setBlockState(bs);
            entity.setTransformation(new AffineTransformation(new Matrix4f().scale(mxX - mnX + 1, 1.0f, mxZ - mnZ + 1)));
            entity.setGlowing(true);
            entity.addCommandTag(MARKER_TAG);
            world.spawnEntity(entity);

            var entry = new MarkerEntry(entity, player.getUuid(), MARKER_SECONDS);
            MARKERS.put(entity.getId(), entry);
            PLAYER_MARKER_IDS.computeIfAbsent(player.getUuid(), k -> new ArrayList<>()).add(entity.getId());
            world.playSound(null, mnX, y, mnZ, SoundEvents.BLOCK_NOTE_BLOCK_BELL, SoundCategory.BLOCKS, 0.6f, 1.2f);
        }
        ensureTimerRunning(world.getServer());
    }

    private static String containerTypeName(String type) {
        if (type == null) return "箱子";
        return switch (type) {
            case "chest" -> "箱子";
            case "trapped_chest" -> "陷阱箱";
            case "barrel" -> "木桶";
            case "hopper" -> "漏斗";
            case "dispenser" -> "发射器";
            case "dropper" -> "投掷器";
            case "furnace" -> "熔炉";
            case "blast_furnace" -> "高炉";
            case "smoker" -> "烟熏炉";
            case "brewing_stand" -> "酿造台";
            case "decorated_pot" -> "饰纹陶罐";
            case "crafter" -> "合成器";
            default -> type.contains("shulker_box") ? "潜影盒" : type;
        };
    }

    private static void sendContainerDetails(ServerPlayerEntity player, List<BlockPos[]> groups,
                                              java.util.Map<BlockPos, RegionScanner.ContainerInfo> posToInfo, String itemName) {
        // Always show container list with clickable look, detailed info only when log enabled
        boolean showDetail = LOG_ENABLED.contains(player.getUuid());
        if (showDetail) player.sendMessage(Text.literal("§a[EunSearch] §e" + itemName + "§a 的容器详情:"), false);
        int idx = 1;
        for (BlockPos[] g : groups) {
            int mnX = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mnZ = Integer.MAX_VALUE, mxZ = Integer.MIN_VALUE;
            for (BlockPos p : g) { if (p.getX() < mnX) mnX = p.getX(); if (p.getX() > mxX) mxX = p.getX(); if (p.getZ() < mnZ) mnZ = p.getZ(); if (p.getZ() > mxZ) mxZ = p.getZ(); }
            int groupCount = 0, groupDirect = 0;
            List<int[]> groupShulkerSlots = new ArrayList<>();
            for (BlockPos p : g) {
                var ci = posToInfo.get(p);
                if (ci == null) continue;
                groupCount += ci.targetCount;
                groupDirect += ci.directCount;
                groupShulkerSlots.addAll(ci.shulkerSlots);
            }
            StringBuilder sb = new StringBuilder();
            if (showDetail) sb.append("§7#").append(idx).append(" ");
            sb.append("§e(").append(mnX).append(",").append(g[0].getY()).append(",").append(mnZ).append(")");
            if (mxX != mnX || mxZ != mnZ) sb.append("§7~§e(").append(mxX).append(",").append(g[0].getY()).append(",").append(mxZ).append(")");
            var firstCi = posToInfo.get(g[0]);
            if (firstCi != null && firstCi.containerType != null) {
                sb.append(" §3").append(containerTypeName(firstCi.containerType));
            }
            sb.append(" §f").append(String.format("%,d", groupCount)).append("个");
            if (showDetail && !groupShulkerSlots.isEmpty()) {
                sb.append(" §7(散装:").append(groupDirect).append(" 盒子:");
                for (int i = 0; i < groupShulkerSlots.size(); i++) {
                    int[] ss = groupShulkerSlots.get(i);
                    if (i > 0) sb.append(",");
                    sb.append("§b槽").append(ss[0]).append("§7:").append(ss[2]);
                }
                sb.append(")");
            }
            sb.append(" ");
            String cType = (firstCi != null && firstCi.containerType != null) ? firstCi.containerType : "chest";
            String cmd = "/eunlook " + mnX + " " + g[0].getY() + " " + mnZ + " " + mxX + " " + mxZ + " " + cType;
            var text = Text.literal(sb.toString())
                .append(Text.literal("§8[§a▶§8]")
                    .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand(cmd))
                        .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(
                            Text.literal("§7点击看向容器")))));
            player.sendMessage(text, false);
            idx++;
        }
    }
}
