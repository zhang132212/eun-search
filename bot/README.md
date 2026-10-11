# EunSearch Bot

Minecraft 仓库机器人，配合 EunSearch 模组使用(根目录中已包含)。

## 快速开始

1. 双击 `start.bat` 启动
2. 按提示依次填写：
   - TCP 端口（对应模组 `/scanTcp` 注册的端口）
   - LittleSkin 账号/密码/玩家名
   - 机器人待命坐标
   - 放置潜影盒坐标
   - 丢弃空盒子坐标
3. 配置完整后自动启动机器人

## 配置文件

`bot_config.json` 自动生成，也可手动编辑：

```json
{
    "host": "127.0.0.1",
    "port": 25565,
    "tcp_port": 3002,
    "tcp_token": "与服务端 EUNSEARCH_TCP_TOKEN 相同的随机字符串",
    "chat_control": false,
    "chat_users": [],
    "player": { "email": "...", "name": "..." },
    "idle_pos": { "x": 1702, "y": 124, "z": -289.5 },
    "place_pos": { "x": 1702, "y": 124, "z": -290 },
    "drop_pos": { "x": 1695, "y": 124, "z": -294 }
}
```

服务端和 Bot 建议同时设置环境变量 `EUNSEARCH_TCP_TOKEN`，用于 TCP 握手认证；未设置时仅保留本机兼容模式。

聊天指令默认关闭。只有将 `chat_control` 设为 `true`，并把玩家名加入 `chat_users`，该玩家才能使用 `!fetch`、`!come`、`!stop` 和 `!ceshi`。

Bot 密码只在启动时输入，并通过 `LITTLESKIN_PASSWORD` 传给子进程，不会写入 `bot_config.json`。

## 多 Bot 部署

复制整个 `bot包` 目录，修改 `bot_config.json` 中 `tcp_port` 和 `player.name`，分别在服务端执行 `/scanTcp <标签> <端口>` 注册。

## 游戏内使用

```
/botSearchAll <标签> <物品> [数量]    全部容器取物
/botSearchChest <标签> <物品> [数量]  仅箱子取物
/botStop <bot名>                      停止bot
```

## 依赖

- Node.js >= 18
- mineflayer ^4.37
