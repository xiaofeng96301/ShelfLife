package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.api.ShelfLifeApi;

/**
 * 物品**离开容器**那一刻的结算 —— 搬运路径的收敛点。
 *
 * <p>为什么需要它：本模组的结算挂在"物品进出容器"这些时刻上，而搬运物品的不只有玩家 ——
 * 漏斗、Create 的溜槽、各种管子都在干这件事。<b>如果不在离开时结算</b>，物品"在来源容器里
 * 待的那段时间"会被记到**目的地**的环境上。最典型的后果是冰箱：从普通箱子抽进冷箱，
 * 箱子里的那几个小时会被当成在冷箱里度过，等于白冻。
 *
 * <p>挂点只有三个，但它们覆盖了所有走"标准路线"的模组：
 * <ul>
 *   <li>{@code BaseContainerBlockEntity#removeItem} —— 原版 {@link Container} API，
 *       原版漏斗走的这条</li>
 *   <li>{@code InvWrapper#extractItem} / {@code SidedInvWrapper#extractItem} —— NeoForge
 *       给原版容器提供的物品能力包装。Create / Mekanism / Pipez 以及以后的新模组都从这儿过</li>
 * </ul>
 *
 * <p>覆盖不到的：模组<b>自己实现的</b> {@code IItemHandler}（比如某台机器内部的库存），
 * 以及拿不到位置的容器（真正的 {@code SimpleContainer}，它不属于任何方块实体）。
 * 这两类都只能放过 —— 少结算一次不会算错，只是那段时间按目的地的环境记。
 */
public final class ContainerTransfer {

    private ContainerTransfer() {
    }

    /**
     * 一个堆刚从 {@code source} 里被抽出来：用**来源容器**的环境结算它。
     *
     * @return 要交给抽取方的堆 —— 通常是原样返回；如果结算后刚好烂透，返回的是产物。
     *         必须把返回值用起来：{@code ItemStack} 的物品类型不可变，"变成别的东西"只能整堆换
     */
    public static ItemStack afterExtraction(ItemStack taken, @Nullable Container source) {
        if (taken.isEmpty() || source == null) return taken;
        // 问不到位置就放过：宁可少结算一次，也不能拿错误的位置去采样
        if (!(source instanceof BlockEntity blockEntity)) return taken;
        Level level = blockEntity.getLevel();
        if (level == null) return taken;

        BlockPos pos = blockEntity.getBlockPos();
        ShelfLifeApi.settleStack(taken, level, pos, level.getBlockState(pos).getBlock());

        // 结算完刚好烂透的话，交给抽取方的东西就该是产物了 ——
        // 否则它会被原样搬进目标容器，多待一段才变
        ItemStack replacement = ShelfLifeApi.replacementIfSpoiled(taken);
        return replacement != null ? replacement : taken;
    }
}
