package org.slf4j.shelflife.logic;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;

/**
 * 腐烂推进的核心算术，背包 tick 和容器开箱共用同一套。
 *
 * <p><b>为什么背包和容器用同一个公式：</b>两个机制本质上都是"距上次结算过了多久"。
 * 两侧都靠 {@code storedTimestamp} 记录上次结算时刻，所以物品在背包和容器之间来回搬运时，
 * 累计消耗始终正确，不会漏算也不会重复计算 —— 这也是背包侧不需要定时扫描的原因。
 *
 * <p><b>环境倍率（{@code rate}）：</b>最终的等效暴露时间是 {@code 真实时间 × rate}，
 * 所以 {@code rate < 1} 就是"变慢"。倍率由 {@link EnvironmentSampler} 从群系温湿度和容器修正算出来。
 *
 * <p><b>{@code rate <= 0} 表示完全停腐</b>（比如通了电的冰箱）。这一支必须单独处理，
 * 而且它揭示了一条硬规则：
 *
 * <blockquote>
 * <b>任何会改变倍率的事件，都必须在那一刻结算一次。</b>
 * </blockquote>
 *
 * 否则停腐那段时间会在倍率恢复后被整个补算 —— 单检查点模型算不出分段的积分，
 * 只能在倍率变化的那一刻"结清"。冰箱断电刷新时间戳不是权宜之计，是不这么做就会算错。
 */
public final class SpoilageSettlement {

    private SpoilageSettlement() {
    }

    /**
     * 按时间差和倍率推进保质期。
     *
     * @return 新的组件值；返回 {@code null} 表示无需改动
     */
    @Nullable
    public static SpoilageData advance(@Nullable SpoilageData data, SpoilageConfig config, float rate, long now) {
        if (data == null) {
            // 第一次见到这个物品：只建立时间基准，不结算。
            // 否则 "now - 0" 会把世界创建至今的全部时间一次性算上，物品瞬间腐烂。
            return SpoilageData.fresh(config.maxSpoilage()).withTimestamp(now);
        }
        if (data.storedTimestamp() == SpoilageData.NO_TIMESTAMP) {
            return data.withTimestamp(now);
        }
        if (rate <= 0.0F) {
            // 完全停腐：只把检查点推到当前时刻，一点不涨
            return data.withTimestamp(now);
        }
        if (data.isSpoiled()) {
            return null;
        }

        long elapsed = now - data.storedTimestamp();
        if (elapsed <= 0) {
            return null;
        }

        long exposure = (long) (elapsed * (double) rate);
        if (exposure < config.ticksPerSpoilage()) {
            // 不足 1 点：时间戳原地不动，零头留到下次一起算
            return null;
        }

        long points = Math.min(exposure / config.ticksPerSpoilage(), data.maxSpoilage());
        SpoilageData advanced = data.addSpoilage((int) points);
        // 只推进"已消费"的那段时间，余数保留；这里要换算回真实时间，所以除回倍率。
        // 若直接把时间戳设成 now，每次结算都会丢掉不足 1 点的零头。
        long consumed = (long) (points * (double) config.ticksPerSpoilage() / rate);
        return advanced.withTimestamp(data.storedTimestamp() + consumed);
    }

    /**
     * 惰性读取：从检查点推算到 {@code now} 的当前保质期，不修改任何东西。
     *
     * <p>背包不再定时结算，所以存储的值可能落后于真实值。凡是"展示"或"临时判断"都应该用这个方法，
     * 而不是直接读 {@link SpoilageData#currentSpoilage()}。
     *
     * <p><b>调用方必须传服务端会用到的同一个倍率</b>，否则 tooltip 显示的和服务端结算的会对不上。
     * 客户端拿 {@code ClientEnvironmentCache.rateFor(ItemStack)}（按物品取 —— 容器里的和背包里的可能不同）。
     */
    public static int effective(@Nullable SpoilageData data, SpoilageConfig config, float rate, long now) {
        // 逻辑判断要的是"够不够一个整点"，直接截断即可
        return (int) effectiveFractional(data, config, rate, now);
    }

    /**
     * 同 {@link #effective}，但<b>保留小数</b>。给显示用。
     *
     * <p>{@link #effective} 返回整点是有意的：结算和"该不该转化"这些判断本来就以点为粒度。
     * 但显示不能这样 —— {@code ticks_per_spoilage} 是 3600 的食物，攒够一个点要 3 分钟，
     * 于是 tooltip 里的"秒"三分钟才动一格，看着像卡住了。
     * （只显示到"分"的时候看不出来，精确到秒之后就成了明显的 bug。）
     */
    public static double effectiveFractional(@Nullable SpoilageData data, SpoilageConfig config, float rate, long now) {
        if (data == null) return 0.0;
        // 时钟未开始（食物刚拿到、还没被任何结算事件碰到）—— 按全新算，否则会把游戏时间全算上
        if (data.storedTimestamp() == SpoilageData.NO_TIMESTAMP) return data.currentSpoilage();
        if (rate <= 0.0F) return data.currentSpoilage();   // 停腐期间不涨
        if (data.isSpoiled()) return data.currentSpoilage();

        long elapsed = now - data.storedTimestamp();
        if (elapsed <= 0) return data.currentSpoilage();

        double points = elapsed * (double) rate / config.ticksPerSpoilage();
        return Math.min(data.currentSpoilage() + points, data.maxSpoilage());
    }

    /**
     * 结算并写回物品。
     *
     * @return {@code true} 表示组件确实变了，调用方可据此把容器标记为已修改
     */
    public static boolean settle(ItemStack stack, SpoilageConfig config, float rate, long now) {
        SpoilageData current = stack.get(Shelflife.SPOILAGE.get());
        SpoilageData next = advance(current, config, rate, now);
        if (next == null || next.equals(current)) {
            return false;
        }
        stack.set(Shelflife.SPOILAGE.get(), next);
        return true;
    }
}
