package org.slf4j.shelflife.logic;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.slf4j.shelflife.data.TemperatureSource;

/**
 * 某一次环境采样的完整结果：算倍率用的输入、最终倍率、是否走了 {@code rate_override}，
 * 以及温度来自哪套系统。
 *
 * <p>之所以把"输入"也带上，是为了让高级提示框能**把公式连同实际数字一起显示出来** ——
 * 只报一个结果数字的话，"为什么是这个倍率"只能靠猜。
 *
 * <p>这个结构也会被塞进网络包发给客户端：客户端不知道打开的容器在世界哪个位置，
 * 更不知道数据包给那个容器写了什么修正，也读不到 createishot 的温度，所以这些数只能由服务端算好送过去。
 *
 * @param temperature 当前曲线**实际消费**的那个数。含义随 {@link #source} 变：
 *                    {@link TemperatureSource#BIOME} 时是群系温度（含容器修正）；
 *                    {@link TemperatureSource#CREATEISHOT} 时是按摄氏曲线**反查**出来的等效原版温度。
 * @param celsius     仅摄氏模式有效：createishot 给出的真实节点温度。其它情况是 {@link #NO_CELSIUS}
 * @param rateOverride 容器数据包里直接指定的倍率，{@link #NO_OVERRIDE} 表示没有
 */
public record EnvironmentSample(float temperature, float humidity, float rate,
                                float rateOverride, TemperatureSource source, float celsius) {

    /**
     * 表示"没有 rate_override"。
     *
     * <p>用负数当哨兵是安全的：{@code EnvironmentSampler} 本来就拒绝负的 override
     * （负值会掉回温度路径），所以负值永远不会是"有效的 override"。
     */
    public static final float NO_OVERRIDE = -1.0F;

    /**
     * 表示"这个采样里没有摄氏温度"（群系模式）。
     *
     * <p>用 NaN 而不是 0.0：0 是个合法的摄氏温度，而 NaN 参与任何显示都会露馅，
     * 不会静静地被当成"零度"。
     */
    public static final float NO_CELSIUS = Float.NaN;

    /** 6 个字段正好是 {@code StreamCodec.composite} 的上限，顺序必须和 record 的声明一致。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvironmentSample> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, EnvironmentSample::temperature,
            ByteBufCodecs.FLOAT, EnvironmentSample::humidity,
            ByteBufCodecs.FLOAT, EnvironmentSample::rate,
            ByteBufCodecs.FLOAT, EnvironmentSample::rateOverride,
            TemperatureSource.STREAM_CODEC, EnvironmentSample::source,
            ByteBufCodecs.FLOAT, EnvironmentSample::celsius,
            EnvironmentSample::new);

    /** 群系模式：温度就是群系温度（含容器的原版单位修正）。 */
    public static EnvironmentSample ofBiome(float temperature, float humidity, float rate) {
        return new EnvironmentSample(temperature, humidity, rate, NO_OVERRIDE, TemperatureSource.BIOME, NO_CELSIUS);
    }

    /**
     * 摄氏模式。
     *
     * @param celsius     真实节点温度，用于显示
     * @param temperature 按摄氏曲线反查出来的等效原版温度，用于套原来那套倍率公式
     */
    public static EnvironmentSample ofCelsius(float celsius, float temperature, float humidity, float rate) {
        return new EnvironmentSample(temperature, humidity, rate, NO_OVERRIDE, TemperatureSource.CREATEISHOT, celsius);
    }

    /** {@code rate_override}：绕过一切温度计算，直接指定倍率。温湿度只作记录。 */
    public static EnvironmentSample ofOverride(float temperature, float humidity, float override,
                                                TemperatureSource source, float celsius) {
        return new EnvironmentSample(temperature, humidity, override, override, source, celsius);
    }

    public boolean hasOverride() {
        return rateOverride >= 0.0F;
    }

    public boolean hasCelsius() {
        return !Float.isNaN(celsius);
    }
}
