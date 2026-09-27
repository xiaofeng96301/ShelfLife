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
 * 服务端 → 客户端：玩家打开的<b>这个容器</b>的环境采样结果。
 *
 * <p><b>为什么非要有这个包：</b>客户端算倍率只能按玩家位置采样，它不知道打开的箱子在世界哪个位置
 * （客户端侧 {@code ChestMenu} 装的是 {@code SimpleContainer}，反查不到方块实体），
 * 更不知道数据包给那个方块写了什么修正。玩家背包里时这个近似是准的，
 * 但冷箱这种把倍率压到 0.05 的容器上就差了二十倍。
 *
 * <p>包里放的是<b>服务端算好的采样结果本身</b>，而不是容器坐标：客户端拿坐标也复现不出来
 * （它没有容器修正表），而直接发结果既不需要这些，也永远和服务端结算用的值一致。
 *
 * <p>样本里的温湿度也一起发，是为了让高级提示框能显示完整的公式和数字。
 *
 * <p><b>{@code containerId} 是配对用的</b>：客户端只在"当前打开的菜单 id == 这个 id"时才采信。
 * 这样不需要监听任何"关箱"事件 —— 换个界面、箱子关掉，id 自然就对不上，脏数据自动失效。
 */
public record ContainerRatePayload(float rate, float temperature, float humidity, float rateOverride, int containerId)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ContainerRatePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "container_rate"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ContainerRatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, ContainerRatePayload::rate,
                    ByteBufCodecs.FLOAT, ContainerRatePayload::temperature,
                    ByteBufCodecs.FLOAT, ContainerRatePayload::humidity,
                    ByteBufCodecs.FLOAT, ContainerRatePayload::rateOverride,
                    ByteBufCodecs.VAR_INT, ContainerRatePayload::containerId,
                    ContainerRatePayload::new);

    public static ContainerRatePayload of(EnvironmentSample sample, int containerId) {
        return new ContainerRatePayload(sample.rate(), sample.temperature(), sample.humidity(),
                sample.rateOverride(), containerId);
    }

    public static EnvironmentSample sampleOf(ContainerRatePayload payload) {
        return new EnvironmentSample(payload.temperature(), payload.humidity(),
                payload.rate(), payload.rateOverride());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 开箱时发一次。开着的容器不会移动，所以倍率唯一的变数是 {@code /reload} 换了数据包 —— 重开一次即可。 */
    public static void handle(ContainerRatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEnvironmentCache.acceptContainerSample(sampleOf(payload), payload.containerId()));
    }
}
