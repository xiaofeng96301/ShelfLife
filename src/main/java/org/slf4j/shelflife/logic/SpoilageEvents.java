package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentReloadListener;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.data.SpoilageReloadListener;
import org.slf4j.shelflife.logic.EnvironmentSample;
import org.slf4j.shelflife.network.ContainerRatePayload;
import org.slf4j.shelflife.network.SpoilageSyncPayload;

import java.util.Optional;

/**
 * 所有事件入口。
 *
 * <p>每个食物相关的处理器都以 {@link SpoilageManager#isEmpty()} 早退 —— 配置表为空时
 * 本模组不打戳、不结算、不发包。
 *
 * <p><b>背包腐烂是事件驱动的，没有定时轮询。</b>原因是保质期组件本质是"检查点 + 时间差"模型：
 * 食物从箱子拿出来在背包里放一段、再放回箱子，下次开箱算的是 {@code 现在 - 检查点 × 倍率}，
 * 背包里那段时间照样被算进去 —— 所以**总量正确性不依赖任何背包侧的定期结算**。
 * 定期扫描唯一的作用是"给新获得的食物写上时钟起点"，而那件事只可能发生在食物进入背包的瞬间，
 * 因此挂在拾取 / 合成 / 熔炼 / 容器开闭上即可。
 *
 * <p><b>环境倍率</b>：结算时按<b>当前位置</b>采样（容器取容器的位置，背包取玩家的位置）。
 * 注意这条规则 —— <b>任何会改变倍率的事件都必须在那一刻结算一次</b>，见
 * {@link SpoilageSettlement#advance} 的类注释。
 *
 * <p>未覆盖的入口：{@code /give}、村民交易、创造模式取出、模组自行塞入。这些路径下食物会保持
 * "时钟未开始"（不腐烂），直到下一次上面任一事件触发才补上起点。生存模式正常玩法不经过这些路径。
 */
@EventBusSubscriber(modid = Shelflife.MODID)
public final class SpoilageEvents {

    private SpoilageEvents() {
    }

    // ------------------------------------------------------------------ 数据包加载

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SpoilageReloadListener(event.getRegistryAccess(), event.getConditionContext()));
        event.addListener(new EnvironmentReloadListener(event.getRegistryAccess(), event.getConditionContext()));
    }

    /** 标签绑定完成的时刻，此时才把规则 / 容器修正里的标签引用展开成具体对象。 */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
        SpoilageManager.resolve();
        EnvironmentManager.resolve();
    }

    // ------------------------------------------------------------------ 配置同步

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(SpoilageSyncPayload.TYPE, SpoilageSyncPayload.STREAM_CODEC, SpoilageSyncPayload::handle);
        registrar.playToClient(ContainerRatePayload.TYPE, ContainerRatePayload.STREAM_CODEC, ContainerRatePayload::handle);
    }

    /** 玩家登录和 {@code /reload} 都会触发。空表也要发，好让客户端清掉上一份配置。 */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // 环境曲线一起发：专用服务器上客户端没有服务端数据包，不给就只能用内置默认曲线算显示
        SpoilageSyncPayload payload = new SpoilageSyncPayload(SpoilageManager.snapshot(), EnvironmentManager.settings());
        event.getRelevantPlayers().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
    }

    // ------------------------------------------------------------------ 背包：时钟起点

    @SubscribeEvent
    public static void onItemPickedUp(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) settleInventory(player);
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) settleInventory(player);
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) settleInventory(player);
    }

    // ------------------------------------------------------------------ 容器

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (SpoilageManager.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AbstractContainerMenu menu = event.getContainer();
        Inventory playerInventory = player.getInventory();
        long now = player.level().getGameTime();

        // 容器的位置：方块实体自己实现了 Container，所以能从槽位反查（箱子、熔炉、发射器都是）。
        // 木桶 / 潜影盒这类内部用 SimpleContainer 承载的取不到，退回玩家位置 ——
        // 反正 stillValid 已经保证玩家就在旁边，群系基本一致。
        BlockPos containerPos = containerPos(menu).orElseGet(player::blockPosition);
        Block containerBlock = player.level().getBlockState(containerPos).getBlock();
        EnvironmentSample sample = EnvironmentSampler.sampleAt(player.level(), containerPos, containerBlock);
        float rate = sample.rate();

        // 把这次采样整个发给客户端：它自己算不出来（不知道容器在世界的位置，也没有容器修正表），
        // 不给的话冷箱的 tooltip / 染色会显示成常温。
        // 带上 containerId：客户端只对"当前打开的就是这个菜单"采信，关箱后脏数据自动失效
        PacketDistributor.sendToPlayer(player, ContainerRatePayload.of(sample, menu.containerId));

        for (Slot slot : menu.slots) {
            // 玩家自己的背包格交给 settleInventory；跳过后每次开箱只推进容器里那部分，
            // 实现"顺序累计、不重复扣同一段时间"
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

        settleInventory(player);
    }

    /** 关箱时补一次：这一趟从容器里取走的东西落到背包了，需要接上时钟。 */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) settleInventory(player);
    }

    // ------------------------------------------------------------------ 内部

    /** 从菜单的槽位反查容器所在的位置。找到第一个由方块实体承载的槽位即可。 */
    private static Optional<BlockPos> containerPos(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof BlockEntity blockEntity && blockEntity.getLevel() != null) {
                return Optional.of(blockEntity.getBlockPos());
            }
        }
        return Optional.empty();
    }

    /**
     * 结算玩家背包 36 格。
     *
     * <p>两个作用：给还没有保质期数据的食物写上时钟起点；把已有检查点推进到当前时刻。
     * 只在这些"背包内容可能变化"的事件里调用，不再定时扫描。
     *
     * <p>倍率按玩家所在位置采样 —— 背包没有"容器修正"这一说。
     */
    private static void settleInventory(ServerPlayer player) {
        if (SpoilageManager.isEmpty()) return;
        Inventory inventory = player.getInventory();
        long now = player.level().getGameTime();
        float rate = EnvironmentSampler.rateAt(player.level(), player.blockPosition(), null);
        // 用下标而不是 for-each：保质期耗尽时需要替换整个堆
        NonNullList<ItemStack> slots = inventory.items;
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
}
