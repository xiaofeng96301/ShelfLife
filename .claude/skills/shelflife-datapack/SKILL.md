---
name: shelflife-datapack
description: 用 ShelfLife 给任意物品加保质期与腐烂——写 food_spoilage 规则、用 spoilage_env 做冷源与温湿度曲线、给发霉做 tint/overlay 染色，含这个模组踩过的坑与验证方法
---

# ShelfLife 数据包（整合包 / 附属模组侧）

要让某个模组的食物会腐烂、或让自己的容器变冷时用。**全部数值都在数据包里**，模组本体只提供机制。

模组：NeoForge **21.1.252** / MC **1.21.1**，modid `shelflife`。可选依赖 `createishot`（装了就用它的温度）。

## 0. 一句话模型

物品身上存一个**检查点**（已腐坏点数 + 上次结算时刻），显示层按时间差实时推算。
结算**只在事件时**发生（拾取 / 合成 / 熔炼 / 开关容器 + 每秒一次的被动刷新）。
所以：**没被玩家碰过的食物不腐烂** —— 箱子、机器、自动农场里的一直是新鲜的，直到有人开箱。

## 1. 最小数据包

```
你的数据包/
├── pack.mcmeta                     { "pack": { "pack_format": 48, ... } }   ← 1.21.1 是 48
└── data/<你的modid>/
    ├── food_spoilage/  哪些物品会烂      （不写 = 本模组对游戏零改动）
    └── spoilage_env/   环境曲线 + 冷源    （不写 = 用内置默认曲线，环境倍率照样生效）
```

**总闸门**：一条食物规则都没有时，模组完全静默 —— 不打戳、不结算、不加 tooltip。

## 2. 食物规则

`data/<ns>/food_spoilage/任意名.json`，两种写法：

```json
// A：一组物品共用一组数值
{ "items": ["minecraft:apple", "#minecraft:fishes"],
  "max_spoilage": 100, "ticks_per_spoilage": 840 }

// B：整表一个文件，顶层写默认值，每个物品只覆盖要改的
{ "max_spoilage": 100, "result": "shelflife:rotten_leftovers",
  "items": { "minecraft:beef": { "ticks_per_spoilage": 240, "result": "shelflife:rotten_meat" } } }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `items` | ✅ | 数组（写法 A）或对象（写法 B）。元素是 `minecraft:apple` 或 `#minecraft:fishes` |
| `max_spoilage` | ✅ | 保质期总量（"点"）。**写 100 就够**，它只影响进度颗粒度 |
| `ticks_per_spoilage` | ✅ | 每消耗 1 点需要的刻数（20 刻 = 1 秒）。**调时长改这个** |
| `result` | ❌ | 烂完变成什么。默认 `shelflife:rotten_leftovers`；`minecraft:air` = 不转化 |
| `tint` | ❌ | 烂透时的染色 `#RRGGBB`，默认 `#7E8C4A`。**乘性，只能压暗** |
| `overlay` | ❌ | `true` = 第二层霉斑淡入，而不是整块染色。见 §4 |

**时长换算**：常温下 `总时长 = max_spoilage × ticks_per_spoilage` 刻。
35 分钟 → 840；1 小时 → 1440；8 小时 → 11520；3 天 → 103680。

多个文件按**文件 id 排序、后者覆盖前者**；同物品被覆盖会打 warn。

条件用 `neoforge:conditions`（本模组已自己包好，直接写就行）：

```json
{ "neoforge:conditions": [{ "type": "neoforge:mod_loaded", "modid": "farmersdelight" }],
  "items": { "farmersdelight:bacon": { "max_spoilage": 100, "ticks_per_spoilage": 480 } } }
```

## 3. 环境与冷源

`data/<ns>/spoilage_env/任意名.json`：

```json
{
  "settings": {
    "reference_temperature": 0.8, "doubling_per": 0.8,
    "humidity_base": 0.75, "humidity_span": 0.5,
    "min_multiplier": 0.05, "max_multiplier": 4.0,
    "temperature_source": "auto",
    "celsius_curve": [[0.0, -30.0], [1.0, 25.0], [2.0, 40.0]]
  },
  "containers": {
    "minecraft:barrel":   { "temperature": -0.1 },
    "你的方块id":          { "temperature": -2.0 },
    "#mymod:insulated":   { "temperature": -1.0, "humidity": -0.2 }
  }
}
```

```
有效温度 = 温度来源 + 容器的 temperature 修正
倍率     = clamp(2^((有效温度 - reference_temperature) / doubling_per)
                 × (humidity_base + humidity_span × 湿度), min, max)
```

- **`temperature` 的单位永远是原版温度刻度（平原 0.8），两种温度来源下都一样** ——
  摄氏模式下节点温度会先反查回这个刻度，偏移加在反查之后，所以一份数据包不用写两遍
- **冷藏 = 给足够大的负偏移**（`-4.0` 会撞到 `min_multiplier`，约 20 倍保质期）
- `rate_override` 直接钉死倍率（绕过温度与上下限），`0` = 完全不腐烂 —— **先读 §5 第一条**
- `temperature_source`：`auto`（默认，装了 createishot 就用它的温度）/ `biome` / `createishot`
- 摄氏模式多一个 `celsius_curve`：**createishot 自己的「群系温度→摄氏」映射**，反向分段线性查。
  改了对方的 `ThermalConfig` 就要跟着校准（平原白天晴天，让 `/createishot thermal at` 的
  环境温度 ≈ 曲线在 0.8 处的插值）
- **湿度永远来自群系**（createishot 没有湿度这个维度）

## 4. 视觉

- 默认：消耗掉 40% 保质期后**整块染色**，最后 60% 里逐渐加深。**乘性，只能压暗变灰**
  - 想偏成某个色，tint 的通道比要**压过底图**：曲奇主色 `#8B5A2B` 的红是绿的 1.54 倍，
    那 tint 的绿/红就得大于 1.54 —— `#6aa392` 恰好等于（乘完是灰的），`#46c49a` 是 2.8（才是绿的）
- `"overlay": true`：**保持原色、只长霉**。需要物品模型里有 `layer1`：

```json
// assets/minecraft/models/item/<物品>.json（覆盖原版只为一层，注意会和其他资源包抢）
{ "parent": "minecraft:item/generated",
  "textures": { "layer0": "minecraft:item/cookie", "layer1": "shelflife:item/mold_overlay" } }
```

  叠加层贴图**画成灰阶 + 其余全透明**（颜色由 `tint` 定，alpha 由腐烂深度定，所以霉会淡入）。
  模型生成器会把 layer0 → tintIndex 0、layer1 → tintIndex 1，模组据此分别处理。

## 5. ⚠️ 这个模组踩过的坑

1. **`rate_override: 0` 只给手工存取的容器用。** 物品被漏斗搬进这类容器时不会结算，
   进容器后的第一次结算会**抹掉**它之前累积的时间。会被自动化喂入的容器请用温度偏移
2. **没被观测的食物不腐烂是设计**（箱子/机器/农场），不是 bug。玩家背包除外 ——
   进了背包 1 秒内必定开始计时（`/give`、交易、创造模式拿的也一样）
3. **`tint` 是乘性的**，浅色提不亮暗贴图；想要的色偏不出来就是通道比不够（见 §4）
4. **`settings` 是整块覆盖、不是逐字段合并**，而且排序比的是 **path 在前、namespace 在后**。
   第三方包写 `data/mypack/spoilage_env/environment.json` 时 path 和内置的打平、接着比 namespace，
   `mypack` 排在 `shelflife` 前面 → **你的整块 settings 会被内置的盖掉**。
   要用**同名 path**（同名时数据包赢过模组内置）或排在其后的文件名（`zz_mypack.json`）
5. **腐烂物参与堆肥要用 data map**：`data/<ns>/data_maps/item/compostables.json`。
   写 `ComposterBlock.COMPOSTABLES` 那个静态表**不生效且不报错**（它已废弃）
6. **`result` 指向不存在的物品**会在加载时 warn，但不会崩 —— 保质期耗尽后什么都不发生
7. **同一物品不要在别处再实现一套腐烂逻辑**，会和本模组打架

## 6. 验证

- 加载后看日志：`[ShelfLife] 保质期配置生效，覆盖 N 个物品` —— **N 是最终生效的物品数，盯这个**
- `/reload` 只重读**外部**数据包；打进 mod jar 的内置包改了要重启
- **F3+H 打开高级提示框**：tooltip 多两行 —— 当前腐烂倍率，以及代入实际数字的公式
  （显示的是**夹上下限之前**的值，能区分"本来就是 0.05"和"算出来 0.03 被夹到 0.05"）
- 想快速看效果：`/give` 一份 + `/time add <刻数>`（比如 4000 刻会把曲奇推到接近烂透）

完整字段表、公式推导、覆盖顺序等细节见仓库里的 `docs/datapack.md`。
