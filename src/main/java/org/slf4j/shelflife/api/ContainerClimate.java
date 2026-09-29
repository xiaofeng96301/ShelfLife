package org.slf4j.shelflife.api;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.data.ContainerModifier;

/**
 * 让**方块实体**自己报"此刻这里多冷/多热"。
 *
 * <h2>先确认你真的需要它</h2>
 *
 * <p><b>固定温度的容器（"我这个方块就是个冰箱"）不需要实现这个接口</b> ——
 * 在数据包里写一行就够了，一行 Java 都不用：
 *
 * <pre>
 * // data/&lt;你的命名空间&gt;/spoilage_env/zz_mypack.json
 * { "containers": { "mymod:fridge": { "temperature": -4.0 } } }
 * </pre>
 *
 * <p>只有<b>动态</b>的容器才需要这个接口 —— 状态会自己变的那种：
 * 通电才冷、燃料烧完就停、开盖时保温失效。
 *
 * <h2>优先级</h2>
 *
 * <p>实体的返回值<b>覆盖</b>数据包给这个方块写的修正；返回 {@code null} 表示
 * "此刻我没有修正"，这时回落到数据包（没有就回落到群系）。
 *
 * <h2>⚠️ 状态变了必须自己结清（否则会算错账）</h2>
 *
 * <p>本模组的腐烂是<b>检查点模型</b>：物品身上只有一个"上次结算时刻 + 当时的值"，
 * 表示不了"这段间隔里先后有两个倍率"。所以有一条硬规则：
 * <b>任何会改变倍率的事件，都必须在那一刻结算一次。</b>
 *
 * <p>实现本接口<b>不会</b>自动获得这条保证 —— {@link #climate()} 是纯查询，
 * 本模组问它的时候不碰容器里任何物品。所以状态翻转时必须<b>自己调一次</b>
 * {@code ShelfLifeApi.settleContainer}，而且在<b>改变状态之前</b>调。
 * <b>"通电"和"断电"是两个独立事件，两个方向都要调</b>：漏掉哪个方向，
 * 那一段间隔就会被按另一边的倍率记账。
 *
 * <pre>{@code
 * public void setPowered(boolean powered) {
 *     if (powered == this.powered) return;
 *     // 先按"旧"环境结清那一段时间；电平改了之后产生的才是新倍率的账
 *     ShelfLifeApi.settleContainer(this, level, worldPosition, getBlockState().getBlock());
 *     this.powered = powered;
 * }
 * }</pre>
 *
 * <p>容器<b>正被玩家开着</b>时，模组自己会在 1 秒内发现倍率变化并补一次结算
 * （见 {@code PlayerRefreshTracker}），那种情况下不调也不会错。但<b>没人开着</b>的时候
 * 没有任何东西会观察到这次变化 —— 要等到下次有人开箱或往外抽东西，才会拿那时候的倍率
 * 把整段间隔算一遍。模组不会主动扫世界上的容器（那正是本模组刻意避开的开销），
 * 所以"我变了"只能由容器自己说。
 *
 * <p>只对<b>方块实体承载的容器</b>生效 —— 木桶、潜影盒那种内部用 {@code SimpleContainer}
 * 承载的没有实体，问不到。
 */
public interface ContainerClimate {

    /**
     * 此刻这个容器施加的温湿度修正；返回 {@code null} 表示不施加（回落到数据包）。
     *
     * <p>这是一个<b>纯查询</b>：模组只把它当数据用，不会因此结算任何物品，
     * 也不会记住上一次的返回值 —— 每次采样都重新问，所以状态改了下一拍就能读到。
     * "状态变了要结清"是<b>你自己的责任</b>，见类注释里的
     * {@code ShelfLifeApi.settleContainer}。
     *
     * <p>调用频率：每次环境采样一次（背包侧每秒~十秒一次、开箱时一次、被抽取时一次），
     * 所以它必须便宜 —— 读几个字段可以，别在里面做遍历或查询。
     */
    @Nullable
    ContainerModifier climate();

    /** 便利方法：这个方块实体是否实现了本接口，实现了就取其修正。 */
    @Nullable
    static ContainerModifier climateOf(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof ContainerClimate climate ? climate.climate() : null;
    }
}
