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

    /** 一个物品引用（具体 id 或 {@code #标签}）加它自己完整的数值。 */
    public record Entry(String itemRef, int maxSpoilage, int ticksPerSpoilage, ResourceLocation result, int tint) {
    }

    // ------------------------------------------------------------------ 形态一

    public record ItemList(List<String> items, int maxSpoilage, int ticksPerSpoilage,
                           ResourceLocation result, int tint) {
        static final Codec<ItemList> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().fieldOf("items").forGetter(ItemList::items),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("max_spoilage").forGetter(ItemList::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("ticks_per_spoilage").forGetter(ItemList::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result", SpoilageConfig.DEFAULT_RESULT).forGetter(ItemList::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint", SpoilageConfig.DEFAULT_TINT).forGetter(ItemList::tint)
        ).apply(instance, ItemList::new));
    }

    // ------------------------------------------------------------------ 形态二

    public record ItemMap(Optional<Integer> maxSpoilage, Optional<Integer> ticksPerSpoilage,
                          Optional<ResourceLocation> result, Optional<Integer> tint,
                          Map<String, Patch> items) {
        static final Codec<ItemMap> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_spoilage").forGetter(ItemMap::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("ticks_per_spoilage").forGetter(ItemMap::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result").forGetter(ItemMap::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint").forGetter(ItemMap::tint),
                Codec.unboundedMap(Codec.STRING, Patch.CODEC).fieldOf("items").forGetter(ItemMap::items)
        ).apply(instance, ItemMap::new));
    }

    /** 单个物品对顶层默认值的覆盖，四个字段都可以不写。 */
    public record Patch(Optional<Integer> maxSpoilage, Optional<Integer> ticksPerSpoilage,
                        Optional<ResourceLocation> result, Optional<Integer> tint) {
        static final Codec<Patch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("max_spoilage").forGetter(Patch::maxSpoilage),
                Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("ticks_per_spoilage").forGetter(Patch::ticksPerSpoilage),
                ResourceLocation.CODEC.optionalFieldOf("result").forGetter(Patch::result),
                SpoilageConfig.TINT_CODEC.optionalFieldOf("tint").forGetter(Patch::tint)
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
                    .map(ref -> new Entry(ref, list.maxSpoilage(), list.ticksPerSpoilage(), list.result(), list.tint()))
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
            ResourceLocation result = patch.result().or(map::result).orElse(SpoilageConfig.DEFAULT_RESULT);
            int tint = patch.tint().or(map::tint).orElse(SpoilageConfig.DEFAULT_TINT);
            entries.add(new Entry(item.getKey(), max.get(), ticks.get(), result, tint));
        }
        return DataResult.success(new SpoilageRule(List.copyOf(entries)));
    }

    private ItemMap toItemMap() {
        Map<String, Patch> items = new LinkedHashMap<>();
        for (Entry entry : entries) {
            items.put(entry.itemRef(), new Patch(
                    Optional.of(entry.maxSpoilage()),
                    Optional.of(entry.ticksPerSpoilage()),
                    Optional.of(entry.result()),
                    Optional.of(entry.tint())));
        }
        return new ItemMap(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), items);
    }
}
