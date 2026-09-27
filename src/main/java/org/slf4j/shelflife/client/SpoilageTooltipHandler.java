package org.slf4j.shelflife.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.ClientSpoilageCache;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentSettings;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.logic.EnvironmentSample;
import org.slf4j.shelflife.logic.SpoilageSettlement;

import java.util.List;
import java.util.Locale;

/**
 * 在物品名称下方显示保质期。
 *
 * <p><b>开了原版高级提示框（F3+H）</b>才会再显示两行排查信息：这次的腐烂倍率，以及它是怎么算出来的。
 * 关着的时候只有一行保质期 —— 平时玩不需要看公式。
 *
 * <p>数据来源有四部分：{@link SpoilageData} 随物品组件同步过来、{@code ticks_per_spoilage}
 * 来自服务端下发的配置表、采样（温湿度 + 倍率）来自 {@link ClientEnvironmentCache}、
 * 曲线系数来自服务端同步的 {@link EnvironmentSettings}。缺任何一个都算不出时间。
 *
 * <p>倍率是<b>按物品</b>取的，不是全局值：容器里的食物和背包里的食物可能同时在屏幕上，
 * 用的却是两个不同的倍率。
 */
@EventBusSubscriber(modid = Shelflife.MODID, value = Dist.CLIENT)
public final class SpoilageTooltipHandler {

    private static final int TICKS_PER_SECOND = 20;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int SECONDS_PER_HOUR = 3600;

    /** 判断原始倍率有没有被夹到上下限的容差 —— float 算出来的 0.05 可能是 0.0499999。 */
    private static final float CLAMP_EPSILON = 1.0E-4F;

    private SpoilageTooltipHandler() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null) return;

        SpoilageConfig config = ClientSpoilageCache.get(stack.getItem());
        if (config == null) return;

        // 客户端的游戏时间和服务端是同步的，够用来算剩余时间
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        EnvironmentSample sample = ClientEnvironmentCache.sampleFor(stack);
        List<Component> tooltip = event.getToolTip();
        int index = Math.min(1, tooltip.size());
        // 名称在索引 0，插到它正下方
        tooltip.add(index, buildShelfLife(data, config, sample.rate(), level.getGameTime()));

        // 倍率和推导过程只在高级提示框里出现 —— 平时玩游戏不需要看见公式
        if (!event.getFlags().isAdvanced()) return;
        tooltip.add(index + 1, buildRate(sample.rate()));
        tooltip.add(index + 2, buildFormula(sample));
    }

    private static Component buildShelfLife(SpoilageData data, SpoilageConfig config, float rate, long now) {
        // 背包不再定时结算，组件里的值是"上次结算时刻的检查点"，
        // 所以这里必须按时间差和倍率推算当前值，否则食物会在背包里放很久但 tooltip 数字一直不动
        int current = SpoilageSettlement.effective(data, config, rate, now);
        if (current >= data.maxSpoilage()) {
            return Component.translatable("tooltip.shelflife.spoiled").withStyle(ChatFormatting.DARK_RED);
        }
        if (rate <= 0.0F) {
            // 停腐的容器（比如通电的冰箱）里，剩余时间没有意义
            return Component.translatable("tooltip.shelflife.preserved").withStyle(ChatFormatting.AQUA);
        }

        // 用组件里的 maxSpoilage 而不是配置里的：物品的保质期上限是它被创建时定下的，
        // 之后管理员改数据包不应该让已有物品的上限跟着变，否则 tooltip 和实际进度会对不上
        int remainingPoints = data.maxSpoilage() - current;
        // 剩余点数是"暴露时间"，换算成真实时间要除回倍率 —— 寒冷环境里同样是 10 点，真实时间更长
        long remainingTicks = (long) (remainingPoints * (double) config.ticksPerSpoilage() / rate);

        long totalSeconds = remainingTicks / TICKS_PER_SECOND;
        long hours = totalSeconds / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;

        if (hours > 0) return Component.translatable("tooltip.shelflife.life.hours", hours, minutes).withStyle(ChatFormatting.GRAY);
        if (minutes > 0) return Component.translatable("tooltip.shelflife.life.minutes", minutes, seconds).withStyle(ChatFormatting.GRAY);
        return Component.translatable("tooltip.shelflife.life.seconds", seconds).withStyle(ChatFormatting.GRAY);
    }

    /** 冷/热一眼可辨：低于 1 是变慢（冷），高于 1 是变快（热）。 */
    private static Component buildRate(float rate) {
        return Component.translatable("tooltip.shelflife.rate", format(rate, 2))
                .withStyle(rate < 1.0F ? ChatFormatting.AQUA : ChatFormatting.GOLD);
    }

    /**
     * 把倍率的推导过程连同实际数字写出来。
     *
     * <p>用 {@code rawMultiplierFor} 而不是 {@code multiplierFor}：得把<b>夹之前</b>的值也显示出来，
     * 否则"算出来 0.012 被夹到 0.05"和"本来就是 0.05"看起来一模一样，而这两种情况的排查方向相反。
     */
    private static Component buildFormula(EnvironmentSample sample) {
        if (sample.hasOverride()) {
            return indented(Component.translatable("tooltip.shelflife.formula.override", format(sample.rate(), 2)));
        }

        EnvironmentSettings settings = EnvironmentManager.settings();
        float raw = settings.rawMultiplierFor(sample.temperature(), sample.humidity());
        String body = String.format(Locale.ROOT, "2^((%s - %s) / %s) x (%s + %s x %s) = %s",
                format(sample.temperature(), 2), format(settings.referenceTemperature(), 2),
                format(settings.doublingPer(), 2), format(settings.humidityBase(), 2),
                format(settings.humiditySpan(), 2), format(sample.humidity(), 2), format(raw, 3));

        MutableComponent line = indented(Component.literal(body));
        if (Math.abs(raw - sample.rate()) > CLAMP_EPSILON) {
            line.append(Component.translatable("tooltip.shelflife.formula.clamped", format(sample.rate(), 2)));
        }
        return line;
    }

    /** 公式缩进两格并压暗，和上面的说明行区分开。 */
    private static MutableComponent indented(Component content) {
        return Component.literal("  ").append(content).withStyle(ChatFormatting.DARK_GRAY);
    }

    /**
     * 固定用 ROOT locale —— 否则某些语言环境下小数点会变成逗号，读起来像两个数字。
     *
     * <p>倍率保留两位：冷箱是 0.05，只留一位会被四舍五入成 0.1，看不出和 0.1 的差别。
     */
    private static String format(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
