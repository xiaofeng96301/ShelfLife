# ShelfLife 数据包

ShelfLife 的全部数值都在数据包里。这份文档是给**要在别的项目里用 ShelfLife 的 AI 或人**的速查表：
照着抄就能让任意物品获得保质期、让任意容器变冷。

模组：NeoForge **21.1.252** / Minecraft **1.21.1**，modid `shelflife`。

> **给 AI 的 TL;DR 在最后。**

---

## 0. 最小数据包结构

```
你的数据包/
├── pack.mcmeta
└── data/
    └── <命名空间>/            ← 一般就用你自己 mod 的 modid
        ├── food_spoilage/     ← 哪些物品会烂（不写 = 这个模组什么也不做）
        └── spoilage_env/      ← 环境曲线 + 哪些容器冷（不写 = 用内置默认曲线）
```

`pack.mcmeta`（1.21.1 的 pack_format 是 **48**）：

```json
{ "pack": { "pack_format": 48, "description": "ShelfLife rules" } }
```

> **总闸门**：一份食物规则都没有时（`SpoilageManager.isEmpty()`），本模组对游戏**零改动** ——
> 不打戳、不结算、不发包、不加 tooltip。所以"我只想加个冷箱"这种需求，光写 `spoilage_env/` 是不够的，
> 至少得有一条食物规则让它活起来。

---

## 1. 食物规则

位置：`data/<命名空间>/food_spoilage/任意文件名.json`

### 写法 A —— 一组物品共用一组数值

```json
{
  "items": ["minecraft:apple", "#minecraft:fishes"],
  "max_spoilage": 100,
  "ticks_per_spoilage": 840,
  "result": "shelflife:rotten_leftovers"
}
```

### 写法 B —— 整张表一个文件（顶层放默认值，每个物品只覆盖要改的字段）

```json
{
  "max_spoilage": 100,
  "ticks_per_spoilage": 840,
  "result": "shelflife:rotten_leftovers",
  "items": {
    "minecraft:beef":  { "ticks_per_spoilage": 240, "result": "shelflife:rotten_meat" },
    "minecraft:bread": { "ticks_per_spoilage": 1680 }
  }
}
```

两种写法**不能混在同一个文件里**，`items` 是数组就按 A 解析、是对象就按 B 解析。
一个文件是 A 还是 B 由 JSON 结构自己决定，不需要额外字段。

### 字段

| 字段 | 必填 | 含义 |
|---|---|---|
| `items` | ✅ | 数组（写法 A）或对象（写法 B）。元素写 `minecraft:apple`（具体物品）或 `#minecraft:fishes`（物品标签） |
| `max_spoilage` | ✅ | 保质期总量，单位是抽象的"点"。写 100 就是"分 100 步烂完" |
| `ticks_per_spoilage` | ✅ | 每消耗 1 点需要的游戏刻（20 刻 = 1 秒） |
| `result` | ❌ | 烂完变成什么。默认 `shelflife:rotten_leftovers`；写 `minecraft:air` = **不转化**，只是烂在手里 |
| `tint` | ❌ | 烂透时的染色，`#RRGGBB`。默认 `#7E8C4A`（发暗的橄榄绿） |

`tint` 的几点说明：

- 显示层从"新鲜"到它是**线性插值**的：消耗掉 40% 保质期后开始变色，最后 60% 里逐渐加深
- **染色是乘性的**，只能压暗/偏色，不能提亮 —— 想表达"发霉"很好用（给个绿），
  想用浅色把暗贴图提亮是做不到的
- **想偏成某个色，tint 的通道比要压过底图的通道比。** 例：曲奇主色 `#8B5A2B` 的红是绿的
  1.54 倍，那 tint 的绿/红就得**大于** 1.54 才看得出来。`#6aa392` 恰好等于 1.54，
  乘完红绿一样大，出来是灰的；`#46c49a` 是 2.8，才是绿的。
  挑颜色前先把底图主色取出来算一下，能省一轮试错
- **alpha 固定 FF**，所以只收六位。八位会有 ARGB/RGBA 两种理解，而且 alpha 写成 0 的后果
  是"整个物品隐形"（原版会把染色的 alpha 直接写进顶点），干脆不让写
- 想要"曲奇发霉是墨绿的、肉是暗红的"，就是给不同规则写不同的 `tint`：
  ```json
  "minecraft:cookie": { "ticks_per_spoilage": 3600, "result": "shelflife:moldy_cookie", "tint": "#46c49a" }
  ```

两个数值都必须 **≥ 1**（`ticks_per_spoilage` 为 0 会导致结算时除零）。

### 怎么调数值

```
经过的刻数 × 环境倍率
      ÷ ticks_per_spoilage  =  这次消耗掉多少点
```

所以在常温（倍率约 ×1）下，**保质期总时长 = `max_spoilage` × `ticks_per_spoilage` 刻**。

| 想要的实际时长 | max_spoilage 100 时写 |
|---|---|
| 35 分钟 | `ticks_per_spoilage: 840` |
| 1 小时 | `ticks_per_spoilage: 1440` |
| 8 小时 | `ticks_per_spoilage: 11520` |
| 3 天 | `ticks_per_spoilage: 103680` |

`max_spoilage` 只影响 tooltip 的进度颗粒度和变色曲线的平滑度。**写 100 就够了**，
真正调时长请改 `ticks_per_spoilage`。

### 多文件与冲突

按**文件 id 排序**依次生效，**排序靠后的赢**（结果可复现）。同一个物品被两处声明且数值不同时，
日志里会有一条 warn 告诉你谁覆盖了谁。

### 标签与条件

- 标签引用（`#minecraft:fishes`）在**标签绑定之后**才展开，所以 `/reload` 不会丢规则
- 支持 NeoForge 条件，但**必须**写成 `neoforge:conditions` —— 本模组自己包了 `ConditionalOps`，
  不写这个包装的话 `mod_loaded` 之类的条件会**静默失效**（目标模组不在也照样加载，比报错更难查）：

```json
{
  "neoforge:conditions": [{ "type": "neoforge:mod_loaded", "modid": "farmersdelight" }],
  "items": {
    "farmersdelight:bacon": { "max_spoilage": 100, "ticks_per_spoilage": 480 }
  }
}
```

### 加载时看日志

```
[ShelfLife] 读到 N 个保质期规则文件，等标签绑定后展开
[ShelfLife] 保质期配置生效，覆盖 N 个物品        ← N 是最终生效的物品数，这是你要盯的数字
```

以下情况有 warn：`result` 指向不存在的物品（保质期耗尽后不会转化）、引用了不存在的物品或标签、
同一物品被覆盖。

---

## 2. 环境：温湿度与冷源

位置：`data/<命名空间>/spoilage_env/任意文件名.json`

**没有这个文件也能用** —— 环境倍率是常开的，没数据包时用内置默认曲线，所以雪原里天生就比沙漠慢。

```json
{
  "settings": {
    "reference_temperature": 0.8,
    "doubling_per": 0.8,
    "humidity_base": 0.75,
    "humidity_span": 0.5,
    "min_multiplier": 0.05,
    "max_multiplier": 4.0,
    "temperature_source": "auto",
    "celsius_curve": [[0.0, -30.0], [1.0, 25.0], [2.0, 40.0]]
  },
  "containers": {
    "minecraft:barrel":    { "temperature": -0.1 },
    "shelflife:cold_box":  { "temperature": -4.0 },
    "#mymod:insulated":    { "temperature": -1.0, "humidity": -0.2 }
  }
}
```

两节都可以省略。多个文件同样按 id 排序、后者覆盖前者。

> **`settings` 是整块覆盖，不是逐字段合并**，而且排序比的是 **path 在前、namespace 在后**。
> 所以第三方数据包写 `data/mypack/spoilage_env/environment.json` 时，path 和内置的
> `data/shelflife/spoilage_env/environment.json` 打平，接着比 namespace，`mypack` 排在 `shelflife`
> 前面 —— **你的整块 settings 会被内置的盖掉**。想让自己的生效，要么用**同名 path**
> （同名时数据包赢过模组内置），要么取一个排在 `environment.json` 之后的名字（比如 `zz_mypack.json`）。

### 倍率公式

```
有效温度 = 群系温度 + 容器的 temperature 修正
有效湿度 = clamp(群系湿度 + 容器的 humidity 修正, 0, 1)

温度因子 = 2 ^ ((有效温度 - reference_temperature) / doubling_per)
湿度因子 = humidity_base + humidity_span × 有效湿度
倍率     = clamp(温度因子 × 湿度因子, min_multiplier, max_multiplier)
```

| 字段 | 默认 | 含义 |
|---|---|---|
| `reference_temperature` | 0.8 | 基准温度，这里倍率正好不变（0.8 就是平原） |
| `doubling_per` | 0.8 | 温度每高这么多，腐烂速度翻倍。**必须 > 0** |
| `humidity_base` / `humidity_span` | 0.75 / 0.5 | 湿度权重，湿度 0.5 时湿度因子正好 1.0 |
| `min_multiplier` / `max_multiplier` | 0.05 / 4.0 | 夹上下限 |
| `temperature_source` | `auto` | 温度从哪来：`auto` / `biome` / `createishot`。见下面的「摄氏模式」 |
| `celsius_curve` | 见下 | 「群系温度 → 摄氏」的锚点表，用来把 createishot 的摄氏温度反查回原版刻度 |

群系温度/湿度直接取原版 `Biome` 的值（平原约 0.8/0.4，沙漠约 2.0/0.0，雪原约 0.0/0.5）。

### 摄氏模式（装了 createishot 时）

`temperature_source` 默认 `auto`：**装了 createishot 就用它的温度，没装就用群系温度。**
想固定住就写 `"biome"` 或 `"createishot"`。

createishot 提供的是真正的热力学温度场（体素网格 + 传导 + 辐射，服务端求解），能表达群系温度
表达不了的东西：站在营火边、海拔升高、入夜降温、下雨。用它的时候：

```
等效温度 = celsius_curve 反查(节点温度 + 容器的 celsius 修正)
倍率     = clamp(2^((等效温度 - reference_temperature) / doubling_per) × 湿度因子, min, max)
```

**`celsius_curve` 就是 createishot 自己的「群系温度 → 摄氏」映射**，方向是「温度 → 摄氏」，
本模组用的时候反过来查。默认 `[[0.0, -30.0], [1.0, 25.0], [2.0, 40.0]]` —— 注意它在 1.0 处有拐点，
所以是分段线性而不是一条直线（低温段大约每 1.0 单位 = 55°C，高温段只有 15°C）。

**这组数需要按你的 createishot 配置校准**（改了它的 `ThermalConfig` 之后这里不会自动跟着变）：
站在平原、白天、晴天，`/createishot thermal at` 读到的环境温度，应该约等于曲线在 0.8 处的插值。

> 超出锚点范围**不夹紧，而是沿最外那段外推** —— 夹紧会让"很热"和"极热"给出同一个倍率。

> **湿度永远来自群系。** createishot 只管温度，没有湿度这个维度。

**想彻底关掉环境倍率**：把 `doubling_per` 调得极大、`min_multiplier` 和 `max_multiplier` 都设成 `1.0`。

### 容器修正

| 字段 | 含义 |
|---|---|
| `temperature` | 温度偏移，单位是**原版温度刻度**。**负数 = 更冷 = 更慢** |
| `humidity` | 加到群系湿度上，夹在 0~1 |
| `rate_override` | **直接指定倍率**，绕过温度和上下限。`0` = 完全不腐烂 |

> **`temperature` 的单位在两种温度来源下完全一样。** 摄氏模式下节点温度会先被反查回原版刻度，
> 这个偏移加在**反查之后** —— 所以同一份数据包在装 / 不装 createishot 时给出同样的冷却效果，
> 不需要写两份。（曲线是非线性的，若把偏移加在摄氏那一层，同一个数字会差一个数量级。）

键可以是方块 id，也可以是 `#方块标签`（一次给一整类方块）。

**冷藏就是给一个足够大的负偏移**：`temperature: -4.0` 会撞到 `min_multiplier` 下限，
得到约 ×0.05，也就是 20 倍保质期 —— 这是 `shelflife:cold_box` 的做法。
它不随群系变：沙漠里的冷箱还是同一个偏移，只是从一个更热的基准往下减。

> 想让箱子"绝对稳定、和群系无关"，可以用 `rate_override` 把倍率钉死。
> 但 **`rate_override: 0` 有个已知的坑**（见下一节）；`0.05` 这类正值没有那个问题。

### ⚠️ `rate_override` 的坑（用 `0` 之前必读）

变质用的是**检查点模型**：物品身上存的是"上次结算的时刻 + 当时的腐坏值"，其余靠时间差推算。

一个检查点**表示不了"这段时间里先后有两个不同的倍率"**。所以设计上有一条硬规则：
**任何会改变倍率的事件，都必须在那一刻结算一次。** 反例最有说服力：倍率 0 期间不结算，
恢复常温后那段冻结时间会被整个补算 —— 冻一个月的东西瞬间全烂。

实际后果：

- **玩家手动存取没问题** —— 开箱/关箱本来就会结算，检查点总是新的
- **但物品被漏斗搬进这类容器时不会结算** → 进容器后的第一次结算会抹掉它之前累积的时间
  （在普通箱子里放的那几个小时，会被当成"在冰箱里"一起放过）

所以：

- 会被**自动化喂入**的容器 → 用 `temperature` 偏移（撞下限），别用 `rate_override`
- 只用 `rate_override: 0` 给**玩家手动存取**的容器

---

## 3. 腐烂物品的堆肥

本模组的两个腐烂物在 `data/shelflife/data_maps/item/compostables.json` 里注册成 50% 堆肥概率。

模组物品要参与堆肥，走 NeoForge 的 **data map**：

```json
// data/<命名空间>/data_maps/item/compostables.json
{ "values": { "mymod:rotten_thing": 0.5 } }
```

**不要**去写 `ComposterBlock.COMPOSTABLES` —— 那个静态表是废弃的，原版 `getValue` 读的是 data map，
改了不生效（而且不会有任何报错）。

---

## 4. 排查手段

- **F3+H 打开高级提示框**，物品 tooltip 会多显示两行：当前腐烂倍率，以及代入实际数字的公式。
  公式里显示的是**夹上下限之前**的值，所以能一眼区分"本来就是 0.05"和"算出来 0.03 被夹到 0.05"
- 改完数据包 `/reload`，客户端会跟着收到新表（单机和服务器都一样），tooltip 立刻变
- 倍率是**按物品**取的：容器里的食物用容器的倍率，背包/手上/地上的用玩家所在位置的倍率

---

## 5. 给 AI 的速查（TL;DR）

在另一个项目里用 ShelfLife：

1. 依赖：NeoForge 21.1.252 / MC 1.21.1，modid `shelflife`
2. 让物品会烂 → 在你的 mod 的 `src/main/resources/data/<你的modid>/food_spoilage/xxx.json` 里写规则，
   物品用**你自己的** id。写法 A（`items` 数组）或写法 B（`items` 对象 + 顶层默认值）
3. 必填 `max_spoilage` 和 `ticks_per_spoilage`（都 ≥ 1）；`result` 不写默认变成
   `shelflife:rotten_leftovers`，写 `minecraft:air` 表示不转化
4. 时长换算：`max_spoilage × ticks_per_spoilage` 刻（20 刻 = 1 秒），常温下成立
5. 让自己的容器更冷 → `spoilage_env/` 里 `"containers": { "你的方块id": { "temperature": -2.0 } }`。
   单位是原版温度刻度，装不装 createishot 都一样
6. 别对**会被漏斗喂入**的容器用 `rate_override`，用 `temperature`
7. 加载后看日志确认 `[ShelfLife] 保质期配置生效，覆盖 N 个物品`，N 要对
8. 别指望它给"没被玩家碰过"的食物计时 —— 这是设计（事件驱动），不是 bug
9. 写了规则的物品**不要**再自己实现腐烂逻辑，会打架
10. 不想让 createishot 的热源影响腐烂 → `"temperature_source": "biome"`；
    它的温度换算对不上 → 校准 `celsius_curve`（`/createishot thermal at`）
