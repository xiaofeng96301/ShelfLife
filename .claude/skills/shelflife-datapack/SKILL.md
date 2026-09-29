---
name: shelflife-datapack
description: 用 ShelfLife 给任意物品加保质期与腐烂——写 food_spoilage 规则、用 spoilage_env 做冷源/腐烂箱与温湿度曲线、给发霉做 tint/overlay 染色、用 /shelflife 指令和真服务器自测验证，含这个模组所有的坑
---

# ShelfLife 数据包（整合包 / 附属模组侧）

要让某个物品会腐烂、或让自己的容器变冷/变热时用。**全部数值都在数据包里**，模组本体只提供机制。

模组：NeoForge **21.1.252** / MC **1.21.1**，modid `shelflife`。可选依赖 `createishot`（装了就用它的温度）。

完整字段表、公式推导、日志速查在仓库的 `docs/datapack.md`；这份是干活用的。

## 0. 一句话模型（不理解这个一定会踩坑）

物品身上存一个**检查点**（已腐坏点数 + 上次结算时刻），**没有"剩余时间"这个字段** ——
tooltip 上的剩余时间是显示层按时间差实时推算的。

结算**只在事件时**发生：拾取 / 合成 / 熔炼 / 开关容器 / 被从容器抽出 + 玩家背包每秒一次的被动刷新。

> **没被玩家碰过、也没被抽出来的食物不会腐烂。** 箱子、机器、自动农场里的一直是新鲜的，
> 直到有人开箱。**这是设计，不是 bug。**

推论：`/give`、交易、创造模式拿的食物，一进**玩家背包**就会在 1 秒内开始计时。

## 1. 最小数据包

```
你的数据包/
├── pack.mcmeta                     { "pack": { "pack_format": 48, ... } }   ← 1.21.1 是 48
└── data/shelflife/                 ← 推荐就用这个命名空间（理由见 §5 第 1 条）
    ├── food_spoilage/  哪些物品会烂   （不写 = 本模组对游戏零改动）
    └── spoilage_env/   环境曲线 + 冷源  （不写 = 用内置默认曲线，环境倍率照样生效）
```

> **"文件放哪"和"规则管哪些物品"是两回事。** 文件放在 `shelflife` 命名空间下，
> 里面的物品 id 照样写你自己的（`"mymod:cheese"`）。这么做只是为了让
> **覆盖顺序变成单纯按文件名排** —— 见 §5 第 1 条。
> 写自己的命名空间也完全支持，只是会撞上"path 相同时比 namespace"那条规则。

**总闸门**：一条食物规则都没有时，模组完全静默 —— 不打戳、不结算、不加 tooltip。

## 2. 食物规则

`data/shelflife/food_spoilage/任意名.json`（文件名取排在 `vanilla_foods.json` 之后的，见 §5 第 1 条），
两种写法（`items` 是数组就按 A、是对象就按 B，不能混用）：

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
| `items` | ✅ | 数组（A）或对象（B）。元素是 `minecraft:apple` 或 `#minecraft:fishes` |
| `max_spoilage` | ✅ | 保质期总量（"点"）。**写 100 就够**，它只影响进度颗粒度 |
| `ticks_per_spoilage` | ✅ | 每消耗 1 点需要的刻数（20 刻 = 1 秒）。**调时长改这个** |
| `result` | ❌ | 烂完变成什么。默认 `shelflife:rotten_leftovers`；`minecraft:air` = 不转化 |
| `tint` | ❌ | 烂透时的染色 `#RRGGBB`，默认 `#7E8C4A`。**乘性，只能压暗** |
| `overlay` | ❌ | `true` = 第二层霉斑淡入，而不是整块染色。见 §4 |

（写法 B 的顶层默认值可以省略，但那样每个物品必须自己写全 `max_spoilage`/`ticks_per_spoilage`。）

**时长换算**：常温下 `总时长 = max_spoilage × ticks_per_spoilage` 刻。
2 分钟 → 24；35 分钟 → 840；1 小时 → 1440；4 小时 → 5760；1 天 → 34560；3 天 → 103680。

条件用 `neoforge:conditions`（本模组已自己包好，直接写就行，**不写包装条件是静默失效的**）：

```json
{ "neoforge:conditions": [{ "type": "neoforge:mod_loaded", "modid": "farmersdelight" }],
  "items": { "farmersdelight:bacon": { "max_spoilage": 100, "ticks_per_spoilage": 480 } } }
```

> **重复声明 = 按字段继承，不是整条替换。** 同一个物品被多处声明时，后一条只覆盖它
> **真正写了**的字段：`max_spoilage`/`ticks_per_spoilage` 必须给出（自己的或本文档顶层的
> 默认值），`result`/`tint`/`overlay` 没写就继承前一条。
> **要显式重置就写出来**（`"overlay": false`）—— 省略 = 继承，写明 = 覆盖。
>
> 这条规则是为一个阴坑准备的：曲奇的模型带霉斑叠加层，另一份包只想改它的保质期，
> 旧语义下会把 `overlay`/`tint` 一起冲回默认值 —— **叠加层还在，但颜色和透明度变了**，
> 看着像渲染坏了，其实是数据被覆盖了。

## 3. 环境与冷源

`data/shelflife/spoilage_env/任意名.json`（同样取排在 `environment.json` 之后的文件名）：

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
    "#mymod:insulated":   { "temperature": -1.0, "humidity": -0.2 },
    "mymod:hot_locker":   { "temperature": 2.4 }
  }
}
```

```
有效温度 = 温度来源的值 + 容器的 temperature 修正      // 来源默认=群系温度
有效湿度 = clamp(群系湿度 + 容器的 humidity 修正, 0, 1)
倍率     = clamp(2^((有效温度 − reference_temperature) / doubling_per)
                 × (humidity_base + humidity_span × 有效湿度), min_multiplier, max_multiplier)
```

平原（温度 0.8 / 湿度 0.4、默认曲线）→ **倍率 ≈ ×0.95**，所以数据包里写的数就是实际速度。
沙漠 ≈ ×2.1，雪原 ≈ ×0.5，丛林 ≈ ×1.4。

- **`temperature` 的单位永远是原版温度刻度（平原 0.8），两种温度来源下都一样** ——
  摄氏模式下节点温度会先反查回这个刻度，偏移加在反查之后，所以一份数据包不用写两遍
- **保鲜（更慢）= 足够大的负偏移**（`-4.0` 撞到 `min_multiplier`，得到约 ×0.05 = 20 倍保质期）
- **反鲜（腐烂箱 / 更快）= 正的偏移或一个大的正 `rate_override`**（见下）
- **同一个方块的两种状态要不同的冷源？** 用 `container_rules`（有序列表，后写的赢），
  匹配条件就是原版 `BlockPredicate` 的形状：

```json
"container_rules": [
  { "blocks": "mymod:freezer", "state": { "top": "true" },  "temperature": -1.6 },
  { "blocks": "mymod:freezer", "state": { "top": "false" }, "rate_override": 0 },
  { "blocks": "#mymod:cold_boxes", "rate_override": 0 }
]
```

  - `blocks` 支持方块 id、`#标签`、以及列表（标签是原版 `HolderSet` 原生支持的）
  - `container_rules` **优先于** `containers`（命中规则就不看老表了）
  - `nbt` **不支持**（本模组只查"方块 + 状态"），写了会被丢弃并打 warn
  - 既没写 `blocks` 也没写 `state` 的规则会命中所有方块 → 被忽略并打 warn
  - **别为"区分状态"去实现 `ContainerClimate`** —— 那是数据包能表达的，实现接口只在
    **数据包表达不了**时才需要（动态状态 / 需要 NBT）
- `rate_override` 直接钉死倍率，**绕过温度、湿度与上下限**：`0` = 完全不腐烂，`20.0` = 硬 20 倍。
  **负数会被忽略**（掉回温度路径）
- 用温度偏移加速时注意 `max_multiplier`（默认 4.0）会先撞上 —— 想要比 ×4 更快就用 `rate_override`
- `temperature_source`：`auto`（默认，装了 createishot 就用它的温度）/ `biome` / `createishot`
- 摄氏模式多一个 `celsius_curve`：**createishot 自己的「群系温度→摄氏」映射**，反向分段线性查。
  改了对方的 `ThermalConfig` 就要跟着校准（平原白天晴天，让 `/createishot thermal at` 的
  环境温度 ≈ 曲线在 0.8 处的插值，默认曲线下约 23°C）
- **湿度永远来自群系**（createishot 没有湿度这个维度）
- 想彻底关掉环境倍率：`doubling_per: 100000`，且 `min_multiplier` 和 `max_multiplier` 都写 `1.0`

## 4. 视觉

- 默认：消耗掉 **40%** 保质期后**整块染色**，最后 60% 里逐渐加深。**乘性，只能压暗变灰**
  - 想偏成某个色，tint 的通道比要**压过底图**：曲奇主色 `#8B5A2B` 红/绿 = 1.54，
    那 tint 的绿/红就得大于 1.54 —— `#6aa392` 恰好等于（乘完是灰的），`#46c49a` 是 2.8（才是绿的）
  - `tint` 只收六位 `#RRGGBB`（alpha 固定 FF，写成 0 会让物品整个隐形，所以不给写）
- `"overlay": true`：**保持原色、只长霉**。需要资源包里物品模型有 `layer1`：

```json
// assets/<ns>/models/item/<物品>.json（覆盖原版只为一层，注意会和其他资源包抢）
{ "parent": "minecraft:item/generated",
  "textures": { "layer0": "minecraft:item/cookie", "layer1": "shelflife:item/mold_overlay" } }
```

  叠加层贴图**画成灰阶 + 其余全透明**（颜色由 `tint` 定，alpha 由腐烂深度定，所以霉会淡入）。
  模型生成器把 layer0 → tintIndex 0、layer1 → tintIndex 1，模组据此分别处理。
  **不写 `layer1` 就没有 1 号索引可问，`overlay` 会安静地什么也不做。**
  `shelflife:item/mold_overlay` 可以直接复用，不必自己画。

## 5. ⚠️ 这个模组踩过的坑

1. **覆盖顺序：写进 `data/shelflife/`，文件名排在 `vanilla_foods.json` / `environment.json`
   之后**（`zz_mypack.json`）。排序是**先比 path、再比 namespace，后者赢**：
   只要同命名空间，排序就等于按文件名排，最省心。用了别的命名空间就会多一步 ——
   你的 `data/mypack/food_spoilage/vanilla_foods.json` 和内置的 **path 相同**，于是比 namespace，
   `mypack` 排在 `shelflife` 前面 → **你整份被内置盖掉**。
   **`settings` 还是整块覆盖、不是逐字段合并**，所以只写一项的话其余字段会回到**内置默认值**
   （不是内置 `environment.json` 那份）—— 看起来就是"改了没生效"。
   `containers` 是按 key 合并的，随便什么文件名都行
2. **`settings` 不能"只覆盖一两个字段"** —— 整块替换，写了就要写全（或者干脆别写、用默认）
3. **没被观测的食物不腐烂是设计**（箱子/机器/农场），不是 bug。玩家背包除外 ——
   进了背包 1 秒内必定开始计时（`/give`、交易、创造模式拿的也一样）
4. **`tint` 是乘性的**，浅色提不亮暗贴图；想要的色偏不出来就是通道比不够（见 §4）
5. **`overlay` 是资源包的事**，不是数据包 —— 物品模型里没有 `layer1` 就什么都不会发生
6. **腐烂物参与堆肥要用 data map**：`data/<ns>/data_maps/item/compostables.json`。
   写 `ComposterBlock.COMPOSTABLES` 那个静态表**不生效且不报错**（它已废弃）
7. **`result` 指向不存在的物品**会在加载时 warn，但不会崩 —— 保质期耗尽后什么都不发生
8. **`rate_override: 0` 的容器如果会被管道/漏斗喂入**：标准路径（原版漏斗、
   `ItemHandler` 包装的抽取）现在会在**抽出时按来源容器结算**，所以既往时间不会被吞；
   但模组自己实现的 `IItemHandler`、以及拿不到位置的容器仍会吞掉那段时间。
   会被自动化喂入的容器优先用 `temperature` 偏移
9. **同一物品不要在别处再实现一套腐烂逻辑**，会和本模组打架
10. **量到时间是"点"不是"秒"**：`max_spoilage` 只影响颗粒度，真正决定时长的是
    `ticks_per_spoilage`，而实际时长还要除以环境倍率

## 6. 验证（别靠猜）

**指令**（权限 2，只对主手物品生效）：

```
/shelflife spoilage get            看组件原值：已腐坏 / 上限 / 时间戳
/shelflife spoilage rot <百分比>    设成"烂了这么多"  ← 测冷箱/腐烂箱用这个最快
/shelflife spoilage set/add/max/clear
```

手上拿一条鱼、`/shelflife spoilage rot 84`，常温下应显示约 1 分 35 秒；
丢进冷箱应立刻变成约 32 分钟（腐坏值没变，变的是除数）。

**看日志**：

```
[ShelfLife] 读到 N 个保质期规则文件，等标签绑定后展开     ← 没有这行 = food_spoilage/ 路径写错了
[ShelfLife] 保质期配置生效，覆盖 N 个物品                ← N 是最终生效的物品数，盯这个
[ShelfLife] 容器环境修正生效，覆盖 N 个方块
```

**看 tooltip**：按 **F3+H** 打开高级提示框，会多两行 —— 当前腐烂倍率，
以及**代入实际数字的公式**。公式里显示的是**夹上下限之前**的值，能区分
"本来就是 0.05"和"算出来 0.012 被夹到 0.05"（这两种要改的东西完全相反）。

**改完 `/reload`**。注意：只重读**外部**数据包 —— 打进 mod jar 的内置包改了要重启。

**要验证真实漏斗/管道的行为**（纯逻辑测不出来的那种）：
仓库里的 `./gradlew runServer -PshelflifeSelfTest=true` 会在真专用服务器上
摆冷箱+漏斗、塞入指定腐坏值的鱼、走真实游戏循环并打印每步的组件原值，
末尾打一行断言汇总（`[ok]` / `[FAIL]`，都可 grep）。

**要验证文档里的示例**：`tools/docs_check/` 是一份小数据包，内容就是文档里那几个 json
原样照抄（含 `container_rules`、按字段继承、`neoforge:conditions`）。拷进
`run-server/world/datapacks/` 跑一次自测，看日志里的规则条数、覆盖警告、
`container_rules 生效 N 条` 对不对得上 —— 改了文档里的示例就顺手跑一次，免得示例悄悄烂掉。

## 7. 给别的模组用的 Java API（要主动配合时）

都在 `org.slf4j.shelflife.api`。**声明为可选依赖**（`compileOnly` + `type = "optional"`），
并且调用点要**双重保护**：`ModList.get().isLoaded("shelflife")` 判断之外，
还要把调用**再包一层单独的类**（否则引用 `ShelfLifeApi` 的那个类一加载就 `NoClassDefFoundError`）。

- `ShelfLifeApi.settleStack(stack, level, pos, block)` —— **搬运物品时该调的就是它**，
  用**来源容器**的位置调。不调的话，"它在来源容器里待的那段时间"会被记到目的地的环境上
  （最典型：从普通箱子抽进冷箱，箱子里的几小时被当成在冷箱里度过 = 白冻）
- `ShelfLifeApi.settleContainer(container, level, pos, block)` —— **动态容器状态要变时调它**
  （通电→断电、燃料烧完），而且**必须在改状态之前**调，结清的才是"旧倍率那一段时间"。
  固定温度的容器**完全不需要**，也就**不需要任何 Java**
- `ShelfLifeApi.isManaged / spoilageOf / maxSpoilageOf / rateAt / replacementIfSpoiled`
- `settleStack` 只结算不换物品 —— 烂透时要用 `replacementIfSpoiled` 的返回值整堆替换
- `ContainerClimate`：方块实体实现它就能当**动态**冷源/热源，它**覆盖**数据包的静态修正。
  `climate()` 是**纯查询**（模组不会因为它去结算任何物品，也不缓存），**必须便宜**。
  **只在数据包表达不了时才实现它**（动态状态 / 需要 NBT）—— 只是"区分方块状态"的话用
  `container_rules`，别写 Java
- **容器定位**：默认靠"遍历菜单槽位找方块实体"来反查容器位置，这条路对用
  `SlotItemHandler` 的机器（Mekanism 等）**必然失败**（那些槽位的容器是一个共享的空
  `SimpleContainer`），后果是**按玩家脚下的倍率给机器里的食物记账**（真写数据）。
  修法二选一：菜单实现 `MenuContainerProvider`（零注册，推荐），或
  `ContainerLocators.register(menu -> ...)`。现在查不到会打一条 warn
- **硬规则**：检查点模型表示不了"一段间隔里先后两个倍率"，所以**任何改变倍率的事件都必须当场结算**。
  容器状态翻转时模组不会自动发现（除非正被玩家开着，那种情况 1 秒内会补），
  要靠上面那个 `settleContainer` 自己说

### 动态冰箱的标准写法（通电才冷）

```java
private boolean powered;                    // ← 状态记在你自己身上，不要从方块状态倒推
private static final ContainerModifier COLD = new ContainerModifier(-4.0F, 0.0F, Optional.empty());

@Override public @Nullable ContainerModifier climate() {
    return powered ? COLD : null;           // 纯查询，只读字段；null = 回落数据包/群系
}

public void setPowered(boolean powered) {   // 通电、断电走同一个方法
    if (powered == this.powered) return;    // 状态没变就别白结清
    ShelfLifeApi.settleContainer(this, level, worldPosition, getBlockState().getBlock());  // ← 先结清
    this.powered = powered;                 // ← 再改状态
    setChanged();
}
```

四条规矩：① **`climate()` 只读自己的字段**（读方块状态的话，红石一变你就没有"改之前"
这个时机了）② **先结清、再改状态** ③ **通电/断电两个方向都要** ④ 别在 `tick()` 里无条件调
（结清是幂等的，但白花钱）。

顺序写反、或漏调一边，**都不会报错**，只在几小时后表现为"食物烂得不对"。
`settleContainer` 内部已处理客户端 / 空 level / 本模组无规则，直接调即可。

**验证**：断电后立刻 `/shelflife spoilage get` —— **时间戳应该正好落在断电那一刻**，
且 `cur` 只涨了冷倍率那一份。`get` 看的是真值，tooltip 是实时推算值，别搞混。
