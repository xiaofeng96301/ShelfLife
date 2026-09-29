package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.ContainerTransfer;

/**
 * 物品被从容器里**取走**时结算一次 —— 原版漏斗抽取走的就是 {@code removeItem}。
 *
 * <p>箱子、木桶、熔炉、漏斗自己…… 所有 {@link BaseContainerBlockEntity} 的子类都在这儿，
 * 一条 hook 全覆盖。见 {@link ContainerTransfer} 说明为什么必须结算。
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class BaseContainerBlockEntityMixin {

    @ModifyReturnValue(
            method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;",
            at = @At("RETURN"))
    private ItemStack shelflife$settleOnExtract(ItemStack taken) {
        return ContainerTransfer.afterExtraction(taken, (Container) (Object) this);
    }
}
