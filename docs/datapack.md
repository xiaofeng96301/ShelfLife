# ShelfLife 数据包参考

ShelfLife 的**全部数值**都在数据包里。这份文档的目标是：**第一次看的人照着抄就能跑起来**，
卡住的时候能在这里查到原因。

模组：NeoForge **21.1.252** / Minecraft **1.21.1**，modid `shelflife`。
可选依赖 `createishot`（装了就用它那套热力学温度）。

> 只想尽快跑起来 → 跳到 [§1 最小可用数据包](#1-最小可用数据包完整可抄)。
> 只想查字段 → [§2.2](#22-字段表) 和 [§3.3](#33-settings-字段表)。
> 出问题了 → [§9 按症状排查](#9-按症状排查)。
> 你是个 AI，只要速查 → [§10](#10-给-ai-的速查tl-dr)。

---

## 0. 先理解它怎么运作（不然一定会踩坑）

### 0.1 检查点模型：物品身上存的是"上次结算时刻"

每个会腐烂的物品身上带一个数据组件 `shelflife:spoilage`，里面三个数：

| 字段 | 含义 |
|---|---|
| `current_spoilage` | 上次结算时，已经腐坏了多少"点" |
| `max_spoilage` | 腐坏到多少点算烂透（写 100 就是"分 100 步烂完"） |
| `stored_timestamp` | 上次结算发生在哪个游戏刻（`0` = 哨兵值，表示"还没开始计时"） |

**它身上没有"剩余时间"这个东西。** 剩余时间是**显示层按时间差实时推算**的：

```
此刻腐坏值 = current_spoilage + (当前刻数 − stored_timestamp) × 倍率 ÷ ticks_per_spoilage
剩余时间   = (max_spoilage − 此刻腐坏值) × ticks_per_spoilage ÷ 倍率 ÷ 20  秒
```

这条很重要，它解释了两件容易误判的事：

- **tooltip 上的秒数一直在走，不代表每秒都在写。** 数字是客户端算出来的，
  组件里的值可能几分钟没动过。看到"卡住"时先确认自己看的是不是 tooltip
- **"腐坏值"和"剩余时间"不是一回事。** 进冷箱后剩余时间会跳成 20 倍 ——
  因为腐坏值没变，但除数（倍率）从 ×1 变成了 ×0.05

### 0.2 什么时候才会结算 —— 为什么我箱子里的食物不烂

结算**只在事件发生时**做，没有"全世界每 tick 扫一遍"这种东西：

| 触发 | 覆盖什么 |
|---|---|
| 拾取物品 / 合成 / 熔炼 | 玩家背包 |
| 开箱 / 关箱 | 那个容器 + 玩家背包 |
| 玩家**每秒**一次的被动刷新 | 玩家背包 + 正开着的那个容器 |
| 漏斗等**从容器抽出物品**的那一刻 | 被抽出的那一堆，按**来源容器**的环境结算 |

于是：

> **⚠️ 没被玩家碰过、也没被抽出来的食物不会腐烂。**
> 自动农场 → 漏斗 → 熔炉 → 漏斗 → 箱子这条链上的食物，在有人开箱之前一直是新鲜的。

**这是设计，不是 bug。** 好处是零轮询开销；代价是你不能指望"离线挂机把肉放坏"。

推论：

- `/give`、村民交易、创造模式取出、别的模组自己塞进来的食物，**一进玩家背包**就会在 1 秒内
  被补上计时起点（在那之前它保持全新、tooltip 也没有保质期那一行）
- **熔炼会让熟食从全新开始** —— 快烂的生肉煮一遍就洗白了。同样是被接受的设计
- 想知道某条自动化产线会不会腐烂，问自己一句：**"这堆东西有机会进玩家背包或被抽出来吗？"**

### 0.3 一个物品要"会烂"需要什么

两件事，缺一不可：

1. 数据包里有一条**食物规则**指向它（§2）
2. 它有机会被**玩家事件**碰到（§0.2）

**总闸门**：一条食物规则都没有时，`SpoilageManager.isEmpty()` 为真，本模组对游戏**零改动** ——
不打戳、不结算、不发网络包、不加 tooltip。所以"我只想加个冷箱"这种需求光写 `spoilage_env/`
是不够的，至少得有一条食物规则让它活起来。

---

## 1. 最小可用数据包（完整可抄）

```
我的数据包/
├── pack.mcmeta
└── data/
    └── shelflife/                   ← 推荐就用这个命名空间，理由见 §2.6
        ├── food_spoilage/
        │   └── my_foods.json
        └── spoilage_env/
            └── my_env.json
```

> **"文件放哪"和"规则管哪些物品"是两回事。** 文件放在 `shelflife` 命名空间下，
> 里面的物品 id 照样写你自己的（`"mymod:cheese"`）。
> 推荐写进 `shelflife` 只是为了**让覆盖顺序变成"单纯按文件名排"** —— 见 §2.6。

`pack.mcmeta`（1.21.1 的 `pack_format` 是 **48**）：

```json
{
  "pack": {
    "pack_format": 48,
    "description": "我的 ShelfLife 规则"
  }
}
```

`data/shelflife/food_spoilage/my_foods.json` —— 让你的两个物品会腐烂：

```json
{
  "items": {
    "mymod:cheese":  { "max_spoilage": 100, "ticks_per_spoilage": 2400 },
    "mymod:sausage": { "max_spoilage": 100, "ticks_per_spoilage": 4800, "result": "mymod:rotten_sausage" }
  }
}
```

`data/shelflife/spoilage_env/my_env.json` —— 让你自己的容器变冷（可选）：

```json
{
  "containers": {
    "mymod:fridge": { "temperature": -4.0 }
  }
}
```

进游戏后 `/reload`，然后看日志：

```
[ShelfLife] 读到 2 个保质期规则文件，等标签绑定后展开
[ShelfLife] 保质期配置生效，覆盖 2 个物品          ← 这个数字是你的目标物品数，必须对得上
[ShelfLife] 容器环境修正生效，覆盖 1 个方块
```

拿一个 `mymod:cheese` 在手里，tooltip 就有一行"保质期: …"。**如果没有任何文件被打进 `data/`，
日志里连第一行都不会出现** —— 那就是路径写错了（注意是 `data/<命名空间>/`，不是 `data/`）。

> **数据包放哪**：
> - 整合包/给玩家用 → 存档的 `datapacks/` 目录，或者做成一个 mod 的
>   `src/main/resources/data/<ns>/...`（内置数据包就是这么放）
> - 内置包改了要**重启**才生效；外部数据包可以 `/reload`
> - **推荐把规则写进 `data/shelflife/`**（和内置同一个命名空间）—— 原因见 §2.6

---

## 2. 食物规则：`data/<ns>/food_spoilage/*.json`

### 2.1 两种写法

**写法 A** —— 一组物品共用一组数值：

```json
{
  "items": ["minecraft:apple", "#minecraft:fishes"],
  "max_spoilage": 100,
  "ticks_per_spoilage": 840,
  "result": "shelflife:rotten_leftovers"
}
```

**写法 B** —— 整张表放一个文件，顶层写默认值，每个物品只覆盖要改的字段：

```json
{
  "max_spoilage": 100,
  "ticks_per_spoilage": 840,
  "result": "shelflife:rotten_leftovers",
  "items": {
    "minecraft:beef":  { "ticks_per_spoilage": 240, "result": "shelflife:rotten_meat" },
    "minecraft:cookie": { "ticks_per_spoilage": 3600, "result": "shelflife:moldy_cookie",
                          "tint": "#46c49a", "overlay": true }
  }
}
```

区别只在 `items` 是**数组**（A）还是**对象**（B），解析器靠 JSON 结构自己分，不需要额外字段。
**两种写法不能混在同一个文件里。**

写法 B 的顶层 `max_spoilage` / `ticks_per_spoilage` 可以不写，但那样**每个物品都必须自己写全** ——
否则加载时报错。这是故意的：与其悄悄用上一个错的值，不如在加载时就炸出来。

### 2.2 字段表

| 字段 | 写法 A | 写法 B 顶层 | 写法 B 单物品 | 含义 |
|---|---|---|---|---|
| `items` | ✅ 数组 | ✅ 对象 | — | 元素写 `minecraft:apple`（物品）或 `#minecraft:fishes`（物品标签） |
| `max_spoilage` | ✅ | ❌ | ❌ | 保质期总量，单位是抽象的"点"。**必须 ≥ 1** |
| `ticks_per_spoilage` | ✅ | ❌ | ❌ | 每消耗 1 点需要的游戏刻（20 刻 = 1 秒）。**必须 ≥ 1**（0 会除零） |
| `result` | ❌ | ❌ | ❌ | 烂完变成什么。默认 `shelflife:rotten_leftovers`；写 `minecraft:air` = **不转化** |
| `tint` | ❌ | ❌ | ❌ | 烂透时的染色 `#RRGGBB`。默认 `#7E8C4A`。见 §2.4 |
| `overlay` | ❌ | ❌ | ❌ | `true` = 在模型第二层淡入霉斑，而不是整块染色。见 §2.5 |

（❌ 表示"这个位置不读这个字段"。）

`result` 指向不存在的物品**不会崩**，但会在加载时打一条 warn，并且保质期耗尽后什么也不会发生。

### 2.3 时长怎么算

```
消耗速率（点/刻） = 环境倍率 ÷ ticks_per_spoilage
```

常温（倍率约 ×1）下：

```
保质期总时长 = max_spoilage × ticks_per_spoilage 刻
```

`max_spoilage` 只是"把保质期切成多少步"，**只影响 tooltip 的进度颗粒度和变色曲线的平滑度**。
调时长请改 `ticks_per_spoilage`。`max_spoilage` 写 **100** 就够了。

| 想要的实际时长（常温下） | `max_spoilage: 100` 时写 |
|---|---|
| 2 分钟 | `ticks_per_spoilage: 24` |
| 35 分钟 | `840` |
| 1 小时 | `1440` |
| 4 小时 | `5760` |
| 8 小时 | `11520` |
| 1 天 | `34560` |
| 3 天 | `103680` |
| 1 年 | `12614400` |

**手算一个例子**：`max_spoilage: 100`、`ticks_per_spoilage: 120`（内置的鳕鱼），
在平原（倍率约 ×0.95）：

- 一整条保质期 = `100 × 120 = 12000` 刻 = 600 秒 = **10 分钟**（倍率 ×1 时）
- 实际因为倍率是 ×0.95 → 比 10 分钟**更耐放**，约 10.5 分钟
- 进冷箱（倍率 ×0.05）→ 变成 20 倍 → 约 **3.5 小时**

一个偏离常规但很好用的技巧：**让它永不腐烂**，把 `ticks_per_spoilage` 写成一个极大的数
（比如 `2147483647`），比写 `result: minecraft:air` 更直接 —— 后者只是"烂了不变东西"，
tooltip 还是会走到"已腐烂"。

### 2.4 `tint`：为什么我调的绿色变成了灰的

- 显示层从"新鲜"到 `tint` 是**线性插值**的：消耗掉 **40%** 保质期后开始变色，最后 60% 里逐渐加深
- **染色是乘性的**（`底图颜色 × tint`），所以它**只能压暗/偏色，不能提亮** ——
  想用浅色把暗贴图提亮是做不到的
- **想让结果偏成某个色，`tint` 的通道比必须压过底图的通道比。** 举个踩过的实例：
  曲奇主色 `#8B5A2B`，红 0x8B=139、绿 0x5A=90，红绿比 **1.54**。
  选 `#6aa392`：绿/红 = 0xA3/0x6a = 163/106 = **1.54**，正好相等 → 乘完红绿一样大 → **出来是灰的**。
  换成 `#46c49a`：绿/红 = 196/70 = **2.8** > 1.54 → 才是绿的。
  **挑颜色前先把底图主色取出来算一下比值，能省好几轮试错。**
- `tint` **只收六位** `#RRGGBB`，alpha 固定 `FF`。不收八位是刻意的：原版会把染色的 alpha
  直接写进顶点，`alpha = 0` 的后果是"整个物品隐形"，干脆不给你写的机会

想给不同食物配不同霉色，就是给每条规则写自己的 `tint`：

```json
"minecraft:cookie": { "ticks_per_spoilage": 3600, "result": "shelflife:moldy_cookie",
                      "tint": "#46c49a", "overlay": true }
```

### 2.5 `overlay`：让物品"保持原色、只长霉"

整块染色有个绕不过去的上限：**它只能压暗**。想要"曲奇一直是正常的亮褐色，只是慢慢长出绿霉"，
就得用 `overlay: true`，它换一条路：

| 层 | tintIndex | 收到什么 | 效果 |
|---|---|---|---|
| 第 0 层（物品自己的贴图） | 0 | `-1`（不染） | **保持原本的亮度** |
| 第 1 层（霉斑图） | 1 | `tint` 的颜色，**alpha = 腐烂深度** | 霉从全透明一点点浮现 |

**这需要资源包配合**（客户端侧，不是数据包）—— 物品模型里必须写出 `layer1`：

```json
// assets/<命名空间>/models/item/<物品>.json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "minecraft:item/cookie",
    "layer1": "shelflife:item/mold_overlay"
  }
}
```

- 模型生成器会把 `layer0` → tintIndex `0`、`layer1` → tintIndex `1`，模组据此分别处理。
  **不写 `layer1` 就没有 1 号索引可问，`overlay` 会安静地什么也不做**
- 叠加层贴图**建议画成灰阶/浅色**，颜色交给 `tint` —— 这样一张图可以配不同 `tint` 复用到别的食物
- 贴图其余部分必须**完全透明**（这是"只长霉"的前提）
- **可以直接复用本模组自带的 `shelflife:item/mold_overlay`**，不必自己画一张
- 覆盖 `assets/minecraft/models/item/<原版物品>.json` 时注意：**别的资源包也会改同一个文件**，
  谁赢取决于资源包顺序。想稳一点就给物品做自己的模型路径，别去动原版那个
- `overlay` 和 `result` **不冲突**：霉长满之后照常换成 `result` 指定的物品

### 2.6 文件放哪个命名空间、覆盖顺序、冲突

**推荐把规则写在 `data/shelflife/food_spoilage/` 下** —— 也就是和内置数据包**同一个命名空间**。
（记住"文件放哪"和"规则管哪些物品"是两回事：文件放在 `shelflife` 下，
里面的物品 id 照样写你自己的 `"mymod:cheese"`。）

理由只有一条：**同一个命名空间里，覆盖顺序就是单纯按文件名排。**
一旦用了别的命名空间，就会多出一条反直觉的规则，而它几乎每个人都会踩一次。

#### 规则本体

> 同一目录下可以有任意多个文件，**按文件 id 排序依次生效，排序靠后的赢**。
> 而 `ResourceLocation` 的比较是 **先比 path、再比 namespace**。

所以只要都在 `data/shelflife/food_spoilage/` 下，**排序就等于按文件名排**：

| 你的文件名 | 与内置 `vanilla_foods.json` 比 | 结果 |
|---|---|---|
| `zz_mypack.json` | `v` < `z`，排在后面 | **你的赢** ✅ |
| `aaa.json` | `a` < `v`，排在前面 | 内置的赢 ❌ 换个排在后面的名字 |

**改一个物品的数值不需要动内置那个文件** —— 重复声明是**按字段**合并的：
后一条只覆盖它**真正写了**的字段，没写的继承前一条（见下面「重复声明的合并规则」）。

#### ⚠️ 如果你要写自己的命名空间

它**仍然完全支持**，只是会撞上这条：排序 **先比 path、再比 namespace**。举例 ——
内置在 `data/shelflife/food_spoilage/vanilla_foods.json`，你写
`data/mypack/food_spoilage/vanilla_foods.json`：两者 **path 完全相同**
（`food_spoilage/vanilla_foods.json`），于是只能比 namespace，而 `mypack` 排在
`shelflife` **前面** → **你整份会被内置的盖掉**。

写自己的命名空间时，取一个**排在 `vanilla_foods.json` 之后**的文件名
（`zz_mypack.json`）就没事。省下的是命名空间上的自由度，代价是这条规则得一直记着。

#### 重复声明的合并规则：**按字段继承**

同一个物品被多处声明时，**后面的只覆盖它自己写了的字段**，没写的继承前面那条：

| 字段 | 省略时 |
|---|---|
| `max_spoilage` / `ticks_per_spoilage` | **必须给出**（自己的，或本文档顶层的默认值）—— 它们没有合理的缺省值，与其猜不如加载时报错 |
| `result` / `tint` / `overlay` | 继承前一条；前面没人声明就各用各的默认值 |

**这条规则是为了避免一个很阴的坑**：曲奇的模型带霉斑叠加层（`overlay: true` 加一个自定义
`tint`），另一份数据包只想改它的保质期，于是写了
`"minecraft:cookie": { "ticks_per_spoilage": 999 }` —— 在"整条替换"的旧语义下，这一条会把
`overlay` 和 `tint` 一起冲回默认值：**叠加层还在，但它的颜色和透明度被换掉了**，
看起来像渲染坏了，其实是数据被覆盖了。

**要显式重置某个字段，就把它写出来**：`"overlay": false`。省略 = 继承，写明 = 覆盖。

#### 出问题时看日志

发生覆盖**且结果确实变了**时，会有一条 warn 告诉你从什么变成了什么 ——
上面那个例子里它会显示 `ticksPerSpoilage=3600 -> 999`，而 `tint`/`overlay` 两边一模一样
（值没变就不会提）。

```
[ShelfLife] 物品 minecraft:apple 的保质期参数被 shelflife:food_spoilage/zz_mypack.json 覆盖（... -> ...）
```

#### 「同名 path」是另一回事（大锤）

真要**整份替换**内置规则（而不是逐条覆盖）时，才用得上"同名 path"：
写 `data/shelflife/food_spoilage/vanilla_foods.json` 会把整个内置文件顶掉 ——
同一个 id 只会有一份资源，**原版资源管理器已经替我们判好了优先级**（数据包 > 模组 jar），
我们根本看不到两个同名文件。日常改数值别用它，那等于要把内置那份整个重写一遍。

### 2.7 条件：只在某个模组存在时才加载

支持 NeoForge 的 `neoforge:conditions`。**模组本体已经自己包好了 `ConditionalOps`，
你直接写就行，不需要额外包装**：

```json
{
  "neoforge:conditions": [
    { "type": "neoforge:mod_loaded", "modid": "farmersdelight" }
  ],
  "items": {
    "farmersdelight:bacon": { "max_spoilage": 100, "ticks_per_spoilage": 480 }
  }
}
```

条件不满足时整个文件被**静默跳过**（不报错、不警告），这正是想要的行为。
注意：**不写这个包装的话条件是静默失效的** —— 目标模组不存在时文件照样加载，
然后就等着日志里刷一堆"引用了不存在的物品"。本模组自己不会犯这个错。

### 2.8 标签

`#minecraft:fishes` 这样的标签引用是在**标签绑定之后**才展开的（不是解析 JSON 的时候），
所以 `/reload` 不会偶发丢规则，标签也能引用到别的模组加进去的内容。

引用了不存在的标签**不报错**，只有一条 warn 并且该条被忽略：

```
[ShelfLife] shelflife:food_spoilage/vanilla_foods.json 引用了不存在的物品标签 #minecraft:not_a_tag，已忽略
```

---

## 3. 环境与冷源：`data/<ns>/spoilage_env/*.json`

**没有这个文件也能用。** 环境倍率是**常开的**：没数据包时用内置默认曲线，
所以雪原里天生比沙漠腐烂得慢。数据包只负责**调曲线**和**加容器修正**，不是开关。

### 3.1 完整示例

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
    "minecraft:barrel":   { "temperature": -0.1 },
    "shelflife:cold_box": { "temperature": -4.0 },
    "#mymod:insulated":   { "temperature": -1.0, "humidity": -0.2 },
    "mymod:hot_locker":   { "rate_override": 8.0 }
  }
}
```

两节都可以省略（`settings` 省略 = 用内置默认曲线；`containers` 省略 = 没有容器修正）。

### 3.2 倍率公式

```
有效温度 = 温度来源的值 + 容器的 temperature 修正
有效湿度 = clamp(群系湿度 + 容器的 humidity 修正, 0, 1)

温度因子 = 2 ^ ((有效温度 − reference_temperature) / doubling_per)
湿度因子 = humidity_base + humidity_span × 有效湿度
倍率     = clamp(温度因子 × 湿度因子, min_multiplier, max_multiplier)
```

- 温度来源默认是**群系温度**；装了 createishot 就是它的**节点温度**（§3.7）
- 湿度**永远**来自群系 —— createishot 没有湿度这个维度
- `reference_temperature` 是"不改变快慢"的基准，取 0.8 正好是平原，所以那里
  **倍率 ≈ 0.95**（湿度因子 0.75 + 0.5×0.4 = 0.95），数据包里写的 `ticks_per_spoilage`
  基本就是实际速度

**手算几个群系**（默认曲线、无容器修正）：

| 群系 | 温度 | 湿度 | 温度因子 | 湿度因子 | 倍率 | 相对平原 |
|---|---|---|---|---|---|---|
| 平原 | 0.8 | 0.4 | `2^0 = 1.00` | 0.95 | **×0.95** | 基准 |
| 沙漠 | 2.0 | 0.0 | `2^1.5 = 2.83` | 0.75 | **×2.12** | 快 2.2 倍 |
| 热带草原 | 1.2 | 0.0 | `2^0.5 = 1.41` | 0.75 | **×1.06** | 略快 |
| 雪原 | 0.0 | 0.5 | `2^-1 = 0.50` | 1.00 | **×0.50** | 慢一半 |
| 丛林 | 0.95 | 0.9 | `2^0.19 = 1.14` | 1.20 | **×1.36** | 快 1.4 倍 |
| 蘑菇岛 | 0.9 | 1.0 | `2^0.125 = 1.09` | 1.25 | **×1.36** | 快 1.4 倍 |
| 下界荒地 | 2.0 | 0.0 | `2^1.5 = 2.83` | 0.75 | **×2.12** | 快 2.2 倍 |

（群系温度/湿度直接取原版 `Biome` 的 `getModifiedClimateSettings()`。）

### 3.3 `settings` 字段表

| 字段 | 默认 | 含义 |
|---|---|---|
| `reference_temperature` | `0.8` | 基准温度，这里倍率正好不变（0.8 = 平原） |
| `doubling_per` | `0.8` | 温度每高这么多，腐烂速度**翻倍**。**必须 > 0**（写 0 会除零，加载时直接报错） |
| `humidity_base` | `0.75` | 湿度因子在湿度 0 时的值 |
| `humidity_span` | `0.5` | 湿度因子的变化幅度（湿度 1 时 = base + span） |
| `min_multiplier` | `0.05` | 倍率下限。**冷源想要"20 倍保质期"就是撞到它** |
| `max_multiplier` | `4.0` | 倍率上限 |
| `temperature_source` | `auto` | `auto` / `biome` / `createishot`，见 §3.7 |
| `celsius_curve` | `[[0,-30],[1,25],[2,40]]` | 只在摄氏模式下用。见 §3.7 |

> 想让湿度完全不参与：`humidity_span` 写 `0.0`（于是湿度因子恒等于 `humidity_base`）。

**想彻底关掉环境倍率**（回到"只有数据包数值说了算"）：

```json
"doubling_per": 100000.0, "min_multiplier": 1.0, "max_multiplier": 1.0
```

（`doubling_per` 调大让温度因子恒为 1；上下限都设 1 让湿度也无从发挥。）

### 3.4 `containers` 字段表

键可以是**方块 id**（`mymod:fridge`），也可以是 `#方块标签`（`#mymod:insulated`，
一次给一整类方块）。

| 字段 | 默认 | 含义 |
|---|---|---|
| `temperature` | `0.0` | 温度**偏移**，单位是**原版温度刻度**。负数 = 更冷 = 更慢 |
| `humidity` | `0.0` | 加到群系湿度上，结果夹在 0~1 |
| `rate_override` | 不写 | **直接钉死倍率**，绕过温度、湿度和上下限。见 §3.5 |

> **`temperature` 的单位在两种温度来源下完全一样。** 摄氏模式下节点温度会先被反查回原版刻度，
> 这个偏移加在**反查之后** —— 所以同一份数据包在装 / 不装 createishot 时给出同样的冷却效果，
> 不需要写两份。（曲线是分段线性的，若把偏移加在摄氏那一层，同一个数字会差一个数量级。）

#### 按方块状态区分：`container_rules`

`containers` 的键只有方块，表达不了"同一个方块、两个状态"的容器 —— 典型是靠 `top` 属性
区分上下半的冷冻柜（一半制冷、一半不制）。这种用 `container_rules`，它是个**有序列表**，
每条 = 一条匹配条件 + 一组修正：

```json
{
  "containers": { "mymod:chest": { "temperature": -2.0 } },
  "container_rules": [
    { "blocks": "mymod:freezer", "state": { "top": "true" },  "temperature": -1.6 },
    { "blocks": "mymod:freezer", "state": { "top": "false" }, "rate_override": 0 },
    { "blocks": "#mymod:cold_boxes", "rate_override": 0 }
  ]
}
```

匹配条件就是**原版进度条件里那个 `BlockPredicate`**（`blocks` / `state` / `nbt` 三个字段名
一字不差），所以熟悉原版写法的人不用学新东西：

| 字段 | 说明 |
|---|---|
| `blocks` | 方块 id、`#标签`，或者它们的列表。标签由原版 `HolderSet` 原生支持，本模组不自己展开 |
| `state` | 状态匹配，如 `{ "top": "true" }`、`{ "type": "bottom" }` |
| `nbt` | **不支持** —— 本模组的查询只有"方块 + 状态"，读不了方块实体。写了会被丢弃并打 warn |

修正部分和 `containers` 里完全一样（`temperature` / `humidity` / `rate_override`）。

几条要记住的：

- **顺序 = 优先级，后写的赢**（同一文件里从上到下，跨文件按文件名排序）。查的时候倒着扫，
  第一条命中的就用它
- **`container_rules` 优先于 `containers`**：命中一条规则就不再去看老表 ——
  也就是说老表里针对同一个方块的条目会被规则盖掉（规则更"显式"）
- 只写 `blocks` 不写 `state` = "这个方块的所有状态"
- **既没写 `blocks` 也没写 `state`** 的规则会命中所有方块 → 被忽略并打 warn
- `blocks` 引用的方块/标签一个都不存在 → 也打 warn（否则它就是条永远匹配不上的死规则，
  而"死规则"比报错更难查）
- 方块状态**对不上**时（比如调用方给的方块和那个位置上的不一致），只会去查老表 ——
  宁可少匹配，也不要拿一个别的方块的状态去命中规则

### 3.5 容器三种做法 —— 什么时候用哪个

两个方向都是同一个字段，只是正负号不同：

| 我想要 | 写法 |
|---|---|
| **保鲜**（更慢、冷藏、冷冻） | 负的 `temperature`，或 `rate_override: 0.05` / `0`（停腐） |
| **反鲜**（更快、腐烂箱） | 正的 `temperature`，或一个大的正 `rate_override` |

具体用哪种：

| 做法 | 写法 | 什么时候用 |
|---|---|---|
| **温度偏移**（推荐） | `{"temperature": -4.0}` / `{"temperature": 2.4}` | 默认就该用这个。它**是环境的一部分**，和"外面有多热"叠加：沙漠里的冷箱比雪原里的冷箱效果弱。会被自动化喂食也没问题 |
| **钉死倍率** | `{"rate_override": 0.05}` | 想要"不管在哪个群系、效果完全一样"，或者想要一个精确的倍率（比如 ×0.1 = 10 倍保质期）。`0` = **完全不腐烂**（真正的停腐，不是"很慢"） |
| **方块实体自己报** | Java 实现 `ContainerClimate` + `ShelfLifeApi.settleContainer` | 只有**动态**容器需要：通电才冷、燃料烧完就停。见 §6.3 |

> **固定温度的容器只需要一行数据包，不需要写任何 Java。** 本模组自己的冷箱就是这么做的
> （`"shelflife:cold_box": { "temperature": -4.0 }`）。
> `ContainerClimate` 那个接口是给"状态会自己变"的容器准备的，别把简单的事情做复杂。

**`rate_override` 的取值规则**（源码级）：

- **`0` = 真正的停腐**（不是"很快"，是"不涨"）
- **正数 = 直接用这个倍率**，**不受 `min_multiplier` / `max_multiplier` 影响** ——
  所以 `20.0` 就是一个硬 20 倍腐烂箱
- **负数会被忽略**（当作没写，掉回温度路径）。负倍率没有意义，
  想让食物烂得更快请写一个大的正数

### 3.6 想做"腐烂箱"（让食物烂得更快）

这正是 `rate_override` 和正温度偏移的用武之地，两种都行：

```json
"containers": {
  "mymod:hot_locker": { "temperature": 2.4 },
  "mymod:incinerator": { "rate_override": 20.0 }
}
```

- `temperature: 2.4` → 有效温度 0.8+2.4 = 3.2 → 温度因子 `2^3 = 8` →
  乘湿度因子 0.95 ≈ **×7.6**（没撞上限，还能随群系浮动）
- `rate_override: 20.0` → **恒定的 ×20**，干脆利落，和群系无关

**别用负数去表达"更快"。** 负的 `rate_override` 会被当成没写。

用温度偏移时注意 `max_multiplier`：默认上限 4.0，所以偏移给太大也没用
（`temperature: 3.2` 就已经撞到上限了）。想要比 ×4 更快，就用 `rate_override`。

### 3.7 摄氏模式（装了 createishot 时）

`temperature_source` 默认 `auto`：**装了 createishot 就用它的温度，没装就用群系温度。**
想固定住就写 `"biome"` 或 `"createishot"`。

createishot 提供的是真正的热力学温度场（体素网格 + 传导 + 辐射，服务端求解），
能表达群系温度表达不了的东西：**站在营火边、海拔升高、入夜降温、下雨**。

用它的时候：

```
等效温度 = celsius_curve 反查(createishot 的节点温度)
倍率     = clamp(2^((等效温度 + 容器temperature修正 − reference_temperature) / doubling_per)
                 × 湿度因子, min, max)
```

**`celsius_curve` 就是 createishot 自己的「群系温度 → 摄氏」映射**，方向是「温度 → 摄氏」，
本模组用的时候反过来查。默认 `[[0.0, -30.0], [1.0, 25.0], [2.0, 40.0]]` ——
注意它在 1.0 处有拐点，所以是**分段线性**而不是一条直线（低温段大约每 1.0 单位 = 55°C，
高温段只有 15°C）。

曲线要满足：**至少两个锚点**，且温度值和摄氏值都**严格递增**（否则同一个摄氏温度对应多个
原版温度，反查没有唯一解）。不满足会在加载时报错。

> **超出锚点范围不夹紧，而是沿最外那段外推。** 夹紧会让"很热"和"极热"给出同一个倍率。

**这组数需要按你的 createishot 配置校准**（对方改了 `ThermalConfig` 之后这里不会自动跟着变）：

1. 站在平原、白天、晴天
2. `/createishot thermal at` 读到的环境温度
3. 应该约等于曲线在 0.8 处的插值结果 —— 默认曲线下 ≈ **23°C**

对不上就改 `celsius_curve`，直到对得上。（校准的目的是让"常温下手感和只用群系温度时一致"。）

**客户端不需要装 createishot。** 温度由服务端下发 ——
`auto` 在服务端解析数据包时就落到了具体来源上，客户端拿到的永远是解析过的值。
（否则没装 createishot 的客户端会按自己的模组列表各解析一遍，两边算出不同的倍率。）

### 3.8 ⚠️ `settings` 的覆盖顺序

**推荐把文件写在 `data/shelflife/spoilage_env/` 下、取一个排在 `environment.json` 之后的名字**
（比如 `zz_mypack.json`）。这样它排在最后，`settings` 就是你的。

> **`settings` 是整块覆盖，不是逐字段合并。** 而且排序比的是
> **path 在前、namespace 在后**（和 §2.6 同一个机制）。
>
> **注意这里的"整块覆盖"和 `food_spoilage` 不一样**：食物规则现在是**按字段继承**的
> （§2.6），但 `settings` 不是 —— 这里省略一个字段 ≠ 继承，而是回到**代码里的内置默认值**。

意味着两件事：

- **想改 `settings` 就得写全。** 只写 `reference_temperature` 一项的话，其余字段会用**内置默认值**，
  而不是内置数据包那份 `environment.json` 里的值 —— 看起来"改了但没生效"，其实是整块被换掉了
- **写自己的命名空间要小心平局。** `data/mypack/spoilage_env/environment.json` 的 path 和内置的
  完全相同，于是比 namespace，而 `mypack` 排在 `shelflife` **前面** → **你整块 settings 被内置盖掉**。
  换成 `zz_mypack.json` 就没事

> **`settings` 不需要用"同名 path"那招。** 它是整块覆盖，一个排在后面的普通文件就能全盘接管；
> 同名 path 是留给"整份替换 `food_spoilage` 规则"的（见 §2.6 末尾）。

最省心的写法其实是：**在同一个文件里写 `settings` 的同时也写 `containers`** ——
反正整块都要写全，不如一次写完。

`containers` **不是**整块覆盖，而是按键合并（`putAll`）——

- 不同的键互不影响，所以"我只加一行冷源"是安全的，随便什么文件名都行
- 同一个键（同一个方块 id / 同一个标签字符串）被两处声明 → 后生效的那个赢
- 一个方块同时被"直接 id"和"标签"命中时，按**键字符串**排序、靠后的赢

---

## 4. 物品组件 `shelflife:spoilage`（给 `/give`、战利品表、配方用）

物品身上那个检查点是个数据组件，id 是 **`shelflife:spoilage`**：

```json
{
  "current_spoilage": 0,
  "max_spoilage": 100,
  "stored_timestamp": 0
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `current_spoilage` | ✅ | 已腐坏点数 |
| `max_spoilage` | ✅ | 上限。到 `current >= max` 就算烂透，会被替换成 `result` |
| `stored_timestamp` | ❌ | 默认 `0`。**`0` 是"不在任何容器里"的哨兵值**，此时不按时间外推，只显示 `current_spoilage` |

> `stored_timestamp: 0` 不是一个"很久以前的时间"，它是**哨兵**。所以
> `/give` 出来的带组件物品，在进玩家背包被补上时间戳之前，**tooltip 上的数字不会动**。
> 这是刻意的（见 `SpoilageData.NO_TIMESTAMP` 的注释）。

写进 `/give`（需要一个 `max_spoilage`/`ticks_per_spoilage` 已在数据包里配好的物品）：

```
# 一条刚过半、还剩一半保质期的鳕鱼（内置鳕鱼 tps=120、max=100 → 常温下约 5 分钟）
/give @s minecraft:cod[shelflife:spoilage={current_spoilage:50,max_spoilage:100}]

# 一条已经烂到 92%（tooltip 会显示"已腐烂"或者只剩一点点）
/give @s minecraft:cod[shelflife:spoilage={current_spoilage:92,max_spoilage:100}]
```

战斗利品表 / 结构战利品表 / 配方结果里也能这么写：

```json
{
  "type": "minecraft:item",
  "name": "mymod:cheese",
  "components": {
    "shelflife:spoilage": { "current_spoilage": 0, "max_spoilage": 100 }
  }
}
```

> **不需要**为了"给物品加保质期"去写组件 —— 数据包里的规则会自动在它第一次被玩家事件
> 碰到时打戳。直接写组件只在两种场景有用：**测试**（指定精确的腐坏程度），
> 和**成品要直接是"陈货"**（比如地牢里翻出来的干粮）。

---

## 5. 调试指令：`/shelflife spoilage`

权限等级 2（和 `/give` 同级）。**只对主手物品生效**，改的是背包里那个实例本身。

```
/shelflife spoilage get              看组件原值：已腐坏 / 上限 / 时间戳
/shelflife spoilage set <点数>        直接设定已腐坏点数
/shelflife spoilage add <点数>        在当前值上累加（可以是负数）
/shelflife spoilage rot <百分比>      设成"烂了这么多"，0~100 —— 测"快烂的鱼"用这个最快
/shelflife spoilage max <点数>        改保质期上限
/shelflife spoilage clear            摘掉组件，物品回到"没有保质期"的原始状态
```

例：手上拿一条鳕鱼，

```
/shelflife spoilage rot 84
```

它就变成"烂了 84%" —— 内置鳕鱼常温下只剩约 1 分 35 秒；丢进冷箱（倍率 ×0.05）
会立刻显示成约 32 分钟。**这正是验证"冷箱到底有没有生效"最快的办法。**

`get` 的输出长这样：

```
minecraft:cod：已腐坏 84 / 100，时间戳 12345
```

> 为什么需要这条指令：不做它就只能手写 §4 那个组件 NBT，
> 而"精确控制腐坏程度"这件事（比如要 84 而不是 85）手写太容易错。

---

## 6. 给其他模组的 Java API

如果你的模组要**主动配合** ShelfLife（而不仅仅是写数据包），有两个接口可以用。
两个都在 `org.slf4j.shelflife.api` 包里。

### 6.1 声明可选依赖

ShelfLife 是**可选**依赖 —— 你不应该硬依赖它。三件事都要做：

**① 编译期依赖**（`build.gradle`）：

```groovy
compileOnly files('libs/shelflife-1.0.0.jar')   // 或者你自己的 maven 坐标
```

**② 声明可选前置**（`src/main/templates/META-INF/neoforge.mods.toml`）：

```toml
[[dependencies.你的modid]]
modId = "shelflife"
type = "optional"      # 没装也不影响启动
ordering = "AFTER"
side = "BOTH"
```

**③ 调用点必须双重保护**：

```java
if (ModList.get().isLoaded("shelflife")) {   // 别让 JVM 去加载引用了 ShelfLife 的类
    ShelfLifeBridge.settle(stack, level, pos);   // 这个类里才 import ShelfLifeApi
}
```

只写 `isLoaded` 判断、却把 `ShelfLifeApi` 的调用直接写在同一个类里是**不够的** ——
类加载时就会 `NoClassDefFoundError`。要把调用**再包一层单独的类/方法**，
让 JVM 只在确认 mod 存在时才去解析它。

### 6.2 `ShelfLifeApi` —— 搬运物品时结算

| 方法 | 用途 |
|---|---|
| `isManaged(Item)` | 这个物品有没有保质期规则（没有的话它永远不会腐烂） |
| `spoilageOf(ItemStack)` | 当前腐坏点数，**按检查点惰性推算**，不写回。未管理返回 `-1` |
| `spoilageOf(stack, level, pos, block)` | 同上，但按给定位置的环境实时推算 |
| `maxSpoilageOf(ItemStack)` | 上限。未管理返回 `-1` |
| `rateAt(level, pos, block)` | 某位置的腐烂倍率（含容器修正与湿度） |
| `settleStack(stack, level, pos, block)` | **用给定位置的环境结算这一堆** —— 搬运时该调的就是它 |
| `replacementIfSpoiled(stack)` | 已经烂透就返回产物，否则 `null` |

**为什么搬运时要调 `settleStack`**：本模组的结算挂在"物品进出容器"这些时刻上。
你用管道 / 溜槽 / 自己的机器搬东西时，那些时刻我们看不见 ——
于是物品"在某个容器里待了多久"会被记到**错误的环境**上。最典型的后果是冰箱：
从普通箱子抽进冷箱，箱子里的那几个小时会被当成在冷箱里度过，等于白冻。

**正确用法：在物品离开容器那一刻，用来源容器的位置调一次。**

```java
ItemStack taken = handler.extractItem(slot, amount, false);
if (!taken.isEmpty()) {
    ShelfLifeApi.settleStack(taken, level, sourcePos, level.getBlockState(sourcePos).getBlock());
    ItemStack replacement = ShelfLifeApi.replacementIfSpoiled(taken);
    if (replacement != null) taken = replacement;   // 刚好烂透：交出去的就该是产物
    // ...把 taken 放进目标
}
```

注意两点：

- `settleStack` **只结算、不换物品** —— 物品类型不可变，"变成别的东西"只能整个替换，
  所以烂透时你要自己用 `replacementIfSpoiled` 的返回值
- **`ItemStack` 是不可变的"值"**，改组件请改你手上那个实例，别改完就丢了

### 6.3 动态容器：`ContainerClimate` + `settleContainer`

**先确认你需要这个。** 固定温度的容器（"我这个方块就是个冰箱"）**不需要写任何 Java** ——
数据包里 [§3.4](#34-containers-字段表) 的 `containers` 一行就够了，本模组的冷箱就是这么实现的。
**只有数据包表达不了的容器**才需要这一节：动态状态（通电才冷、燃料烧完就停）、
或者需要 NBT 才能判断的。**如果差异只是"同一个方块的不同状态"（比如上下半），
那是数据包能表达的 —— 用 §3.4 的 `container_rules` 就行，一行 Java 都不用写。**

#### ① 先解决一个前置问题：`climate()` 读什么

这决定了你**能不能**把顺序写对。

**`climate()` 要读你自己记的字段，不要直接读方块状态。** 因为"先结清、再改状态"这个唯一
正确的顺序，前提是"结清的那一刻采样到的还是旧环境"。如果 `climate()` 是从方块状态算出来的，
而方块状态已经被改掉了，你就**根本没有"改之前"这个时机**可用：

```java
// ✅ 读自己的字段：什么时候改由你定，"改之前"这个时机是存在的
@Override
public @Nullable ContainerModifier climate() {
    return powered ? COLD : null;
}

// ❌ 读实时方块状态：红石一变它就变了，你已经错过结清时机
@Override
public @Nullable ContainerModifier climate() {
    return getBlockState().getValue(PropagatedPowerBlock.POWERED) ? COLD : null;
}
```

真要读方块状态也行，那就得把时机抢回来：**在你自己调 `setBlockState` 之前**先结清。

还要注意返回值的语义差别：

| 返回 | 含义 |
|---|---|
| `null` | "此刻我没有修正" → **回落到数据包**（数据包也没有就回落到群系） |
| `new ContainerModifier(0, 0, empty)` | "我此刻的修正是 0" → **连数据包给这个方块的修正一起压掉** |

所以"断电 = 变成普通箱子"应该返回 `null`，而不是零修正 —— 除非你就是要无视数据包那条。

#### ② 完整例子：一台通电才冷的冰箱

```java
public class PoweredFridgeBlockEntity extends BaseContainerBlockEntity implements ContainerClimate {

    private static final int SLOTS = 27;

    /** 通电时的修正。定义成常量：这个对象每次采样都会被取用，没必要反复 new */
    private static final ContainerModifier COLD = new ContainerModifier(-4.0F, 0.0F, Optional.empty());

    /** 当前状态记在你自己身上，不从方块状态倒推 —— 见 ① */
    private boolean powered;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public PoweredFridgeBlockEntity(BlockPos pos, BlockState state) {
        super(MyMod.POWERED_FRIDGE_BE.get(), pos, state);
    }

    // ---------------------------------------------------------- 报"此刻多冷"

    @Override
    public @Nullable ContainerModifier climate() {
        // 纯查询：只读一个字段。全模组每次环境采样都会走到这里，别放任何查询或遍历
        return powered ? COLD : null;      // 断电返回 null = 回落到数据包 / 群系
    }

    // ---------------------------------------------------------- 状态变化

    /**
     * 供电方（发电机、能量线、红石逻辑）从这里通知。
     *
     * <p><b>通电和断电走的是同一个方法</b> —— 别把某一个方向写在别处，那样很容易只结清一半。
     */
    public void setPowered(boolean powered) {
        if (powered == this.powered) return;      // 状态没变：不用结清，也不用改

        // ⚠️ 全部正确性都在这一行的位置上：必须在 this.powered 被改掉**之前**调。
        // 此刻 climate() 读到的还是旧状态，所以结清的是"旧倍率那一段时间"；
        // 改完之后产生的才是新倍率的账。
        ShelfLifeApi.settleContainer(this, level, worldPosition, getBlockState().getBlock());

        this.powered = powered;
        setChanged();

        // 如果"通没通电"在画面上看得出来，常规做法还要同步给客户端。
        // 注意这里要自己判 level 非空 —— settleContainer 不需要，它内部处理了
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ---------------------------------------------------------- 标准容器样板

    @Override public int getContainerSize() { return SLOTS; }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> items) { this.items = items; }
    @Override protected Component getDefaultName() { return Component.translatable("container.mymod.powered_fridge"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inv) { return ChestMenu.threeRows(id, inv, this); }
    // saveAdditional / loadAdditional 里记得把 powered 一起存了，否则重启后状态就丢了
}
```

`ShelfLifeApi.settleContainer` 内部会处理"是不是客户端""level 是不是 null""本模组有没有规则"
这几件事，调用方直接调就行，不用在它外面套一堆判断。

#### ③ 四条规矩

| 规矩 | 为什么 |
|---|---|
| `climate()` **只读自己的字段** | 否则你没有"改状态之前"这个时机（见 ①） |
| **先结清，再改状态** | 结清用的是"此刻"的环境。反过来就是拿**新**倍率去算**旧**间隔 |
| **两个方向都要** | 通电、断电是两个独立事件。漏一个，那段间隔就记到另一边的倍率上了 |
| **只在状态真的变了时调** | 别在 `tick()` 里无条件调。结清本身是幂等的（只消费整点，多调也不会算错），但白花钱 |

顺序写反、或者漏调一边，**都不会报任何错**，只会在几小时后表现为"这堆食物怎么烂得不对"。

#### ④ 怎么验证你没写错

1. 往冰箱里放一条鱼，手上拿着它 `/shelflife spoilage get`，记下时间戳
2. 通电，等半分钟让它按冷倍率攒一点
3. **断电**（这一步应该结清一次）
4. 再 `/shelflife spoilage get` —— **时间戳应该正好落在"断电那一刻"**，
   而 `cur` 只涨了冷倍率那一份
5. 对照：如果第 3 步漏了，等下次开箱时 `cur` 会按**常温**把这几分钟一次性补上

> `get` 读的是**组件里存着的锚点**（真值），tooltip 上是**按时间实时推算**的显示值 ——
> 两者不是一回事，验证要看 `get`。按 **F3+H** 打开高级提示框，倍率那一行会直接
> 告诉你此刻是 ×0.05 还是常温。

#### ⑤ 数据包那边要不要写？

- **断电 = 普通箱子**（不做任何修正）→ **什么都不用写**，`climate()` 返回 `null` 自然落回群系
- **断电后还想有一点保温**（"箱体本身隔热"）→ 数据包里给这个方块写一条
  `{"temperature": -0.5}` 兜底，通电时 `climate()` 的返回值会盖掉它 ——
  这就是 ① 里"返回 `null` vs 返回零修正"的用法差别

#### ⑥ 不调会怎样

本模组的腐烂是**检查点模型**：物品身上只有一个"上次结算时刻 + 当时的值"，
**表示不了"这段间隔里先后有两个倍率"**。于是有一条硬规则：

> **任何会改变倍率的事件，都必须在那一刻结算一次。**

实现 `ContainerClimate` **不会**自动获得这条保证，所以漏调的后果是：

| 情况 | 后果 |
|---|---|
| 容器**正被玩家开着** | 模组自己会在 1 秒内发现倍率变化并补一次结算（用旧倍率），漏调也不会错 |
| 容器**没被任何玩家开着** | **没有任何东西会观察到这次变化**。要等到下次有人开箱或往外抽东西，才会拿那时候（新）的倍率把整段间隔算一遍 |

模组**不会**主动扫描世界上的容器来发现这种变化 —— 那正是本模组刻意避开的开销。
所以"我变了"只能由容器自己说。这也是为什么这个接口必须存在。

**顺序写反的代价不是理论，是实测的数字。** 同样一段"20 分钟前开始计时、还没腐坏"的鱼，
只改"按谁的环境结清"这一个参数（内置鳕鱼 `tps=120`）：

| 结清用的环境 | 算式 | 结果 |
|---|---|---|
| 冷箱（×0.05） | `24000 × 0.05 ÷ 120` | `cur = 10`，还剩 3 小时 |
| 常温（×0.95） | `24000 × 0.95 ÷ 120 = 190` → 截到 100 | **直接烂透，变成腐烂肉类** |

结清本身是**把检查点重新锚定**：当前值一点不变，时间戳往后推到"账已结清的那一刻"，
所以之后产生的才是新倍率的账。实测里 `live` 结清前后都是 `10.000`、时间戳恰好前移 24000 刻。

> 同理，**搬运物品**的模组要在抽取时调 `ShelfLifeApi.settleStack`（§6.2），
> 那个是"物品离开容器"的对应动作。

### 6.4 容器定位扩展点：`MenuContainerProvider` / `ContainerLocators`

**这是干什么的。** 本模组默认靠遍历菜单槽位、找"槽位容器是方块实体"的那个来反查容器位置。
这条路对原版容器都成立，但对两类真实容器**永远不成立**，而且失败得完全静默：

- **用 `SlotItemHandler` 的机器**（Mekanism 以及一大票模组）——
  它的槽位容器是一个**共享的空 `SimpleContainer`**，不是方块实体，按构造必然查不到
- **把两个容器包起来的复合容器**（双联箱、Balm 那类"懒人厨房冰箱"）

**后果不只是提示框的倍率不对**：开箱时那次结算会按**玩家脚下**的倍率给容器里的食物记账，
是实打实的算错账。以前这种情况连日志都没有，现在会打一条 warn：

```
[ShelfLife] 反查不到容器位置：com.example.MyMachineMenu，将退化为按玩家位置采样（容器修正不会生效）…
```

**两种接法，选一种：**

**① 自己的菜单实现 `MenuContainerProvider`（推荐，零注册）**

```java
public class MyMachineMenu extends AbstractContainerMenu implements MenuContainerProvider {
    private final MyMachineBlockEntity machine;

    @Override
    public @Nullable BlockEntity shelfLifeContainer() {
        return machine;
    }
}
```

**② 菜单不是自己的**（别人的模组写死了）→ **注册一个定位器**：

```java
// 在 mod 的 init 阶段
ContainerLocators.register(menu ->
        menu instanceof TheirMachineMenu their ? their.getTileEntity() : null);
```

注册顺序 = 优先级（先注册的先问）。问的顺序是**先菜单自己的接口、再注册表、最后才遍历槽位** ——
显式声明永远优先于猜。每个定位器都被单独兜了异常：一个模组写坏了不会让所有容器的定位一起失效。

> 这两个类都在 `api/` 里，所以用到它们的代码同样要按 §6.1 做**可选依赖**保护。

---

## 7. 日志速查

加载数据包时（`/reload` 或开服）：

| 日志 | 含义 |
|---|---|
| `读到 N 个保质期规则文件，等标签绑定后展开` | 食物规则文件数。**没有这一行 = `food_spoilage/` 路径写错了** |
| `保质期配置生效，覆盖 N 个物品` | **最终生效的物品数 —— 盯这个数字**。比预期少就是有引用没解析成功（往下看 warn） |
| `容器环境修正生效，覆盖 N 个方块` | 容器修正生效数。没写 `containers` 时不会出现 |
| `物品 X 的保质期参数被 Y 覆盖（a -> b）` | 同一物品被多处声明且数值不同。正常但要知道 |
| `X 的 result 指向不存在的物品 Y，保质期耗尽后不会转化` | `result` 写错了（或那个模组没装） |
| `X 引用了不存在的物品 Y，已忽略` | 物品 id 写错了 |
| `X 引用了不存在的物品标签 #Y，已忽略` | 标签不存在（可能是写错，也可能是那个模组没装，此时应该配 `neoforge:conditions`） |
| `X 里的物品引用 'Y' 不是合法 ResourceLocation，已忽略` | JSON 里写成了 `minecraft apple` 这种 |
| `解析 X 失败：...` | JSON 结构不对（比如两种写法混用、字段类型错）。**后面的错误信息会指出具体位置** |
| `容器修正引用了不存在的方块 'X'，已忽略` | 容器路径写错了 |
| `容器修正引用了不存在的方块标签 X，已忽略` | 同上，标签版 |

---

## 8. 视觉效果参考

| 消耗的保质期 | 画面 |
|---|---|
| 0% ~ 40% | 完全正常，tooltip 显示剩余时间 |
| 40% ~ 100% | 线性过渡到 `tint`（或 `overlay` 的 alpha 从 0 到 1） |
| 100% | 替换成 `result` 指定的物品 |

**tooltip 的两种档位**（原版行为）：

- 默认：只有一行"保质期: 3小时12分"，不足一分钟写 `<1分`
- **按 F3+H 打开高级提示框**：时间精确到秒，并多两行 ——
  当前的**腐烂倍率**，以及**代入实际数字的公式**

公式那行显示的是**夹上下限之前**的值，所以能一眼区分"本来就是 0.05"和"算出来 0.012 被夹到 0.05"
（这两种情况要改的东西完全相反：改曲线 vs 改上下限）。

---

## 9. 按症状排查

| 症状 | 原因 |
|---|---|
| **日志里连"读到 N 个保质期规则文件"都没有** | 路径不对。必须是 `data/<命名空间>/food_spoilage/xxx.json` |
| **物品在箱子里不腐烂** | 设计如此（§0.2）。它必须被玩家事件碰到，或被从容器里抽出来 |
| **`/give` 的食物一开始没有 tooltip** | 它还没被打戳。进玩家背包 1 秒内会补上 |
| **tooltip 的秒数不动** | 看的是组件原值且 `stored_timestamp` 是 0（§4）。或者你盯的是 `/shelflife spoilage get` 的输出 —— **那才是真值，tooltip 是推算值** |
| **丢进冷箱后保质期变成 20 倍** | 正常。腐坏值没变，是除数（倍率）从 ×1 变成 ×0.05 |
| **改成 `tint` 之后颜色是灰的 / 没变化** | 通道比不够（§2.4）。先算底图的红绿比 |
| **`overlay: true` 什么也没发生** | 物品模型里没有 `layer1`（§2.5）。这是资源包的事，不是数据包 |
| **`/reload` 后规则没变** | ① 你改的是打进 jar 的**内置**包 → 要重启；② 你的文件被别的包按 §2.6 的规则盖掉了 |
| **改了 `settings` 但一点效果都没有** | §3.8 的覆盖顺序陷阱。换个排得靠后的文件名（`zz_xxx.json`） |
| **冷箱不够冷 / 冷箱不冷** | 倍率撞到了 `min_multiplier`（默认 0.05）—— 想要更冷请把这个值调低 |
| **腐烂箱不够快** | 撞到了 `max_multiplier`（默认 4.0）→ 改用 `rate_override`（它不受上下限约束） |
| **新加的容器修正完全没效果** | 键写的是方块 id，要确认那个方块**真的实现了 `Container`**（冷源只在容器被采样时施加） |
| **动态冰箱（通电才冷）断电后账算错** | 改状态**之前**没调 `ShelfLifeApi.settleContainer`，或者只调了一个方向。见 §6.3 |
| **机器 / 复合容器里的食物按常温在记账** | 反查不到容器位置（日志里有 warn）。让菜单实现 `MenuContainerProvider`，或注册 `ContainerLocators`。见 §6.4 |
| **改了 `tint`/`overlay`，但霉斑的颜色/透明度不对** | 有另一份数据包也声明了这个物品。重复声明**按字段继承**，它多半只改了自己写的那几个字段 —— 看日志里的覆盖警告。见 §2.6 |
| **想给同一个方块的两种状态配不同冷源** | 用 `container_rules` 的 `state`，别为它写 Java。见 §3.4 |
| **堆叠之后保质期变了** | 饥荒式加权平均：`(旧值×旧数量 + 新值×新数量) ÷ 总数`。这是设计 |

---

## 10. 给 AI 的速查（TL;DR）

在另一个项目里用 ShelfLife：

1. 依赖：NeoForge 21.1.252 / MC 1.21.1，modid `shelflife`
2. 让物品会烂 → 在 `<你的源码>/resources/data/<你的modid>/food_spoilage/xxx.json` 写规则。
   写法 A（`items` 是数组）或写法 B（`items` 是对象 + 顶层默认值），**不能混用**
3. 必填 `max_spoilage` 和 `ticks_per_spoilage`（都 ≥ 1）；`result` 不写默认变成
   `shelflife:rotten_leftovers`，写 `minecraft:air` 表示不转化
4. 时长换算：常温下 `总时长 = max_spoilage × ticks_per_spoilage` 刻（20 刻 = 1 秒）
5. 让自己的容器更冷 → `spoilage_env/` 里 `"containers": { "你的方块id": { "temperature": -2.0 } }`。
   单位是原版温度刻度，装不装 createishot 都一样
6. 想让容器**更快**腐烂 → 正的 `temperature`，或 `rate_override: 20.0`。
   **负数会被忽略**
7. 规则写进 `data/shelflife/{food_spoilage,spoilage_env}/`（**和内置同一个命名空间**），
   文件名取排在 `vanilla_foods.json` / `environment.json` 之后的（比如 `zz_mypack.json`）——
   这样覆盖顺序就是**单纯按文件名排**，没有 namespace 平局那套反直觉规则。
   写自己的命名空间也行，但 path 和内置相同时会按 namespace 分胜负（`mypack` 排在
   `shelflife` 前面 → 你会被盖掉）
8. 加载后看日志确认 `[ShelfLife] 保质期配置生效，覆盖 N 个物品`，N 要对
9. 别指望它给"没被玩家碰过"的食物计时 —— 这是设计（事件驱动）
10. 不想让 createishot 的热源影响腐烂 → `"temperature_source": "biome"`；
    它的温度换算对不上 → 校准 `celsius_curve`（`/createishot thermal at`）
11. 要精确控制腐坏程度来测试 → `/shelflife spoilage rot 84`（手上拿着物品）
12. 写了规则的物品**不要**再自己实现一套腐烂逻辑，会打架
13. 动态容器（通电才冷那种）：`climate()` 只读自己的字段，**状态真的变了时先调
    `ShelfLifeApi.settleContainer` 再改状态**，通电/断电两个方向都要 —— 完整示范见 §6.3
14. **同一个方块的两种状态**要不同冷源 → `container_rules`
    （`{"blocks": …, "state": {…}}`，形状同原版 `BlockPredicate`；`nbt` 不支持；
    优先于 `containers`）。**别为它去实现 `ContainerClimate`** —— 那是数据包能表达的事
15. 重复声明同一个物品是**按字段继承**的（后写的只覆盖它写了的字段）；
    要显式重置某个字段就把它写出来。`settings` 不一样，它仍是整块覆盖
16. 你的机器容器（用 `SlotItemHandler` 那类）要让菜单实现 `MenuContainerProvider`
    （或注册 `ContainerLocators`），否则里面食物的账会按**玩家脚下**的倍率记
