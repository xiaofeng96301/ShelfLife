package org.slf4j.shelflife.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把双联箱那两个半个箱子掏出来。
 *
 * <p>双联箱在菜单里是以 {@link CompoundContainer} 的形式出现的（{@code ChestBlock} 的
 * {@code acceptDouble} 里 {@code new CompoundContainer(左, 右)}），而它**不是方块实体** ——
 * 于是 {@code InventorySpoilage.locate} 的"找第一个由方块实体承载的槽位"那一步整个漏掉，
 * 一路退化到"按玩家位置采样"：两个箱子里的食物都按你站的地方算账，冷箱 / 容器修正全不生效，
 * 而且只打一条 warn，不报错。
 *
 * <p>两个字段在 {@code CompoundContainer} 里是 {@code private final}，没有 getter，只能这样拿。
 * 取半个就够：双联箱的两半一定是相邻的同种箱子，群系和容器修正完全一致。
 *
 * <p>（接口只能待在 mixin 包里 —— mixin 配置只扫这个包。用它的地方是
 * {@code InventorySpoilage#locate}。）
 */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor {

    /**
     * 名字带 {@code shelflife$} 前缀是刻意的：{@code getContainer1()} 这种"自然的"名字
     * 别的模组很可能也在往同一个类上加，两处撞上就是 Mixin 冲突 + 游戏起不来。
     * （{@code @Accessor} 明确写了字段名，所以方法名叫什么都可以。）
     */
    @Accessor("container1")
    Container shelflife$container1();
}
