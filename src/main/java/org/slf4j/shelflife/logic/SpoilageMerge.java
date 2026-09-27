package org.slf4j.shelflife.logic;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;

/**
 * 饥荒式堆叠合并：保质期不同的同类食物允许堆叠，堆叠后取加权平均。
 *
 * <p>原版认为组件不同就是不同物品，压根不会让它们堆。所以这套逻辑要在**每个合并点**做两件事：
 * <ol>
 *   <li>放宽该处的"可堆叠"判定 —— 见各 mixin 里对 {@code isSameItemSameComponents} 的包裹</li>
 *   <li>在原版 {@code grow} 之前把目标堆的保质期改成加权平均 —— {@link #averageBeforeMerge}</li>
 * </ol>
 *
 * <p><b>刻意不做全局放宽</b>（即不改 {@code ItemStack.isSameItemSameComponents} 本身）：那个方法同时被
 * {@code ItemStack.matches} 用着，而 {@code matches} 是 {@code AbstractContainerMenu.synchronizeSlotToRemote}
 * 判断"槽位要不要同步给客户端"的依据。全局放宽会让保质期的变化永远同步不到客户端 ——
 * 表现为服务端在腐烂、客户端 tooltip 永远显示旧值，且不报错。
 */
public final class SpoilageMerge {

    private SpoilageMerge() {
    }

    /**
     * 两个堆能否按本模组的规则合并（宽于原版）。
     *
     * <p><b>{@code target} 必须已经带有保质期数据</b>，这是本类的核心不变量，不是可选的优化：
     * 只有"已在我们管理之下"的堆才允许被写入加权平均。反例是创造模式物品栏 ——
     * 它的槽位指向一份共享的物品列表实例，若允许往没有组件的堆上写，
     * 整个创造物品栏的食物会被永久污染成"带新鲜度的食物"，之后从里面拿什么都是有保质期的。
     *
     * <p>{@code source} 可以没有组件（视为全新，保质期按 0 计）—— 捡起地上的食物就是这个情形。
     */
    public static boolean canStackTogether(ItemStack target, ItemStack source) {
        if (target.isEmpty() || source.isEmpty()) return false;
        if (!target.is(source.getItem())) return false;
        return target.has(Shelflife.SPOILAGE.get());
    }

    /**
     * 是否需要由本模组接管这次合并。
     *
     * <p>两边组件完全相同（含保质期和时间戳都一样）时返回 {@code false} —— 那种情况原版自己就会判定为可堆叠，
     * 平均也是空操作，没必要插手。
     */
    public static boolean needsCustomMerge(ItemStack target, ItemStack source) {
        if (!canStackTogether(target, source)) return false;
        DataComponentType<SpoilageData> type = Shelflife.SPOILAGE.get();
        SpoilageData targetData = target.get(type);
        SpoilageData sourceData = source.get(type);
        return sourceData == null || !targetData.equals(sourceData);
    }

    /**
     * 把 {@code target} 的保质期改成与 {@code source} 的加权平均。
     *
     * <p><b>必须在 {@code target.grow(amount)} 之前调用</b>：本方法读的是合并前的数量。
     * 前提是 {@link #canStackTogether} 已成立（即 {@code target} 带保质期数据）。
     *
     * @param amount 即将并入的数量，不是 source 的总数 —— 目标堆可能只装得下一部分
     */
    public static void averageBeforeMerge(ItemStack target, ItemStack source, int amount) {
        if (amount <= 0) return;

        DataComponentType<SpoilageData> type = Shelflife.SPOILAGE.get();
        SpoilageData targetData = target.get(type);
        if (targetData == null) return;   // 防御：不变量被破坏时宁可不写，也不能凭空造组件
        SpoilageData sourceData = source.get(type);

        int targetCount = target.getCount();
        int mergedCount = targetCount + amount;
        if (mergedCount <= 0) return;

        int targetSpoilage = targetData.currentSpoilage();
        int sourceSpoilage = sourceData != null ? sourceData.currentSpoilage() : 0;

        long weighted = (long) targetSpoilage * targetCount + (long) sourceSpoilage * amount;
        // 时间戳沿用 target 的：合并点里拿不到 Level，也就算不出"当前时间"。
        // 好在容器里的物品每次开箱都会被统一打戳，所以同容器内合并时两边时间戳本来就相同，这个选择是精确的；
        // 反而若把时间戳清成"未定"，下次结算会当作全新物品，把两次开箱之间的那段时间整个跳过。
        target.set(type, new SpoilageData((int) (weighted / mergedCount), targetData.maxSpoilage(),
                targetData.storedTimestamp()));
    }
}
