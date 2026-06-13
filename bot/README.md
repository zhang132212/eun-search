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
    "player": { "email": "...", "pass": "...", "name": "..." },
    "idle_pos": { "x": 1702, "y": 124, "z": -289.5 },
    "place_pos": { "x": 1702, "y": 124, "z": -290 },
    "drop_pos": { "x": 1695, "y": 124, "z": -294 }
}
```

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
