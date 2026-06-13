# EunSearch — Minecraft Fabric 模组 + Mineflayer 机器人联动

通过 **NapCat**（OneBot v11）桥接 QQ 群与 Minecraft 服务器，实现群内查询容器物品 + 机器人自动取货配送。

## 项目架构

```
QQ 群 ──WebSocket──> NapCat ──WebSocket──> Fabric Mod (服务端)
                                              │
                                              │ TCP :3002 (JSON Lines)
                                              ▼
                                         bot.js (Mineflayer 机器人)
                                              │
                                              │ 导航/取货/送货
                                              ▼
                                       Minecraft 服务器 (世界内操作)
```

### 两部分组件

| 组件 | 技术栈 | 作用 |
|------|--------|------|
| **Fabric Mod** | Java 21, Fabric 1.21.11 | 扫描 .mca 容器数据、对接 QQ 群、生成物品图片、响应 bot 查询 |
| **bot.js** | Node.js, Mineflayer | 接收玩家 `!fetch` 指令、自动前往仓库取货、潜影盒递归处理、假人配送 |

---

## Mod 功能

- **QQ 群查询**：群里发 `#铁锭` → 扫描指定区域容器 → 返回含 MC 原版纹理的图片
- **指定物品扫描**：多物品逗号分隔、中英文名辨识、按占比生成进度条表格
- **全物品扫描**：统计区域内全部物品种类及数量，宫格降序排列
- **容器搜索标记**：`/scan search` 查找含指定物品的容器，BlockDisplayEntity 发光标记
- **直接读取 .mca**：不加载区块，从存档文件解析容器 NBT，兼容新旧格式 + 潜影盒递归
- **指令 Tab 补全**：1000+ 物品 ID 和中文名，自动联想
- **热重载配置**：修改 `config/eun_search.json` 后自动生效，NapCat 参数变更自动重连 WebSocket
- **Mixin 注册表绕过**：自动放行白名单 bot 账号，避免 registry sync 断连

## Bot 功能

- **`!fetch <标签> <物品> [数量]`**：玩家聊天触发，机器人自动取货
  1. 先扫自身背包 + 潜影盒
  2. 不足时通过 TCP 向 Mod 查询仓库容器位置
  3. 按物品数量降序规划取货路线
  4. 前往容器取出物品，遇到潜影盒则放置→打开→取货→破坏→丢弃空盒
  5. 通过 Carpet `/player` 假人交付物品给请求者
- **`!come`**：让机器人导航到玩家位置
- **自动重连**：掉线后自动重登（LittleSkin 外置登录）

---

## Mod ↔ Bot 通信协议

**TCP 端口**: `3002`（bot.js 中 `TCP_PORT` 常量）

**协议格式**: JSON Lines（每行一个 JSON，`\n` 分隔）

**bot → mod**：
```json
{"type":"fetch","tag":"仓库","itemId":"minecraft:diamond","count":64}
```

**mod → bot**：
```json
{
  "type": "scan_result",
  "requestTag": "仓库",
  "itemId": "minecraft:diamond",
  "dimension": "minecraft:overworld",
  "found": 3,
  "containers": [
    {"x":10,"y":64,"z":20,"count":128,"type":"chest","shulkerSlots":[{"slot":5,"count":32}]}
  ]
}
```

---

## 环境要求

| 组件 | 版本 |
|------|------|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.16.9+ |
| Fabric API | 0.141.3+ |
| Java | 21 |
| Node.js | 18+ |
| NapCat | 最新版 (OneBot v11) |

---

## 快速开始

### 1. 构建并安装 Mod

```bash
gradle build
```

构建产物 `build/libs/eun_search-1.0.0.jar`，放入服务器 `mods/` 目录。

构建过程自动从 Minecraft 客户端 JAR 提取约 1900 张物品 + 方块 + 实体纹理，并内置 1000+ 中文名映射。

### 2. 配置 Mod

首次启动后编辑 `config/eun_search.json`：

```json
{
  "napcat": {
    "host": "127.0.0.1",
    "port": 3001,
    "token": "",
    "wsPath": "/"
  },
  "defaultGroups": ["879313249"],
  "scans": []
}
```

Mod 启动时自动连接 NapCat WebSocket 并在 `3002` 端口开启 TCP 服务供 bot 连接。

### 3. 添加扫描区域

```mcfunction
# 指定物品
/scan item iron_ingot 13 142 75 to 11 150 73 铁锭

# 多物品（逗号分隔）
/scan item iron_ingot,diamond 0 64 0 to 100 80 100 贵金属

# 全物品
/scan all 0 0 0 to 100 80 100 仓库
```

### 4. 启动 Bot

```bash
npm install
node bot.js
```

Bot 使用 LittleSkin 外置登录连接到服务器。启动后可通过 `!fetch` 指令触发取货。

> **注意**：bot.js 中的服务器地址和账号信息需要在代码中修改（见下方问题说明）。

---

## 已知问题

### 1. 缺失依赖 `yggdrasil`

`bot.js` 第 346 行 `require('yggdrasil')`，但 `package.json` 中未声明该依赖。运行前需手动安装：

```bash
npm install yggdrasil
```

### 2. Bot 配置文件硬编码

`bot.js` 第 6-10 行硬编码了服务器 IP、账号密码和世界坐标。建议改为环境变量或外部配置文件，避免泄露。

### 3. TCP 单连接限制

`BotTCPServer` 仅维护一个 bot 连接，新连接会断开旧连接。多 bot 场景需要扩展。

### 4. 日志文件为空

`logs/latest.log` 为 0 字节，日志配置可能未正确写入。

### 5. Bot `setMaxListeners(50)` 警告

`bot.js` 第 374 行提高 EventEmitter 监听器上限至 50，可能掩盖内存泄漏。建议排查未移除的监听器。

---

## 开发者须知

### 不要使用 Mojang 原始包名

本项目使用 **Fabric Yarn 映射**，所有 NBT 相关类必须使用映射后的包名：

| 正确 (Yarn) | 错误 (Mojang Mapping) |
|---|---|
| `net.minecraft.nbt.NbtCompound` | ~~`net.minecraft.nbt.CompoundTag`~~ |
| `net.minecraft.nbt.NbtList` | ~~`net.minecraft.nbt.ListTag`~~ |
| `net.minecraft.nbt.NbtElement` | ~~`net.minecraft.nbt.Tag`~~ |
| `net.minecraft.nbt.NbtIo` | ~~`net.minecraft.nbt.NbtIo`~~ |
| `net.minecraft.nbt.AbstractNbtNumber` | ~~`net.minecraft.nbt.NumericTag`~~ |

同时注意：
- Brigadier 命令系统使用 `com.mojang.brigadier.*`（这个是正确的，Brigadier 是 Mojang 库，不经过 Yarn 映射）
- 实体 / 方块 / 物品使用 `net.minecraft.*` 映射名（如 `net.minecraft.block.Blocks`，而非 Mojang 名）
- `.mca` 文件读取使用标准 `java.io.RandomAccessFile`，不要引入 Mojang 内部类

**如果错误使用 Mojang 原始包名，编译将报错（类不存在），运行时会 `NoClassDefFoundError`。**

### 项目结构

```
eun_search-mod/
├── build.gradle                    # Fabric Loom 构建 + 纹理提取任务
├── settings.gradle
├── gradle.properties               # MC 1.21.11 / Fabric 0.141.3
├── bot.js                          # Mineflayer 机器人 (Node.js)
├── test_bot.js                     # Bot 逻辑单元测试
├── package.json                    # Node.js 依赖
├── preview/                        # 图片渲染预览工具
├── logs/                           # 运行日志
└── src/
    ├── main/java/com/eun_search/
    │   ├── EunSearchMod.java          # 入口：初始化 WebSocket + TCP + 指令
    │   ├── config/
    │   │   ├── ModConfig.java      # 配置读写 + 热重载
    │   │   └── ScanEntry.java      # 扫描配置数据模型
    │   ├── qq/
    │   │   └── NapCatClient.java   # NapCat WebSocket 通信
    │   ├── bot/
    │   │   └── BotTCPServer.java   # bot.js TCP 通信桥
    │   ├── scan/
    │   │   └── RegionScanner.java  # .mca 解析 + 容器扫描
    │   ├── command/
    │   │   └── ScanCommand.java    # /scan 指令树 + Tab 补全
    │   ├── quick/
    │   │   └── QuickModeHandler.java  # 快速配置模式
    │   ├── mixin/
    │   │   └── RegistrySyncBypass.java  # bot 注册表绕过
    │   ├── render/
    │   │   ├── ScanImageRenderer.java      # 物品扫描表格渲染
    │   │   ├── ScanAllImageRenderer.java   # 全物品宫格渲染
    │   │   ├── ItemTextureLoader.java      # 纹理加载 + 回退
    │   │   └── ItemNameMap.java            # 1000+ 物品中文名
    │   └── api/
    │       └── EunSearchAPI.java     # 公开 API
    └── test/java/com/eun_search/test/
        ├── RegionScannerIntegrationTest.java
        ├── EdgeCaseTests.java
        ├── ModConfigTest.java
        └── TextureRenderTest.java
```

### 运行测试

```bash
# Mod 单元测试
gradle test

# Bot 逻辑测试
node test_bot.js
```

---

## 指令列表

| 指令 | 说明 |
|------|------|
| `/scan item <物品> x1 y1 z1 to x2 y2 z2 <标签>` | 添加物品扫描（多物品逗号分隔） |
| `/scan all x1 y1 z1 to x2 y2 z2 <标签>` | 添加全物品扫描 |
| `/scan list` | 列出所有扫描 |
| `/scan remove <标签>` | 删除扫描 |
| `/scan run <标签>` | 手动运行扫描 |
| `/scan search <标签> <物品>` | 标记含物品的容器 |
| `/scan markClear` | 清除发光标记 |
| `/scan quick` | 快速配置模式 |
| `/scan config group add/remove/list` | 管理默认 QQ 群 |
| `/scan config napcat host/port/token` | 配置 NapCat 连接 |
| `/scan config reload` | 热重载配置 |
