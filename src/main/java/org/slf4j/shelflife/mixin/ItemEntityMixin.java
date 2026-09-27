package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 地上的掉落物合并（{@code tryToMerge} → {@code areMergable} → {@code merge}）。
 *
 * <p>这里的 3 参 {@code merge} 是整条掉落物合并路径唯一的写入点，而且两个堆都是参数 ——
 * 是全套合并点里最干净的一个。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    /** 放宽"能否合并"的判定，否则保质期不同的两堆在原版看来就不是同一种物品。 */
    @WrapOperation(
            method = "areMergable(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenMergable(ItemStack destinationStack, ItemStack originStack, Operation<Boolean> original) {
        return original.call(destinationStack, originStack) || SpoilageMerge.canStackTogether(destinationStack, originStack);
    }

    /** 原版只会保留 destination 的组件（把 origin 的保质期直接丢掉），这里改成加权平均。 */
    @Inject(
            method = "merge(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At("HEAD"),
            cancellable = true)
    private static void shelflife$spoilageAwareMerge(ItemStack destinationStack, ItemStack originStack, int amount,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        if (!SpoilageMerge.needsCustomMerge(destinationStack, originStack)) return;

        int moved = Math.min(Math.min(destinationStack.getMaxStackSize(), amount) - destinationStack.getCount(),
                originStack.getCount());
        if (moved <= 0) return;

        SpoilageMerge.averageBeforeMerge(destinationStack, originStack, moved);
        // 镜像原版：结果是一个新的堆，调用方会拿它替换掉 destination 实体的物品
        ItemStack merged = destinationStack.copyWithCount(destinationStack.getCount() + moved);
        originStack.shrink(moved);
        cir.setReturnValue(merged);
    }
}
