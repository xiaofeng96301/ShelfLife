package org.slf4j.shelflife.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
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
import org.slf4j.shelflife.logic.InventorySpoilage;
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
        // 这是**写**操作，只能在服务端做。调用方不必自己判端 —— 方块实体有些方法两端都会跑，
        // 让它们每次都得记得加 isClientSide 判断，迟早有人漏
        if (level.isClientSide) return false;
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

    /**
     * 按给定位置<b>此刻</b>的环境，结算整个容器里的所有格子。
     *
     * <p><b>这是给"动态容器"用的：容器自己的状态要变了（通电→断电、燃料烧完、开盖），
     * 在改变状态之前调一次。</b>因为本模组的腐烂是<b>检查点模型</b> —— 物品身上只有一个
     * "上次结算时刻 + 当时的值"，它表示不了"这段间隔里先后有两个倍率"。所以有一条硬规则：
     * <b>任何会改变倍率的事件，都必须在那一刻结算一次。</b>
     *
     * <p>调用时机很关键：<b>必须在改变自己的状态之前调</b>。此刻采样读到的还是旧环境，
     * 结清的就是"旧倍率那一段时间"；改完状态之后产生的都是新倍率的账。
     * 反过来（先改状态再调）会把整段间隔都按新倍率算，等于白冻或者凭空加速。
     *
     * <pre>{@code
     * public void setPowered(boolean powered) {
     *     if (powered == this.powered) return;
     *     ShelfLifeApi.settleContainer(this, level, worldPosition, getBlockState().getBlock());
     *     this.powered = powered;      // 结清之后才改
     * }
     * }</pre>
     *
     * <p><b>不调会怎样</b>：如果这个容器当时<b>正被玩家开着</b>，模组自己会在 1 秒内发现倍率变了
     * 并补一次结算（见 {@code PlayerRefreshTracker}），所以那种情况下不调也没事。
     * 但如果<b>没人开着</b>，就没有任何东西会观察到这次变化 —— 要等到下次有人开箱或往外抽东西时，
     * 才会用那时候（新）的倍率把整段间隔算一遍。这就是上面那条硬规则要挡的事。
     * 模组<b>不会</b>主动扫描世界上的容器来发现这种变化（那正是本模组刻意避开的开销），
     * 所以这个"我变了"必须由容器自己说。
     *
     * @param container      要结算的容器（一般就是方块实体自己）
     * @param containerBlock 容器所在的方块，用于取环境修正；可传 {@code null}（只用群系环境）
     * @return 被改写的格子数
     */
    public static int settleContainer(Container container, Level level, BlockPos pos,
                                      @Nullable Block containerBlock) {
        if (container == null || level == null) return 0;
        // 同上：写操作，只在服务端做。见 settleStack 的注释
        if (level.isClientSide) return 0;
        float rate = EnvironmentSampler.rateAt(level, pos, containerBlock);
        return InventorySpoilage.settleContainer(container, rate, level.getGameTime());
    }

    @Nullable
    private static SpoilageConfig configOf(ItemStack stack) {
        return stack.isEmpty() ? null : SpoilageManager.get(stack.getItem());
    }
}
