package org.slf4j.shelflife.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.api.ContainerLocators;
import org.slf4j.shelflife.api.ShelfLifeApi;
import org.slf4j.shelflife.block.ColdBoxBlockEntity;
import org.slf4j.shelflife.logic.InventorySpoilage;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;
import org.slf4j.shelflife.logic.EnvironmentSampler;
import org.slf4j.shelflife.logic.SpoilageSettlement;

import java.util.Locale;
import java.util.Optional;

/**
 * <b>临时调试场景</b>：在真服务器上复刻"漏斗往冷箱里塞快烂的鱼"，把每一步的组件值打出来。
 *
 * <p>用法（只在专用服务器上跑，客户端完全不受影响；不传开关就一行都不会执行）：
 * <pre>
 *   ./gradlew runServer -PshelflifeSelfTest=true
 * </pre>
 * 用 Gradle 属性而不是 {@code -D} 是踩出来的：系统属性不是配置缓存的输入，改了它缓存不会失效，
 * 开关会静默地一直用上一次的值。
 *
 * <p>为什么不用 GameTest：GameTest 要一份 structure 模板文件，而这里需要的是<b>真实世界的真实漏斗</b>
 * 走一遍真实游戏循环，环境（群系温度 → 冷箱冷源）也得是真的。
 *
 * <p>流程：冷箱上面放漏斗 → 塞第一条鱼 → 等 40 tick → 打印冷箱内容 → 再塞一条 → 等 40 tick → 打印 → 关服。
 * 每次打印都做两遍：先看组件原值，再模拟一次"打开冷箱"（容器结算用的就是容器位置的环境），
 * 因为你的复现步骤里"打开冷箱看一眼"本身就是一次结算。
 *
 * <p>它只读逻辑（除了摆方块和塞鱼），不改任何行为，也不需要改动被测代码。
 * 已经靠它抓到并回归验证过一个 bug：漏斗先 shrink 后 grow 导致来源堆被当成全新，
 * 目标堆保质期凭空翻倍（见 {@code HopperBlockEntityMixin} 的注释）。
 *
 * <p><b>留在仓库里是有意的</b> —— 它是这个模组唯一能验证"真实漏斗走真实游戏循环"的手段，
 * 而那正是上面那个 bug 唯一会暴露的地方（纯逻辑单测测不出来）。
 */
@EventBusSubscriber(modid = Shelflife.MODID)
public final class SpoilageSelfTest {

    private static final Logger LOG = LoggerFactory.getLogger("ShelfLife-SelfTest");
    private static final String PROPERTY = "shelflife.selftest";

    /** 摆台子的位置。挑个平坦的空域，前后左右上下都会先清成空气。 */
    private static final BlockPos BOX = new BlockPos(8, 100, 8);

    /** 复刻观察到的现场：84/100 —— 常温下显示约 1分35秒，进 0.05 的冷箱约 32 分。 */
    private static final int FISH_SPOILAGE = 84;

    private static boolean active;
    private static int step;
    private static int cooldown;

    /** 断言失败的条数。跑完打一行汇总，日志里好搜。 */
    private static int failures;

    private SpoilageSelfTest() {
    }

    // ------------------------------------------------------------------ 开服时搭台子

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean(PROPERTY)) return;

        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();

        LOG.info("========== ShelfLife 自测开始 ==========");
        LOG.info("规则表是否为空 = {}（false 才说明数据包加载成功）", SpoilageManager.isEmpty());
        if (SpoilageManager.get(Items.COD) == null) {
            LOG.error("{} 没有保质期规则，后面的自测没有意义，直接结束", BuiltInRegistries.ITEM.getKey(Items.COD));
            server.halt(false);
            return;
        }

        // 强制加载并让区块进入"方块实体 ticking"档位，否则漏斗根本不会被 tick
        level.setChunkForced(BOX.getX() >> 4, BOX.getZ() >> 4, true);

        // 清出一小块空地，免得方块埋在地形里
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(BOX.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(BOX, Shelflife.COLD_BOX.get().defaultBlockState());
        level.setBlockAndUpdate(BOX.above(), Blocks.HOPPER.defaultBlockState());

        LOG.info("冷箱 {} = {}", BOX, level.getBlockState(BOX).getBlock());
        LOG.info("漏斗 {} = {}", BOX.above(), level.getBlockState(BOX.above()).getBlock());
        LOG.info("冷箱位置倍率 = {}", rateAt(level));
        LOG.info("漏斗位置倍率 = {}", EnvironmentSampler.rateAt(level, BOX.above(), null));

        active = true;
        step = 0;
        cooldown = 40;   // 等方块实体建好、区块 tick 起来
    }

    // ------------------------------------------------------------------ 逐步推进

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!active) return;
        if (--cooldown > 0) return;

        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();

        switch (step) {
            case 0 -> {
                pushIntoHopper(level, fish(level, FISH_SPOILAGE));
                step = 1;
                cooldown = 40;
            }
            case 1 -> {
                dump(level, "第一条鱼（84）进箱之后");
                pushIntoHopper(level, fish(level, FISH_SPOILAGE));
                step = 2;
                cooldown = 40;
            }
            case 2 -> {
                dump(level, "再叠一条一样烂的（84）—— 加权平均下 cur 应该<b>不变</b>，还是 84");
                pushIntoHopper(level, fish(level, 20));
                step = 3;
                cooldown = 40;
            }
            case 3 -> {
                dump(level, "叠一条新鲜的（20）—— 期望 cur=(84*1+20*1)/2=52");
                step = 4;
                cooldown = 40;
            }
            case 4 -> {
                testSettleContainer(level, true);
                step = 5;
                cooldown = 40;
            }
            case 5 -> {
                testSettleContainer(level, false);
                step = 6;
                cooldown = 40;
            }
            case 6 -> {
                testContainerRules(level);
                step = 7;
                cooldown = 20;
            }
            case 7 -> {
                testContainerLocators(level);
                step = 8;
                cooldown = 20;
            }
            case 8 -> {
                testMergeKeepsOverlay();
                step = 9;
                cooldown = 20;
            }
            default -> {
                if (failures == 0) {
                    LOG.info("========== ShelfLife 自测通过：0 项失败 ==========");
                } else {
                    LOG.error("========== ShelfLife 自测结束：{} 项失败 ==========", failures);
                }
                active = false;
                server.halt(false);
            }
        }
    }

    // ------------------------------------------------------------------ 动作

    private static ItemStack fish(ServerLevel level, int spoilage) {
        ItemStack stack = new ItemStack(Items.COD);
        SpoilageConfig config = SpoilageManager.get(Items.COD);
        int max = config != null ? config.maxSpoilage() : 100;
        // 时间戳设成"此刻"：这样存进去的值就是给定的数，可读性最好，也不用去猜零头
        stack.set(Shelflife.SPOILAGE.get(),
                new SpoilageData(spoilage, max, level.getGameTime()));
        return stack;
    }

    private static void pushIntoHopper(ServerLevel level, ItemStack stack) {
        BlockEntity be = level.getBlockEntity(BOX.above());
        if (!(be instanceof HopperBlockEntity hopper)) {
            LOG.error("漏斗的方块实体没建出来: {}", be);
            return;
        }
        // 漏斗此刻应该是空的；先把它的状态打出来，"没被 tick 到"和"被 tick 了但没推"是两种病
        LOG.info("塞入前 漏斗内容 = {}", contents(level, hopper, rateAt(level)));
        for (int i = 0; i < hopper.getContainerSize(); i++) {
            if (hopper.getItem(i).isEmpty()) {
                hopper.setItem(i, stack);
                LOG.info("往漏斗第 {} 格放入 {}", i, contents(level, hopper, rateAt(level)));
                return;
            }
        }
        LOG.error("漏斗满了，放不进去");
    }

    /** 打印冷箱内容 —— 打印前、打印后各来一次"模拟开箱结算"。 */
    private static void dump(ServerLevel level, String phase) {
        LOG.info("---------- {} ----------", phase);
        LOG.info("漏斗内容 = {}", contentsOf(level, BOX.above()));
        LOG.info("冷箱内容（组件原值） = {}", contentsOf(level, BOX));

        settleBox(level);
        LOG.info("冷箱内容（模拟开箱结算后） = {}", contentsOf(level, BOX));
    }

    /**
     * 验证 {@code ShelfLifeApi.settleContainer}（"动态容器改了状态就自己结清"那套）。
     *
     * <p>往冷箱的 1 号格放一条"20 分钟前开始计时、但一点都还没烂"的鱼，然后调 API 结清。
     * 两次调的差别只有 {@code containerBlock} 传不传 —— 也就是"按冷箱的环境结"还是"按常温结"，
     * 这正是"先改状态再结清"会犯的错：同一段经过的时间，倍率取错就会算出完全不同的结果。
     *
     * @param asContainer {@code true} = 按冷箱环境（×0.05）→ 期望 cur=10；
     *                    {@code false} = 只用群系（×0.95）→ 期望 cur 顶到 100 并变成腐烂物
     */
    private static void testSettleContainer(ServerLevel level, boolean asContainer) {
        if (!(level.getBlockEntity(BOX) instanceof ColdBoxBlockEntity box)) {
            LOG.error("冷箱的方块实体没建出来");
            return;
        }
        SpoilageConfig config = SpoilageManager.get(Items.COD);
        int max = config != null ? config.maxSpoilage() : 100;
        long now = level.getGameTime();

        // 时间戳推到 20 分钟前：这段时间的账一笔都还没记
        box.setItem(1, new ItemStack(Items.COD));
        box.getItem(1).set(Shelflife.SPOILAGE.get(), new SpoilageData(0, max, now - 24000));

        float rate = asContainer
                ? EnvironmentSampler.rateAt(level, BOX, level.getBlockState(BOX).getBlock())
                : EnvironmentSampler.rateAt(level, BOX, null);
        LOG.info("---------- settleContainer 测试（containerBlock {}） 期望 = {} ----------",
                asContainer ? "传冷箱" : "传 null（常温）",
                asContainer ? "cur=10" : "cur=100 并转化成腐烂物");
        LOG.info("结清前 = {}", describe(level, box.getItem(1), rate));

        int changed = ShelfLifeApi.settleContainer(box, level, BOX,
                asContainer ? level.getBlockState(BOX).getBlock() : null);

        LOG.info("结清后（改写了 {} 格，用的倍率 {}）= {}", changed, rate,
                describe(level, box.getItem(1), rate));
    }

    /** 复刻 {@code PlayerContainerEvent.Open} 对容器做的那一次结算。 */
    private static void settleBox(ServerLevel level) {
        if (!(level.getBlockEntity(BOX) instanceof ColdBoxBlockEntity box)) return;
        float rate = rateAt(level);
        long now = level.getGameTime();
        for (int i = 0; i < box.getContainerSize(); i++) {
            ItemStack stack = box.getItem(i);
            if (stack.isEmpty()) continue;
            SpoilageConfig config = SpoilageManager.get(stack.getItem());
            if (config == null) continue;
            SpoilageSettlement.settle(stack, config, rate, now);
        }
    }

    // ------------------------------------------------------------------ 断言

    /**
     * 一条断言。不中断 —— 一次跑完把所有问题都报出来，比修一个跑一次快。
     *
     * <p>标记用 ASCII 的 {@code [ok]} / {@code [FAIL]} 而不是 ✓/✗：日志是按平台编码写的，
     * 用符号的话在别的编码下看就是乱码，连 grep 都搜不到 —— 而这个夹具的全部价值就是能从日志里读出来。
     */
    private static void check(boolean ok, String what) {
        if (ok) {
            LOG.info("[ok]   {}", what);
        } else {
            failures++;
            LOG.error("[FAIL] {}", what);
        }
    }

    // ------------------------------------------------------------------ 三项新东西的验证

    /**
     * {@code container_rules}：同一个方块靠状态区分出不同的倍率，以及"带标签、不带 state"的规则。
     *
     * <p>用半砖当样本：{@code minecraft:oak_slab} 的 {@code type} 有 top/bottom 两个值，
     * 而且它是纯原版方块 —— 不需要为此新增任何内容。
     */
    private static void testContainerRules(ServerLevel level) {
        LOG.info("---------- container_rules：按方块状态匹配 ----------");
        BlockPos top = BOX.offset(4, 0, 0);
        BlockPos bottom = BOX.offset(4, 0, 1);
        BlockPos wool = BOX.offset(4, 0, 2);

        for (BlockPos pos : new BlockPos[]{top, bottom, wool}) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(top, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        level.setBlockAndUpdate(bottom, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        level.setBlockAndUpdate(wool, Blocks.WHITE_WOOL.defaultBlockState());

        check(rateAt(level, top) == 0.0F, "上半砖（state type=top）命中 rate_override 0");
        check(Math.abs(rateAt(level, bottom) - 3.0F) < 1.0E-4F, "下半砖（state type=bottom）命中 3.0");
        check(Math.abs(rateAt(level, wool) - 1.5F) < 1.0E-4F,
                "羊毛（#minecraft:wool 标签 + 不带 state）命中 1.5 —— 标签是 HolderSet 原生支持的");
    }

    /**
     * 容器定位：默认那条路（遍历槽位找方块实体）对机器容器**必然查不到**，
     * 注册一个定位器之后必须能救回来。
     *
     * <p>{@link DummyMenu} 的槽位挂在一个 {@code SimpleContainer} 上 ——
     * 既不是方块实体、也不是玩家背包，正好是 {@code SlotItemHandler} 那类机器容器的处境。
     */
    private static void testContainerLocators(ServerLevel level) {
        LOG.info("---------- 容器定位扩展点 ----------");
        check(ContainerLocators.size() == 0, "一开始注册表是空的");

        // 这一步会走"查不到"的分支，日志里应该出现一条"反查不到容器位置"的 warn
        check(InventorySpoilage.locate(new DummyMenu()).isEmpty(),
                "没有定位器时查不到（并打一条 warn，日志里能看到）");

        ContainerLocators.register(menu -> menu instanceof DummyMenu ? level.getBlockEntity(BOX) : null);

        Optional<InventorySpoilage.ContainerLocation> located = InventorySpoilage.locate(new DummyMenu());
        check(located.isPresent(), "注册定位器之后能反查到位置");
        check(located.isPresent() && located.get().pos().equals(BOX),
                "反查到的正是冷箱那个坐标（拿到的是方块实体自己的 level + pos）");
        check(ContainerLocators.size() == 1, "注册表里现在有一个定位器");
    }

    /**
     * 同一个物品被重复声明时**按字段继承**，不是整条替换。
     *
     * <p>夹具里给 {@code minecraft:cookie} 只写了 {@code ticks_per_spoilage}，
     * 所以内置那条的 {@code overlay: true} 和自定义 {@code tint} 必须原样留着 ——
     * 以前是整条替换，叠加层还在但颜色/透明度被冲回默认值，看起来像渲染坏了。
     */
    private static void testMergeKeepsOverlay() {
        LOG.info("---------- 重复声明：按字段继承 ----------");
        SpoilageConfig cookie = SpoilageManager.get(Items.COOKIE);
        if (cookie == null) {
            LOG.error("  ✗ 曲奇没有规则 —— 夹具没装上？");
            failures++;
            return;
        }
        check(cookie.ticksPerSpoilage() == 999, "后声明写了的字段被覆盖（tps 999）");
        check(cookie.overlay(), "后声明**没写**的 overlay 继承下来了（还是 true）");
        check(cookie.tint() == 0xFF46C49A, "后声明没写的 tint 也继承下来了（还是 #46C49A）");
    }

    /**
     * 测试用菜单：槽位挂在一个 {@code SimpleContainer} 上，**不是方块实体**。
     *
     * <p>这正是 {@code SlotItemHandler} 那类机器容器的处境（它的槽位容器是一个共享的空
     * {@code SimpleContainer}），用来验证"默认查不到"和"注册表能救回来"两条路。
     */
    private static final class DummyMenu extends AbstractContainerMenu {

        private final SimpleContainer container = new SimpleContainer(9);

        DummyMenu() {
            super(MenuType.GENERIC_9x3, 0);
            for (int i = 0; i < 9; i++) {
                addSlot(new Slot(container, i, 0, 0));
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 读数

    private static float rateAt(ServerLevel level) {
        return rateAt(level, BOX);
    }

    private static float rateAt(ServerLevel level, BlockPos pos) {
        return EnvironmentSampler.rateAt(level, pos, level.getBlockState(pos).getBlock());
    }

    private static String contentsOf(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.Container container)) return "<没有容器: " + be + ">";
        return contents(level, container, rateAt(level));
    }

    private static String contents(ServerLevel level, net.minecraft.world.Container container, float rate) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ; ");
            sb.append('[').append(i).append("] ").append(describe(level, stack, rate));
        }
        return sb.isEmpty() ? "<空>" : sb.toString();
    }

    /**
     * 同一个显示公式（{@code (max - live) * tps / rate}）算出来的剩余时间，
     * 外加组件的三个原始字段 —— 前者是"你看到的"，后者是"真正存着的"。
     */
    private static String describe(ServerLevel level, ItemStack stack, float rate) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (data == null) return stack.getCount() + "x " + name + " [无组件]";

        SpoilageConfig config = SpoilageManager.get(stack.getItem());
        if (config == null) return stack.getCount() + "x " + name + " [无规则]";

        long now = level.getGameTime();
        double live = SpoilageSettlement.effectiveFractional(data, config, rate, now);
        double remainingPoints = data.maxSpoilage() - live;
        double remainingSeconds = remainingPoints * config.ticksPerSpoilage() / rate / 20.0;

        return String.format(Locale.ROOT,
                "%dx %s | cur=%d max=%d ts=%d elapsed=%d | live=%.3f 剩余=%.3f点 = %.1f秒 (tps=%d, rate=%.4f)",
                stack.getCount(), name, data.currentSpoilage(), data.maxSpoilage(), data.storedTimestamp(),
                now - data.storedTimestamp(), live, remainingPoints, remainingSeconds,
                config.ticksPerSpoilage(), rate);
    }
}
