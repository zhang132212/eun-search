# EunSearch

Minecraft 26.2 容器搜索与自动取物项目。EunSearch 负责找物品，Eun Native Courier 负责让 Carpet 假人走到仓库取货、回收余料并交货。

## 项目结构与联动关系

| 目录 | 作用 | 运行方式 |
| --- | --- | --- |
| [mod/](mod/) | EunSearch，模组 ID `eun_search`：扫描区域、查询容器、中文物品名和搜索标记 | Fabric 服务端模组 |
| [native-bot/](native-bot/README.md) | Eun Native Courier，模组 ID `eun_native_courier`：寻路、取物、潜影盒处理、回收、假人运输 | Fabric 服务端模组，依赖 EunSearch 和 Carpet |
| [bot/](bot/README.md) | 旧 Mineflayer 客户端 bot 与看门狗，保留供参考 | 独立 Node.js 进程 |

**26.2 原生取物请看 [Native Courier 安装与配置文档](native-bot/README.md)。两个模组需要分别安装；Courier 不包含也不替代 EunSearch。**

```text
/search mis 钻石 → EunSearch 查询并展示位置

/botSearchAll mis 钻石 64 → Native Courier
  → 调用同服 EunSearchAPI 搜索仓库
  → Carpet 工作假人 Courier 寻路、开箱取物
  → 把需求物品丢给收货假人，核对后让收货假人下线保存
  → 回收多余盒子和散货，返回待机点
  → 中文完成消息 + 可复制的 /player <收货假人名> spawn
```

原生 bot 直接在服务端调用 EunSearch 的 Java API，正常使用不需要 TCP、Node.js、Mineflayer 或额外登录账号。可选 TCP 接口只用于兼容旧的 `bot_command` 调度消息。当前 26.2 分支的 `/botSearchAll`、`/botSearchChest`、`/botStop` 由 **Native Courier** 注册；只安装 EunSearch 时没有这些取物入口。

## 安装与开始使用

服务端使用 Minecraft **26.2**、Java **25**、Fabric Loader **0.19.3 或兼容版本**及 Fabric API **0.156.0+26.2**。

1. 将 EunSearch 的 `eun_search-*.jar` 放进服务端 `mods/`。搜索功能可单独使用。
2. 要自动取物，再安装兼容 26.2 的 Carpet 和 `eun-native-courier-*.jar`，按 [bot 文档](native-bot/README.md#安装与第一次取物) 配置走道、待机点、处理站和回收站。
3. 重启服务端，在仓库所在维度创建扫描标签，然后用 `/search` 确认物品可找到。

```mcfunction
/scan all <x1> <y1> <z1> to <x2> <y2> <z2> mis
/search mis 钻石
/botSearchChest mis 钻石 64
```

尖括号参数需替换为真实坐标；扫描区域应包括存货容器。**EunSearch 的扫描区域与 Courier 的可走区域是两套配置**，后者在 `config/eun-native-courier/config.json` 中设置，不会自动读取 `/scan range`。

## EunSearch 常用指令

| 指令 | 功能 |
| --- | --- |
| `/scan help` | 查看完整帮助 |
| `/scan all <x1> <y1> <z1> to <x2> <y2> <z2> <标签>` | 保存扫描区域 |
| `/scan list`、`/scan run <标签>` | 查看标签、执行扫描 |
| `/search <标签> <物品>` | 查询物品及容器位置 |
| `/scan search <标签> <物品>` | 搜索并标记容器 |
| `/scan log` | 切换扫描槽位日志 |
| `/scan quick` | 切换快速坐标配置 |
| `/scan range ...` | EunSearch 的范围配置；原生 Courier 使用自己的 `walk` |
| `/scanTcp <标签> <端口>` | 可选 TCP 服务；原生 bot 默认不需要 |

取物命令、Tab 补全、两种回收模式、失败恢复及构建方法见 [Native Courier README](native-bot/README.md)。旧 Mineflayer 的启动与配置见 [bot/README.md](bot/README.md)，不要让两套实现同时控制同一个工作假人。

## 构建

EunSearch：在 `mod/` 中使用 Java 25 执行 `./gradlew build`（Windows 为 `gradlew.bat build`），产物在 `mod/build/libs/`。

Native Courier 是独立 Gradle 项目，需要另行准备 Carpet 编译依赖，步骤见 [构建与测试](native-bot/README.md#从源码构建)。仓库现有 GitHub Actions 构建 EunSearch；Courier 的本地自动化测试说明见 [TESTING.md](native-bot/TESTING.md)。

许可证：[MIT](LICENSE)。