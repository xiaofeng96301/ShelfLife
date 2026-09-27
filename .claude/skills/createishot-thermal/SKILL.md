---
name: createishot-thermal
description: 用 createishot 温度系统做整合包暖冷向魔改——给任意模组的方块加热源/改热材料、调档位与 HUD、用诊断命令和 mmccpp 的 MCP 工具验证
---

# createishot 温度系统（整合包侧）

给整合包加"冷热"这套玩法时用。

## 0. 一句话模型

三层：**环境层**（群系/海拔/昼夜/雨 → 环境温度）→ **热传导**（体素网格上求稳态温度场，
Gauss-Seidel 松弛）→ **感知层**（玩家体感 = 所在节点温度 + 辐射 − 保温，含潮湿）。

关键性质，决定了你能怎么调：

- **没有"距离衰减公式"**。`getBlockTemperature(pos)` 那种按距离线性插值的做法被明确禁止。
  你看到"离火近就热"是传导 + 辐射真算出来的，所以"墙角放篝火比空地热"是**涌现**的，不是特判。
- **热必须线性累加**。两个营火 = 两份功率，想让热度翻倍就再放一个。
- **Sable 物理结构（载具）有独立的局部热网格**，和世界网格只在边界交换面通信。
  所以"船舱里烧火，热气不往舱外世界跑"是设计如此，不是 bug。
- 玩家 HUD 的颜色和温度计读的是**体感温度**，不是节点温度——两者可能差很多（辐射/装备）。

## 1. 先确认环境

| 东西 | 位置 |
|---|---|
| 配置 | `config/createishot-common.toml`（COMMON 型，跟实例走，不进存档） |
| 热材料数据包 | `data/<任意命名空间>/thermal_materials/*.json` |
| 诊断命令 | `/createishot thermal status` 和 `/createishot thermal at`（都要权限等级 2） |

**数据包目录的命名空间随便写**（`mypack` 就行），不要求放在 `createishot` 下——
它是个 `SimpleJsonResourceReloadListener`，扫所有命名空间。改完 `/reload` 生效，不用重启。

## 2. 最高频需求：让某方块发热

先分类，两条路差一个数量级的工作量：

| 情况 | 做法 | 要写 Java 吗 |
|---|---|---|
| **无条件一直发热**（岩浆、火、岩浆块…） | 数据包 JSON 里给 `source_power` | **不用** |
| **要看状态/方块实体才知道热不热**（点燃的营火、烈焰人燃烧室、可调燃烧器） | 数据包给数值 + 代码注册开关 | 要 |

出厂数据包里"无条件"那批就是纯数据的：`fire` 1800、`lava` 2600、`magma` 600。

### 数据包 schema

单文件单材料，扁平结构，`targets` 里方块 ID 和 `#tag` **可以混写**：

```json
{
  "name": "my_heater",
  "thermal_class": "solid",
  "conductivity": 0.6,
  "heat_capacity": 0.5,
  "emissivity": 0.5,
  "source_power": 2500,
  "open": false,
  "priority": 60,
  "targets": ["mymod:my_heater", "#mymod:heaters"]
}
```

| 字段 | 默认 | 含义 |
|---|---|---|
| `name` | 文件名 | 只用于调试和命令输出 |
| `thermal_class` | `solid` | `air` / `fluid` / `solid`，决定相邻交换基准（`air-air` 1.0、`固体-air` 0.5、`固体-固体` 0.3、带 fluid 的 0.6/0.7） |
| `conductivity` | 0.5 | 导热系数，0 = 完全隔热 |
| `heat_capacity` | 0.5 | 热容，越大升温越慢 |
| `emissivity` | 0.5 | 对流散热倍率，越大越存不住热 |
| `source_power` | 0 | 自身功率，**>0 才算热源** |
| `open` | `class==air` | 是否"通透"：通透方块不遮挡邻居的暴露面计数 |
| `priority` | 0 | 匹配优先级，大者胜 |
| `targets` | `[]` | 方块 ID 或 `#tag` |

两条硬约束（写错整个文件被丢弃并报错）：

- `thermal_class: "air"` **必须** `open: true`，否则墙角效应失效（空气被当墙）。
- `heat_capacity` 必须为正。

**目标不存在是安全的**：方块被移除、模组没装、标签不存在，都只是这条规则不匹配，不会报错。
本模组的 `zz_probe_missing_targets.json` 专门钉住这个行为，别去动它。
所以"我改了没生效"通常不是这类问题，而是 **ID 拼错** 或 **priority 被别人压过**。

### 覆盖出厂数值

不用改它的文件：自己命名空间下写一条同 `priority` 更高的规则盖掉即可。
**别依赖"同级按名字典序"这个兜底**，显式给一个更高的 `priority`。

## 3. 出厂数值参考表

调参前先看这张表，多数需求改个数字就够（`source_power` 无量纲，只有相对大小有意义）：

| name | source_power | 导热 | 热容 | 散热 | 覆盖对象 |
|---|---|---|---|---|---|
| air | 0 | 0.30 | 0.02 | 1.00 | 空气（`open: true`） |
| fire | 1800 | 0.25 | 0.02 | 1.00 | 火、灵魂火 |
| campfire | 3000 | 0.45 | 0.30 | 0.80 | 营火、灵魂营火 |
| lava | 2600 | 0.60 | 3.00 | 0.10 | 岩浆、岩浆锅 |
| burner | 2200 | 0.55 | 0.45 | 0.60 | 烈焰人燃烧室、可调燃烧器、蒸汽口 |
| magma | 600 | 0.60 | 0.70 | 0.55 | 岩浆块 |
| metal | 0 | 0.92 | 0.40 | 0.30 | 各类金属块/栏杆 |
| stone | 0 | 0.50 | 0.60 | 0.50 | 石头及**未匹配方的兜底** |
| glass | 0 | 0.40 | 0.50 | 0.60 | 玻璃类 |
| water | 0 | 0.55 | 4.00 | 0.15 | 水、蜂蜜、巧克力等流体 |
| ice | 0 | 0.70 | 0.90 | 0.40 | 冰/浮冰/蓝冰 |
| wood | 0 | 0.16 | 0.35 | 0.70 | 木材类 |
| leaf | 0 | 0.10 | 0.20 | 0.95 | 树叶 |
| insulator | 0 | 0.05 | 0.15 | 0.90 | 羊毛、桌布 → **保温材料长这样** |

调参直觉：

- **想更保温**：`conductivity` 调低（热量不容易传导走）+ `heat_capacity` 调低（自己不囤热、升温快）。
  两个都低才是"保温"，只压低导热会变成"慢慢加热的砖头"。
- **想更散热**：抬 `emissivity`。
- **想整体变暖/变冷**：别逐个改材料，用配置里的 `physics.conductionScale` /
  `physics.convectionScale` 全局倍率（1.0 是基准，调小对流 = 整体更热）。
- **想让玩家周围更大范围被算热**：`scheduling.influenceRadius`（默认 12）是"以玩家为中心
  发现热源的半径"，调大则更远的火源也会进入计算；`scheduling.sourceMargin`（默认 4）是
  "发现热源后无条件激活的邻域半径"，调大则单个火源点亮更多格子。两个都不用改材料。

## 4. 用代码扩展（mmccpp 侧）

### 4.1 依赖怎么加

Java API 要用到，就得先能在编译期看到 createishot。仓库里已经有 maven-publish 的 `mavenJava`
发布（坐标 `io.github.uicdb:createishot:1.0-SNAPSHOT`），但**本机 `~/.m2/repository/io/github/uicdb/`
下目前只有 mmccpp**，所以先跑一次 `./gradlew publishToMavenLocal`，
并确认那个目录下真的出现了 createishot 再往下走。然后：

```groovy
repositories { mavenLocal() }
dependencies { compileOnly "io.github.uicdb:createishot:1.0-SNAPSHOT" }
```

只 `compileOnly`：运行期靠整合包里的 jar，不要打进你自己模组。

### 4.2 状态相关的热源

`HeatSource` 是个 `@FunctionalInterface`，`power(BlockGetter, BlockPos, BlockState)` 返回当前功率（0 = 不发热）。
**数值来自数据包，代码只管开关**——这样调数值不用重编译：

```java
// 先确认模组在，再碰它的类：缺依赖时直接引用方块类会 NoClassDefFoundError
if (ModList.get().isLoaded("createishot")) {
    HeatSourceRegistry.register("mymod:kiln", (level, pos, state) -> {
        if (!state.getValue(MyKilnBlock.LIT)) {
            return 0f;
        }
        // 功率上限由数据包里该方块的 source_power 给出
        return ThermalMaterialRegistry.INSTANCE.get(state).sourcePower();
    });
}
```

所以一个状态热源是**两件事一起做**：代码注册开关 + 数据包给那个方块 `source_power`。
只做前者会得到 0（兜底材料功率是 0）。

**时机**：createishot 自己在 `FMLCommonSetupEvent` 里 `enqueueWork` 跑 `bootstrap()` 解析热源方块，
你在自己的 `FMLCommonSetupEvent` 里 `enqueueWork` 注册即可（`register` 不会清表，早一点晚一点都行）。
`register` 按注册表 ID 查方块，**方块不存在就静默跳过**——和上面数据包一样，缺模组不会炸。

想读方块实体来调油门（"烧得越旺越热"），照抄 `AeronauticsBurnerHeatSource`：
把数据包的 `source_power` 当上限，乘以从方块实体读出的 0..1 比例再夹紧，
这样对方模组的量纲变了也不会把网格算爆。

### 4.3 数据包从代码注入

**首选还是静态文件**：`src/main/resources/data/<mypack>/thermal_materials/*.json`，
随 jar 打包、一定在初始 data load 时就在，最不容易出问题。

走 mmccpp 的 `VirtualDatapackEvent` 注入也是可行的思路：

```java
event.addRawJson("data/mypack/thermal_materials/kiln.json", json);
```

> **但注入时机没实测过**：createishot 的材料表是 `SimpleJsonResourceReloadListener`，
> 在 `AddReloadListenerEvent` 注册、`TagsUpdatedEvent` 时重建索引。虚拟数据包必须在**初始
> data load 之前**注入才会立刻生效，晚了就得 `/reload` 一次。**写之前先在游戏里验一遍**，
> 别假设它会生效。验法：进世界后 `/createishot thermal status` 看"热材料条目"数字有没有涨。

### 4.4 查询温度：`ThermalApi`（对外门面）

要问温度统一走 `io.github.uicdb.createishot.api.ThermalApi`，**不要**自己拼底层调用：

| 想要 | 调用 |
|---|---|
| 某坐标的节点温度 | `ThermalApi.nodeCelsius(level, pos)` |
| 某坐标的环境基准温度 | `ThermalApi.ambientCelsius(level, pos)` |
| 某坐标比环境热多少 | `ThermalApi.anomalyCelsius(level, pos)` |
| 这个位置有没有算过 | `ThermalApi.hasData(level, pos)` |
| 生物的完整体感 | `ThermalApi.readout(entity)` |
| 生物的体感温度 | `ThermalApi.perceivedCelsius(entity)` |
| 生物的热阻 | `ThermalApi.insulationOf(entity)` |
| 这方块是不是热源 | `ThermalApi.isHeatSource(state)` |
| 这位置此刻的功率 | `ThermalApi.heatPowerAt(level, pos)` |

**它替你挡掉的坑**：温度存在两套坐标系（世界一套、每个 Sable 结构一套）。结构里的方块在 plot 里，
世界里那一格是空气——拿结构内部的世界坐标**直接**去问世界网格，会**静默返回环境温度而不是报错**。
`nodeCelsius` 先判断这个位置归谁管，再查对应那套网格。

**要注意的**：

- 实体查询收 `LivingEntity`，**玩家和普通生物一视同仁**（护甲热阻按物品 ID 词根推断、
  潮湿按淋雨/泡水判定）。但 `readout` 内部对每个附近热源做射线遮挡检测，是这层最贵的调用，
  别每 tick 对每个生物调。
- **服务端权威**：传客户端的 `Level` 进来只能退化成环境温度。
- `anomalyCelsius` 恒等于 `nodeCelsius − ambientCelsius`。判断"这里是不是异常热"用它，
  别拿绝对温度跟常量比——环境本身随群系、海拔、昼夜、天气变。
- `hasData` 用来区分"真的就是这个温度"和"这里还没算过"（`nodeCelsius` 在没数据时返回环境温度）。
- 载具内部那一半分流**没有端到端测试覆盖**（GameTest 里组装不了真实 sub-level，
  见 `ContraptionBoundaryTest` 的说明），只有"直接驱动局部网格"的间接覆盖。改动这附近要格外小心。

## 5. HUD / 潮湿 / 饰品

- 快捷栏按**体感温度**换 5 档贴图。阈值在 `hud.tier*`，默认：极冷 `<0`、冷 `<15`、热 `>=30`、
  极热 `>=45`。贴图后缀 **`f` 是冷、`z` 是热**（和字母直觉相反）。
- 温度计读数是 Curios 饰品 `createishot:thermometer`（槽位 `thermometer`，配方玻璃+铜锭+红石）。
  排障时把 `hud.thermometerRequiresAccessory` 设 `false`，可以免装备直接显示——
  用来区分"渲染坏了"和"没装备饰品"。
- 温度计刻度按体感温度线性映射，默认 `-20°C → 0%`、`60°C → 100%`（`hud.thermometerMin/MaxCelsius`）。
- **潮湿** = 下雨 或 泡水。两个例外已经处理掉了：
  - 物理结构**内部**的水不算潮湿（Aeronautics 没实现气室，载具下沉后主世界的水灌进船舱是它的
    功能缺失，不代表玩家真的下水了）；
  - 头顶有**物理结构方块**遮挡就不算淋雨（结构方块在 plot 里，世界那一格是空气，
    原版 `isRainingAt` 会误判成"能看见天空"）。

## 6. 验证与排障

### 诊断命令（最有用的一对）

```
/createishot thermal status   # 材料条目数/未匹配方块数、活跃节点、轮间隔、单轮求解与采集耗时、
                              # 以及每个物理结构热网格的明细
/createishot thermal at       # 你自己：节点温度/环境/辐射/保温削减/体感 + 坐标 + 结构热源收集情况
```

读法（按这个顺序排除）：

| 现象 | 说明 |
|---|---|
| "未显式匹配 N 个方块" 很大 | 你的材料规则没覆盖到，去查 ID/标签 |
| 热源数 0 | 没扫到热源 → 数据包规则没匹配上或 `source_power` 没写 |
| 活跃节点 0 | 求解根本没跑（离玩家太远 / 阈值问题） |
| 热源那一格 ≈ 环境温度 | 功率没注入（开关恒 false）或传导断了 |
| 辐射 `+0.0°C` | 热源没被收集到，或被判为被挡住——看 `at` 打出的"热源收集：N 格内几个，最近几格" |
| 轮间隔 > 20 tick | 服务器 TPS 低于阈值，它自己降精度了（配置 `scheduling.tpsDegradeThreshold`） |

### mmccpp 的 MCP 工具（游戏启动后）

**以实际可用工具为准**，先 `wait_for_stage` 到 `READY` / `WORLD_READY`：

- `block_info` / `registry_query` — 查目标方块的**注册表 ID 和它身上的标签**，
  这是写 `targets` 前必须做的一步（不要凭模组文档猜 ID）
- `datapack_query` / `resource_read` — 确认自己的 JSON 真的进了数据包、字段没被吃掉
- `take_screenshot` — 看快捷栏档位颜色和温度计，验证体感链路
- `world_info` — 确认维度/坐标，配合 `thermal at` 对坐标

## 7. 别踩这些

- **别硬编码方块 ID 到 Java 里**，一律走注册表 ID + 允许缺失；缺模组必须静默跳过而不是崩。
- **别去改 createishot 自己的数据包文件**，在你自己的命名空间里用更高 `priority` 覆盖。
- 数据包里用 `#tag` 要注意：标签在 `TagsUpdatedEvent` 才绑定完，材料表在那一刻才重建索引。
  所以刚 `/reload` 完立刻发问号很正常。
- **热是服务端权威的**。客户端只收两个字节（档位 + 温度计柱高），
  所以客户端不可能读到"某个方块的温度"——要读就在服务端脚本/模组里读。
- 结构（载具）里外是**两套坐标系**，载具转向后"玩家世界位置"和"结构内哪一格"不是一回事。
  查温度就走 `ThermalApi`（见 §4.4），它替你分流；**不要**拿世界坐标直接去查世界网格
  ——载具内的坐标在那里是空气，会**静默返回环境温度**。真要自己写分流，参考
  `ContraptionThermalManager.frameAt(...)` + `ContraptionThermalGrid#getTemperatureAtWorld`，
  以及 createishot 仓库里的 `project_sable_plot_coordinate_spaces` 记忆。
