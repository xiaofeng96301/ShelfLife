package org.slf4j.shelflife.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.client.ClientEnvironmentCache;
import org.slf4j.shelflife.logic.EnvironmentSample;

/**
 * 服务端 → 客户端：玩家<b>自身所在位置</b>的环境采样。
 *
 * <p><b>为什么需要它：</b>温度来源是 createishot 时，客户端<b>读不到</b>那个温度 ——
 * 它是服务端权威的，客户端只收两个字节（HUD 档位 + 温度计柱高）。
 * 而 tooltip 和染色都要按玩家脚下的倍率来显示，所以只能由服务端算好送过来。
 *
 * <p>温度来源是群系时这个包不发：客户端自己按群系算就是准的，没有任何理由多发。
 *
 * <p>发送时机与环境结算绑在一起（见 {@code SpoilageEvents#onServerTick}）：
 * 每 5 tick 采一次，倍率变了才发 —— 所以常态下网络开销是零。
 */
public record PlayerEnvironmentPayload(EnvironmentSample sample) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerEnvironmentPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "player_environment"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerEnvironmentPayload> STREAM_CODEC =
            StreamCodec.composite(EnvironmentSample.STREAM_CODEC, PlayerEnvironmentPayload::sample,
                    PlayerEnvironmentPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerEnvironmentPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEnvironmentCache.acceptPlayerSample(payload.sample()));
    }
}
