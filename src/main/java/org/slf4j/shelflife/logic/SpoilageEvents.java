package org.slf4j.shelflife.logic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentReloadListener;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.data.SpoilageReloadListener;
import org.slf4j.shelflife.network.ContainerRatePayload;
import org.slf4j.shelflife.network.PlayerEnvironmentPayload;
import org.slf4j.shelflife.network.SpoilageSyncPayload;

import java.util.List;

/**
 * <b>事件入口</b>。这个类只做一件事：把原版/NeoForge 的事件接到我们的逻辑上，方法都尽量薄。
 *
 * <p>分工（想找逻辑去对应的类，不用在这里翻）：
 * <ul>
 *   <li><b>算</b> —— {@link SpoilageSettlement}：检查点、倍率、加权平均，只认数据不认位置</li>
 *   <li><b>写</b> —— {@link InventorySpoilage}：遍历背包/容器、替换到期物品、补时钟起点</li>
 *   <li><b>检测</b> —— {@link PlayerRefreshTracker}：每个玩家上次看到的环境、该不该结算/发包</li>
 *   <li><b>采样</b> —— {@link EnvironmentSampler}：某位置此刻多冷多湿、倍率多少</li>
 *   <li><b>对外</b> —— {@code api/ShelfLifeApi}：别的模组要用的入口</li>
 * </ul>
 *
 * <p><b>核心模型</b>：物品身上存一个检查点（已腐坏点数 + 上次结算时刻），显示层按时间差实时推算。
 * 于是"总量正确"不依赖任何定期结算，但有一条硬规则：<b>任何改变倍率的事件，都必须在那一刻结算一次</b>
 * —— 单检查点表示不了"一段间隔里先后有两个倍率"。群系温度只在玩家移动时变，上面那些事件天然覆盖；
 * 而 createishot 的温度会自己变（昼夜、天气、营火熄灭），没有事件可挂，所以才需要
 * {@link PlayerRefreshTracker} 定时复查。细节见那个类。
 *
 * <p>未覆盖的入口：{@code /give}、村民交易、创造模式取出、模组自行塞入。这些路径不会立刻打戳，
 * 但食物只要进了玩家背包，被动刷新会在 1 秒内补上时钟起点。
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
        // 版本 2：EnvironmentSample 加了 source 和 celsius，ContainerRatePayload 的包体跟着变了
        PayloadRegistrar registrar = event.registrar("2");
        registrar.playToClient(SpoilageSyncPayload.TYPE, SpoilageSyncPayload.STREAM_CODEC, SpoilageSyncPayload::handle);
        registrar.playToClient(ContainerRatePayload.TYPE, ContainerRatePayload.STREAM_CODEC, ContainerRatePayload::handle);
        registrar.playToClient(PlayerEnvironmentPayload.TYPE, PlayerEnvironmentPayload.STREAM_CODEC, PlayerEnvironmentPayload::handle);
    }

    /** 玩家登录和 {@code /reload} 都会触发。空表也要发，好让客户端清掉上一份配置。 */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        // 环境曲线一起发：专用服务器上客户端没有服务端数据包，不给就只能用内置默认曲线算显示
        SpoilageSyncPayload payload = new SpoilageSyncPayload(SpoilageManager.snapshot(), EnvironmentManager.settings());
        boolean celsius = EnvironmentManager.settings().isCelsius();
        event.getRelevantPlayers().forEach(player -> {
            PacketDistributor.sendToPlayer(player, payload);
            if (celsius && player.level() != null) {
                // 摄氏模式下客户端读不到温度，登录 / reload 后立刻给一份准确的
                PacketDistributor.sendToPlayer(player, new PlayerEnvironmentPayload(
                        EnvironmentSampler.sampleAt(player.level(), player.blockPosition(), null)));
            }
        });
    }

    // ------------------------------------------------------------------ 被动刷新

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerRefreshTracker.forget(player);
        }
    }

    /**
     * 每 tick 走一遍，但**每个玩家按自己的档位**决定这一 tick 要不要复查他
     * （开着容器 1 秒一次，否则 10 秒一次）。真正的判定在 {@link PlayerRefreshTracker}。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SpoilageManager.isEmpty()) return;

        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        // 单独开一个采样区段，好在 F3+L / `/debug` 的火焰图里直接看到这一趟到底多少耗时 ——
        // "感觉不大"不可信，能读出来的数字才可信。找不到就说明真的可以忽略
        ProfilerFiller profiler = players.get(0).serverLevel().getProfiler();
        profiler.push("shelflifePlayerRefresh");
        try {
            for (ServerPlayer player : players) {
                if (tick % PlayerRefreshTracker.interval(player) != 0) continue;
                PlayerRefreshTracker.check(player);
            }
        } finally {
            profiler.pop();
        }
    }

    // ------------------------------------------------------------------ 背包：时钟起点

    @SubscribeEvent
    public static void onItemPickedUp(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer player) InventorySpoilage.settle(player);
    }

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) InventorySpoilage.settle(player);
    }

    @SubscribeEvent
    public static void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) InventorySpoilage.settle(player);
    }

    // ------------------------------------------------------------------ 容器

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (SpoilageManager.isEmpty()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        AbstractContainerMenu menu = event.getContainer();
        InventorySpoilage.ContainerLocation at = InventorySpoilage.locate(menu).orElseGet(
                () -> new InventorySpoilage.ContainerLocation(player.level(), player.blockPosition()));
        // 木桶 / 潜影盒这类内部用 SimpleContainer 承载的取不到方块实体，退回玩家位置 ——
        // 反正 stillValid 已经保证玩家就在旁边，环境基本一致
        EnvironmentSample sample = EnvironmentSampler.sampleAt(at.level(), at.pos(),
                at.level().getBlockState(at.pos()).getBlock());

        // 把这次采样整个发给客户端：它自己算不出来（不知道容器在世界的位置，也没有容器修正表，
        // 更读不到 createishot 的温度）。带上 containerId 让客户端只对"当前打开的就是这个菜单"采信
        PacketDistributor.sendToPlayer(player, ContainerRatePayload.of(sample, menu.containerId));

        InventorySpoilage.settleMenu(menu, player.getInventory(), sample.rate(), player.level().getGameTime());
        InventorySpoilage.settle(player);
    }

    /** 关箱时补一次：这一趟从容器里取走的东西落到背包了，需要接上时钟。 */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) InventorySpoilage.settle(player);
    }
}
