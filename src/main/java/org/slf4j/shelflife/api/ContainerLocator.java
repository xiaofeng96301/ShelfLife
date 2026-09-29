package org.slf4j.shelflife.api;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 告诉 ShelfLife"这个菜单背后是哪个方块实体"。
 *
 * <p><b>为什么需要它。</b>本模组默认靠遍历菜单槽位、找 {@code slot.container} 是方块实体的那个
 * 来反查位置。这条路对原版容器（箱子、木桶、熔炉、漏斗……）都成立，但有两类真实容器
 * <b>永远不成立</b>，而且失败得完全静默：
 *
 * <ul>
 *   <li><b>用 {@code SlotItemHandler} 的机器</b>（Mekanism 以及一大票模组）——
 *       {@code SlotItemHandler} 的槽位容器是一个<b>共享的空 {@code SimpleContainer}</b>，
 *       不是方块实体，按构造必然查不到</li>
 *   <li><b>把两个容器包起来的复合容器</b>（双联箱、Balm 那类"懒人厨房冰箱"）</li>
 * </ul>
 *
 * <p>后果不只是提示框显示的倍率不对：开箱时那次结算（{@code settleMenu}）是**真写物品组件**的，
 * 它会把容器里的食物按<b>玩家脚下</b>的常温记账。所以这是一个实打实的算错账。
 *
 * <p><b>怎么用：</b>在 mod 的 init 阶段注册一个（顺序 = 优先级，先注册的先问）：
 *
 * <pre>{@code
 * // 自己的菜单最简单：连注册都不用，让菜单实现 MenuContainerProvider 就行
 * ContainerLocators.register(menu ->
 *         menu instanceof MyMachineMenu machine ? machine.getBlockEntity() : null);
 * }</pre>
 *
 * <p>注册的类会引用 ShelfLife 的 API，所以调用点要按 {@code ShelfLifeApi} 那套做**可选依赖**
 * 保护（`compileOnly` + `type = "optional"` + 把注册再包一层单独的类）。
 */
@FunctionalInterface
public interface ContainerLocator {

    /**
     * 从这个菜单反查它背后的容器方块实体。
     *
     * @return 查不到返回 {@code null}（交给下一个定位器 / 槽位扫描）
     */
    @Nullable
    BlockEntity locate(AbstractContainerMenu menu);
}
