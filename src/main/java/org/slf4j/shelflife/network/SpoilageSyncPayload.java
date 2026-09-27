package org.slf4j.shelflife.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.data.ClientSpoilageCache;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentSettings;
import org.slf4j.shelflife.data.SpoilageConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端 → 客户端：整张保质期配置表 + 环境曲线。
 *
 * <p>登录和 {@code /reload} 后各发一次。配置表为空时也要发 —— 否则玩家 {@code /reload} 把数据包删掉后，
 * 客户端会一直留着上一份配置，tooltip 继续显示过期数据。
 *
 * <p>用物品注册名而不是数字 id 做键：不依赖注册表同步顺序，且出问题时日志可读。
 *
 * <p><b>为什么要连环境曲线一起发：</b>专用服务器上客户端<b>没有</b>服务端的数据包，
 * 自己算倍率只能用内置默认曲线 —— 提示框显示的倍率和剩余时间会和服务端实际结算的对不上。
 * 单机不受影响（集成的服务端和客户端是同一个进程，静态字段本来就是同一份）。
 *
 * <p>容器修正（哪个方块冷）<b>不发</b>：客户端不需要按方块反查冷源 —— 它不知道容器在世界的位置，
 * 这件事由 {@link ContainerRatePayload} 直接把算好的采样结果送过去解决。
 */
public record SpoilageSyncPayload(Map<ResourceLocation, SpoilageConfig> configs, EnvironmentSettings environment)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SpoilageSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "spoilage_sync"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Map<ResourceLocation, SpoilageConfig>> MAP_CODEC =
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, SpoilageConfig.STREAM_CODEC);

    public static final StreamCodec<RegistryFriendlyByteBuf, SpoilageSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    MAP_CODEC, SpoilageSyncPayload::configs,
                    EnvironmentSettings.STREAM_CODEC, SpoilageSyncPayload::environment,
                    SpoilageSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SpoilageSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientSpoilageCache.accept(payload.configs());
            EnvironmentManager.applySynced(payload.environment());
        });
    }
}
