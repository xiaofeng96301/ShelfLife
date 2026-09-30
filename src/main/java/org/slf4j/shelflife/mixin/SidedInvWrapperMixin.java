package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 同上，用于<b>带朝向的</b>容器包装（双联箱、熔炉这类 {@code WorldlyContainer}）。
 *
 * <p>原版方块的能力是分面的 —— 大多数方块拿到的是 {@link SidedInvWrapper} 而不是
 * {@code InvWrapper}，所以两个都得挂。塞入侧的逻辑和那边一字不差，理由见
 * {@link InvWrapperMixin} 的注释（尤其是"这个包装的合并方向是后到的组件赢"那条）。
 *
 * <p>抽取侧同样**不用挂**：{@code SidedInvWrapper.extractItem} 源码第 176 行就是
 * {@code this.inv.removeItem(slot1, m)}，上游的 {@code BaseContainerBlockEntityMixin} 已经管了。
 */
@Mixin(SidedInvWrapper.class)
public abstract class SidedInvWrapperMixin {

    @WrapOperation(
            method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenInsert(ItemStack incoming, ItemStack resident, Operation<Boolean> original,
                                                 @Share("residentSpoilage") LocalRef<SpoilageData> residentSpoilage) {
        residentSpoilage.set(resident.get(Shelflife.SPOILAGE.get()));

        if (original.call(incoming, resident)) return true;
        return SpoilageMerge.canStackTogether(resident, incoming);
    }

    @WrapOperation(
            method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;grow(I)V"))
    private static void shelflife$averageOnInsert(ItemStack merged, int residentCount, Operation<Void> original,
                                                  @Share("residentSpoilage") LocalRef<SpoilageData> residentSpoilage) {
        SpoilageData resident = residentSpoilage.get();
        if (resident != null) {
            SpoilageData averaged = SpoilageMerge.averaged(resident, merged.get(Shelflife.SPOILAGE.get()),
                    residentCount, merged.getCount());
            if (averaged != null) {
                merged.set(Shelflife.SPOILAGE.get(), averaged);
            }
        }
        original.call(merged, residentCount);
    }
}
