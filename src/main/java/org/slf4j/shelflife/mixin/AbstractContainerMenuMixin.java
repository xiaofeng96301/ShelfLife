package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 所有原版菜单的 shift 点击合并都汇聚在 {@code moveItemStackTo}（26 个菜单的 quickMoveStack 都在调它）。
 *
 * <p>这里只包裹那一次 {@code isSameItemSameComponents} 判定，同时完成"放宽判定"和"算加权平均"：
 * 判定返回 true 后紧跟的就是写入分支，所以在这个位置算平均值读到的仍是合并前的数量。
 * 用 {@code @WrapOperation} 而不是 {@code @Redirect}，其它模组对同一个调用的包裹才能照常串联。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @WrapOperation(
            method = "moveItemStackTo",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean shelflife$spoilageAwareMerge(ItemStack source, ItemStack target, Operation<Boolean> original,
                                                 @Local Slot slot) {
        if (original.call(source, target)) return true;
        // 注意参数顺序：SpoilageMerge 的第一个参数必须是"合并目标"，
        // 因为 canStackTogether 要求目标已带保质期数据（见该方法注释）
        if (!SpoilageMerge.canStackTogether(target, source)) return false;

        // 判定放宽成"可合并"了，顺手把平均值算好。
        // 这里读到的 target/source 都还是合并前的状态，slot.getMaxStackSize 决定了实际能并进来多少
        // （可能是部分合并，所以不能用 source 的总数当权重）。
        int capacity = slot.getMaxStackSize(target);
        int amount = Math.min(source.getCount(), capacity - target.getCount());
        if (amount > 0) {
            SpoilageMerge.averageBeforeMerge(target, source, amount);
        }
        return true;
    }

    /**
     * 鼠标"拿着 A 点 B"的合并判定 —— 和 {@code moveItemStackTo}（shift 快捷移动）是两处<b>独立</b>的代码。
     *
     * <pre>
     * slot.mayPlace(carried) → isSameItemSameComponents(slotStack, carried)   ← 这里
     *                            ├ true  → slot.safeInsert(...)   ← 由 SlotMixin 接管（含加权平均）
     *                            └ false → 两个堆互换位置          ← 看起来就是"点了没堆叠"
     * </pre>
     *
     * <p>所以这里<b>只放宽判定、一个数都不碰</b>：判定放开后原版会走 {@code safeInsert}，
     * 那条路已经被 {@link SlotMixin} 接管了，在这里再算一遍平均值会算两次。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", ordinal = 0))
    private boolean shelflife$spoilageAwareClick(ItemStack slotStack, ItemStack carried, Operation<Boolean> original) {
        if (original.call(slotStack, carried)) return true;
        // 合并目标（留下来的那一堆）是槽位里的：canStackTogether 要求目标已带保质期数据
        return SpoilageMerge.canStackTogether(slotStack, carried);
    }

    /**
     * 槽位放不下时（{@code mayPlace} 为 false，比如熔炉输出槽）的合并：物品并进<b>光标上那一堆</b>。
     *
     * <p>这条路走的是 {@code tryRemove} + {@code grow}，<b>不经过</b> {@code safeInsert}，
     * 没有别人接手算平均值，所以这里得自己算。数量镜像原版紧随其后的调用：
     * {@code tryRemove(slotStack.getCount(), carried.getMaxStackSize() - carried.getCount(), player)}。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", ordinal = 1))
    private boolean shelflife$spoilageAwareClickIntoCarried(ItemStack slotStack, ItemStack carried, Operation<Boolean> original) {
        if (original.call(slotStack, carried)) return true;
        // 这一处的合并方向相反：物品并进光标上的那一堆，所以目标是 carried
        if (!SpoilageMerge.canStackTogether(carried, slotStack)) return false;

        int amount = Math.min(slotStack.getCount(), carried.getMaxStackSize() - carried.getCount());
        if (amount > 0) {
            SpoilageMerge.averageBeforeMerge(carried, slotStack, amount);
        }
        return true;
    }
}
