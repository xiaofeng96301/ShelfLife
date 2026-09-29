package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.ContainerTransfer;

/**
 * NeoForge 给原版容器提供的物品能力包装 —— <b>所有走能力抽取的模组都从这儿过</b>。
 *
 * <p>Create、Mekanism、Pipez 抽取箱子里的物品时拿到的就是这个包装（Create 甚至在
 * {@code AllArmInteractionPointTypes} 里直接 {@code new SidedInvWrapper(...)}），
 * 所以钩住它等于一次性兼容了"现在和以后"的管子 —— 不需要为每家模组写一份。
 *
 * <p>{@code simulate = true} 的调用是**纯粹的询问**，没有真的抽走东西，绝不能结算。
 */
@Mixin(InvWrapper.class)
public abstract class InvWrapperMixin {

    /** 包装的那个容器。私有字段，用 accessor 拿出来判断它是不是方块实体（能问到位置）。 */
    @Accessor("inv")
    public abstract Container shelflife$wrapped();

    @ModifyReturnValue(
            method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private ItemStack shelflife$settleOnExtract(ItemStack taken, int slot, int amount, boolean simulate) {
        if (simulate) return taken;
        return ContainerTransfer.afterExtraction(taken, shelflife$wrapped());
    }
}
