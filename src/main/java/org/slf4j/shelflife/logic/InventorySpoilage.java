package org.slf4j.shelflife.logic;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.api.ContainerLocators;
import org.slf4j.shelflife.api.MenuContainerProvider;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.mixin.CompoundContainerAccessor;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已经提醒过"反查不到容器位置"的菜单类。每个类只说一次，免得刷屏。 */
    private static final Set<Class<?>> WARNED_MENUS = ConcurrentHashMap.newKeySet();

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
            if (isOutputOnly(slot, stack)) continue;
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

    /**
     * 结算<b>任意 {@link Container}</b> 的全部格子 —— 不经过菜单，直接对着容器写。
     *
     * <p>给"容器的环境要变了，先按<b>旧</b>环境结清"用（见 {@code ShelfLifeApi#settleContainer}）。
     * 和 {@link #settleMenu} 的区别只是操作对象：那边是菜单槽位（要跳过玩家背包、要 {@code slot.set}），
     * 这边是容器本身。
     *
     * @return 被改写的格子数，调用方可据此决定要不要额外标记
     */
    public static int settleContainer(Container container, float rate, long now) {
        int changed = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;

            boolean changedHere = SpoilageSettlement.settle(stack, config, rate, now);
            ItemStack replacement = SpoilageTransformation.replacementFor(stack, config);
            if (replacement != null) {
                // 刚好在这一轮烂透：整堆换掉，和别处一样 —— ItemStack 改不了"它是什么物品"
                container.setItem(i, replacement);
                changedHere = true;
            }
            if (changedHere) {
                container.setChanged();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 这个槽位是"只出不进"的输出槽吗 —— 是的话我们一律不碰。
     *
     * <p><b>为什么输出槽碰不得：</b>原版（以及很多模组）判断"还能不能再产出一个"用的是
     * {@code ItemStack.isSameItemSameComponents(输出槽里的东西, 这次要产的产物)}。
     * 产物是刚构造出来的、<b>没有保质期组件</b>的；而我们在输出槽上打一个组件，就让它俩
     * "不是同一个物品"了 —— 后果是熔炉<b>彻底卡住</b>：烧出第一个之后 {@code canBurn} 永远是 false，
     * 再也不会烧第二个。爆出来的现象是"一组鱼只有第一条能烧出来"。
     *
     * <p>合成台和各种机器的输出槽是同一个道理：它们的"能不能继续产出"也多半是这么比的。
     *
     * <p>判定用 {@link Slot#mayPlace}：只出不进的槽位放不进东西，所以返回 {@code false}。
     * 不用 {@code instanceof ResultSlot} 之类的类型判断 —— 那样只覆盖原版，覆盖不到模组的机器。
     *
     * <p>代价：极少数"只接受特定物品"的槽位（燃料槽之类）里如果被塞进了别的东西，
     * 那件东西不会被结算。少结算一次只是"那段时间按下次结算的环境记"，比卡住一个机器好得多。
     */
    private static boolean isOutputOnly(Slot slot, ItemStack stack) {
        return !slot.mayPlace(stack);
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
            if (isOutputOnly(slot, stack)) continue;
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

    /**
     * 从菜单反查容器所在的位置。
     *
     * <p>顺序是**先显式、后猜**：
     * <ol>
     *   <li>菜单自己实现了 {@link MenuContainerProvider}，或者别的模组在
     *       {@link ContainerLocators} 里注册过定位器 —— 直接问它们</li>
     *   <li>再退回遍历槽位，找第一个由方块实体承载的（原版箱子/木桶/熔炉/漏斗都在这条路上）</li>
     * </ol>
     *
     * <p><b>双联箱要在第 2 条里单独接一手</b>：它在菜单里是一个 {@code CompoundContainer}，
     * 本身不是方块实体，直着判会整个漏掉（见 {@link #blockEntityBehind}）。
     *
     * <p>第 2 条对 {@code SlotItemHandler} 那类机器容器**永远猜不中** ——
     * 它的槽位容器是一个共享的空 {@code SimpleContainer}。所以查不到时这里会**出声**：
     * 查不到的后果不是"显示不对"，而是开箱那次结算会按玩家脚下的倍率给容器里的食物记账，
     * 也就是静默地算错账。每个菜单类只提醒一次。
     */
    public static Optional<ContainerLocation> locate(AbstractContainerMenu menu) {
        // ① 显式声明的（最了解自己的是菜单自己，其次是注册过的模组）
        BlockEntity explicit = ContainerLocators.find(menu);
        if (explicit != null) {
            Level explicitLevel = explicit.getLevel();
            if (explicitLevel != null) {
                return Optional.of(new ContainerLocation(explicitLevel, explicit.getBlockPos()));
            }
        }

        // ② 猜：找第一个由方块实体承载的槽位
        for (Slot slot : menu.slots) {
            BlockEntity blockEntity = blockEntityBehind(slot.container);
            if (blockEntity == null) continue;
            Level beLevel = blockEntity.getLevel();
            if (beLevel != null) {
                return Optional.of(new ContainerLocation(beLevel, blockEntity.getBlockPos()));
            }
        }

        // ③ 都没有：提醒一次。这次是靠逐行读源码才找到的，一条日志能省两小时
        if (WARNED_MENUS.add(menu.getClass())) {
            LOGGER.warn("[ShelfLife] 反查不到容器位置：{}，将退化为按玩家位置采样（容器修正不会生效）。"
                            + "如果是你的菜单，让它实现 MenuContainerProvider，或在 ContainerLocators 里注册一个定位器",
                    menu.getClass().getName());
        }
        return Optional.empty();
    }

    /**
     * 这个槽位的容器背后是哪个方块实体 —— 不是方块实体就返回 {@code null}。
     *
     * <p>比直接 {@code instanceof BlockEntity} 多一层，是为了<b>双联箱</b>：它在菜单里是一个
     * {@code CompoundContainer} —— 两个半个箱子被包在里面，而它自己不是方块实体。
     * 不接这一手的话，双联箱会一路走进"查不到"那条分支：两个箱子里的食物都按玩家脚下的倍率记账，
     * 冷箱 / {@code container_rules} 的修正全部不生效，而且只留一条 warn。
     *
     * <p>取哪一半都行：双联箱的两半一定是相邻的同种箱子，群系和容器修正必然一致。
     */
    @Nullable
    private static BlockEntity blockEntityBehind(Container container) {
        if (container instanceof BlockEntity blockEntity) return blockEntity;
        if (container instanceof CompoundContainerAccessor accessor
                && accessor.shelflife$container1() instanceof BlockEntity blockEntity) {
            return blockEntity;
        }
        return null;
    }
}
