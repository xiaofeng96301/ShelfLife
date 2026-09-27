package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
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
 * @param tint   保质期<b>耗尽时</b>的染色（{@code #RRGGBB}）。显示层从"新鲜"到它线性插值
 *               （见 {@code SpoilageTintHandler}）。不写就用 {@link #DEFAULT_TINT}
 * @param overlay {@code true} 表示这个物品的模型有**第二层**（霉斑叠加层），
 *                走"底层不染 + 叠加层按 alpha 淡入"的路子，而不是整块乘性染色。
 *                需要物品模型里写上 {@code layer1} 配合，见 {@code SpoilageTintHandler} 的注释
 *
 * <p><b>整块染色是乘性的</b>，只能压暗/偏色、不能变亮 —— 所以"让发霉的曲奇保持亮褐色、
 * 只长出绿霉"是做不到的，那需要 {@code overlay}。
 */
public record SpoilageConfig(int maxSpoilage, int ticksPerSpoilage, ResourceLocation result, int tint,
                             boolean overlay) {

    /**
     * {@code result} 缺省时的产物。
     *
     * <p>"写了保质期但没写产物"是最常见的情形，默认给剩菜，省得每个规则都要重复写一遍
     * {@code "result": "shelflife:rotten_leftovers"}。
     */
    public static final ResourceLocation DEFAULT_RESULT =
            ResourceLocation.fromNamespaceAndPath(Shelflife.MODID, "rotten_leftovers");

    /** 缺省的"烂透了"的颜色：偏绿的中性色，乘上去把食物压暗发绿。 */
    public static final int DEFAULT_TINT = 0xFF7E8C4A;

    /**
     * {@code #RRGGBB} ↔ int。alpha 固定为 FF。
     *
     * <p><b>不接受 8 位</b>：原版渲染会把染色的 alpha 直接写进顶点，写成 0 就是"整个物品隐形"。
     * 只收 6 位就没有这个歧义，也不需要使用者去记这件事。
     */
    public static final Codec<Integer> TINT_CODEC = Codec.STRING.comapFlatMap(
            SpoilageConfig::parseTint, value -> String.format("#%06X", value & 0xFFFFFF));

    private static DataResult<Integer> parseTint(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) {
            return DataResult.error(() -> "tint 要写成 #RRGGBB（六位十六进制，alpha 固定 FF）：" + value);
        }
        try {
            return DataResult.success(0xFF000000 | Integer.parseInt(hex, 16));
        } catch (NumberFormatException e) {
            return DataResult.error(() -> "tint 不是合法的十六进制颜色：" + value);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SpoilageConfig> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpoilageConfig::maxSpoilage,
            ByteBufCodecs.VAR_INT, SpoilageConfig::ticksPerSpoilage,
            ResourceLocation.STREAM_CODEC, SpoilageConfig::result,
            ByteBufCodecs.INT, SpoilageConfig::tint,
            ByteBufCodecs.BOOL, SpoilageConfig::overlay,
            SpoilageConfig::new
    );
}
