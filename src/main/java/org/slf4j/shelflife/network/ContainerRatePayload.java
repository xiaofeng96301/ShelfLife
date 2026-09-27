package org.slf4j.shelflife.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.client.ClientEnvironmentCache;
import org.slf4j.shelflife.logic.EnvironmentSample;

/**
 * 服务端 → 客户端：当前打开的<b>这个容器</b>的环境采样。
 *
 * <p><b>为什么非要有这个包：</b>客户端算倍率只能按玩家位置采样，它不知道打开的箱子在世界哪个位置
 * （客户端侧 {@code ChestMenu} 装的是 {@code SimpleContainer}，反查不到方块实体），
 * 更不知道数据包给那个方块写了什么修正，也读不到 createishot 的温度。
 * 玩家背包里时"按玩家位置采样"这个近似是准的，但冷箱这种把倍率压到 0.05 的容器上就差二十倍。
 *
 * <p>包里放的是<b>服务端算好的采样本身</b>，而不是容器坐标：客户端拿坐标也复现不出来。
 *
 * <p><b>{@code containerId} 是配对用的</b>：客户端只在"当前打开的菜单 id == 这个 id"时才采信。
 * 这样不需要监听任何"关箱"事件 —— 换个界面、箱子关掉，id 自然就对不上，脏数据自动失效。
 */
public record ContainerRatePayload(EnvironmentSample sample, int containerId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ContainerRatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "container_rate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerRatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    EnvironmentSample.STREAM_CODEC, ContainerRatePayload::sample,
                    ByteBufCodecs.VAR_INT, ContainerRatePayload::containerId,
                    ContainerRatePayload::new);

    public static ContainerRatePayload of(EnvironmentSample sample, int containerId) {
        return new ContainerRatePayload(sample, containerId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 开箱时发一次；<b>之后容器倍率变了还要补发</b> —— 营火熄灭、入夜、下雨都会在箱子开着的时候
     * 改变它所在地的温度，所以"容器不会移动、倍率不会变"这个假设是不成立的。
     */
    public static void handle(ContainerRatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEnvironmentCache.acceptContainerSample(payload.sample(), payload.containerId()));
    }
}
