package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * NeoForge 给原版容器提供的物品能力包装 —— <b>所有走能力存取的模组都从这儿过</b>。
 *
 * <p><b>塞入侧</b>（{@code insertItem}）：管道 / 溜槽 / 别的机器往容器里放东西时走这里，
 * 两个问题都要管：
 * <ol>
 *   <li>原版判定 {@code isSameItemSameComponents} 把"腐坏值不同"当成不同物品 →
 *       <b>压根合不上</b>，东西被原样退回去 → 放宽判定</li>
 *   <li>合上了也只留容器里那堆的组件，新来的腐坏值被丢掉 → 算加权平均</li>
 * </ol>
 *
 * <p>⚠️ <b>这个包装的合并方向是"后到的组件赢"</b>：{@code copy = stack.copy(); copy.grow(已有数量)}
 * —— 写进槽位的是一份<b>新来那堆的副本</b>，和原版容器"留下来的那个赢"正好相反。
 * 所以平均不能往"原有的那堆"上写（写完就被丢掉了），必须写在那份 copy 上；
 * 而且要用低层原语 {@link SpoilageMerge#averaged} 让"留下来的那个"当 target ——
 * 这样 max 和时间戳沿用容器里原有的，和其余合并点保持一致。
 *
 * <p>⚠️ <b>光挂这里不够。</b>漏斗推入时 NeoForge 的 {@code VanillaInventoryCodeHooks.insertStack}
 * 在调用 {@code insertItem} 之前还有一道**它自己硬编码**的 {@code isSameItemSameComponents} ——
 * 那道过不去的话，这个槽一个字节都不会写，东西会被塞进下一个空槽（表现：永远不堆叠）。
 * 两处都要放宽，见 {@link VanillaInventoryCodeHooksMixin}。
 *
 * <p><b>抽取侧不用挂。</b>{@code SidedInvWrapper.extractItem} 内部就是
 * {@code inv.removeItem(...)}（源码第 176 行），{@code BaseContainerBlockEntityMixin}
 * 已经在更上游的 {@code removeItem} 上结清了；在这儿再挂一次是纯冗余，所以这里没有它。
 * （{@code removeItem} 还是"玩家手动从箱子里拿"的必经之路，上游那一条不能删。）
 */
@Mixin(InvWrapper.class)
public abstract class InvWrapperMixin {

    @WrapOperation(
            method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$widenInsert(ItemStack incoming, ItemStack resident, Operation<Boolean> original,
                                                 @Share("residentSpoilage") LocalRef<SpoilageData> residentSpoilage) {
        // 参数顺序是"新来的"在前、"容器里那个"在后（源码第 50 行）。
        // 先把它记下来：下面真正写入时拿到的是"新来的副本"，读不到容器里这一堆
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
            // merged = 新来那堆的副本（数量 = 这一份新来的），residentCount = 容器里原有的数量。
            // 让 resident 当 target：加权平均是 (原数量×原值 + 新数量×新值) ÷ 总数，
            // 而 max 与时间戳沿用 resident 的 —— 和漏斗/菜单那几条合并点一个口径
            SpoilageData averaged = SpoilageMerge.averaged(resident, merged.get(Shelflife.SPOILAGE.get()),
                    residentCount, merged.getCount());
            if (averaged != null) {
                // merged 是刚 copy 出来的、紧接着就会被写进槽位，所以写在它身上是有效的
                merged.set(Shelflife.SPOILAGE.get(), averaged);
            }
        }
        // 这两处 grow 都在 `if (!simulate)` 里，所以纯查询永远走不到这儿
        original.call(merged, residentCount);
    }
}
