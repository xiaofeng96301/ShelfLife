package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 环境（温度 + 湿度）如何换算成腐烂速率倍率的曲线系数。
 *
 * <pre>
 * 温度因子 = 2 ^ ((有效温度 − referenceTemperature) / doublingPer)
 * 湿度因子 = humidityBase + humiditySpan × 有效湿度        // 湿度 0.5 时正好是 1.0
 * 倍率     = clamp(温度因子 × 湿度因子, minMultiplier, maxMultiplier)
 * </pre>
 *
 * <p>{@code referenceTemperature} 是"不变快慢"的基准温度 —— 取 0.8 就是平原，
 * 那里的食物按数据包里写的 {@code ticks_per_spoilage} 原速腐烂。
 *
 * <p>全部字段都可省略，缺省用 {@link #DEFAULT}。这样最常见的用法是只写想改的那一两项。
 *
 * <p>要同步给客户端：专用服务器上客户端没有服务端的数据包，不送过去它就只能用内置默认曲线，
 * 提示框里的倍率、剩余时间全是错的。见 {@code SpoilageSyncPayload}。
 */
public record EnvironmentSettings(float referenceTemperature, float doublingPer,
                                  float humidityBase, float humiditySpan,
                                  float minMultiplier, float maxMultiplier) {

    /** 平原基准、每升温 0.8 翻倍、湿度权重 ±25%、倍率限制在 0.05~4。 */
    public static final EnvironmentSettings DEFAULT =
            new EnvironmentSettings(0.8F, 0.8F, 0.75F, 0.5F, 0.05F, 4.0F);

    public static final Codec<EnvironmentSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("reference_temperature", DEFAULT.referenceTemperature()).forGetter(EnvironmentSettings::referenceTemperature),
            // doubling_per 为 0 会除零，必须挡掉
            Codec.FLOAT.validate(v -> v > 0 ? DataResult.success(v)
                            : DataResult.error(() -> "doubling_per 必须大于 0"))
                    .optionalFieldOf("doubling_per", DEFAULT.doublingPer()).forGetter(EnvironmentSettings::doublingPer),
            Codec.FLOAT.optionalFieldOf("humidity_base", DEFAULT.humidityBase()).forGetter(EnvironmentSettings::humidityBase),
            Codec.FLOAT.optionalFieldOf("humidity_span", DEFAULT.humiditySpan()).forGetter(EnvironmentSettings::humiditySpan),
            Codec.FLOAT.optionalFieldOf("min_multiplier", DEFAULT.minMultiplier()).forGetter(EnvironmentSettings::minMultiplier),
            Codec.FLOAT.optionalFieldOf("max_multiplier", DEFAULT.maxMultiplier()).forGetter(EnvironmentSettings::maxMultiplier)
    ).apply(instance, EnvironmentSettings::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, EnvironmentSettings> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.FLOAT, EnvironmentSettings::referenceTemperature,
                    ByteBufCodecs.FLOAT, EnvironmentSettings::doublingPer,
                    ByteBufCodecs.FLOAT, EnvironmentSettings::humidityBase,
                    ByteBufCodecs.FLOAT, EnvironmentSettings::humiditySpan,
                    ByteBufCodecs.FLOAT, EnvironmentSettings::minMultiplier,
                    ByteBufCodecs.FLOAT, EnvironmentSettings::maxMultiplier,
                    EnvironmentSettings::new);

    /**
     * 夹上下限<b>之前</b>的原始倍率。
     *
     * <p>单独暴露出来是给提示框用的：光看结果没法区分"本来就是 0.05"和"算出来 0.012 被夹到 0.05"，
     * 而这两种情况的排查方向完全相反（改曲线 vs 改上下限）。
     */
    public float rawMultiplierFor(float temperature, float humidity) {
        double temperatureFactor = Math.pow(2.0, (temperature - referenceTemperature) / doublingPer);
        float humidityFactor = humidityBase + humiditySpan * humidity;
        return (float) (temperatureFactor * humidityFactor);
    }

    /** 把一处的有效温湿度算成倍率。 */
    public float multiplierFor(float temperature, float humidity) {
        return Math.clamp(rawMultiplierFor(temperature, humidity), minMultiplier, maxMultiplier);
    }
}
