# ShelfLife

NeoForge 1.21.1 的食物保质期模组。食物会随时间腐烂，烂到头的会变成腐烂物。

**所有数值都来自数据包。** 模组本体只提供机制 —— 哪些食物会烂、烂多久、烂成什么，全部写在
`data/<命名空间>/food_spoilage/*.json` 里；"哪里冷、哪里热"写在 `spoilage_env/*.json` 里。
内置了一份覆盖原版食物的数据包，开箱即用。

| 我想…… | 去哪 |
|---|---|
| 让我的食物会腐烂 / 我的容器变冷 | **[docs/datapack.md](docs/datapack.md)** —— 完整字段表 + 可抄的最小数据包 |
| 出问题了、不知道哪一步错了 | [docs/datapack.md §9 按症状排查](docs/datapack.md) |
| 在别的项目里用这个模组（AI 或人） | [.claude/skills/shelflife-datapack/SKILL.md](.claude/skills/shelflife-datapack/SKILL.md) |
| 我的模组要主动配合它（Java） | [§ 给其他模组的 Java API](#给其他模组的-java-api) |
| 想快速试出一个效果 | [§ 调试指令](#调试指令) —— `/shelflife spoilage rot 84` |

## 特性

- **保质期**：食物 hover 显示剩余时间（不足一分钟写 `<1分`）
- **腐烂倍率**：按 **F3+H** 打开原版高级提示框后，时间精确到秒，并多显示两行 ——
  当前的腐烂倍率，以及**代入实际数字的公式**
- **渐进变色**：消耗掉 40% 保质期后开始染色，最后 60% 里逐渐加深（也可改成"只长霉"）
- **到期转化**：保质期耗尽的食物变成腐烂物（默认腐烂剩菜，肉类在数据包里指定成腐烂肉类）
- **环境影响**：温度 + 湿度换算成腐烂倍率，越冷越干越慢，越热越湿越快。
  温度默认取**群系**；**装了 createishot 时改用它的热力学温度**（可选依赖）——
  站在营火边、海拔升高、入夜降温、下雨都会影响腐烂速度。湿度永远取群系
- **容器修正**：容器可以在数据包里加温湿度修正或直接钉死倍率。本模组提供一个**冷箱**
- **堆叠合并**：两份保质期不同的同类食物合并时，按数量取加权平均（饥荒式），不是简单覆盖

## 快速上手：让一个物品会腐烂

1. 建一个数据包（或者直接放进你自己 mod 的 `src/main/resources/data/` 下）：

```
my_pack/
├── pack.mcmeta                     { "pack": { "pack_format": 48 } }
└── data/shelflife/                 ← 推荐就用这个命名空间，理由见 docs/datapack.md §2.6
    ├── food_spoilage/my_foods.json
    └── spoilage_env/my_env.json
```

> **文件放哪 ≠ 规则管哪些物品。** 文件放在 `shelflife` 命名空间下，
> 里面的物品 id 照样写你自己的（`"mymod:cheese"`）。这样做的唯一目的是让
> **覆盖顺序变成单纯按文件名排**，避开"path 相同就比 namespace"那条反直觉规则。

2. `food_spoilage/my_foods.json` —— 让奶酪 8 小时烂完、香肠 2 天烂完：

```json
{
  "items": {
    "mymod:cheese":  { "max_spoilage": 100, "ticks_per_spoilage": 5760 },
    "mymod:sausage": { "max_spoilage": 100, "ticks_per_spoilage": 34560,
                       "result": "mymod:rotten_sausage" }
  }
}
```

> 换算：`max_spoilage × ticks_per_spoilage` 刻 = 常温下的保质期（20 刻 = 1 秒）。
> `100 × 5760 = 576000` 刻 = 8 小时。

3. `spoilage_env/my_env.json` —— 让你自己的冰箱冷到 20 倍保质期：

```json
{ "containers": { "mymod:fridge": { "temperature": -4.0 } } }
```

4. `/reload`，看日志确认：

```
[ShelfLife] 保质期配置生效，覆盖 2 个物品        ← 数字要对得上
[ShelfLife] 容器环境修正生效，覆盖 1 个方块
```

5. 拿一个 `mymod:cheese` 在手里，tooltip 就有"保质期"那一行了。
   想立刻看腐烂的样子 → **拿着它输入 `/shelflife spoilage rot 80`**。

**只做第 2 步就能跑。** 环境那一步是可选的（不写就用内置默认曲线，雪原里天生比沙漠慢）。

## 调试指令

权限等级 2（和 `/give` 同级），**只对主手物品生效**：

```
/shelflife spoilage get              看组件原值：已腐坏 / 上限 / 时间戳
/shelflife spoilage set <点数>        直接设定已腐坏点数
/shelflife spoilage add <点数>        在当前值上累加
/shelflife spoilage rot <百分比>      设成"烂了这么多" 0~100
/shelflife spoilage max <点数>        改保质期上限
/shelflife spoilage clear            摘掉组件，回到"没有保质期"的原始状态
```

典型用法：手上拿一条鱼 → `/shelflife spoilage rot 84` → 常温下显示约 **1 分 35 秒** →
丢进冷箱 → 变成约 **32 分钟**。**腐坏值一点没变，变的是除数（倍率）。**
这是验证"冷箱到底有没有生效"最快的办法，也是验证自定义容器最直接的手段。

## 设计取舍（重要，先读这段再用）

**这个模组不是"每个食物每 tick 都在烂"。** 腐烂是事件驱动的：食物身上存的是
「已腐坏点数 + 上次结算时刻」这个**检查点**，剩下的靠时间差推算。

- 结算只在**事件**时发生：拾取 / 合成 / 熔炼 / 开关容器 / 被从容器抽出，
  加上玩家背包**每秒**一次的被动刷新
- 因此 <strong>没被玩家碰过、也没被抽出来的食物不会腐烂</strong>。自动农场 → 漏斗 → 熔炉 →
  漏斗 → 箱子这条链上的食物，在有人开箱之前一直是新鲜的。**这是刻意的设计，不是 bug** ——
  代价是你不能指望"离线挂机把肉放坏"，换来的是零轮询开销
- `/give`、村民交易、创造模式取出、其它模组自行塞入这些路径不会立刻打戳，但**食物只要进了
  玩家背包，1 秒内就会补上时钟起点**（在那之前它保持"全新"、tooltip 没有保质期行）
- **箱子/机器里**没被观测过的食物仍然不会腐烂：被动刷新只覆盖玩家自己和"正开着的那个容器"，
  不扫世界
- **熔炼会让熟食从全新开始**：快烂的肉煮一遍就洗白了。同样是被接受的设计
- ⚠️ **tooltip 上跳动的剩余时间是实时推算的，不等于"每秒都在写"。** 物品身上存的只是
  检查点，显示层按当前倍率从它推算 —— 所以数字连续在走，而实际写入只发生在上面那些时刻。
  这也是为什么组件里的值和 tooltip 显示的会是两个数（想看真值用 `/shelflife spoilage get`）
- 环境倍率按"物品当前所在位置"采样。**任何会改变倍率的事件都必须在那一刻结算一次** ——
  单检查点模型表示不了"一段间隔里先后有两个倍率"。群系温度只在玩家移动或行动时变，
  上面那些事件天然覆盖；但 **createishot 的温度会自己变**（昼夜、下雨、营火熄灭），
  没有任何玩家事件可挂，所以服务端**每秒**复查一次在线玩家的环境，变了就用**旧倍率**当场结清
- **被动刷新不做重复的时间处理**：只有两种情况会写 ——「还没有时钟起点的」补一次起点
  （对每件食物只命中一次），和「刚好到期的」结算并替换成腐烂物。**没到期的物品一个字节都不写**
- 容器温度偏移的单位是**原版温度刻度**，装不装 createishot 都一样 ——
  摄氏模式下节点温度先反查回原版刻度，偏移加在**反查之后**，所以同一份数据包不必写两遍

## 冷箱

27 格容器，界面就是原版 `9×3` 那一套。

方块本身**不含任何温度数据** —— "有多冷"来自数据包给它写的一行温度偏移
（`"shelflife:cold_box": { "temperature": -4.0 }`），所以改它是改一个 json 数字的事，
不用动代码。默认偏移会撞到倍率下限，得到约 ×0.05，也就是 **20 倍保质期**。

它是**静态**冰箱：冷源就是上面那一行数据包，**不需要任何 Java**。
（`ContainerClimate` 那个接口是给"通电才冷"这类**动态**容器准备的，冷箱用不上。）

它**不会和原版箱子连成双联箱**（没有继承 `ChestBlock`），是个独立的小箱子。

## 给其他模组的 Java API

如果你的模组要主动配合 ShelfLife（而不只是写数据包），`org.slf4j.shelflife.api` 有两个接口。
**完整教程见 [docs/datapack.md §6](docs/datapack.md)**，这里是要点：

- **声明为可选依赖**：`compileOnly` + `neoforge.mods.toml` 里 `type = "optional"`，
  并且调用点要**双重保护** —— 只判 `ModList.get().isLoaded("shelflife")` 是不够的，
  还得把调用**再包一层单独的类**，否则引用 `ShelfLifeApi` 的那个类一加载就 `NoClassDefFoundError`
- `ShelfLifeApi.settleStack(stack, level, pos, block)` —— **搬运物品时该调的就是它**，
  用**来源容器**的位置调。不调的话，"它在来源容器里待的那段时间"会被记到目的地的环境上
  （最典型：从普通箱子抽进冷箱，箱子里的那几小时被当成在冷箱里度过 = 白冻）
- `ShelfLifeApi.settleContainer(container, level, pos, block)` —— **动态容器状态要变时调它**
  （通电→断电、燃料烧完），而且**必须在改状态之前**调，这样结清的是"旧倍率那一段时间"。
  固定温度的容器**完全不需要**这一条，也就**不需要任何 Java**
- `ShelfLifeApi.isManaged / spoilageOf / maxSpoilageOf / rateAt / replacementIfSpoiled`
- `ContainerClimate`：方块实体实现它就能当**动态**冷源/热源，它**覆盖**数据包的静态修正。
  `climate()` 是**纯查询**（模组不会因为它去结算任何物品，也没有缓存），**必须便宜**。
  **只在数据包表达不了时才用它**（动态状态 / 需要 NBT）
- `MenuContainerProvider` / `ContainerLocators`：**告诉 ShelfLife"这个菜单背后是哪个方块"**。
  默认那条路（遍历槽位找方块实体）对用 `SlotItemHandler` 的机器**必然失败**，
  后果是机器里的食物被按**玩家脚下**的倍率记账 —— 自己的菜单实现前者即可，别人的菜单用后者注册

> **别把简单的事情做复杂**：`"containers": { "你的方块": { "temperature": -4.0 } }` 一行数据包
> 就够表达"我的方块是个冰箱"，这是**静态**容器的正道，冷箱自己就是这么实现的。
> 上面那两个接口是给**动态**容器（状态会自己变）和**搬运物品的模组**准备的。

> 本模组自己已经挂了原版漏斗、`InvWrapper` / `SidedInvWrapper` 的抽取、
> 以及 `BaseContainerBlockEntity#removeItem`，所以 Create / Mekanism / Pipez 这类
> **走标准路线**的管道是被自动覆盖的。上面这套 API 是给"自己实现了 `IItemHandler`"的模组准备的。

## 数据包

- 食物规则：`data/<命名空间>/food_spoilage/*.json`
- 环境曲线与容器修正：`data/<命名空间>/spoilage_env/*.json`
- **推荐用 `data/shelflife/`**（和内置同一个命名空间）

内置数据包在 `src/main/resources/data/shelflife/` 下，可以直接当范例抄：

| 文件 | 形态 |
|---|---|
| `food_spoilage/vanilla_foods.json` | 写法 B（整表 + 顶层默认值） |
| `food_spoilage/example_modded.json` | 写法 A（数组），故意留着当兼容性活样本 |
| `spoilage_env/environment.json` | 默认曲线 + 木桶/冷箱的修正 |

**完整字段表、公式推导、覆盖顺序、日志速查、按症状排查 → [docs/datapack.md](docs/datapack.md)。**

几条最容易踩的先放在这：

- **规则写进 `data/shelflife/`，文件名排在 `vanilla_foods.json` / `environment.json` 之后**
  （比如 `zz_mypack.json`）—— 覆盖顺序就是**单纯按文件名排**，最省心
- 用了**别的**命名空间也行，但排序会变成「先比 path、再比 namespace」：
  你的 `data/mypack/food_spoilage/vanilla_foods.json` 和内置的 path 打平 →
  比 namespace → `mypack` 排在前 → **你整份被内置盖掉**
- **`settings` 是整块覆盖、不是逐字段合并**：只写一项的话，其余字段回到**内置默认值**
  （而不是内置数据包那份 `environment.json` 的值），看着就是"改了没生效"；
  `containers` 是按 key 合并的，随便什么文件名都行
- **重复声明同一个物品是按字段继承的**，不是整条替换：后一条只覆盖它写了的字段
  （`result`/`tint`/`overlay` 没写就留着上一条的）。想显式重置就写出来（`"overlay": false`）
- **同一个方块的两种状态要不同冷源**（比如冷冻柜上下半）→ 用 `spoilage_env` 里的
  `container_rules`（`{"blocks": ..., "state": {...}}`，形状就是原版 `BlockPredicate`），
  不用写 Java
- 条件用 `neoforge:conditions`（模组已自己包好，直接写）

## 从源码构建

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 起开发客户端
```

**自测**：真专用服务器上跑"漏斗往冷箱里塞快烂的鱼"，把每步的组件原值打进日志：

```bash
./gradlew runServer -PshelflifeSelfTest=true
```

（工作目录在 `run-server/`，和客户端的 `run/` 分开；跑完自动关服。
见 `src/main/java/org/slf4j/shelflife/debug/SpoilageSelfTest.java`。
纯逻辑单测测不出"真实的调用顺序"这类 bug，所以留着它。）

**文档示例校验**：`tools/docs_check/` 是一份小数据包，内容是本文档里那几个例子**原样照抄**。
拷进 `run-server/world/datapacks/` 再跑一次自测，看日志里的规则条数、覆盖警告、
`容器环境修正生效，覆盖 20 个方块` 对不对得上 —— 文档里的例子改了就顺手跑一次，
免得示例悄悄烂掉。

贴图由作者手工绘制，**不要**跑生成脚本覆盖（`tools/generate_textures.py` 现在默认跳过已存在的文件）。

## 已知缺口

- **冷箱还没有合成配方**（`data/shelflife/recipe/` 是空的），目前只能从创造模式标签页获取。
  掉落表是有的（打掉会掉自己）
- **通电冰箱**：接口（`ContainerClimate` + `ShelfLifeApi.settleContainer`）已经齐了，
  缺的是一个**示例方块** —— 本模组不带耗电冰箱。要自己做的话照着
  [docs/datapack.md §6.3](docs/datapack.md) 抄即可。（注意：**冷箱是静态冰箱，
  和这件事无关**，它只需要那一行数据包）
- 管道往容器里**塞**物品时没有走加权平均（抽取侧已经覆盖）。
  症状是"管道能洗掉腐坏值"：不停往一堆快烂的里面塞新鲜的，那堆永远不变烂
