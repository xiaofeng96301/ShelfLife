package org.slf4j.shelflife.api;

import com.mojang.logging.LogUtils;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link ContainerLocator} 的注册表。见那个接口的注释：为什么需要、以及怎么注册。
 *
 * <p>问的顺序是<b>先具体、后一般</b>：
 * <ol>
 *   <li>菜单自己实现了 {@link MenuContainerProvider}？直接问它（最了解自己的就是它自己，
 *       而且<strong>零注册</strong>）</li>
 *   <li>再按注册顺序问 {@link ContainerLocator}（先注册的先问）</li>
 *   <li>都没结果，才回到本模组默认的槽位扫描</li>
 * </ol>
 *
 * <p>注册表只在 mod 初始化阶段写、之后只读，所以用 {@link CopyOnWriteArrayList} 就够，
 * 不需要加锁。注册了就不能撤销 —— 模组加载期注册一次是唯一的用法。
 */
public final class ContainerLocators {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<ContainerLocator> LOCATORS = new CopyOnWriteArrayList<>();

    private ContainerLocators() {
    }

    /**
     * 注册一个定位器。<b>在 mod 的 init 阶段调</b>；注册顺序 = 优先级（先注册的先被问）。
     */
    public static void register(ContainerLocator locator) {
        LOCATORS.add(locator);
    }

    /**
     * 反查容器。**给 ShelfLife 内部用**，其他模组不需要调这个（自己去实现
     * {@link MenuContainerProvider} 或注册 {@link ContainerLocator} 就行）。
     *
     * @return 查不到返回 {@code null}
     */
    @Nullable
    public static BlockEntity find(AbstractContainerMenu menu) {
        // ① 菜单自己知道得最清楚
        if (menu instanceof MenuContainerProvider provider) {
            BlockEntity fromMenu = provider.shelfLifeContainer();
            if (fromMenu != null) return fromMenu;
        }

        // ② 注册表。**每个定位器都要兜异常**：一个模组写坏了不该让所有容器的定位一起失效，
        // 而且这种坏法本来就该出声
        for (ContainerLocator locator : LOCATORS) {
            try {
                BlockEntity located = locator.locate(menu);
                if (located != null) return located;
            } catch (Throwable t) {
                LOGGER.warn("[ShelfLife] 一个容器定位器抛异常，已跳过：{}", locator, t);
            }
        }
        return null;
    }

    /** 现在注册了几个定位器。仅供自测/排查。 */
    public static int size() {
        return LOCATORS.size();
    }
}
