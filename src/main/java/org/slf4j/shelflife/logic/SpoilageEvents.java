package org.slf4j.shelflife.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.EnvironmentReloadListener;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.data.SpoilageReloadListener;
import org.slf4j.shelflife.data.TemperatureSource;
import org.slf4j.shelflife.network.ContainerRatePayload;
import org.slf4j.shelflife.network.PlayerEnvironmentPayload;
import org.slf4j.shelflife.network.SpoilageSyncPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 所有事件入口。
 *
 * <p>每个食物相关的处理器都以 {@link SpoilageManager#isEmpty()} 早退 —— 配置表为空时
 * 本模组不打戳、不结算、不发包。
 *
 * <p><b>背包的总量正确性不依赖任何定期结算。</b>保质期组件本质是"检查点 + 时间差"模型：
 * 食物从箱子拿出来在背包里放一段、再放回箱子，下次开箱算的是 {@code 现在 - 检查点 × 倍率}，
 * 背包里那段时间照样被算进去 —— 所以食物不在背包里的时候少算不了，在的时候也多做不了。
 *
 * <p>既然如此，为什么还有一条 {@link #onServerTick 被动刷新}？不是为总量，是为两件
 * <b>只有"当下"才看得见</b>的事：倍率自己变了（见下），以及某一堆刚好到期该转化了。
 * 它每秒只采一次环境 + 刷新一遍背包：补上"还没有时钟起点"的（只写这一次），
 * 把到期的换成腐烂物。<b>其余物品一个字节都不写。</b>
 *
 * <p><b>环境倍率</b>：结算时按<b>当前位置</b>采样（容器取容器的位置，背包取玩家的位置）。
 *
 * <p><b>硬规则：任何改变倍率的事件都必须在那一刻结算一次。</b>单检查点模型表示不了
 * "一段间隔里先后有两个倍率"，不结算就会把整段按新倍率算。
 * 群系温度只在玩家移动或行动时变，所以上面那些事件天然覆盖了它；但
 * <b>createishot 的温度会自己变</b>（昼夜、下雨、营火熄灭），没有任何玩家事件可挂 ——
 * 于是多了一条 {@link #onServerTick 被动刷新}：每秒比一次倍率，变了就用<b>旧倍率</b>当场结清。
 * 常态下它只是采一次环境，只有倍率真的变了才结算 —— 不是"每秒把背包重算一遍"。
 *
 * <p>未覆盖的入口：{@code /give}、村民交易、创造模式取出、模组自行塞入。这些路径下食物会保持
 * "时钟未开始"（不腐烂），直到下一次上面任一事件触发才补上起点。生存模式正常玩法不经过这些路径。
 */
@EventBusSubscriber(modid = Shelflife.MODID)
public final class SpoilageEvents {

    /**
     * 玩家身上的被动刷新间隔（tick）。1 秒一次。
     *
     * <p>这一趟做两件都很轻的事：采一次环境（网格查表 + 群系查询），再刷新一遍背包。
     *
     * <p><b>不做重复的时间处理</b>：只有两种情况会写 —— 「还没有时钟起点的」补一次起点
     * （只命中一次），和「刚好到期的」结算并替换。检查点模型下显示层本来就按时间差实时推算，
     * 每秒把 36 格重写一遍没有意义，只会白白触发客户端同步。详见 {@link #refreshItem}。
     */
    private static final int PLAYER_REFRESH_INTERVAL = 20;

    /** 倍率变化超过这个幅度才值得结算 / 发包 —— float 噪声不该触发。 */
    private static final float RATE_CHANGE_EPSILON = 1.0E-3F;

    /** 每个在线玩家上一次观察到的环境，用来发现"倍率自己变了"。退出时清掉。 */
    private static final Map<UUID, TrackedEnvironment> trackedEnvironments = new HashMap<>();

    private SpoilageEvents() {
    }

    /** 容器的位置配上**它自己的** level，见 {@link #containerLocation}。 */
    private record ContainerLocation(Level level, BlockPos pos) {
    }

    /**
     * @param position 上次采样时玩家所在的方块位置，编码成 long（{@code BlockPos.asLong()}）。
     *                 用 long 而不是 {@code BlockPos} 对象：{@code Entity.blockPosition()} 返回的是
     *                 可变的内部对象，存引用会被后续移动悄悄改掉
     */
    private record TrackedEnvironment(long position, EnvironmentSample ambient,
                                      int containerId, EnvironmentSample container) {
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
        boolean celsiusMode = EnvironmentManager.settings().source() == TemperatureSource.CREATEISHOT;
        event.getRelevantPlayers().forEach(player -> {
            PacketDistributor.sendToPlayer(player, payload);
            if (celsiusMode && player.level() != null) {
                // 摄氏模式下客户端读不到温度，登录 / reload 后立刻给一份准确的
                PacketDistributor.sendToPlayer(player, new PlayerEnvironmentPayload(
                        EnvironmentSampler.sampleAt(player.level(), player.blockPosition(), null)));
            }
        });
    }

    // ------------------------------------------------------------------ 环境复查

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            trackedEnvironments.remove(player.getUUID());
        }
    }

    /**
     * 每 {@value #PLAYER_REFRESH_INTERVAL} tick 把在线玩家过一遍。两件事：
     *
     * <ol>
     *   <li><b>倍率变了就结算</b> —— 存在的理由见类注释：createishot 的温度会自己变，
     *       而单检查点模型要求倍率一变就结算。顺带把"走进沙漠"这类群系倍率变化也修成了当场结算。</li>
     *   <li><b>到期的就转化</b> —— 只做判断，没到期的不写。见 {@link #PLAYER_REFRESH_INTERVAL}。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SpoilageManager.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % PLAYER_REFRESH_INTERVAL != 0) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        // 单独开一个采样区段，好在 F3+L / `/debug` 的火焰图里直接看到这一趟到底多少耗时 ——
        // "感觉不大"不可信，能读出来的数字才可信。找不到就说明真的可以忽略
        ProfilerFiller profiler = players.get(0).serverLevel().getProfiler();
        profiler.push("shelflifePlayerRefresh");
        try {
            for (ServerPlayer player : players) {
                checkEnvironment(player);
            }
        } finally {
            profiler.pop();
        }
    }

    private static void checkEnvironment(ServerPlayer player) {
        long now = player.level().getGameTime();
        long position = player.blockPosition().asLong();
        TrackedEnvironment previous = trackedEnvironments.get(player.getUUID());

        // 群系模式下"玩家周围的环境"只由位置决定（群系 + 容器修正，而背包不吃容器修正），
        // 所以没挪窝就没必要再采一次 —— 省掉一次群系查询。
        // 摄氏模式不能省：createishot 的温度会随昼夜/天气/热源自己变，必须每次都问。
        EnvironmentSample ambient = previous != null && previous.position() == position && !isCelsiusMode()
                ? previous.ambient()
                : EnvironmentSampler.sampleAt(player.level(), player.blockPosition(), null);

        boolean ambientChanged = previous != null && rateChanged(previous.ambient(), ambient);
        if (ambientChanged) {
            // 用**旧倍率**结算：这段间隔真实经历的是旧倍率，新倍率从现在才开始生效
            settleInventory(player, previous.ambient().rate(), now);
        }
        if ((previous == null || ambientChanged) && isCelsiusMode()) {
            PacketDistributor.sendToPlayer(player, new PlayerEnvironmentPayload(ambient));
        }

        AbstractContainerMenu menu = player.containerMenu;
        int containerId = menu == player.inventoryMenu ? -1 : menu.containerId;
        EnvironmentSample containerSample = null;
        if (containerId >= 0) {
            Optional<ContainerLocation> location = containerLocation(menu);
            if (location.isPresent()) {
                ContainerLocation at = location.get();
                containerSample = EnvironmentSampler.sampleAt(at.level(), at.pos(),
                        at.level().getBlockState(at.pos()).getBlock());

                // 开着的容器倍率也会自己变（营火熄灭、入夜、下雨），所以同样要复查并补发
                if (previous != null && previous.containerId() == containerId && previous.container() != null
                        && rateChanged(previous.container(), containerSample)) {
                    settleMenu(menu, player.getInventory(), previous.container().rate(), now);
                    PacketDistributor.sendToPlayer(player, ContainerRatePayload.of(containerSample, containerId));
                }
            }
        }

        // 判断处理：到期的就地转化。背包用玩家所在位置的倍率，容器用容器自己的 ——
        // 和上面结算、以及显示层用的都是同一个数，两边不能对不上
        refreshInventory(player, ambient.rate(), now);
        if (containerId >= 0) {
            float containerRate = containerSample != null ? containerSample.rate() : ambient.rate();
            refreshMenu(menu, player.getInventory(), containerRate, now);
        }

        trackedEnvironments.put(player.getUUID(),
                new TrackedEnvironment(position, ambient, containerId, containerSample));
    }

    /** 刷新整个背包：补时钟起点 + 把到期的换成腐烂物。 */
    private static void refreshInventory(ServerPlayer player, float rate, long now) {
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
    private static void refreshMenu(AbstractContainerMenu menu, Inventory playerInventory, float rate, long now) {
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
     *   <li><b>还没有时钟起点的</b> —— 补上起点，<b>只命中一次</b>。这里有两种情况：
     *       组件压根不存在（{@code /give}、创造模式、从没结算过的容器里拿出来的），
     *       以及组件在但时间戳还是 0（尚未开始计时）。{@code settle} 两种情况都会补成"从现在开始算"。</li>
     *   <li><b>已经到期的</b> —— 先结算一次（把"其实早就到 max 了"写进组件），再返回产物。
     *       这点写入紧接着就随整堆被替换而消失。</li>
     * </ol>
     *
     * <p>判断走的是只读的 {@link SpoilageSettlement#effective}（按检查点实时推算），
     * 所以**没到期的物品不会被写到** —— 那是这套设计的关键：检查点模型下显示层本来就按时间差
     * 实时推算，每秒把 36 格重写一遍只会白白触发客户端同步。
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

    private static boolean rateChanged(EnvironmentSample a, EnvironmentSample b) {
        return Math.abs(a.rate() - b.rate()) > RATE_CHANGE_EPSILON;
    }

    /** 摄氏模式才需要往客户端推环境 —— 群系模式下客户端自己按群系算就是准的。 */
    private static boolean isCelsiusMode() {
        return EnvironmentManager.settings().source() == TemperatureSource.CREATEISHOT;
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
        ContainerLocation at = containerLocation(menu).orElseGet(
                () -> new ContainerLocation(player.level(), player.blockPosition()));
        // 木桶 / 潜影盒这类内部用 SimpleContainer 承载的取不到方块实体，退回玩家位置 ——
        // 反正 stillValid 已经保证玩家就在旁边，环境基本一致。
        EnvironmentSample sample = EnvironmentSampler.sampleAt(at.level(), at.pos(),
                at.level().getBlockState(at.pos()).getBlock());

        // 把这次采样整个发给客户端：它自己算不出来（不知道容器在世界的位置，也没有容器修正表，
        // 更读不到 createishot 的温度）。带上 containerId 让客户端只对"当前打开的就是这个菜单"采信
        PacketDistributor.sendToPlayer(player, ContainerRatePayload.of(sample, menu.containerId));

        settleMenu(menu, player.getInventory(), sample.rate(), player.level().getGameTime());
        settleInventory(player);
    }

    /** 关箱时补一次：这一趟从容器里取走的东西落到背包了，需要接上时钟。 */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity() instanceof ServerPlayer player) settleInventory(player);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 从菜单的槽位反查容器所在的位置，<b>连同它自己的 level</b>。
     *
     * <p>这两者必须成对使用：Sable 载具里的方块实体报的是<b>结构内部的 plot 坐标</b>，
     * 拿它去玩家的 level 里查会查到世界上一格无关的方块（群系和 createishot 温度全错，
     * 而且不会报错，只会静默给出错误答案）。
     */
    private static Optional<ContainerLocation> containerLocation(AbstractContainerMenu menu) {
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

    /**
     * 结算菜单里属于容器的那些格子。
     *
     * <p>跳过玩家自己的背包格（交给 {@link #settleInventory}）：这样每次开箱只推进容器里那部分，
     * 实现"顺序累计、不重复扣同一段时间"。
     */
    private static void settleMenu(AbstractContainerMenu menu, Inventory playerInventory, float rate, long now) {
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

    /**
     * 结算玩家背包 36 格，倍率按玩家所在位置现采。
     *
     * <p>两个作用：给还没有保质期数据的食物写上时钟起点；把已有检查点推进到当前时刻。
     */
    private static void settleInventory(ServerPlayer player) {
        if (SpoilageManager.isEmpty()) return;
        settleInventory(player, EnvironmentSampler.rateAt(player.level(), player.blockPosition(), null),
                player.level().getGameTime());
    }

    /** 用指定倍率结算背包。环境复查发现倍率变化时用**旧倍率**调这个。 */
    private static void settleInventory(ServerPlayer player, float rate, long now) {
        Inventory inventory = player.getInventory();
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
