package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 漏斗往容器里搬物品时的合并（{@code tryMoveInItem}）。
 *
 * <p>判定（{@code canMergeItems}）和写入（{@code grow}）都在 {@code tryMoveInItem} 里，但判定是个独立的
 * 静态方法，从外面取不到它的结果。这里包裹的是 {@code tryMoveInItem} 内部**对 canMergeItems 的那次调用** ——
 * 这个位置两个堆都是调用参数，取起来干净，不用去猜局部变量序号。
 *
 * <p>来源堆通过 {@code @Share} 从判定处传到写入处。{@code @Share} 的作用域是一次方法调用，
 * 不会串到别的漏斗上去。
 *
 * <p>{@code grow} 触发时 {@code shrink} 已经执行过了（原版顺序是 shrink 再 grow），
 * 但 {@link SpoilageMerge#averageBeforeMerge} 只用来源堆的**组件值**、不用它的数量，所以顺序不影响结果。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @WrapOperation(
            method = "tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;canMergeItems(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenCanMerge(ItemStack target, ItemStack source, Operation<Boolean> original,
                                                   @Share("spoilageSource") LocalRef<ItemStack> spoilageSource) {
        if (original.call(target, source)) return true;
        if (!SpoilageMerge.canStackTogether(target, source)) return false;

        spoilageSource.set(source);
        return true;
    }

    @WrapOperation(
            method = "tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;grow(I)V"))
    private static void shelflife$averageOnMerge(ItemStack target, int amount, Operation<Void> original,
                                                 @Share("spoilageSource") LocalRef<ItemStack> spoilageSource) {
        ItemStack source = spoilageSource.get();
        if (source != null) {
            SpoilageMerge.averageBeforeMerge(target, source, amount);
        }
        original.call(target, amount);
    }
}
