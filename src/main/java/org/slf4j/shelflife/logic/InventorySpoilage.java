package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;

import java.util.Optional;

/**
 * 对<b>背包和容器里的物品</b>做结算与刷新 —— "写"的那一半。
 *
 * <p>和 {@link SpoilageSettlement}（纯计算，只认数据不认位置）分开，是为了让两件事能各自独立地改：
 * 这里是遍历、替换、标记（**在哪写、写什么**），算术全在那个类里（**算出来是多少**）。
 * 事件入口在 {@link SpoilageEvents}，什么时候判定在 {@link PlayerRefreshTracker}。
 *
 * <p>对外模组要用的那句"搬运时结算一下"在 {@code api/ShelfLifeApi}，内部这两条路最终都走
 * {@link SpoilageSettlement}，所以语义只有一套。
 */
public final class InventorySpoilage {

    private InventorySpoilage() {
    }

    // ------------------------------------------------------------------ 结算（写回检查点）

    /**
     * 结算玩家背包 36 格，倍率按玩家所在位置现采。
     *
     * <p>两个作用：给还没有保质期数据的食物写上时钟起点；把已有检查点推进到当前时刻。
     */
    public static void settle(ServerPlayer player) {
        if (SpoilageManager.isEmpty()) return;
        settle(player, EnvironmentSampler.rateAt(player.level(), player.blockPosition(), null),
                player.level().getGameTime());
    }

    /** 用指定倍率结算背包。环境复查发现倍率变化时，用**旧倍率**调这个。 */
    public static void settle(ServerPlayer player, float rate, long now) {
        // 用下标而不是 for-each：保质期耗尽时需要替换整个堆
        NonNullList<ItemStack> slots = player.getInventory().items;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;

            SpoilageSettlement.settle(stack, config, rate, now);
            ItemStack replacement = SpoilageTransformation.replacementFor(stack, config);
            if (replacement != null) {
                slots.set(i, replacement);
            }
        }
    }

    /**
     * 结算菜单里属于容器的那些格子。
     *
     * <p>跳过玩家自己的背包格（交给 {@link #settle(ServerPlayer, float, long)}）：这样每次开箱
     * 只推进容器里那部分，实现"顺序累计、不重复扣同一段时间"。
     */
    public static void settleMenu(AbstractContainerMenu menu, Inventory playerInventory, float rate, long now) {
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;

            boolean changed = SpoilageSettlement.settle(stack, config, rate, now);
            // 结算后如果刚好保质期耗尽，就地换成数据包指定的产物。必须由这里替换整个堆 ——
            // ItemStack 的物品类型不可变，改不了"它是什么物品"
            ItemStack replacement = SpoilageTransformation.replacementFor(stack, config);
            if (replacement != null) {
                slot.set(replacement);
                changed = true;
            }

            if (changed) {
                // 标记容器已修改，保证进存档。
                // 网络同步不用管：ServerPlayer.tick 每 tick 都会 containerMenu.broadcastChanges()。
                slot.setChanged();
            }
        }
    }

    // ------------------------------------------------------------------ 被动刷新（只判断，不重复写）

    /** 刷新整个背包：补时钟起点 + 把到期的换成腐烂物。 */
    public static void refresh(ServerPlayer player, float rate, long now) {
        // 用下标而不是 for-each：需要替换整个堆
        NonNullList<ItemStack> slots = player.getInventory().items;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;
            ItemStack replacement = refreshItem(stack, config, rate, now);
            if (replacement != null) {
                slots.set(i, replacement);
            }
        }
    }

    /** 同上，用于当前打开的容器。 */
    public static void refreshMenu(AbstractContainerMenu menu, Inventory playerInventory, float rate, long now) {
        for (Slot slot : menu.slots) {
            if (slot.container == playerInventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;
            ItemStack replacement = refreshItem(stack, config, rate, now);
            if (replacement != null) {
                slot.set(replacement);
                slot.setChanged();
            }
        }
    }

    /**
     * 刷新一堆食物。只有两种情况下会写，其余一律返回 {@code null} 什么都不碰：
     *
     * <ol>
     *   <li><b>还没有时钟起点的</b> —— 补上起点，<b>只命中一次</b>。组件压根不存在
     *       （{@code /give}、创造模式、从没结算过的容器里拿出来的）和时间戳还是 0 都算。</li>
     *   <li><b>已经到期的</b> —— 先结算一次（把"其实早就到 max 了"写进组件），再返回产物。
     *       这点写入紧接着就随整堆被替换而消失。</li>
     * </ol>
     *
     * <p>判断走的是只读的 {@link SpoilageSettlement#effective}（按检查点实时推算），
     * 所以没到期的物品不会被写到 —— 那是这套设计的关键：检查点模型下显示层本来就按时间差
     * 实时推算，把每一格都重写一遍只会白白触发客户端同步。
     */
    @Nullable
    private static ItemStack refreshItem(ItemStack stack, SpoilageConfig config, float rate, long now) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null || data.storedTimestamp() == SpoilageData.NO_TIMESTAMP) {
            // 进了玩家背包就当"从现在开始算"。没有这一步，/give 或创造模式拿到的食物
            // 会一直停在"全新"，而被动刷新只判断不写，永远不会给它补上起点
            SpoilageSettlement.settle(stack, config, rate, now);
            return null;
        }

        if (SpoilageSettlement.effective(data, config, rate, now) < data.maxSpoilage()) return null;

        SpoilageSettlement.settle(stack, config, rate, now);
        return SpoilageTransformation.replacementFor(stack, config);
    }

    // ------------------------------------------------------------------ 位置反查

    /**
     * 容器的位置配上**它自己的** level。
     *
     * <p>这两者必须成对使用：Sable 载具里的方块实体报的是<b>结构内部的 plot 坐标</b>，
     * 拿它去玩家的 level 里查会查到世界上一格无关的方块（群系和 createishot 温度全错，
     * 而且不会报错，只会静默给出错误答案）。
     */
    public record ContainerLocation(Level level, BlockPos pos) {
    }

    /** 从菜单的槽位反查容器所在的位置。找到第一个由方块实体承载的槽位即可。 */
    public static Optional<ContainerLocation> locate(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof BlockEntity blockEntity) {
                Level beLevel = blockEntity.getLevel();
                if (beLevel != null) {
                    return Optional.of(new ContainerLocation(beLevel, blockEntity.getBlockPos()));
                }
            }
        }
        return Optional.empty();
    }
}
