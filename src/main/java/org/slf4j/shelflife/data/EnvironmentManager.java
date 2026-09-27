package org.slf4j.shelflife.data;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.shelflife.logic.CreateishotCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境参数表：腐烂速率曲线 + 各容器方块的温湿度修正。
 *
 * <p>和 {@link SpoilageManager} 一样分两段解析（reload 时只存原始引用，标签绑定后再展开），
 * 理由也一样 —— reload 监听器跑的时候方块标签可能还没绑定。
 *
 * <p><b>注意：环境倍率是常开的</b>，没有数据包时用 {@link EnvironmentSettings#DEFAULT} 的内置曲线。
 * 数据包只负责<b>调曲线</b>和<b>加容器修正</b>，不是开关。想彻底关掉就把
 * {@code doubling_per} 调得极大、{@code min_multiplier} 和 {@code max_multiplier} 都设成 1。
 */
public final class EnvironmentManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile EnvironmentSettings settings = EnvironmentSettings.DEFAULT;
    /** 解析前的原始键：方块 id 或 {@code #标签}。 */
    private static volatile List<Map.Entry<String, ContainerModifier>> rawContainers = List.of();
    private static volatile Map<Block, ContainerModifier> byBlock = Map.of();

    private EnvironmentManager() {
    }

    /** 第一段：数据包 reload，纯 JSON。 */
    public static void setRaw(@Nullable EnvironmentSettings newSettings, Map<String, ContainerModifier> containers) {
        if (newSettings != null) {
            // 在这里把 auto 落到具体来源上，之后（包括同步给客户端的）永远是解析过的值。
            // 放在这里而不是查询时，是因为客户端也会跑这套代码 —— 让它按自己的模组列表解析，
            // 没装 createishot 的客户端就会显示和服务端不一样的倍率。
            settings = newSettings.resolved(CreateishotCompat.isLoaded());
        }
        List<Map.Entry<String, ContainerModifier>> sorted = new ArrayList<>(containers.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        rawContainers = List.copyOf(sorted);
        byBlock = Map.of();
    }

    /**
     * 客户端专用：接收服务端同步过来的曲线。
     *
     * <p>专用服务器上客户端不加载数据包，曲线只能这么来。单机下这个值和 reload 写进去的完全一致，
     * 重复设置是无害的 —— 所以不需要区分"这次是不是专用服务器"。
     *
     * <p>容器修正表<b>不</b>同步：客户端的冷源信息由 {@code ContainerRatePayload} 直接送采样结果，
     * 见该类的注释。
     */
    public static void applySynced(EnvironmentSettings synced) {
        settings = synced;
    }

    /** 第二段：方块标签已绑定，展开标签引用。 */
    public static void resolve() {
        Map<Block, ContainerModifier> resolved = new HashMap<>();
        for (Map.Entry<String, ContainerModifier> entry : rawContainers) {
            String ref = entry.getKey();
            if (ref.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(ref.substring(1));
                if (tagId == null) {
                    LOGGER.error("[ShelfLife] 容器修正的标签引用 '{}' 不是合法 ResourceLocation，已忽略", ref);
                    continue;
                }
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
                BuiltInRegistries.BLOCK.getTag(tag).ifPresentOrElse(
                        holders -> holders.forEach(holder -> resolved.put(holder.value(), entry.getValue())),
                        () -> LOGGER.warn("[ShelfLife] 容器修正引用了不存在的方块标签 {}，已忽略", ref));
            } else {
                ResourceLocation blockId = ResourceLocation.tryParse(ref);
                if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
                    LOGGER.warn("[ShelfLife] 容器修正引用了不存在的方块 '{}'，已忽略", ref);
                    continue;
                }
                resolved.put(BuiltInRegistries.BLOCK.get(blockId), entry.getValue());
            }
        }
        byBlock = Map.copyOf(resolved);
        if (!byBlock.isEmpty()) {
            LOGGER.info("[ShelfLife] 容器环境修正生效，覆盖 {} 个方块", byBlock.size());
        }
    }

    public static EnvironmentSettings settings() {
        return settings;
    }

    @Nullable
    public static ContainerModifier modifierFor(Block block) {
        return byBlock.get(block);
    }
}
