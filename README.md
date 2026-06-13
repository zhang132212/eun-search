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

## 部署

### 服务端
1. 将 `mod/build/libs/eun_search-*.jar` 放入服务端 `mods/` 目录
2. 启动服务端
3. 在游戏内执行 `/scanTcp <标签> <端口>` 注册 TCP 端口

### Bot
```bash
cd bot
双击 start.bat
```
按提示填写配置（TCP端口、账号、坐标），启动后自动连接服务端。
