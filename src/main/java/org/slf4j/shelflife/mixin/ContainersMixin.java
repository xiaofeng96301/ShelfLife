package org.slf4j.shelflife.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.shelflife.api.ShelfLifeApi;

/**
 * 容器被砸掉 / 炸掉时，先把里面的东西按<b>容器自己的环境</b>结清，再让它掉出来。
 *
 * <p><b>为什么需要它。</b>{@code Containers.dropContents} 读内容物用的是
 * {@code inventory.getItem(i)} + {@code copy()}（源码第 22-23 行），<b>全程不调 {@code removeItem}</b> ——
 * 而本模组的抽取结算恰恰挂在 {@code removeItem} 上（见 {@code ContainerTransfer}）。
 * 所以"砸箱子"这条路一个钩子都碰不到：食物在箱子里待的那段时间一笔账都没记，
 * 等落地被捡起来才由目标环境补算 —— 从冰箱里砸出来的鱼会按你砸箱子时脚下的倍率烂，不是冰箱的。
 *
 * <p><b>为什么钩的是那个私有的重载。</b>三个入口
 * （{@code dropContents(level, pos, container)}、{@code dropContents(level, entity, container)}、
 * 以及 {@code onRemove} 调的 {@code dropContentsOnDestroy}）最后都汇进
 * {@code dropContents(Level, double, double, double, Container)}。钩公开的那几个反而会在
 * "砸箱子"这条路上结清**两次**（{@code dropContentsOnDestroy} 自己又会调一次公开的）。
 * 钩这个私有的汇点：一次，且全覆盖。
 *
 * <p><b>为什么方块要用方块实体自己记的那个，而不是去世界里读。</b>
 * {@code dropContentsOnDestroy} 是从 {@code Block#onRemove} 里调的，而那时区块里**已经换上新方块**了
 * （原版把旧状态当参数传进来，正是为了让它还能知道"原来是什么"）。
 * 这时候 {@code level.getBlockState(pos)} 拿到的是空气 → 容器自己的倍率修正
 * （{@code container_rules} 那套）就丢了，退化成"按纯群系结清"。
 * 方块实体缓存的 {@code getBlockState()} 才是"我原来是什么方块"。
 *
 * <p>位置和 level 也必须成对取（{@code be.getBlockPos()} + {@code be.getLevel()}）——
 * Sable 那类载具里的方块实体报的是结构内部的坐标，配世界的 level 会查到一格无关的方块。
 *
 * <p><b>一处刻意的不精确</b>：这里用的是 {@code ShelfLifeApi.settleContainer}，而它对"只出不进的
 * 输出槽"没有保护（它按 {@code Container} 走，拿不到 {@code Slot}）。放在这里没问题：
 * 这些入口的语境都是"容器正要被清空 / 销毁"，往哪个格子写组件都不会再影响它了。
 * 别把这条结论推广到活着的机器上（见 {@code ShelfLifeApi.settleContainer} 的注释）。
 *
 * <p>没覆盖的：{@code dropContents(Level, BlockPos, NonNullList)} —— 那个重载手里没有
 * {@code Container}，也就无从知道该按哪个环境结清。它丢的是合成出来的成品，
 * 本来也没有"在某个容器里待过"的账要记。
 */
@Mixin(Containers.class)
public abstract class ContainersMixin {

    @Inject(
            method = "dropContents(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/Container;)V",
            at = @At("HEAD"))
    private static void shelflife$settleBeforeDrop(Level level, double x, double y, double z,
                                                   Container inventory, CallbackInfo ci) {
        if (inventory instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
            ShelfLifeApi.settleContainer(inventory, blockEntity.getLevel(), blockEntity.getBlockPos(),
                    blockEntity.getBlockState().getBlock());
            return;
        }
        // 不是方块实体（比如带箱子的矿车）：退回按坐标采样。拿不到"容器方块"，
        // 环境修正会少一截，但总比完全不结算好
        BlockPos pos = BlockPos.containing(x, y, z);
        ShelfLifeApi.settleContainer(inventory, level, pos, level.getBlockState(pos).getBlock());
    }
}
