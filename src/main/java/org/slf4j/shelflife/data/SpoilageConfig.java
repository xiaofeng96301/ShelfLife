package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.shelflife.Shelflife;

/**
 * 某个物品的保质期参数，同时用于 JSON 解析和客户端网络同步。
 *
 * <p>两个数值都要求 ≥ 1：{@code ticksPerSpoilage} 为 0 会导致结算时除零。
 *
 * @param result 保质期耗尽后转化成的物品。JSON 里可以不写，默认 {@link #DEFAULT_RESULT}；
 *               想显式关闭转化就写 {@code "minecraft:air"}
 */
public record SpoilageConfig(int maxSpoilage, int ticksPerSpoilage, ResourceLocation result) {

    /**
     * {@code result} 缺省时的产物。
     *
     * <p>"写了保质期但没写产物"是最常见的情形，默认给剩菜，省得每个规则都要重复写一遍
     * {@code "result": "shelflife:rotten_leftovers"}。
     */
    public static final ResourceLocation DEFAULT_RESULT =
            ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "rotten_leftovers");

    public static final StreamCodec<RegistryFriendlyByteBuf, SpoilageConfig> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpoilageConfig::maxSpoilage,
            ByteBufCodecs.VAR_INT, SpoilageConfig::ticksPerSpoilage,
            ResourceLocation.STREAM_CODEC, SpoilageConfig::result,
            SpoilageConfig::new
    );
}
