# EunSearch

Minecraft 1.21.11 容器物品扫描与 Bot 联动系统

## 项目结构

```
mod/    — Fabric 服务端模组 (EunSearch)
bot/    — Mineflayer 机器人 + 看门狗
```

## 模组功能

- `/scan` — 划定区域扫描容器物品
- `/scan search` — 搜索并发光标记含特定物品的容器
- `/scan range` — 预设 bot 寻路范围
- `/scan log` — 开关扫描结果槽位详细日志
- `/scan quick` — 快速配置坐标
- `/scanTcp` — 多 bot TCP 端口管理
- `/botSearchAll` / `/botSearchChest` — 触发 bot 取物
- `/botStop` — 停止指定 bot

## Bot 使用

```bash
cd bot
npm install
node watchdog_direct.js
```

按提示填写配置，启动后自动连接到服务端 TCP 端口。
