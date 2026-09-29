package org.slf4j.shelflife.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.logic.EnvironmentSampler;
import org.slf4j.shelflife.logic.SpoilageSettlement;
import org.slf4j.shelflife.logic.SpoilageTransformation;

/**
 * 给其他模组用的门面。所有跨模组调用都从这里进，别去碰内部类。
 *
 * <p>典型用途是<b>搬运物品</b>：本模组的结算挂在"物品进出容器"这些时刻上，
 * 而别的模组用管道 / 溜槽 / 自己的机器搬东西时，那些时刻我们看不见 ——
 * 于是物品"在某个容器里待了多久"会被记到错误的环境上（最典型的是冷箱：
 * 从普通箱子抽进冰箱，之前累积的时间会被当成在冰箱里度过）。
 * 在抽取时调一次 {@link #settleStack} 就能对上。
 *
 * <p>本模组自己没有规则（没装数据包）时，这里所有方法都是安全的空操作。
 */
public final class ShelfLifeApi {

    private ShelfLifeApi() {
    }

    /** 这个物品有没有保质期规则。没有的话它永远不会腐烂，也就不会被本模组碰。 */
    public static boolean isManaged(Item item) {
        return SpoilageManager.get(item) != null;
    }

    /**
     * 这个物品堆当前腐坏到多少 —— <b>按检查点惰性推算</b>，不写回，也不需要环境。
     *
     * <p>注意它按"最后一次结算时的环境"外推，所以可能和 {@link #spoilageOf(ItemStack, Level, BlockPos, Block)}
     * 略有出入。要精确值就用那个带位置的。
     *
     * @return 未管理 / 还没有数据时返回 {@code -1}
     */
    public static int spoilageOf(ItemStack stack) {
        SpoilageConfig config = configOf(stack);
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (config == null || data == null) return -1;
        return data.currentSpoilage();
    }

    /** 同上，但按给定位置的环境实时推算。 */
    public static int spoilageOf(ItemStack stack, Level level, BlockPos pos, @Nullable Block containerBlock) {
        SpoilageConfig config = configOf(stack);
        if (config == null) return -1;
        float rate = EnvironmentSampler.rateAt(level, pos, containerBlock);
        return (int) SpoilageSettlement.effectiveFractional(stack.get(Shelflife.SPOILAGE.get()), config,
                rate, level.getGameTime());
    }

    /** 这个物品堆的保质期上限（也就是"腐坏到多少算烂透"）。未管理返回 {@code -1}。 */
    public static int maxSpoilageOf(ItemStack stack) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        return data == null ? -1 : data.maxSpoilage();
    }

    /** 某个位置的腐烂倍率（含容器修正与湿度；温度来源是 createishot 时会去问它）。 */
    public static float rateAt(Level level, BlockPos pos, @Nullable Block containerBlock) {
        return EnvironmentSampler.rateAt(level, pos, containerBlock);
    }

    /**
     * 用给定位置的环境结算这一堆 —— <b>搬运物品时该调的就是它</b>。
     *
     * <p>调用时机：物品<b>离开</b>某个容器时，用**来源容器**的位置调一次。
     * 这样"它在来源容器里待的那段时间"就按那个容器的环境记了账，之后进冰箱才会从零开始。
     *
     * <p>只结算，不替换物品 —— 如果结算后发现它刚好烂透，调用方应该接着调
     * {@link #replacementIfSpoiled} 并把结果放进目标槽位（{@code ItemStack} 的物品类型不可变，
     * 想让它"变成别的东西"只能整个换掉）。
     *
     * @return 组件是否真的被改动了（调用方可据此决定要不要标记容器已修改）
     */
    public static boolean settleStack(ItemStack stack, Level level, BlockPos pos, @Nullable Block containerBlock) {
        if (stack.isEmpty()) return false;
        SpoilageConfig config = configOf(stack);
        if (config == null) return false;

        float rate = EnvironmentSampler.rateAt(level, pos, containerBlock);
        return SpoilageSettlement.settle(stack, config, rate, level.getGameTime());
    }

    /**
     * 如果这一堆已经烂透，返回它该变成的东西，否则返回 {@code null}。
     *
     * <p>配合 {@link #settleStack} 用：先结算，再问这个，非 null 就把返回的堆放进槽位。
     */
    @Nullable
    public static ItemStack replacementIfSpoiled(ItemStack stack) {
        SpoilageConfig config = configOf(stack);
        return config == null ? null : SpoilageTransformation.replacementFor(stack, config);
    }

    @Nullable
    private static SpoilageConfig configOf(ItemStack stack) {
        return stack.isEmpty() ? null : SpoilageManager.get(stack.getItem());
    }
}
