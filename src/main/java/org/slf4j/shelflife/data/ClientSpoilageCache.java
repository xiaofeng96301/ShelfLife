package org.slf4j.shelflife.data;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.shelflife.client.SpoilageTintHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的保质期配置缓存，由服务端在登录 / {@code /reload} 后同步过来。
 *
 * <p>多人游戏下客户端的本地数据包和服务端不一定一致，所以这份缓存只能来自网络，不能自己读数据包。
 *
 * <p>用到的都是通用类（{@code BuiltInRegistries} 等），在专用服务端上也可以安全加载 ——
 * 服务端收不到 toClient 包，缓存保持为空，tooltip 逻辑自然短路。
 */
public final class ClientSpoilageCache {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Map<Item, SpoilageConfig> byItem = Map.of();

    private ClientSpoilageCache() {
    }

    /** 收到服务端同步包时调用。 */
    public static void accept(Map<ResourceLocation, SpoilageConfig> raw) {
        Map<Item, SpoilageConfig> resolved = new HashMap<>();
        raw.forEach((id, config) -> {
            if (BuiltInRegistries.ITEM.containsKey(id)) {
                resolved.put(BuiltInRegistries.ITEM.get(id), config);
            } else {
                // 客户端缺这个物品，说明服务端装了客户端没有的模组，跳过即可
                LOGGER.debug("[ShelfLife] 客户端没有物品 {}，忽略其保质期配置", id);
            }
        });
        byItem = Map.copyOf(resolved);
        LOGGER.debug("[ShelfLife] 已接收 {} 条保质期配置", byItem.size());

        // 让渲染层给这些物品染色。必须在客户端做 —— 专用服务端没有渲染层，
        // SpoilageTintHandler 那个类在那边的类路径上不该被加载（分支不进就永远不会解析它）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            SpoilageTintHandler.refresh(byItem.keySet());
        }
    }

    /** 断开连接时清空，避免残留上一个服务器的配置。 */
    public static void clear() {
        byItem = Map.of();
    }

    @Nullable
    public static SpoilageConfig get(Item item) {
        return byItem.get(item);
    }

    public static boolean isEmpty() {
        return byItem.isEmpty();
    }
}
