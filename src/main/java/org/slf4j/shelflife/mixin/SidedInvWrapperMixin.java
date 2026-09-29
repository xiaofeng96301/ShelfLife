package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.WorldlyContainer;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.ContainerTransfer;

/**
 * 同上，用于**带朝向的**容器包装（双联箱、熔炉这类 {@link WorldlyContainer}）。
 *
 * <p>原版方块的能力是分面的 —— 大多数方块拿到的是 {@link SidedInvWrapper} 而不是
 * {@code InvWrapper}，所以两个都得挂。
 */
@Mixin(SidedInvWrapper.class)
public abstract class SidedInvWrapperMixin {

    @Accessor("inv")
    public abstract WorldlyContainer shelflife$wrapped();

    @ModifyReturnValue(
            method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private ItemStack shelflife$settleOnExtract(ItemStack taken, int slot, int amount, boolean simulate) {
        if (simulate) return taken;
        return ContainerTransfer.afterExtraction(taken, shelflife$wrapped());
    }
}
