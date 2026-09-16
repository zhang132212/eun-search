# Eun Native Courier · Minecraft 26.2

原生 Fabric 服务端取物 bot。使用 Carpet 假人完成真实移动、开箱、潜影盒取物、余料回收和交货，不需要 Mineflayer 或独立客户端进程。当前版本 **0.1.0**，适用于预先配置好走道和回收设施的仓库。

[下载 0.1.0 测试版 jar 与示例配置](https://github.com/zhang132212/eun-search/releases/tag/courier-v0.1.0-mc26.2)。依赖需另外安装，详见下文。

## 与 EunSearch 的关系

本目录是 [eun-search](https://github.com/zhang132212/eun-search) 仓库中的**独立附加模组**，产物为 `eun-native-courier-0.1.0.jar`，模组 ID 为 `eun_native_courier`。它必须与同服的 **EunSearch（`eun_search`）和 Carpet** 一起安装。

| 组件 | 负责什么 |
| --- | --- |
| EunSearch（本仓库 `mod/`） | 保存扫描标签和区域；查询物品在哪些容器里；提供中文物品名及搜索结果 |
| Native Courier（本目录） | 检查背包、规划有限范围内的路径、取物和处理潜影盒、执行回收流程、核对交货 |
| Carpet | 创建工作假人和收货假人，提供移动/交互动作以及假人下线保存 |
| 旧 `bot/` | Mineflayer 实现，仅供旧流程参考；不是本模组的运行依赖 |

`/search mis 钻石` 由 EunSearch 处理，用于查看搜索结果；`/botSearchAll mis 钻石 64` 和 `/botSearchChest mis 钻石 64` 由本模组处理，用于实际取货。

取物时通过同进程的 `EunSearchAPI.getScanEntry()`、`EunSearchAPI.searchItem()` 查询标签与容器，通过 `ItemNameMap` 解析中文名称和补全。搜索结果用于定位，真正取货时仍检查现场容器和槽位。**正常取物不经过 TCP**；`tcpPort: 0` 即可工作。

```text
玩家下达取物指令
  → 检查 Courier 背包，只补取缺少的数量
  → EunSearch 查找来源 → Courier 在配置范围内走到容器
  → 取散货，或把潜影盒搬到处理站取货并收回盒子
  → 创建收货假人 → 丢出需求数量 → 核对收货 → 收货假人下线保存
  → 回收多余盒子和散货 → 完成回收站交互 → 返回待机点
  → 中文完成提示及淡蓝色 [点击此处复制指令]
```

## 版本与依赖

| 依赖 | 本版本构建/验证所用版本 |
| --- | --- |
| Minecraft Java Edition | **26.2** |
| Java JDK / 服务端 Java | **25** |
| Fabric Loader | **0.19.3** |
| Fabric API | **0.156.0+26.2** |
| Carpet | 26.2 兼容构建，验证使用 `fabric-carpet-26.2+v260616.jar` |
| EunSearch | **1.0.14，Minecraft 26.2 构建**；本仓库 `26.2` 分支 |

只需安装在服务端。模组依赖声明允许其他 Carpet/EunSearch 版本加载，但实际调用了其内部接口，不能据此保证其他版本兼容。本项目不分发 Carpet 依赖。

## 安装与第一次取物

1. 将 EunSearch、Carpet、Fabric API 和 `eun-native-courier-0.1.0.jar` 放入同一服务端的 `mods/`，重启服务端。
2. 创建目录 `config/eun-native-courier/`，把本目录的 [config.mis.example.json](config.mis.example.json) 复制为其中的 `config.json`，替换成自己仓库的坐标和标签。示例是已有 `mis` 仓库的配置，不会自动搭建机器，不能不改坐标就用于任意世界。
3. 在仓库所在维度，用 EunSearch 设置包含存货容器的扫描区域。已有 `mis` 标签时不用重复创建。

   ```mcfunction
   /scan all <x1> <y1> <z1> to <x2> <y2> <z2> mis
   /search mis 钻石
   ```

4. 加载配置、检查工作假人和回收目标，再执行小额取物：

   ```mcfunction
   /eunfetch reload
   /eunfetch spawn
   /eunfetch status
   /eunfetch recovery
   /botSearchChest mis 钻石 1
   ```

`/eunfetch recovery` 是只读检查，显示实际站位和扫描到的回收目标，不存入物品或点击音符盒。配置错误需结合 `status` 和服务端日志排查；`reload` 的 `OK` 不代表所有站位已验证。刚启动时须等背包恢复检查结束后再下任务。

任务结束后点击淡蓝色 **[点击此处复制指令]**，到同一服务器的收货位置执行复制的 `/player <收货假人名> spawn`。货物保存在这个假人的玩家数据中；此命令召回带货假人，后续按服务器的假人取货方式拿出物品。

### 指令与 Tab 补全

以下 bot 指令需要 **OP 2 级权限**。一次仅允许一个任务，单次数量 **1–2304**，实际运输量还受背包容量、物品堆叠上限和工作站条件限制。

| 指令 | 作用 |
| --- | --- |
| `/botSearchAll <标签> <物品> <数量> [收货假人名]` | 在 EunSearch 返回的来源中取物 |
| `/botSearchChest <标签> <物品> <数量> [收货假人名]` | 只从普通箱/陷阱箱取物，也可处理箱子里的潜影盒 |
| `/botStop [工作假人名]` | 停止当前任务，保留背包与在线工作假人 |
| `/eunfetch fetch ...`、`/eunfetch chest ...` | 与上述两种取物入口对应 |
| `/eunfetch continue <标签> <物品> <本次总数> [新收货假人名]` | 兼容入口；和普通取物一样先检查当前背包，不重放旧任务 |
| `/eunfetch reload` | 重载配置；需先停止正在运行的任务 |
| `/eunfetch spawn` | 在待机点召唤工作假人，已在线则沿用 |
| `/eunfetch status`、`/eunfetch stop` | 查看状态、停止任务 |
| `/eunfetch inspect` | 查看工作假人位置和待机点附近方块 |
| `/eunfetch recovery` | 检查回收配置、站位和目标 |

`/botSearchChest`、`/botSearchAll` 支持逐参数 Tab：允许的标签 → 中文物品名、英文短 ID 或完整 ID → 常用数量 → 新收货假人名。例：`钻石`、`diamond`、`minecraft:diamond`。数量可手动输入范围内任意整数。补全读取注册表和名称表，不扫描仓库或触发任务。

收货假人名可省略，由模组生成。指定名称限 1–16 位英文字母、数字或下划线，不得与工作假人相同；已在线或存档背包非空的收货假人会被拒绝。请使用专用名字。

## 配置说明

完整配置见两个 JSON 示例。它们默认 `tcpPort: 0`，无需 `/scanTcp`。

| 字段 | 含义 |
| --- | --- |
| `worker` | 工作假人名，示例为 `Courier` |
| `dimension` | 仓库维度，例如 `minecraft:overworld`；必须与 EunSearch 标签维度一致 |
| `allowedTag` | 允许使用的 EunSearch 扫描标签，例如 `mis` |
| `idle` | 待机时脚下的 `[x,y,z]`，须在主 `walk` 内且能站立 |
| `walk` | `[minX,maxX,minY,maxY,minZ,maxZ]`，约束假人脚下位置 |
| `place` | 放潜影盒的**支撑方块**整数坐标；盒子放在其上方，那里须为空且无人阻挡 |
| `autoSpawn` | 启动/重载后自动召唤工作假人，示例为 `true` |
| `autoRespawn` | 工作假人死亡或下线后清空任务并重召，示例为 `true` |
| `recovery` | 下述 `sequence` 或 `drop` 回收方案 |
| `tcpPort` | `0` 禁用可选 TCP；正数连接同服 EunSearch 端口 |

**扫描区域与寻路范围分别配置。** EunSearch 的区域用于找货，Courier 的 `walk` 与 `recovery.walkAreas` 的并集用于走路；不会从 `/scan range` 自动导入。来源容器、处理站和回收站都需要有可达走道，相关区块需要已加载。

### 固定流程回收：sequence

[config.mis.example.json](config.mis.example.json) 使用这一模式：

```json
"recovery": {
  "mode": "sequence",
  "batchItems": 1728,
  "batchDelayMillis": 2000,
  "loose": {"action": "loose", "position": [-118.51, 77, -145.36], "facing": "south", "range": 4.5},
  "walkAreas": [[-121, -115, 76.875, 78.67, -147, -144.4]],
  "steps": [
    {"action": "deposit", "position": [-117.45, 77, -145], "facing": "south", "range": 4.5},
    {"action": "use", "position": [-119.55, 76.875, -145], "facing": "south", "range": 4.5, "block": "minecraft:note_block"}
  ]
}
```

以上是 `config.json` 中的一个字段片段，完整可复制 JSON 见示例文件。

1. **盒子**：走到 `deposit.position`，按朝向找到最近的普通箱/陷阱箱，把待回收潜影盒存进去，盒内剩余物品一并保留。
2. **散货**：走到 `loose.position`，打开朝向最近的潜影盒，塞入物品后关闭。**现场机器负责自动打盒、收走并补充新空盒**；Courier 不挖也不拾取这台机器的盒子。
3. 每批最多 `batchItems` 个（1–1728），还受槽位与堆叠上限限制。有剩余散货时至少等待 `batchDelayMillis` 毫秒（2000–60000），再次执行盒子回收检查，再装下一批；等待新空盒超出 30 秒会失败。
4. 待回收盒子和散货都处理完，才执行末尾 `use`：走到指定位置右键最近的音符盒。此时本轮回收才算完成。

`steps` 支持 `deposit`、`walk` 途经点和 `use` 音符盒；必须包含且只能包含一个 `deposit`，最后一步必须为 `use`。`loose` 单独配置，不写进 `steps`。有散货需要处理时应配置 `loose`。

方向支持 `south/west/north/east`。扫描在对应朝向左右各 45 度的前方扇区内寻找最近匹配方块，`range` 最大 4.5 格；最近目标被遮挡会停止。配置的是脚下位置，程序会在附近寻找实际可站立表面；用 `/eunfetch recovery` 查看对齐结果。示例 Y 值对应具体机器的地面/方块顶面，移植时需要重新测量。

### 定点定向丢出：drop

完整示例见 [config.mis.drop.example.json](config.mis.drop.example.json)。替换 `recovery` 即可切换：

```json
"recovery": {
  "mode": "drop",
  "position": [-128.5, 77, -148.5],
  "yaw": 90,
  "pitch": 61.6,
  "receipt": [-129.5, 76.8, -148.5]
}
```

走到 `position` 后停止移动，按 `yaw/pitch` 丢出待回收盒子和散货，并核对 `receipt` 附近容器的收取或地面物品落点。这个模式不执行音符盒步骤。朝向：南 `0`、西 `90`、北 `180`、东 `-90`；俯仰角正数向下，负数向上。

旧配置未提供 `recovery` 时，仍兼容 `drop`（丢出站位）和 `dropLook`（瞄准点）；新配置建议明确选择模式。

## 背包、交货与异常处理

- 任务先检查主背包。已有目标物品计入需求，只补取差额；不足时先回收无关物品，并保留能补足需求的含货潜影盒。数量已足够时先交付需求数量，再回收超额及无关物品。
- 从仓库取出的潜影盒会搬到 `place` 上方处理，取完货正常挖回，核对盒子内容后收起，交货后统一回收，不直接在挖盒处扔掉。
- 主背包 36 格全满时，若副手为空，会临时交换以空主手打开容器，并立即恢复物品；副手也占用且无空位时停止，不覆盖副手。
- 交货使用真实掉落物，并限定收货假人拾取。核对实际数量后调用 Carpet 假人下线保存，相当于 `/player <name> kill`，**不是原版 `/kill` 的死亡掉落**。回收失败时不发送完整成功提示；已交付数量可从状态及任务记录核对。
- 仓库不足时可能部分交货，提示实际数量；不能把指令中的数量上限当成必定可完成的保证。
- 死亡或普通下线会清空工作假人的任务；开启 `autoRespawn` 时约 20 tick 后在 `idle` 重召。离开 `walk` 与额外走道覆盖的 **X/Z 区块**或进入其他维度时，无论普通重召开关如何，都会终止任务、下线保存并在待机点重召。正常寻路仍按精确坐标边界约束。
- 工作假人的自动重召不作用于收货假人。服务器重启不重跑旧任务；启动时会检查空背包是否需要恢复保存数据，非空背包不覆盖，普通重召不重复读取旧存档。
- 路径不通、箱满、开盒失败或操作中止时停止动作、关闭菜单，保留未转移物品；处理中已放置的盒子和未确认交货的假人可能需要人工处理。

每个任务写入服务端 `config/eun-native-courier/<任务ID>.json`，包含阶段、数量、处理中的盒子坐标和货物实体 UUID。再次取物会利用当前背包，但**不会自动认领上次留在地上或已放置的盒子**，也不会自动重放历史任务。

运输依赖**同一服务器的玩家存档**，本模组不提供跨服玩家数据同步；外部世界同步/还原工具若覆盖玩家数据，也会影响保存的货物。

## 可选 TCP 兼容

需要接入旧调度程序时，设置例如 `tcpPort: 3008`。模组会检查 EunSearch 是否已启动此端口，未启动则为 `allowedTag` 启动，再以 `worker` 名称注册本机 TCP 客户端。复用现有端口时，需确保它对应相同仓库标签。

本仓库 26.2 分支的 EunSearch TCP 服务绑定 `127.0.0.1`，Courier 也连接此地址。支持 `type: "bot_command"` 下的 `fetchAll`、`fetchChest`、`stop` 调度，不代表实现了旧 Mineflayer 客户端的全部协议。直接执行游戏内 bot 命令始终不依赖此通道。

## 从源码构建

需要 **Java 25 JDK**，首次构建需要下载 Gradle/Minecraft/Fabric 依赖。EunSearch 通过反射连接，不需要把 EunSearch jar 加入编译类路径；Carpet 需要手动提供。

1. 将与服务器一致的 26.2 Carpet jar 复制为 `native-bot/libs/carpet.jar`（自行创建 `libs` 目录）。该依赖不提交到 Git。
2. 将 `JAVA_HOME` 指向 Java 25，在 `native-bot/` 目录执行：

   ```bash
   # Linux / macOS
   ./gradlew build
   ```

   ```powershell
   # Windows PowerShell
   .\gradlew.bat build
   ```

3. 构建包含自动化测试。安装产物为 `build/libs/eun-native-courier-0.1.0.jar`；`-sources.jar` 是源码附件，不放进服务端 `mods/`。

部分 Windows 环境在中文路径下运行 Gradle 测试进程会失败，可使用随附脚本在临时英文路径内构建，再把 jar 复制回本项目：

```powershell
.\build-windows.ps1 -JavaHome 'C:\Java\jdk-25' -StageRoot 'C:\Temp'
```

参数需替换成本机路径；默认读取 `JAVA_HOME` 和 `TEMP`。脚本打印并保留本次临时构建目录供排错，不复用旧源码目录。只跑测试可执行 `./gradlew test`。测试覆盖与现场验证步骤见 [TESTING.md](TESTING.md)。

## 当前限制

- 单个工作假人、单个允许标签、同一时间一个任务。面向固定仓库走道，不是全地形通用寻路器；支持普通步行、部分台阶/半砖、双箱和一层潜影盒。
- 只读取已加载区块，不会沿路拆建筑或瞬移取货；复杂机器的时序和可打开空间必须现场验证。
- 不支持跨维度取物、嵌套收纳袋、跨服务器同步或自动重放失败任务。
- Carpet 创建新的假人名字时可能同步查询 Mojang 资料，产生主线程停顿；当前版本未实现异步预热，不能承诺无卡顿。
- 完成提示已中文化，状态和部分异常仍含英文。

许可证沿用仓库 [MIT](../LICENSE)。
