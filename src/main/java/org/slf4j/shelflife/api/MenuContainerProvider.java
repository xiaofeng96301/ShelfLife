package org.slf4j.shelflife.api;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 让<b>菜单自己</b>回答"我背后是哪个方块实体"。
 *
 * <p>和 {@link ContainerLocators} 的区别只有一个：<b>这个不用注册</b>。
 * 你自己的菜单类实现它就行 —— 本模组每次要反查位置时都会问一遍。
 * 只有"菜单不是自己的"（别人的模组写死了）才需要去 {@code ContainerLocators.register}。
 *
 * <p>为什么要问菜单而不是槽位：很多模组的机器用 {@code SlotItemHandler}，
 * 它的槽位容器是一个共享的空 {@code SimpleContainer}，**不是方块实体** ——
 * 靠遍历槽位永远查不到（详见 {@link ContainerLocator} 的注释）。而菜单自己当然知道
 * 自己开的是哪台机器。
 *
 * <p>典型实现就是原样返回构造时存下来的那个方块实体：
 *
 * <pre>{@code
 * public class MyMachineMenu extends AbstractContainerMenu implements MenuContainerProvider {
 *     private final MyMachineBlockEntity machine;
 *
 *     @Override
 *     public @Nullable BlockEntity shelfLifeContainer() {
 *         return machine;
 *     }
 * }
 * }</pre>
 */
public interface MenuContainerProvider {

    /**
     * 这个菜单背后的容器方块实体；没有就返回 {@code null}。
     *
     * <p>会在<b>开箱时</b>和**玩家开着它时的每秒复查**里被调用，所以别在这里做重活。
     */
    @Nullable
    BlockEntity shelfLifeContainer();
}
