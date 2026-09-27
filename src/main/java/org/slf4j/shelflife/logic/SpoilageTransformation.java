package org.slf4j.shelflife.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;

/**
 * 保质期耗尽后把食物换成数据包指定的产物（腐烂剩菜 / 腐烂肉类之类）。
 *
 * <p>产物由规则的 {@code result} 字段指定；不写时用 {@link SpoilageConfig#DEFAULT_RESULT}（腐烂剩菜）。
 * 写 {@code "minecraft:air"} 可以显式关闭转化 —— 那种规则下食物保质期耗尽后保持原样，
 * 只是 tooltip 显示"已腐烂"。
 *
 * <p><b>只能由持有槽位的地方调用。</b>{@code ItemStack} 的物品类型不可变（没有 {@code setItem}），
 * 所以转化必须靠"替换整个堆"完成 —— 调用方拿到返回值后要把它写回背包格或容器槽。
 */
public final class SpoilageTransformation {

    private SpoilageTransformation() {
    }

    /**
     * 若该堆保质期已耗尽且规则指定了产物，返回替换用的新堆；返回 {@code null} 表示无需替换。
     *
     * <p>调用前必须已经 settle 过，否则读到的检查点可能是旧的，会漏掉"刚好这一轮保质期耗尽"的情况。
     */
    @Nullable
    public static ItemStack replacementFor(ItemStack stack, SpoilageConfig config) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null || !data.isSpoiled()) return null;

        // "minecraft:air" 是约定的"不转化"写法：注册表查空气返回 Items.AIR，在这里被挡掉
        Item result = BuiltInRegistries.ITEM.get(config.result());
        if (result == Items.AIR) return null;

        // 数量原样保留。产物不带保质期数据 —— 它本身就是"已经烂掉"的状态，不再有保质期，
        // 也就不在配置表里，不会被再次打戳。
        return new ItemStack(result, stack.getCount());
    }
}
