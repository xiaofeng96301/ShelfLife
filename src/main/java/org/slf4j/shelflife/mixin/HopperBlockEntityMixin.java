package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 漏斗往容器里搬物品时的合并（{@code tryMoveInItem}）。
 *
 * <p>判定（{@code canMergeItems}）和写入（{@code grow}）都在 {@code tryMoveInItem} 里，但判定是个独立的
 * 静态方法，从外面取不到它的结果。这里包裹的是 {@code tryMoveInItem} 内部**对 canMergeItems 的那次调用** ——
 * 这个位置两个堆都是调用参数，取起来干净，不用去猜局部变量序号。
 *
 * <p><b>来源的保质期必须在判定那一刻就取下来</b>，不能等到写入时再读 —— 原版顺序是
 * {@code stack.shrink(j)} 在前、{@code itemstack.grow(j)} 在后，而 shrink 到 0 的堆
 * 因为 {@code ItemStack.getComponents()} 的短路而读不出组件（返回 null）。
 * 等到 grow 再读，来源会被当成"全新"，把目标堆稀释成一半：表现为漏斗每往里送一份食物，
 * 箱里那堆的保质期就凭空翻一倍。所以这里分享的是<b>取好的 {@link SpoilageData}</b>，
 * 不是 {@code ItemStack} 引用 —— 分享引用等于把"读到 null"的时机问题留在了原地。
 *
 * <p>{@code @Share} 的作用域是一次方法调用，不会串到别的漏斗上去。
 *
 * <p>本处不写 {@code ordinal}：{@code grow} 有两个调用点（shrink 内部也是发 {@code grow(负数)}），
 * 但两者可以靠符号区分 —— 负数那一支在 {@code averaged} 里直接早退。宁可这样，也不去赌字节码里
 * 谁先谁后（这正是本次踩的坑）。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @WrapOperation(
            method = "tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;canMergeItems(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenCanMerge(ItemStack target, ItemStack source, Operation<Boolean> original,
                                                   @Share("spoilageSource") LocalRef<SpoilageData> spoilageSource) {
        // 无条件先取下来：此刻来源堆还完好，再晚一步 shrink 就把它清空了
        spoilageSource.set(source.get(Shelflife.SPOILAGE.get()));

        if (original.call(target, source)) return true;
        return SpoilageMerge.canStackTogether(target, source);
    }

    @WrapOperation(
            method = "tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;grow(I)V"))
    private static void shelflife$averageOnMerge(ItemStack target, int amount, Operation<Void> original,
                                                 @Share("spoilageSource") LocalRef<SpoilageData> spoilageSource) {
        // 负数那一支是 shrink(j) 内部的 grow(-j)（接收者是来源堆），在 averaged 里会被 amount <= 0 挡掉
        SpoilageData sourceData = spoilageSource.get();
        if (sourceData != null) {
            SpoilageMerge.averageBeforeMerge(target, sourceData, amount);
        }
        original.call(target, amount);
    }
}
