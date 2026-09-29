package org.slf4j.shelflife.data;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端权威的保质期配置表。
 *
 * <p>生命周期分两段，是为了绕开"reload 监听器跑的时候物品标签还没绑定"这个坑：
 * <ol>
 *   <li>{@link #setRules} —— 数据包 reload 时调用，只存原始 JSON，不碰注册表</li>
 *   <li>{@link #resolve} —— {@code TagsUpdatedEvent} 时调用，这时标签一定已绑定，再展开标签</li>
 * </ol>
 * 如果直接在 reload 监听器里展开标签，{@code /reload} 会偶发丢规则。
 *
 * <p>本类只在服务端使用；客户端拿的是网络同步过去的 {@link ClientSpoilageCache}。
 */
public final class SpoilageManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 按文件 id 排序，保证冲突覆盖的结果可复现（同一物品被多个文件声明时，id 靠后的生效）。 */
    private static volatile List<Map.Entry<ResourceLocation, SpoilageRule>> rules = List.of();
    private static volatile Map<Item, SpoilageConfig> byItem = Map.of();

    private SpoilageManager() {
    }

    /** 第一段：数据包 reload，纯 JSON，不做任何注册表查询。 */
    public static void setRules(Map<ResourceLocation, SpoilageRule> parsed) {
        List<Map.Entry<ResourceLocation, SpoilageRule>> sorted = new ArrayList<>(parsed.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        rules = List.copyOf(sorted);
        // 先清空，避免标签绑定失败时旧配置继续生效造成"看起来还行"的错觉
        byItem = Map.of();
        LOGGER.info("[ShelfLife] 读到 {} 个保质期规则文件，等标签绑定后展开", sorted.size());
    }

    /** 第二段：标签已绑定，展开成物品表。 */
    public static void resolve() {
        Map<Item, SpoilageConfig> resolved = new HashMap<>();
        Set<ResourceLocation> checkedResults = new HashSet<>();

        for (Map.Entry<ResourceLocation, SpoilageRule> fileEntry : rules) {
            ResourceLocation fileId = fileEntry.getKey();
            for (SpoilageRule.Entry entry : fileEntry.getValue().entries()) {
                String ref = entry.itemRef();
                if (ref.startsWith("#")) {
                    resolveTag(fileId, ref.substring(1), entry, resolved);
                } else {
                    resolveItem(fileId, ref, entry, resolved);
                }

                // 提前校验产物 id：写错了要在这里就报出来，
                // 而不是等玩家把食物放到保质期耗尽才发现什么也没发生。
                // 只校验**这条声明自己写了的** —— 没写就是继承别的文件或用默认值，
                // 那个 id 不归这条声明负责（默认值本身是存在的）。
                // 每个 id 只报一次，免得一张表里几十条各刷一遍警告。
                // （"minecraft:air" 是合法的"不转化"写法，它存在，所以不会被报）
                entry.result().ifPresent(result -> {
                    if (checkedResults.add(result) && !BuiltInRegistries.ITEM.containsKey(result)) {
                        LOGGER.warn("[ShelfLife] {} 的 result 指向不存在的物品 {}，保质期耗尽后不会转化", fileId, result);
                    }
                });
            }
        }

        byItem = Map.copyOf(resolved);
        LOGGER.info("[ShelfLife] 保质期配置生效，覆盖 {} 个物品", byItem.size());
    }

    /** 配置表为空时整个模组对游戏零改动 —— 所有逻辑都以此为闸门。 */
    public static boolean isEmpty() {
        return byItem.isEmpty();
    }

    @Nullable
    public static SpoilageConfig get(Item item) {
        return byItem.get(item);
    }

    /** 打包成"物品注册名 -> 参数"，用于发给客户端。 */
    public static Map<ResourceLocation, SpoilageConfig> snapshot() {
        Map<ResourceLocation, SpoilageConfig> out = new HashMap<>();
        byItem.forEach((item, config) -> out.put(BuiltInRegistries.ITEM.getKey(item), config));
        return out;
    }

    private static void resolveTag(ResourceLocation fileId, String tagPath, SpoilageRule.Entry entry, Map<Item, SpoilageConfig> out) {
        ResourceLocation tagId = ResourceLocation.tryParse(tagPath);
        if (tagId == null) {
            LOGGER.error("[ShelfLife] {} 里的标签引用 '#{}' 不是合法 ResourceLocation，已忽略", fileId, tagPath);
            return;
        }
        TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
        BuiltInRegistries.ITEM.getTag(tag).ifPresentOrElse(
                holders -> holders.forEach(holder -> put(out, holder.value(), entry, fileId, "#" + tagPath)),
                () -> LOGGER.warn("[ShelfLife] {} 引用了不存在的物品标签 #{}，已忽略", fileId, tagPath));
    }

    private static void resolveItem(ResourceLocation fileId, String itemPath, SpoilageRule.Entry entry, Map<Item, SpoilageConfig> out) {
        ResourceLocation itemId = ResourceLocation.tryParse(itemPath);
        if (itemId == null) {
            LOGGER.error("[ShelfLife] {} 里的物品引用 '{}' 不是合法 ResourceLocation，已忽略", fileId, itemPath);
            return;
        }
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
            LOGGER.warn("[ShelfLife] {} 引用了不存在的物品 {}，已忽略", fileId, itemId);
            return;
        }
        put(out, BuiltInRegistries.ITEM.get(itemId), entry, fileId, itemPath);
    }

    /**
     * 把一条声明落到某个具体物品上。
     *
     * <p><b>同一个物品被多处声明时按字段合并</b>：这条声明真正写了的字段才覆盖，没写的留着上一条的。
     * 以前是整条替换，于是"只想改一下曲奇的保质期"会把它的 {@code overlay}/{@code tint}
     * 一起冲回默认值 —— 表现是霉斑叠加层还在、但颜色和透明度不对，看着像渲染坏了。
     * 详见 {@link SpoilageRule.Entry#mergeOver}。
     *
     * <p>想显式重置某个字段就把它写出来（{@code "overlay": false}）；省略 = 继承。
     */
    private static void put(Map<Item, SpoilageConfig> out, Item item, SpoilageRule.Entry entry,
                            ResourceLocation fileId, String ref) {
        SpoilageConfig previous = out.get(item);
        SpoilageConfig merged = previous == null ? entry.toConfig() : entry.mergeOver(previous);
        if (previous != null && !previous.equals(merged)) {
            LOGGER.warn("[ShelfLife] 物品 {} 的保质期参数被 {} 覆盖（{} -> {}）",
                    BuiltInRegistries.ITEM.getKey(item), fileId, previous, merged);
        }
        out.put(item, merged);
    }
}
