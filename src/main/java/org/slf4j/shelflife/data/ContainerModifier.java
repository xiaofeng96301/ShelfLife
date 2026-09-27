package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/**
 * 某个方块作为容器时，对所在地温度/湿度的修正。
 *
 * <p>这是"冰箱"的实现方式 —— **不特殊化，只当成温度偏移**。于是同一套机制能表达：
 * <ul>
 *   <li>冰箱：{@code temperature: -2.0}</li>
 *   <li>箱子放在雪原：不需要修正，群系温度本来就低</li>
 *   <li>冰箱放在沙漠：两个偏移相加，仍然凉</li>
 *   <li>别的模组做的冰箱：只要在数据包里给它一行</li>
 * </ul>
 *
 * <p><b>{@code temperature} 的单位永远是原版温度刻度，两种温度来源下都一样。</b>
 * 摄氏模式下节点温度会先被反查回原版刻度，这个偏移加在<b>反查之后</b> ——
 * 所以同一个数据包在装 / 不装 createishot 时给出同样的冷却效果，不需要写两份。
 *
 * <p>{@code rateOverride} 是"直接指定倍率"的逃生门，优先于温度。想让冰箱彻底停腐
 * （倍率 0）就用它 —— 靠温度偏移只能逼近 0，而倍率 0 在结算公式里是需要单独处理的分支。
 */
public record ContainerModifier(float temperature, float humidity, Optional<Float> rateOverride) {

    public static final Codec<ContainerModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.optionalFieldOf("temperature", 0.0F).forGetter(ContainerModifier::temperature),
            Codec.FLOAT.optionalFieldOf("humidity", 0.0F).forGetter(ContainerModifier::humidity),
            Codec.FLOAT.optionalFieldOf("rate_override").forGetter(ContainerModifier::rateOverride)
    ).apply(instance, ContainerModifier::new));
}
