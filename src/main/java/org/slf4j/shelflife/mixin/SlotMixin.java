package org.slf4j.shelflife.mixin;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * GUI 里用光标点击合并走的是 {@code Slot#safeInsert}，不经过 {@code moveItemStackTo} ——
 * 只包 {@link AbstractContainerMenuMixin} 会漏掉最常见的鼠标左键拖动合并。
 *
 * <p>这里用 {@code @Inject(HEAD) + cancel} 而不是包裹判定：{@code safeInsert} 主体只有 8 行，
 * 而"合并数量"是方法内部的局部变量，从外面 hooks 不到。只在保质期确实不同时接管并取消原版，
 * 其余情况一律放行，把影响面压到最小。
 */
@Mixin(Slot.class)
public abstract class SlotMixin {

    @Inject(
            method = "safeInsert(Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true)
    private void shelflife$spoilageAwareSafeInsert(ItemStack stack, int increment, CallbackInfoReturnable<ItemStack> cir) {
        if (stack.isEmpty()) return;

        Slot self = (Slot) (Object) this;
        ItemStack target = self.getItem();
        if (target.isEmpty()) return;   // 空槽位是"放入"不是"合并"，没有平均值可算
        if (!SpoilageMerge.needsCustomMerge(target, stack)) return;   // 组件一模一样时让原版自己处理
        if (!self.mayPlace(stack)) return;

        int amount = Math.min(Math.min(increment, stack.getCount()), self.getMaxStackSize(stack) - target.getCount());
        if (amount > 0) {
            // 镜像原版 safeInsert 的合并分支，只多一步加权平均
            SpoilageMerge.averageBeforeMerge(target, stack, amount);
            stack.shrink(amount);
            target.grow(amount);
            self.setByPlayer(target);
        }
        cir.setReturnValue(stack);
    }
}
