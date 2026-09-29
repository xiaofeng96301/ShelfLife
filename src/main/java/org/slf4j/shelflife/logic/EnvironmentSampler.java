package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.api.ContainerClimate;
import org.slf4j.shelflife.data.ContainerModifier;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentSettings;
import org.slf4j.shelflife.data.TemperatureSource;

/**
 * 把一个位置（可选带容器方块）换算成腐烂速率倍率。
 *
 * <p><b>温度可以来自两套系统</b>（见 {@link TemperatureSource}）：群系温度，或者 createishot 的
 * 节点温度（环境 + 热源，服务端求解）。<b>湿度永远来自群系</b> —— createishot 只管温度，
 * 没有湿度这个维度。
 *
 * <p>摄氏模式下，createishot 给出的摄氏温度会先按 {@link EnvironmentSettings#celsiusToTemperature}
 * 反查回<b>原版温度刻度</b>，再套原来那套公式。这样常温下手感与只用群系温度时完全一致，
 * 额外白得海拔 / 昼夜 / 天气 / 热源的影响；也就意味着 {@code multiplierFor} 的入参永远是
 * "原版温度刻度"，不必知道温度是从哪来的。
 *
 * <p>这里是**唯一**施加容器修正的地方，所以 {@code rate_override} 和温度修正的优先级也只定义一次。
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

    /** 完整采样：除倍率外还给出算这个倍率用的输入和来源，供提示框显示公式。 */
    public static EnvironmentSample sampleAt(Level level, BlockPos pos, @Nullable Block containerBlock) {
        EnvironmentSettings settings = EnvironmentManager.settings();

        Biome.ClimateSettings climate = level.getBiome(pos).value().getModifiedClimateSettings();
        // 湿度只有群系这一个来源，两种模式都一样
        float humidity = climate.downfall();

        ContainerModifier modifier = containerBlock == null ? null : EnvironmentManager.modifierFor(containerBlock);
        // 方块实体可以自己报"此刻多冷"（通电冰箱那种动态冷源），它覆盖数据包的静态修正 ——
        // 见 ContainerClimate。没有实体承载的容器（木桶、潜影盒）问不到，就只用数据包
        if (containerBlock != null) {
            ContainerModifier fromEntity = ContainerClimate.climateOf(level.getBlockEntity(pos));
            if (fromEntity != null) {
                modifier = fromEntity;
            }
        }
        if (modifier != null) {
            humidity = Math.clamp(humidity + modifier.humidity(), 0.0F, 1.0F);
        }

        // 摄氏模式：向 createishot 要节点温度。拿不到（没装 / 不是服务端）就掉回群系路径 ——
        // 这两个分支的降级是必须的，否则没装模组的玩家会直接崩
        Float celsius = settings.source() == TemperatureSource.CREATEISHOT
                ? CreateishotCompat.celsiusAt(level, pos)
                : null;
        if (celsius != null) {
            // 先反查回原版温度刻度，容器的温度偏移加在**这一层** ——
            // 曲线是非线性的（低温段约 55°C / 单位），若把偏移加在摄氏那一层，
            // 同样一句 "temperature: -4.0" 在两种模式下的冷却效果会差一个数量级。
            // 加在这一层，数据包就不必为两种模式各写一份。
            float temperature = settings.celsiusToTemperature(celsius);
            if (hasOverride(modifier)) {
                return EnvironmentSample.ofOverride(temperature, humidity, modifier.rateOverride().get(),
                        TemperatureSource.CREATEISHOT, celsius);
            }
            temperature += modifier == null ? 0.0F : modifier.temperature();
            return EnvironmentSample.ofCelsius(celsius, temperature, humidity,
                    settings.multiplierFor(temperature, humidity));
        }

        float temperature = climate.temperature();
        if (modifier != null) {
            if (hasOverride(modifier)) {
                return EnvironmentSample.ofOverride(temperature, humidity, modifier.rateOverride().get(),
                        TemperatureSource.BIOME, EnvironmentSample.NO_CELSIUS);
            }
            temperature += modifier.temperature();
        }
        return EnvironmentSample.ofBiome(temperature, humidity, settings.multiplierFor(temperature, humidity));
    }

    /**
     * 负值不是合法的倍率，一律当作没写（掉回温度路径）—— 快速腐烂箱请写一个大的正值。
     */
    private static boolean hasOverride(@Nullable ContainerModifier modifier) {
        return modifier != null && modifier.rateOverride().isPresent() && modifier.rateOverride().get() >= 0.0F;
    }
}
