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
 * {@code SimpleContainer} 自己的合并路径（{@code addItem} → {@code moveItemsToOccupiedSlotsWithSameType}）。
 *
 * <p>它和菜单、{@code Inventory}、漏斗**都不是同一条路**：{@code SimpleContainer} 是"容器"接口
 * 最常用的通用实现，模组自制的机器/背包大量直接用它（原版也有几处），
 * 它内部的合并判定和写入都得单独挂。
 *
 * <p>⚠️ <b>别拿"哪个方块"来推断谁走这条路。</b>箱子 / 木桶 / 漏斗 / 熔炉都是
 * {@code BaseContainerBlockEntity} 那一支 —— 走菜单和漏斗的钩子；
 * <b>漏斗矿车也不是</b>（它是 {@code AbstractMinecartContainer}，但因为实现了 {@code Hopper}，
 * 复用的是 {@code HopperBlockEntity} 里那几个静态方法 —— {@code suckInItems} 里的
 * {@code tryTakeInItemFromSlot} 和 {@code addItem}，所以由漏斗那条钩子覆盖）。
 * 判断标准只有一个：<b>这个容器内部是不是用 {@code SimpleContainer} 装的</b>。
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
