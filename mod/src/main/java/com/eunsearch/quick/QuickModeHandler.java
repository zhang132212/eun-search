package com.eunsearch.quick;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class QuickModeHandler {

    private static final Map<UUID, QuickState> STATES = new ConcurrentHashMap<>();

    enum Stage {
        FIRST, SECOND, RECORDED
    }

    static class QuickState {
        Stage stage;
        BlockPos first, second;
    }

    public static void toggle(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        QuickState state = STATES.get(id);

        if (state == null) {
            enter(player);
        } else {
            exit(player, state);
        }
    }

    private static void enter(ServerPlayerEntity player) {
        QuickState state = new QuickState();
        state.stage = Stage.FIRST;
        STATES.put(player.getUuid(), state);
        send(player, "§a[EunSearch] 快速配置模式已开启\n§7左键方块 → 记录坐标  右键 → 撤销上一个记录\n§7输入 §e/scan quick §7可退出并复制坐标");
    }

    private static void exit(ServerPlayerEntity player, QuickState state) {
        if (state.stage == Stage.RECORDED && state.first != null && state.second != null) {
            String coords = state.first.getX() + " " + state.first.getY() + " " + state.first.getZ()
                    + " to " + state.second.getX() + " " + state.second.getY() + " " + state.second.getZ();
            send(player, "§a[EunSearch] 坐标已就绪，点击复制:");
            player.sendMessage(
                    Text.literal("§e" + coords)
                            .styled(s -> s.withClickEvent(
                                    new ClickEvent.CopyToClipboard(coords)
                            ).withColor(Formatting.YELLOW)),
                    false
            );
        }
        STATES.remove(player.getUuid());
        send(player, "§7[EunSearch] 快速配置模式已退出");
    }

    public static void onLeftClick(ServerPlayerEntity player, BlockPos pos) {
        UUID id = player.getUuid();
        QuickState state = STATES.get(id);
        if (state == null) return;

        switch (state.stage) {
            case FIRST -> {
                state.first = pos;
                state.stage = Stage.SECOND;
                send(player, "§a[EunSearch] 已记录第一个坐标: §e(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                        + "\n§7请左键点击第二个方块");
            }
            case SECOND -> {
                state.second = pos;
                state.stage = Stage.RECORDED;
                send(player, "§a[EunSearch] 已记录第二个坐标: §e(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                        + "\n§7输入 §e/scan quick §7退出并复制坐标");

                String preview = state.first.getX() + " " + state.first.getY() + " " + state.first.getZ()
                        + " to " + state.second.getX() + " " + state.second.getY() + " " + state.second.getZ();
                send(player, "§7预览: §e" + preview);
            }
            case RECORDED -> {
                send(player, "§e[EunSearch] 两个坐标已记录，右键可撤销上一个，输入 /scan quick 退出");
            }
        }
    }

    public static void onRightClick(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        QuickState state = STATES.get(id);
        if (state == null) return;

        switch (state.stage) {
            case SECOND -> {
                String oldFirst = state.first != null ? "(" + state.first.getX() + "," + state.first.getY() + "," + state.first.getZ() + ")" : "";
                state.first = null;
                state.stage = Stage.FIRST;
                send(player, "§c[EunSearch] 已撤销第一个坐标 §7" + oldFirst);
            }
            case RECORDED -> {
                state.stage = Stage.SECOND;
                String oldSecond = state.second != null ? "(" + state.second.getX() + "," + state.second.getY() + "," + state.second.getZ() + ")" : "";
                state.second = null;
                send(player, "§c[EunSearch] 已撤销第二个坐标 §7" + oldSecond
                        + "\n§7请左键点击第二个方块");
            }
            case FIRST -> {
                send(player, "§7[EunSearch] 没有可撤销的坐标");
            }
        }
    }

    public static boolean isInQuickMode(ServerPlayerEntity player) {
        return STATES.containsKey(player.getUuid());
    }

    public static void removePlayer(UUID uuid) {
        STATES.remove(uuid);
    }

    private static void send(ServerPlayerEntity player, String msg) {
        player.sendMessage(Text.literal(msg), false);
    }
}
