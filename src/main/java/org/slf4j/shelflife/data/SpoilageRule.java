package org.slf4j.shelflife.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一个数据包文件的解析结果，统一归一化成"每个物品引用一条、数值齐全"的列表。
 *
 * <p>{@code items} 支持两种写法：
 *
 * <pre>
 * // 形态一：一组物品共用一组数值
 * { "items": ["minecraft:apple", "#minecraft:fishes"],
 *   "max_spoilage": 100, "ticks_per_spoilage": 840, "result": "shelflife:rotten_leftovers" }
 *
 * // 形态二：整张表放一个文件，顶层写默认值，每个物品只覆盖要改的字段
 * { "max_spoilage": 100, "result": "shelflife:rotten_leftovers",
 *   "items": { "minecraft:beef": { "ticks_per_spoilage": 240, "result": "shelflife:rotten_meat" } } }
 * </pre>
 *
 * <p>形态二的顶层 {@code ticks_per_spoilage} 可以不写 —— 那就要求每个物品自己写全，
 * 否则解析报错。这是故意的：与其悄悄用上一个错的值，不如在加载时就炸出来。
 *
 * <p>解析时**不查注册表、不展开标签**，只做 JSON → 对象的转换；真正的解析推迟到
 * {@code TagsUpdatedEvent}，见 {@link SpoilageManager#resolve()}。
 */
public record SpoilageRule(List<Entry> entries) {

    /**
     * 一个物品引用（具体 id 或 {@code #标签}）加它自己完整的数值。
     *
     * <p>{@code result} / {@code tint} / {@code overlay} 是 {@link Optional}，
     * 因为<strong>要区分"没写"和"写了默认值"</strong>：没写表示"继承之前那条，
     * 或者用默认值"，写了就盖掉。见 {@link #mergeOver}。
     *
     * <p>两个数值不这样处理：它们在两种写法下都是"必须给出"的（形态二可以用顶层的默认值，
     * 但那也是这个文件明确写了的），所以永远是普通 int。
     */
    public record Entry(String itemRef, int maxSpoilage, int ticksPerSpoilage,
                        Optional<ResourceLocation> result, Optional<Integer> tint, Optional<Boolean> overlay) {

        /** 补齐默认值，得到一个完整配置。这个物品第一次被声明时用这个。 */
        public SpoilageConfig toConfig() {
            return new SpoilageConfig(maxSpoilage, ticksPerSpoilage,
                    result.orElse(SpoilageConfig.DEFAULT_RESULT),
                    tint.orElse(SpoilageConfig.DEFAULT_TINT),
                    overlay.orElse(false));
        }

        /**
         * 覆盖到已有配置上：<b>只覆盖这条声明真正写了的字段</b>，没写的继承 {@code previous}。
         *
         * <p>这条规则是为一个很容易踩的坑准备的：曲奇的模型带霉斑叠加层（{@code overlay: true}
         * 加一个自定义 {@code tint}），另一份数据包只想改它的保质期，于是写了一条
         * {@code "minecraft:cookie": { "ticks_per_spoilage": 3600 }} ——
         * 在"整条替换"的旧语义下，这条会把 {@code overlay} 和 {@code tint} 一起冲回默认值，
         * 表现是**叠加层还在、但它的透明度和颜色被换掉了**，看起来像渲染坏了，其实是数据被覆盖了。
         * 改成按字段继承之后，没写的字段原样留着。
         *
         * <p>要**显式重置**某个字段，就把它写出来（{@code "overlay": false}）——
         * 省略 = 继承，写明 = 覆盖。
         */
        public SpoilageConfig mergeOver(SpoilageConfig previous) {
            return new SpoilageConfig(maxSpoilage, ticksPerSpoilage,
                    result.orElse(previous.result()),
                    tint.orElse(previous.tint()),
                    overlay.orElse(previous.overlay()));
        }
    }

    // ------------------------------------------------------------------ 形态一

    public record ItemList(List<String> items, int maxSpoilage, int ticksPerSpoilage,
                           Optional<ResourceLocation> result, Optional<Integer> tint, Optional<Boolean> overlay) {
        static final Codec<ItemList> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().fieldOf("items").forGetter(ItemList::items),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("max_spoilage").forGetter(ItemList::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("ticks_per_spoilage").forGetter(ItemList::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result").forGetter(ItemList::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint").forGetter(ItemList::tint),
                Codec.BOOL.optionalFieldOf("overlay").forGetter(ItemList::overlay)
        ).apply(instance, ItemList::new));
    }

    // ------------------------------------------------------------------ 形态二

    public record ItemMap(Optional<Integer> maxSpoilage, Optional<Integer> ticksPerSpoilage,
                          Optional<ResourceLocation> result, Optional<Integer> tint, Optional<Boolean> overlay,
                          Map<String, Patch> items) {
        static final Codec<ItemMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_spoilage").forGetter(ItemMap::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("ticks_per_spoilage").forGetter(ItemMap::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result").forGetter(ItemMap::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint").forGetter(ItemMap::tint),
                Codec.BOOL.optionalFieldOf("overlay").forGetter(ItemMap::overlay),
                Codec.unboundedMap(Codec.STRING, Patch.CODEC).fieldOf("items").forGetter(ItemMap::items)
        ).apply(instance, ItemMap::new));
    }

    /** 单个物品对顶层默认值的覆盖，五个字段都可以不写。 */
    public record Patch(Optional<Integer> maxSpoilage, Optional<Integer> ticksPerSpoilage,
                        Optional<ResourceLocation> result, Optional<Integer> tint, Optional<Boolean> overlay) {
        static final Codec<Patch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_spoilage").forGetter(Patch::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("ticks_per_spoilage").forGetter(Patch::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result").forGetter(Patch::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint").forGetter(Patch::tint),
                Codec.BOOL.optionalFieldOf("overlay").forGetter(Patch::overlay)
        ).apply(instance, Patch::new));
    }

    // ------------------------------------------------------------------ 编解码

    /**
     * 解码时先按形态一试，失败了再按形态二试 —— JSON 里 {@code items} 是数组还是对象，
     * 两条路自然分得开，不需要我自己判断。
     */
    public static final Codec<SpoilageRule> CODEC = Codec.either(ItemList.CODEC, ItemMap.CODEC).flatXmap(
            SpoilageRule::normalize,
            // 只解析不编码，但给一个真实可用的编码方向，免得留个会抛异常的坑
            rule -> DataResult.<Either<ItemList, ItemMap>>success(Either.right(rule.toItemMap())));

    private static DataResult<SpoilageRule> normalize(Either<ItemList, ItemMap> either) {
        if (either.left().isPresent()) {
            ItemList list = either.left().get();
            return DataResult.success(new SpoilageRule(list.items().stream()
                    .map(ref -> new Entry(ref, list.maxSpoilage(), list.ticksPerSpoilage(),
                            list.result(), list.tint(), list.overlay()))
                    .toList()));
        }
        return fromMap(either.right().orElseThrow());
    }

    private static DataResult<SpoilageRule> fromMap(ItemMap map) {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Patch> item : map.items().entrySet()) {
            Patch patch = item.getValue();
            Optional<Integer> max = patch.maxSpoilage().or(map::maxSpoilage);
            Optional<Integer> ticks = patch.ticksPerSpoilage().or(map::ticksPerSpoilage);
            if (max.isEmpty() || ticks.isEmpty()) {
                return DataResult.error(() -> "物品 " + item.getKey()
                        + " 既没有自己的 max_spoilage / ticks_per_spoilage，顶层也没给默认值");
            }
            // 这里**不再补默认值**，只把"顶层写了、物品没写"归到顶层。
            // 两边都没写就保持 empty —— 那表示"继承别的文件 / 用默认值"，由 SpoilageManager 决定
            Optional<ResourceLocation> result = patch.result().or(map::result);
            Optional<Integer> tint = patch.tint().or(map::tint);
            Optional<Boolean> overlay = patch.overlay().or(map::overlay);
            entries.add(new Entry(item.getKey(), max.get(), ticks.get(), result, tint, overlay));
        }
        return DataResult.success(new SpoilageRule(List.copyOf(entries)));
    }

    private ItemMap toItemMap() {
        Map<String, Patch> items = new LinkedHashMap<>();
        for (Entry entry : entries) {
            items.put(entry.itemRef(), new Patch(
                    Optional.of(entry.maxSpoilage()),
                    Optional.of(entry.ticksPerSpoilage()),
                    entry.result(),
                    entry.tint(),
                    entry.overlay()));
        }
        return new ItemMap(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), items);
    }
}
