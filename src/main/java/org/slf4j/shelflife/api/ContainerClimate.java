package org.slf4j.shelflife.api;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.data.ContainerModifier;

/**
 * 让**方块实体**自己报"此刻这里多冷/多热"。
 *
 * <p>数据包里的 {@code spoilage_env} 只能表达<b>静态</b>的冷源（"这个方块是冰箱"）。
 * 表达不了"通电时才冷"—— 那需要方块知道自己此刻的状态。这个接口就是那个口子：
 * 方块实体实现它，本模组每次采样都会问一次。
 *
 * <p><b>优先级</b>：实体的返回值<b>覆盖</b>数据包给这个方块写的修正；
 * 返回 {@code null} 表示"此刻我没有修正"，这时回落到数据包（没有就回落到群系）。
 * 于是"通电时 -25、断电时照常温"这种逻辑可以写成：
 *
 * <pre>{@code
 * public class MyFridgeBlockEntity extends BlockEntity implements ContainerClimate {
 *     @Override
 *     public ContainerModifier climate() {
 *         return powered ? new ContainerModifier(-4.0F, 0.0F, Optional.empty()) : null;
 *     }
 * }
 * }</pre>
 *
 * <p>只对<b>方块实体承载的容器</b>生效 —— 木桶、潜影盒那种内部用 {@code SimpleContainer}
 * 承载的没有实体，问不到。
 */
public interface ContainerClimate {

    /**
     * 此刻这个容器施加的温湿度修正；返回 {@code null} 表示不施加（回落到数据包）。
     *
     * <p>这个方法会被**每次环境采样**调用（背包侧每秒~十秒一次、开箱时一次），
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
