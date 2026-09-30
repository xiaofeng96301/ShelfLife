package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 机器自己的物品栏（{@link ItemStackHandler}）被塞入时的合并。
 *
 * <p><b>为什么需要它。</b>模组机器暴露的往往是裸的 {@code ItemStackHandler} —— 它<b>不是</b>
 * {@code Container}，所以本模组挂在原版容器上的那些合并点（菜单、漏斗、{@code SimpleContainer}）
 * 全都覆盖不到它，挂在 {@code InvWrapper}/{@code SidedInvWrapper} 上的也覆盖不到
 * （那两个包装的是 {@code Container}）。于是从管道 / 溜槽 / 别的机器往里塞东西时：
 *
 * <ol>
 *   <li>{@code isSameItemSameComponents} 判定把"腐坏值不同"当成不同物品 → <b>压根合不上</b>，
 *       东西被原样退回去</li>
 *   <li>就算合上了，机器那边保留的是自己的组件，<b>新来的腐坏值被丢掉</b> —— 也就是
 *       "不停往里塞新鲜的，那堆永远不会变烂"</li>
 * </ol>
 *
 * <p>两条都要管：先把判定放宽（和别处一样用 {@link SpoilageMerge#canStackTogether}），
 * 再在真正的写入点算加权平均。
 *
 * <p>写入点是 {@code existing.grow(...)}（源码第 74 行）。这里和漏斗不同：<b>来源堆在这个分支里
 * 没有被清空</b>（grow 的是槽位里那一堆，方法返回的是另一个副本），所以本来可以直接读它的组件；
 * 但为了和漏斗那边长一个样、也不依赖"此刻它还没被改动"这种细节，还是在判定那一刻
 * 用 {@code @Share} 把它的数据取下来。
 *
 * <p>加权平均写在 {@code existing}（槽位里留下来的那一堆）上，所以 max 和时间戳沿用它的 ——
 * 和其余合并点一致。
 */
@Mixin(ItemStackHandler.class)
public abstract class ItemStackHandlerMixin {

    @WrapOperation(
            method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenInsert(ItemStack incoming, ItemStack existing, Operation<Boolean> original,
                                                 @Share("incomingSpoilage") LocalRef<SpoilageData> incomingSpoilage) {
        // 参数顺序是"新来的"在前、"槽位里那个"在后（源码第 59 行）。
        // 合并目标是留下来的那个，所以 canStackTogether 要先收 existing
        incomingSpoilage.set(incoming.get(org.slf4j.shelflife.Shelflife.SPOILAGE.get()));

        if (original.call(incoming, existing)) return true;
        return SpoilageMerge.canStackTogether(existing, incoming);
    }

    @WrapOperation(
            method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;grow(I)V"))
    private static void shelflife$averageOnInsert(ItemStack existing, int amount, Operation<Void> original,
                                                  @Share("incomingSpoilage") LocalRef<SpoilageData> incomingSpoilage) {
        // 这一句在 `if (!simulate)` 里（源码第 70-77 行），所以 simulate 的调用永远不会走到这儿 ——
        // 不用额外判断，查询绝不会写东西
        SpoilageMerge.averageBeforeMerge(existing, incomingSpoilage.get(), amount);
        original.call(existing, amount);
    }
}
