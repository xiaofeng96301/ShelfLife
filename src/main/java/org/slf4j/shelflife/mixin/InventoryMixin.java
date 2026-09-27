package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 玩家背包的合并路径 —— 捡起地上的物品、{@code Inventory#add}、战利品发放都走这里。
 *
 * <p>和别的合并点不同，这里的"能否合并"判定和真正的写入不在同一个方法里：
 * 判定在 {@code hasRemainingSpaceForItem}，写入在 {@code addResource}。所以两处都要动 ——
 * 只放宽判定的话，{@code addResource} 会照常 merge 却把来源的保质期丢掉，比不合并还糟。
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {

    /** 放宽 {@code getSlotWithRemainingSpace} 用来挑目标格子的判定。 */
    @WrapOperation(
            method = "hasRemainingSpaceForItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean shelflife$widenRemainingSpace(ItemStack destination, ItemStack origin, Operation<Boolean> original) {
        return original.call(destination, origin) || SpoilageMerge.canStackTogether(destination, origin);
    }

    /** 命中合并分支时接管，补上加权平均 —— 其余情况一律放行给原版。 */
    // 必须写全描述符：Inventory 有两个 addResource 重载，只写方法名会挑中 addResource(ItemStack)
    @Inject(
            method = "addResource(ILnet/minecraft/world/item/ItemStack;)I",
            at = @At("HEAD"),
            cancellable = true)
    private void shelflife$spoilageAwareAddResource(int slot, ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        Inventory self = (Inventory) (Object) this;
        ItemStack destination = self.getItem(slot);
        // 空槽位是"放入"不是"合并"，没有平均值可算，来源的组件本来就会被整份复制过去
        if (destination.isEmpty()) return;
        if (!SpoilageMerge.needsCustomMerge(destination, stack)) return;

        int sourceCount = stack.getCount();
        int freeSpace = self.getMaxStackSize(destination) - destination.getCount();
        int moved = Math.min(sourceCount, freeSpace);
        if (moved <= 0) return;

        // 镜像原版 addResource 的合并分支
        SpoilageMerge.averageBeforeMerge(destination, stack, moved);
        int remaining = sourceCount - moved;
        destination.grow(moved);
        destination.setPopTime(5);
        cir.setReturnValue(remaining);
    }
}
