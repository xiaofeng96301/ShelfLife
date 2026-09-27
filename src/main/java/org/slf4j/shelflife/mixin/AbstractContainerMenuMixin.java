package org.slf4j.shelflife.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.slf4j.shelflife.logic.SpoilageMerge;

/**
 * 所有原版菜单的 shift 点击合并都汇聚在 {@code moveItemStackTo}（26 个菜单的 quickMoveStack 都在调它）。
 *
 * <p>这里只包裹那一次 {@code isSameItemSameComponents} 判定，同时完成"放宽判定"和"算加权平均"：
 * 判定返回 true 后紧跟的就是写入分支，所以在这个位置算平均值读到的仍是合并前的数量。
 * 用 {@code @WrapOperation} 而不是 {@code @Redirect}，其它模组对同一个调用的包裹才能照常串联。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @WrapOperation(
            method = "moveItemStackTo",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean shelflife$spoilageAwareMerge(ItemStack source, ItemStack target, Operation<Boolean> original,
                                                 @Local Slot slot) {
        if (original.call(source, target)) return true;
        // 注意参数顺序：SpoilageMerge 的第一个参数必须是"合并目标"，
        // 因为 canStackTogether 要求目标已带保质期数据（见该方法注释）
        if (!SpoilageMerge.canStackTogether(target, source)) return false;

        // 判定放宽成"可合并"了，顺手把平均值算好。
        // 这里读到的 target/source 都还是合并前的状态，slot.getMaxStackSize 决定了实际能并进来多少
        // （可能是部分合并，所以不能用 source 的总数当权重）。
        int capacity = slot.getMaxStackSize(target);
        int amount = Math.min(source.getCount(), capacity - target.getCount());
        if (amount > 0) {
            SpoilageMerge.averageBeforeMerge(target, source, amount);
        }
        return true;
    }

    /**
     * 鼠标"拿着 A 点 B"的合并判定 —— 和 {@code moveItemStackTo}（shift 快捷移动）是两处<b>独立</b>的代码。
     *
     * <pre>
     * slot.mayPlace(carried) → isSameItemSameComponents(slotStack, carried)   ← 这里
     *                            ├ true  → slot.safeInsert(...)   ← 由 SlotMixin 接管（含加权平均）
     *                            └ false → 两个堆互换位置          ← 看起来就是"点了没堆叠"
     * </pre>
     *
     * <p>所以这里<b>只放宽判定、一个数都不碰</b>：判定放开后原版会走 {@code safeInsert}，
     * 那条路已经被 {@link SlotMixin} 接管了，在这里再算一遍平均值会算两次。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", ordinal = 0))
    private boolean shelflife$spoilageAwareClick(ItemStack slotStack, ItemStack carried, Operation<Boolean> original) {
        if (original.call(slotStack, carried)) return true;
        // 合并目标（留下来的那一堆）是槽位里的：canStackTogether 要求目标已带保质期数据
        return SpoilageMerge.canStackTogether(slotStack, carried);
    }

    /**
     * 槽位放不下时（{@code mayPlace} 为 false，比如熔炉输出槽）的合并：物品并进<b>光标上那一堆</b>。
     *
     * <p>这条路走的是 {@code tryRemove} + {@code grow}，<b>不经过</b> {@code safeInsert}，
     * 没有别人接手算平均值，所以这里得自己算。数量镜像原版紧随其后的调用：
     * {@code tryRemove(slotStack.getCount(), carried.getMaxStackSize() - carried.getCount(), player)}。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z", ordinal = 1))
    private boolean shelflife$spoilageAwareClickIntoCarried(ItemStack slotStack, ItemStack carried, Operation<Boolean> original) {
        if (original.call(slotStack, carried)) return true;
        // 这一处的合并方向相反：物品并进光标上的那一堆，所以目标是 carried
        if (!SpoilageMerge.canStackTogether(carried, slotStack)) return false;

        int amount = Math.min(slotStack.getCount(), carried.getMaxStackSize() - carried.getCount());
        if (amount > 0) {
            SpoilageMerge.averageBeforeMerge(carried, slotStack, amount);
        }
        return true;
    }

    /**
     * 拖拽分配（按住左键拖过一串槽位）和双击收集（PICKUP_ALL）共用的那道门。
     *
     * <p>它是个静态方法、被调用的地方不止一处，所以放宽**必须加在这里**，加在调用点会漏。
     * 本处只放宽判定 —— 真正的数值处理在下面两个包裹里，因为那两条路的合并方向与
     * {@code moveItemStackTo} 不同，光开门会让玩家能"洗保质期"。
     */
    @WrapOperation(
            method = "canItemQuickReplace(Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/item/ItemStack;Z)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean shelflife$spoilageAwareQuickReplace(ItemStack carried, ItemStack slotStack,
                                                               Operation<Boolean> original) {
        if (original.call(carried, slotStack)) return true;
        // 目标是槽位里那一堆（留下来的那个）
        return SpoilageMerge.canStackTogether(slotStack, carried);
    }

    /**
     * 拖拽分配：原版是 {@code slot.setByPlayer(dragSource.copyWithCount(n))} ——
     * <b>把整个槽位替换成来源堆的副本</b>，根本不是合并。所以加权平均必须写进那个新堆，
     * 写旧堆没有意义（紧接着就被丢掉了）。
     *
     * <p>ordinal 0 = 字节码里第一个 {@code setByPlayer}，也就是这一处（后面几个是 SWAP 用的）。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;setByPlayer(Lnet/minecraft/world/item/ItemStack;)V", ordinal = 0))
    private void shelflife$averageDragged(Slot slot, ItemStack placed, Operation<Void> original) {
        ItemStack before = slot.getItem();   // 还没被写，是槽位里原有的那一堆
        int added = placed.getCount() - before.getCount();
        // 来源就是光标上那一堆：本次调用期间原版只动了它的副本，getCarried() 还是原件
        ItemStack source = ((AbstractContainerMenu) (Object) this).getCarried();
        SpoilageMerge.averageIntoReplacement(placed, before, source, added);
        original.call(slot, placed);
    }

    /**
     * 双击收集：物品并进<b>光标上那一堆</b>，方向和其它合并点相反。
     * 在 {@code grow} 之前把平均值算好，后面那句 {@code carried.grow(...)} 就是正常累加。
     *
     * <p>ordinal 1 = 字节码里第二个 {@code safeTake}（第一个是 THROW 投掷用的，不参与合并）。
     */
    @WrapOperation(
            method = "doClick(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;safeTake(IILnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/item/ItemStack;", ordinal = 1))
    private ItemStack shelflife$averagePickedUp(Slot slot, int amount, int maxAmount, Player player,
                                                Operation<ItemStack> original) {
        ItemStack taken = original.call(slot, amount, maxAmount, player);
        if (!taken.isEmpty()) {
            ItemStack target = ((AbstractContainerMenu) (Object) this).getCarried();
            if (SpoilageMerge.canStackTogether(target, taken)) {
                SpoilageMerge.averageBeforeMerge(target, taken, taken.getCount());
            }
        }
        return taken;
    }
}
