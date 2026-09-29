package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

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
 * <p><b>{@link #source} 决定「有效温度」从哪来。</b>默认 {@code auto}：装了 createishot 就用它的
 * 摄氏温度，没装就用群系温度。摄氏模式下「有效温度」不是摄氏值本身，而是先按
 * {@link #celsiusToTemperature} 反查回<b>原版温度刻度</b>再套上面那套公式 ——
 * 这样常温下手感与只用群系温度时完全一致，额外白得海拔 / 昼夜 / 天气 / 热源的影响。
 *
 * <p>全部字段都可省略，缺省用 {@link #DEFAULT}。这样最常见的用法是只写想改的那一两项。
 *
 * <p>要同步给客户端：专用服务器上客户端没有服务端的数据包，不送过去它就只能用内置默认曲线，
 * 提示框里的倍率、剩余时间全是错的。见 {@code SpoilageSyncPayload}。
 */
public record EnvironmentSettings(float referenceTemperature, float doublingPer,
                                  float humidityBase, float humiditySpan,
                                  float minMultiplier, float maxMultiplier,
                                  TemperatureSource source, List<CelsiusKnot> celsiusCurve) {

    /**
     * 曲线上的一个锚点：原版温度刻度 → 摄氏。
     *
     * <p>定义方向是「温度 → 摄氏」（createishot 就是这么映射的），用的时候要**反查**。
     */
    public record CelsiusKnot(float temperature, float celsius) {

        static final Codec<CelsiusKnot> CODEC = Codec.FLOAT.listOf(2, 2).xmap(
                list -> new CelsiusKnot(list.get(0), list.get(1)),
                knot -> List.of(knot.temperature(), knot.celsius()));

        static final StreamCodec<RegistryFriendlyByteBuf, CelsiusKnot> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.FLOAT, CelsiusKnot::temperature,
                        ByteBufCodecs.FLOAT, CelsiusKnot::celsius,
                        CelsiusKnot::new);
    }

    /**
     * createishot 默认的「群系温度 → 摄氏」映射：0.0 → −30°C，1.0 → 25°C，2.0 → 40°C
     * （在 1.0 处有拐点，所以是分段线性而不是一条直线）。
     *
     * <p><b>这组数需要实机校准。</b>它是照着 createishot 的默认配置抄的，而对方改了配置之后这里不会
     * 自动跟着变。校准方法：站在平原白天晴天，`/createishot thermal at` 读环境温度，
     * 那个值应该约等于这里 0.8 处的插值结果。
     */
    public static final List<CelsiusKnot> DEFAULT_CELSIUS_CURVE = List.of(
            new CelsiusKnot(0.0F, -30.0F),
            new CelsiusKnot(1.0F, 25.0F),
            new CelsiusKnot(2.0F, 40.0F));

    /** 平原基准、每升温 0.8 翻倍、湿度权重 ±25%、倍率限制在 0.05~4。 */
    public static final EnvironmentSettings DEFAULT =
            new EnvironmentSettings(0.8F, 0.8F, 0.75F, 0.5F, 0.05F, 4.0F,
                    TemperatureSource.AUTO, DEFAULT_CELSIUS_CURVE);

    public static final Codec<EnvironmentSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("reference_temperature", DEFAULT.referenceTemperature()).forGetter(EnvironmentSettings::referenceTemperature),
            // doubling_per 为 0 会除零，必须挡掉
            Codec.FLOAT.validate(v -> v > 0 ? DataResult.success(v)
                            : DataResult.error(() -> "doubling_per 必须大于 0"))
                    .optionalFieldOf("doubling_per", DEFAULT.doublingPer()).forGetter(EnvironmentSettings::doublingPer),
            Codec.FLOAT.optionalFieldOf("humidity_base", DEFAULT.humidityBase()).forGetter(EnvironmentSettings::humidityBase),
            Codec.FLOAT.optionalFieldOf("humidity_span", DEFAULT.humiditySpan()).forGetter(EnvironmentSettings::humiditySpan),
            Codec.FLOAT.optionalFieldOf("min_multiplier", DEFAULT.minMultiplier()).forGetter(EnvironmentSettings::minMultiplier),
            Codec.FLOAT.optionalFieldOf("max_multiplier", DEFAULT.maxMultiplier()).forGetter(EnvironmentSettings::maxMultiplier),
            TemperatureSource.CODEC.optionalFieldOf("temperature_source", DEFAULT.source()).forGetter(EnvironmentSettings::source),
            Codec.list(CelsiusKnot.CODEC).validate(EnvironmentSettings::validateCurve)
                    .optionalFieldOf("celsius_curve", DEFAULT.celsiusCurve()).forGetter(EnvironmentSettings::celsiusCurve)
    ).apply(instance, EnvironmentSettings::new));

    /**
     * 手写而不是 {@code StreamCodec.composite}：字段有 8 个，超过了 composite 的重载数量，
     * 而且曲线是变长列表。顺序必须和 {@link #CODEC} 的语义一致。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvironmentSettings> STREAM_CODEC = StreamCodec.of(
            (buf, settings) -> {
                buf.writeFloat(settings.referenceTemperature());
                buf.writeFloat(settings.doublingPer());
                buf.writeFloat(settings.humidityBase());
                buf.writeFloat(settings.humiditySpan());
                buf.writeFloat(settings.minMultiplier());
                buf.writeFloat(settings.maxMultiplier());
                TemperatureSource.STREAM_CODEC.encode(buf, settings.source());
                buf.writeVarInt(settings.celsiusCurve().size());
                for (CelsiusKnot knot : settings.celsiusCurve()) {
                    CelsiusKnot.STREAM_CODEC.encode(buf, knot);
                }
            },
            buf -> {
                float reference = buf.readFloat();
                float doubling = buf.readFloat();
                float humidityBase = buf.readFloat();
                float humiditySpan = buf.readFloat();
                float min = buf.readFloat();
                float max = buf.readFloat();
                TemperatureSource source = TemperatureSource.STREAM_CODEC.decode(buf);
                int knots = buf.readVarInt();
                List<CelsiusKnot> curve = new ArrayList<>(knots);
                for (int i = 0; i < knots; i++) {
                    curve.add(CelsiusKnot.STREAM_CODEC.decode(buf));
                }
                // 从网络包来的曲线也要挡一道：坏包不该让后面除零
                return new EnvironmentSettings(reference, doubling > 0 ? doubling : DEFAULT.doublingPer(),
                        humidityBase, humiditySpan,
                        Math.min(min, max), max, source,
                        curve.size() >= 2 ? curve : DEFAULT.celsiusCurve());
            });

    /**
     * 把 {@link TemperatureSource#AUTO} 落到具体来源上。
     *
     * <p>只在服务端解析数据包时调用一次，之后同步给客户端的永远是解析过的值 —— 否则客户端会按
     * <b>自己的</b>模组列表各解析一遍，没装 createishot 的客户端和装了它的服务器会算出两个不同的倍率。
     */
    /** 温度是不是来自 createishot。客户端和服务端都会用到这个判断，所以放在这里。 */
    public boolean isCelsius() {
        return source == TemperatureSource.CREATEISHOT;
    }

    public EnvironmentSettings resolved(boolean createishotLoaded) {
        TemperatureSource concrete = source.resolve(createishotLoaded);
        if (concrete == source) return this;
        return new EnvironmentSettings(referenceTemperature, doublingPer, humidityBase, humiditySpan,
                minMultiplier, maxMultiplier, concrete, celsiusCurve);
    }

    /**
     * 曲线必须能反查：至少两个锚点，且摄氏值<b>严格递增</b>（否则同一个摄氏温度对应多个原版温度，
     * 反查没有唯一解），同时按温度递增排列。
     */
    private static DataResult<List<CelsiusKnot>> validateCurve(List<CelsiusKnot> curve) {
        if (curve.size() < 2) {
            return DataResult.error(() -> "celsius_curve 至少需要两个锚点");
        }
        for (int i = 1; i < curve.size(); i++) {
            CelsiusKnot prev = curve.get(i - 1);
            CelsiusKnot next = curve.get(i);
            // lambda 只能捕获 final 变量，循环下标要另存一份
            int index = i;
            if (next.celsius() <= prev.celsius()) {
                return DataResult.error(() -> "celsius_curve 的摄氏值必须严格递增（第 " + index + " 个锚点不满足）");
            }
            if (next.temperature() <= prev.temperature()) {
                return DataResult.error(() -> "celsius_curve 的温度值必须严格递增（第 " + index + " 个锚点不满足）");
            }
        }
        return DataResult.success(List.copyOf(curve));
    }

    /**
     * 把 createishot 的摄氏温度**反查**回原版温度刻度。
     *
     * <p>曲线的定义方向是「温度 → 摄氏」（见 {@link #DEFAULT_CELSIUS_CURVE} 的注释），
     * 这里走反方向：找到摄氏值落在哪一段，再线性插值出对应的温度。
     *
     * <p><b>超出端点不夹紧，而是沿最外那段外推。</b>夹紧会把"很热"和"极热"压成同一个值，
     * 于是营火和岩浆给出同样的倍率；外推至少保持单调，再交由 {@code min/max_multiplier} 收口。
     */
    public float celsiusToTemperature(float celsius) {
        CelsiusKnot prev = celsiusCurve.get(0);
        for (int i = 1; i < celsiusCurve.size(); i++) {
            CelsiusKnot next = celsiusCurve.get(i);
            if (celsius <= next.celsius() || i == celsiusCurve.size() - 1) {
                float span = next.celsius() - prev.celsius();
                if (span == 0.0F) return prev.temperature();   // 校验已挡住，防御性分支
                float progress = (celsius - prev.celsius()) / span;
                return prev.temperature() + progress * (next.temperature() - prev.temperature());
            }
            prev = next;
        }
        return prev.temperature();
    }

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
