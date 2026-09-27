package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.data.ContainerModifier;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentSettings;

/**
 * 把一个位置（可选带容器方块）换算成腐烂速率倍率。
 *
 * <p><b>倍率是常开的</b>：没有数据包时用内置曲线，所以雪原里天生就比沙漠慢。
 *
 * <p>对外有两个入口：{@link #rateAt} 只要结果，{@link #sampleAt} 连输入一起给
 * （提示框要把公式和数字显示出来，光有结果不够）。
 */
public final class EnvironmentSampler {

    private EnvironmentSampler() {
    }

    /**
     * 采样倍率。结算路径用这个。
     *
     * @param containerBlock 容器所在的方块；玩家背包传 {@code null}（背包不享受容器修正）
     */
    public static float rateAt(Level level, BlockPos pos, @Nullable Block containerBlock) {
        return sampleAt(level, pos, containerBlock).rate();
    }

    /**
     * 完整采样：除倍率外还给出算这个倍率用的有效温湿度。
     *
     * <p>容器修正只在<b>这一处</b>施加，所以 {@code rate_override} 和温度偏移两种表达方式的
     * 优先级也只在这里定义一次。
     */
    public static EnvironmentSample sampleAt(Level level, BlockPos pos, @Nullable Block containerBlock) {
        EnvironmentSettings settings = EnvironmentManager.settings();

        Biome.ClimateSettings climate = level.getBiome(pos).value().getModifiedClimateSettings();
        float temperature = climate.temperature();
        float humidity = climate.downfall();

        ContainerModifier modifier = containerBlock == null ? null : EnvironmentManager.modifierFor(containerBlock);
        if (modifier != null) {
            // rate_override 是"直接指定倍率"的逃生门 —— 冰箱想彻底停腐（0）只能靠它，
            // 光靠温度偏移逼近不到 0。负值不是合法的倍率，一律当作没写（掉回温度路径）
            if (modifier.rateOverride().isPresent() && modifier.rateOverride().get() >= 0.0F) {
                float override = modifier.rateOverride().get();
                // 温湿度照原样带出去：这种情况公式里不显示它们，但结构上要保持一致
                return new EnvironmentSample(temperature, humidity, override, override);
            }
            temperature += modifier.temperature();
            humidity = Math.clamp(humidity + modifier.humidity(), 0.0F, 1.0F);
        }

        return new EnvironmentSample(temperature, humidity,
                settings.multiplierFor(temperature, humidity), EnvironmentSample.NO_OVERRIDE);
    }
}
