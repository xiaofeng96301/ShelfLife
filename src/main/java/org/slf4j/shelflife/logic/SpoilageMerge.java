package org.slf4j.shelflife.logic;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
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
     * 把 {@code target} 的保质期改成与来源的加权平均 —— <b>来源数据由调用方先取好</b>。
     *
     * <p>为什么要专门有这么一个重载：搬运物品的各个调用点里，写入发生的时机不一样，
     * 有一个地方的来源堆<b>到写入时已经读不出自己的组件了</b>。
     *
     * <p>具体是漏斗：{@code HopperBlockEntity.tryMoveInItem} 的顺序是<b>先 shrink 再 grow</b>
     * （1.21.1 的第 350 / 351 行），而被搬走的那一堆 shrink 之后数量为 0；偏偏
     * {@code ItemStack.getComponents()} 在 {@code count <= 0} 时返回 {@code DataComponentMap.EMPTY}，
     * 于是 {@code source.get(SPOILAGE)} 变成 {@code null}，平均就按"来源是全新（0 点）"算 ——
     * 目标堆被稀释成一半，保质期凭空翻倍。每漏进来一个就再减半一次，很快就顶到上限。
     *
     * <p>所以：<b>能不能在这一刻读来源堆，取决于调用点的顺序</b>。判定和写入之间隔了 shrink 的，
     * 必须在判定那一刻把 {@link SpoilageData} 取好再传进来（见 {@code HopperBlockEntityMixin}）；
     * 其余调用点判定即写入，用下面那个 {@code ItemStack} 重载就行。
     *
     * @param sourceData 来源堆<b>在合并判定时</b>的数据。{@code null} 表示它本来就没有组件（算全新）
     * @param amount     即将并入的数量，不是来源的总数 —— 目标堆可能只装得下一部分
     */
    public static void averageBeforeMerge(ItemStack target, @Nullable SpoilageData sourceData, int amount) {
        DataComponentType<SpoilageData> type = Shelflife.SPOILAGE.get();
        SpoilageData targetData = target.get(type);
        if (targetData == null) return;   // 防御：不变量被破坏时宁可不写，也不能凭空造组件

        SpoilageData averaged = averaged(targetData, sourceData, target.getCount(), amount);
        if (averaged != null) {
            target.set(type, averaged);
        }
    }

    /** 现从活着的来源堆上取组件。适用于"判定与写入之间没有动过来源堆"的调用点。 */
    public static void averageBeforeMerge(ItemStack target, ItemStack source, int amount) {
        averageBeforeMerge(target, source.get(Shelflife.SPOILAGE.get()), amount);
    }

    /**
     * 算出加权平均后的数据，<b>不写任何东西</b>。
     *
     * <p>单独拆出来是因为有些合并点**不能往原堆上写**：拖拽分配走的是
     * {@code slot.setByPlayer(dragSource.copyWithCount(n))} —— 槽位里原本那一堆会被整体<b>替换</b>掉，
     * 所以平均值必须写进"将要写进去的那个新堆"里，而不是旧堆上。
     *
     * @param targetCount 目标堆合并前的数量（不是合并后的）
     * @param amount      即将并入的数量，不是 source 的总数 —— 目标堆可能只装得下一部分
     * @return {@code targetData} 为 null 或参数不合法时原样返回
     */
    @Nullable
    public static SpoilageData averaged(SpoilageData targetData, @Nullable SpoilageData sourceData,
                                        int targetCount, int amount) {
        if (targetData == null || amount <= 0) return targetData;
        int mergedCount = targetCount + amount;
        if (mergedCount <= 0) return targetData;

        int targetSpoilage = targetData.currentSpoilage();
        int sourceSpoilage = sourceData != null ? sourceData.currentSpoilage() : 0;

        long weighted = (long) targetSpoilage * targetCount + (long) sourceSpoilage * amount;
        // 时间戳沿用 target 的：合并点里拿不到 Level，也就算不出"当前时间"。
        // 好在容器里的物品每次开箱都会被统一打戳，所以同容器内合并时两边时间戳本来就相同，这个选择是精确的；
        // 反而若把时间戳清成"未定"，下次结算会当作全新物品，把两次开箱之间的那段时间整个跳过。
        return new SpoilageData((int) (weighted / mergedCount), targetData.maxSpoilage(),
                targetData.storedTimestamp());
    }

    /**
     * 拖拽分配专用：把加权平均写进那个**即将覆盖槽位**的新堆。
     *
     * <p>{@code replacement} 是原版刚 {@code copyWithCount} 出来的新对象，独占、可以随便改。
     * 判定用 {@code replacement}（它就是来源堆的副本）当 source、槽位里原有的那一堆当 target ——
     * 和别的合并点一致：留下来的那个才是 target。
     *
     * @param added 这次实际放进去的数量（= 新堆数量 − 槽位原有数量），≤ 0 时什么都不做
     */
    public static void averageIntoReplacement(ItemStack replacement, ItemStack targetBefore,
                                              ItemStack source, int added) {
        if (added <= 0) return;
        if (!canStackTogether(targetBefore, source)) return;

        DataComponentType<SpoilageData> type = Shelflife.SPOILAGE.get();
        SpoilageData averaged = averaged(targetBefore.get(type), source.get(type),
                targetBefore.getCount(), added);
        if (averaged != null) {
            replacement.set(type, averaged);
        }
    }
}
