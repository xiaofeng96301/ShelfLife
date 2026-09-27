package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * {@code SimpleContainer#addItem} 的合并路径 —— 桶、漏斗矿车等一批容器直接用它，
 * 不走菜单也不走 {@code Inventory}。
 */
@Mixin(SimpleContainer.class)
public abstract class SimpleContainerMixin {

    /** 放宽"同类型"判定，否则保质期不同的两堆在 {@code moveItemToOccupiedSlotsWithSameType} 里配不上对。 */
    @WrapOperation(
            method = "moveItemToOccupiedSlotsWithSameType(Lnet/minecraft/world/item/ItemStack;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean shelflife$widenSameType(ItemStack target, ItemStack source, Operation<Boolean> original) {
        return original.call(target, source) || SpoilageMerge.canStackTogether(target, source);
    }

    /** 原版 {@code other.grow(j)} 会把 source 的保质期丢掉；这里补上加权平均。 */
    @Inject(
            method = "moveItemsBetweenStacks(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void shelflife$spoilageAwareMove(ItemStack stack, ItemStack other, CallbackInfo ci) {
        if (!SpoilageMerge.needsCustomMerge(other, stack)) return;

        SimpleContainer self = (SimpleContainer) (Object) this;
        int capacity = self.getMaxStackSize(other);
        int moved = Math.min(stack.getCount(), capacity - other.getCount());
        if (moved <= 0) return;

        SpoilageMerge.averageBeforeMerge(other, stack, moved);
        other.grow(moved);
        stack.shrink(moved);
        self.setChanged();
        ci.cancel();
    }
}
