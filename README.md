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

## Bot-Mod 联动流程

```
 玩家输入 /botSearchChest "仓库" 橡木原木 1728
          │
          ▼
  ScanCommand 广播到匹配的 TCP 端口
          │
          ▼  TCP ──── BotTCPServer ──── EunSearchAPI ──── RegionScanner
          │                                                  │
          │                                              读取 .mca 文件
          │                                              扫描所有容器
          │                                              合并双箱槽位
          │                                                  │
          │                                                  ▼
          │                                              返回 ScanResult
          │                                                  │
          │                                                  ▼
          ▼  TCP ◄────  {containers:[{x,y,z,type,shulkerSlots,
  Bot 收到仓库数据                     isDoubleChest,partnerX,partnerZ}]}
          │
          ▼
   planContainers()  按散装量降序排列容器路线
          │
          ▼
  ┌─ processOneContainer() 遍历
  │    │
  │    ├─ findStandPos()  找可站立位置
  │    ├─ getPathTo()     预判路径可达性
  │    ├─ nav()           导航到容器旁
  │    ├─ openContainer() 打开箱子
  │    │
  │    ├─ 散装物品 → withdraw() 取出
  │    │
  │    └─ 盒装物品（按槽位）:
  │         ├─ clickWindow(slot,0,1) 取出潜影盒
  │         ├─ 槽位不匹配 → rescan 重新扫描
  │         │
  │         └─ processShulkerBox():
  │               ├─ nav() 到放置点
  │               ├─ placeBlock() 放盒子
  │               ├─ openContainer() 打开盒子
  │               ├─ withdraw() 取出目标物品
  │               ├─ dig() 挖掘盒子 → 捡回
  │               └─ toss() 丢到丢弃点
  │
  ├─ 容器取完 → 下一个容器
  │
  └─ 全部取完
          │
          ▼
   deliverItems()
          │
          ├─ /player <玩家> spawn  召唤假人
          ├─ openContainer(假人)   打开背包
          ├─ deposit()             转交物品
          └─ /player <玩家> kill   移除假人
          │
          ▼
   goIdle()  回到待命点
```
