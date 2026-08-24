# EunSearch Client (Servux)

独立分支：`client-servux`。

目标：做成一个纯客户端容器搜索 mod，可以在任何运行 **MiniHUD + Servux 容器同步** 的服务端上使用。扫描范围围绕玩家，不扫全图，范围可通过设置界面调整。

## 当前已实现（原型）
- `/esearch <物品>`：以玩家为中心，扫描已加载区块中的容器方块实体，并通过 `servux:entity_data` 请求容器 NBT。
- `/esettings`：打开设置界面，调整扫描半径（格）和每个 tick 的最大请求数。
- `/eclear`：清空搜索结果。
- HUD 显示当前扫描半径、是否连接 Servux、命中数量。
- 配置：`config/eun_search_client.json`。
- **快捷键 J**（可在原版按键设置中改）：主手拿住要查找的物品，按 J 直接在当前范围内搜索该物品。
- **快捷键 I+J**：同时按 I 和 J 打开设置界面；两个键都可在原版按键设置中改。

## 依赖
- Fabric 26.2 + Fabric API
- malilib `0.29.4`
- 服务端需安装 **Servux** 并启用 `entity_data` 数据提供器（MiniHUD 容器同步依赖此通道）。

## 限制
- 只扫描**已加载区块**，不扫全图。
- 未加载区块不会自动请求，后续可加 Servux Bulk 通道或服务端扩展。
- 当前标记先以 HUD 命中数体现，后续可加客户端线框/高亮渲染。
