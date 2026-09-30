package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * NeoForge 接管漏斗"推出去"时那道<b>硬编码</b>的合并判定。
 *
 * <p><b>为什么必须有这条。</b>漏斗往目标里推东西有<b>两条</b>路，取决于目标有没有物品能力：
 *
 * <ul>
 *   <li>目标不带能力（比如本模组的冷箱 —— {@code BaseContainerBlockEntity} 又不是
 *       {@code WorldlyContainer}）→ 走原版 {@code ejectItems} 的
 *       {@code addItem} → {@code tryMoveInItem}，由 {@link HopperBlockEntityMixin} 覆盖</li>
 *   <li>目标带能力（箱子、木桶、模组机器……）→ 在 {@code ejectItems} 第一行就被
 *       {@code VanillaInventoryCodeHooks.insertHook} <b>整段接管</b>，
 *       原版那条路一次都不执行</li>
 * </ul>
 *
 * <p>而 NeoForge 那条路里 {@code insertStack} 有<b>两道</b>门，第一道是能力层的
 * {@code insertItem(slot, stack, true)}（探测，由 InvWrapper/SidedInvWrapper 的钩子放宽了），
 * 第二道是它<b>自己硬编码</b>的：
 *
 * <pre>
 * } else if (ItemStack.isSameItemSameComponents(itemstack, stack)) {   // ← 这里
 *     stack = destInventory.insertItem(slot, stack, false);
 * }
 * </pre>
 *
 * <p>光放宽第一道是不够的：第二道过不去，这个槽就一个字节都不写，循环会继续去找<b>下一个空槽</b> ——
 * 表现就是"漏斗搬进来的食物<b>永远不堆叠</b>，每个占一个新格子"，而且每条的时间戳都不同
 * （抽取时各打各的），恰好证明它们本该合并。
 *
 * <p>所以这道也得放宽。放宽之后 {@code insertItem(slot, stack, false)} 才会被调到，
 * 而那一次会走到 {@code InvWrapper}/{@code SidedInvWrapper} 的 grow 钩子上 —— 那里的加权平均
 * 才是真正把值算对的地方。
 */
@Mixin(VanillaInventoryCodeHooks.class)
public abstract class VanillaInventoryCodeHooksMixin {

    @WrapOperation(
            method = "insertStack(Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/lang/Object;Lnet/neoforged/neoforge/items/IItemHandler;Lnet/minecraft/world/item/ItemStack;I)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenInsertStack(ItemStack resident, ItemStack arriving, Operation<Boolean> original) {
        // 参数顺序是"槽位里已有的"在前、"新来的"在后（源码第 135 行）。
        // 合并目标（留下来的那个）是 resident，所以 canStackTogether 先收它
        if (original.call(resident, arriving)) return true;
        return SpoilageMerge.canStackTogether(resident, arriving);
    }
}
