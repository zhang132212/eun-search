# EunSearch Mod

Minecraft 1.21.11 Fabric 容器物品扫描模组，配合 Mineflayer bot 实现自动取物。

## 功能

- **区域扫描**：直接读取 `.mca` 区域文件，无需加载区块
- **双箱结构**：自动识别左右半箱，正确合并 0-53 槽位
- **发光标记**：`/scan search` 用 BlockDisplay 实体标记含目标物品的容器
- **多 bot 框架**：`/scanTcp` 管理多个 TCP 端口，每个端口绑定扫描标签
- **寻路范围预设**：`/scan range` 限制 bot 开箱站位，防止卡寻路
- **点击查看**：聊天栏容器列表附带 `[▶]`，点击即转向容器 + 绿色发光标记
- **Mixin 注册表绕过**：自动放行 bot 账号

## 指令

| 指令 | 说明 |
|------|------|
| `/scan all x1 y1 z1 to x2 y2 z2 <标签>` | 添加全物品扫描 |
| `/scan list` | 列出所有扫描 |
| `/scan remove <标签>` | 删除扫描 |
| `/scan run <标签>` | 手动运行扫描 |
| `/scan search <标签> <物品>` | 搜索并标记容器 |
| `/scan markClear` | 清除标记 |
| `/scan quick` | 快速配置坐标 |
| `/scan range <标签> set x y z to x y z` | 预设寻路范围 |
| `/scan range <标签> list` | 列出寻路范围 |
| `/scan range <标签> remove x y z to x y z` | 删除寻路范围 |
| `/scan log` | 开关槽位详情日志 |
| `/scan config reload` | 热重载配置 |
| `/scanTcp <标签> <端口>` | 注册 TCP 端口 |
| `/scanTcp list` | 列出端口 |
| `/scanTcp remove <端口>` | 关闭端口 |
| `/botSearchAll <标签> <物品> [数量]` | 取物(全部容器) |
| `/botSearchChest <标签> <物品> [数量]` | 取物(仅箱子) |
| `/botStop <bot名>` | 停止 bot |

## 构建

```bash
gradle build
```

产物 `build/libs/eun_search-*.jar` 放入服务端 `mods/`。

## 配置

`config/eun_search.json`：

```json
{
  "scans": [
    {
      "tag": "仓库",
      "allItems": true,
      "x1": 0, "y1": 64, "z1": 0,
      "x2": 100, "y2": 80, "z2": 100,
      "dimension": "minecraft:overworld",
      "ranges": [[10, 64, 0, 12, 80, 5]]
    }
  ],
  "tcpPorts": {
    "3002": ["仓库"]
  }
}
```

## 环境

| 组件 | 版本 |
|------|------|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.16.9+ |
| Fabric API | 0.141.3+ |
| Java | 21 |
