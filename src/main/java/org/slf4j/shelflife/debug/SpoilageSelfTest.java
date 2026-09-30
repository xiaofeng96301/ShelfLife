package org.slf4j.shelflife.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
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

import java.util.List;
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

    /**
     * 熔炉-漏斗-箱子那条竖井的顶端（下面两格分别是漏斗和箱子）。
     *
     * <p><b>必须在 {@link #BOX} 的同一个区块里</b> —— 只有那个区块被强制加载，
     * 换个区块的话漏斗根本不会被 tick，测试会"因为没搬东西"而失败，看着像被测代码的问题。
     */
    private static final BlockPos FURNACE_POS = new BlockPos(12, 100, 12);

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
                ItemStack merged = boxStack(level);
                check(merged.getCount() == 2 && spoilageOf(merged) == 84,
                        "漏斗合并：两条一样烂的鱼合成一堆、腐坏值不变（期望 2x / cur=84，实际 "
                                + merged.getCount() + "x / cur=" + spoilageOf(merged) + "）");
                pushIntoHopper(level, fish(level, 20));
                step = 3;
                cooldown = 40;
            }
            case 3 -> {
                dump(level, "叠一条新鲜的（20）—— 期望 cur=(84*1+20*1)/2=52");
                ItemStack merged = boxStack(level);
                check(merged.getCount() == 3 && spoilageOf(merged) == 62,
                        "漏斗合并：加权平均 (84*2+20*1)/3 = 62（期望 3x / cur=62，实际 "
                                + merged.getCount() + "x / cur=" + spoilageOf(merged) + "）");
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
            case 9 -> {
                testOutputSlotIsUntouched(level);
                step = 10;
                cooldown = 20;
            }
            case 10 -> {
                testItemHandlerHooks(level);
                step = 11;
                cooldown = 20;
            }
            case 11 -> {
                setUpHopperOutputRig(level);
                step = 12;
                cooldown = 200;   // 漏斗每 8 刻搬一件，给足时间
            }
            case 12 -> {
                testHopperOutputStacks(level);
                step = 13;
                cooldown = 20;
            }
            case 13 -> {
                testDropContentsSettles(level);
                step = 14;
                cooldown = 20;
            }
            case 14 -> {
                testDoubleChestLocated(level);
                step = 15;
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

    /**
     * 文档夹具（`tools/docs_check/`）装上了吗。
     *
     * <p>靠它里面的标志物判断：它把苹果的 {@code ticks_per_spoilage} 写成了 9999。
     * 没装的时候相关用例**跳过**而不是失败 —— 那个夹具是给人手动拷进去的，
     * 自测本身必须默认就能全绿。
     */
    private static boolean fixtureInstalled() {
        SpoilageConfig apple = SpoilageManager.get(Items.APPLE);
        return apple != null && apple.ticksPerSpoilage() == 9999;
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
        if (!fixtureInstalled()) {
            LOG.info("  [skip] 夹具 tools/docs_check 没装上，跳过");
            return;
        }
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
        if (!fixtureInstalled()) {
            LOG.info("  [skip] 夹具 tools/docs_check 没装上，跳过");
            return;
        }
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
     * 输出槽不能被碰：碰了会让熔炉**彻底卡死**。
     *
     * <p>原版（和很多模组）判断"还能不能再产出一个"用的是
     * {@code isSameItemSameComponents(输出槽里的东西, 这次要产的产物)}。产物是刚构造的、
     * 没有保质期组件的；我们往输出槽打一个组件，两者就"不是同一个物品"了 ——
     * 现象就是"一组鱼只有第一条能烧出来"。
     *
     * <p>用"DummyMenu + 一个只出不进的槽位"复现：同样是没组件的鳕鱼，
     * 结算之后普通槽位该被打上组件、输出槽该原封不动。两条路都要验
     * （{@code settleMenu} 和 {@code refreshMenu} 各有一份循环）。
     */
    private static void testOutputSlotIsUntouched(ServerLevel level) {
        LOG.info("---------- 输出槽不碰（熔炉卡死的根因） ----------");
        long now = level.getGameTime();

        DummyMenu settleMenu = new DummyMenu();
        ItemStack normal = new ItemStack(Items.COD);
        ItemStack output = new ItemStack(Items.COD);
        settleMenu.getSlot(0).set(normal);
        settleMenu.getSlot(1).set(output);
        // playerInventory 传 null：这条测试只关心"哪些槽位会被写"
        InventorySpoilage.settleMenu(settleMenu, null, 0.95F, now);
        check(normal.get(Shelflife.SPOILAGE.get()) != null, "settleMenu：普通槽位照常结算");
        check(output.get(Shelflife.SPOILAGE.get()) == null, "settleMenu：输出槽原封不动");

        DummyMenu refreshMenu = new DummyMenu();
        ItemStack normal2 = new ItemStack(Items.COD);
        ItemStack output2 = new ItemStack(Items.COD);
        refreshMenu.getSlot(0).set(normal2);
        refreshMenu.getSlot(1).set(output2);
        InventorySpoilage.refreshMenu(refreshMenu, null, 0.95F, now);
        check(normal2.get(Shelflife.SPOILAGE.get()) != null, "refreshMenu：普通槽位照常补时钟起点");
        check(output2.get(Shelflife.SPOILAGE.get()) == null, "refreshMenu：输出槽原封不动");
    }

    /**
     * {@code IItemHandler} 那一层的三条钩子。
     *
     * <p>以前只挂了原版容器（菜单/漏斗/SimpleContainer），于是**机器和管道**这两类全漏了：
     * 判定不放宽 → 不同腐坏值的东西压根合不上；就算合上了也只留机器自己那堆的组件 →
     * "不停往里塞新鲜的，那堆永远不会变烂"。
     *
     * <p>四条断言：
     * <ol>
     *   <li>{@code ItemStackHandler}（机器自己的物品栏，**不是 Container**）能合上且取平均</li>
     *   <li>{@code InvWrapper}（非分面的容器包装）同上</li>
     *   <li>{@code SidedInvWrapper}（分面的，双联箱/熔炉那种）同上</li>
     *   <li>经包装**抽**东西时仍然会被结清 —— 这条是保护"把两条抽取钩子收敛到
     *       {@code removeItem} 一条"这个改动的</li>
     * </ol>
     */
    private static void testItemHandlerHooks(ServerLevel level) {
        LOG.info("---------- IItemHandler：机器 / 管道那一层 ----------");
        long now = level.getGameTime();

        // ①ItemStackHandler：机器自己开的物品栏，不是 Container
        ItemStackHandler handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, spoiledFish(now, 84));
        ItemStack handlerRemainder = handler.insertItem(0, spoiledFish(now, 20), false);
        check(handlerRemainder.isEmpty(), "ItemStackHandler：不同腐坏值能合上（判定放宽了）");
        check(spoilageOf(handler.getStackInSlot(0)) == 52,
                "ItemStackHandler：加权平均 (84+20)/2 = 52，实际 " + spoilageOf(handler.getStackInSlot(0)));

        // ②InvWrapper：普通 Container（箱子/木桶这类）
        SimpleContainer simple = new SimpleContainer(1);
        simple.setItem(0, spoiledFish(now, 84));
        ItemStack invRemainder = new InvWrapper(simple).insertItem(0, spoiledFish(now, 20), false);
        check(invRemainder.isEmpty() && spoilageOf(simple.getItem(0)) == 52,
                "InvWrapper：合上且取平均，实际 " + spoilageOf(simple.getItem(0)));

        // ③SidedInvWrapper：分面的容器。冷箱不是 WorldlyContainer（BaseContainerBlockEntity
        //   只实现 Container），所以拿熔炉来验 —— 它有朝向、输入槽在 UP 这一面
        BlockPos furnacePos = BOX.offset(6, 0, 0);
        level.setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
        if (level.getBlockEntity(furnacePos) instanceof WorldlyContainer furnace) {
            furnace.setItem(0, spoiledFish(now, 84));
            // 熔炉没燃料就不会烧，所以输入槽里的东西在测试期间不会变
            ItemStack sidedRemainder = new SidedInvWrapper(furnace, Direction.UP)
                    .insertItem(0, spoiledFish(now, 20), false);
            check(sidedRemainder.isEmpty() && spoilageOf(furnace.getItem(0)) == 52,
                    "SidedInvWrapper：合上且取平均，实际 " + spoilageOf(furnace.getItem(0)));
        } else {
            check(false, "SidedInvWrapper：熔炉的方块实体没建出来");
        }

        // ④抽取侧：经包装抽出来的东西必须已经被"按来源容器的环境"结清了。
        //   冷箱的倍率被数据包钉在 0.05，所以 24000 刻 → 10 点，是确定的数
        if (level.getBlockEntity(BOX) instanceof ColdBoxBlockEntity box) {
            ItemStack inBox = new ItemStack(Items.COD);
            inBox.set(Shelflife.SPOILAGE.get(), new SpoilageData(0, 100, now - 24000));
            box.setItem(7, inBox);

            ItemStack taken = new InvWrapper(box).extractItem(7, 1, false);
            check(spoilageOf(taken) == 10,
                    "经 InvWrapper 抽取仍会被 removeItem 结清（冷箱 ×0.05 × 24000 刻 = 10 点），实际 "
                            + spoilageOf(taken));
        } else {
            check(false, "抽取测试：冷箱的方块实体没建出来");
        }
    }

    /** 一条"timestamp 推到 N 刻前、腐坏值是 cur"的鱼。 */
    private static ItemStack spoiledFish(long now, int cur) {
        ItemStack stack = new ItemStack(Items.COD);
        stack.set(Shelflife.SPOILAGE.get(), new SpoilageData(cur, 100, now));
        return stack;
    }

    /** 读组件里的腐坏值；没有组件返回 -1。 */
    private static int spoilageOf(ItemStack stack) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        return data == null ? -1 : data.currentSpoilage();
    }

    /** 冷箱 0 号格里的那堆（漏斗那条链的落点）。 */
    private static ItemStack boxStack(ServerLevel level) {
        return level.getBlockEntity(BOX) instanceof ColdBoxBlockEntity box
                ? box.getItem(0)
                : ItemStack.EMPTY;
    }

    /**
     * 熔炉产出 → 漏斗 → 箱子：<b>必须能堆叠</b>。
     *
     * <p>为什么它值得单独盯：输出槽现在**不会被打戳**（打了会让熔炉卡死，那是另一个修复），
     * 所以刚从熔炉拿出来的东西是**没有保质期组件**的；而漏斗抽取时
     * {@code ContainerTransfer.afterExtraction} 会给它补一个时间戳。于是"新来的有组件、
     * 箱子里那堆没有"，两边组件不同 → 原版判定合不上；而本模组的放宽判定有个前置条件：
     * <b>目标堆必须已经有组件</b>（当年为了不污染创造物品栏定的）→ 两边都不放行 → 不堆叠。
     *
     * <p>用"直接往输出槽塞一堆没组件的熟鳕鱼"来复现，不用真等它烤 —— 等价于熔炉刚烧出来的状态。
     */
    private static void setUpHopperOutputRig(ServerLevel level) {
        LOG.info("---------- 熔炉产出 → 漏斗 → 箱子 ----------");
        BlockPos furnace = FURNACE_POS;
        BlockPos hopper = furnace.below();
        BlockPos chest = hopper.below();
        for (BlockPos pos : new BlockPos[]{furnace, hopper, chest}) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());

        if (level.getBlockEntity(furnace) instanceof AbstractFurnaceBlockEntity furnaceBe) {
            // 刚烤好、还没有任何组件 —— 这正是"输出槽不被打戳"的结果
            furnaceBe.setItem(2, new ItemStack(Items.COOKED_COD, 8));
        } else {
            LOG.error("熔炉的方块实体没建出来");
        }
    }

    private static void testHopperOutputStacks(ServerLevel level) {
        BlockPos chest = FURNACE_POS.below().below();
        if (!(level.getBlockEntity(chest) instanceof Container chestBe)) {
            check(false, "漏斗下游的箱子没建出来");
            return;
        }

        int usedSlots = 0;
        int total = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < chestBe.getContainerSize(); i++) {
            ItemStack stack = chestBe.getItem(i);
            if (stack.isEmpty()) continue;
            usedSlots++;
            total += stack.getCount();
            if (detail.length() > 0) detail.append(" ; ");
            detail.append('[').append(i).append("] ").append(describe(level, stack, 0.95F));
        }

        check(total >= 2, "漏斗确实把东西搬进箱子了（至少 2 件，实际 " + total + "）");
        check(usedSlots <= 1, "搬进去的食物应该堆成一堆（占用 " + usedSlots + " 个槽位）：" + detail);
    }

    /**
     * 砸箱子：里面的东西必须**按箱子的环境**结清之后再掉出来。
     *
     * <p>{@code Containers.dropContents} 读内容物用的是 {@code getItem(i)} + {@code copy()}，
     * <b>不调 {@code removeItem}</b> —— 抽取那几条钩子一个都碰不到。所以这条路以前完全不结算：
     * 食物在箱子里待的时间一笔账都不记，等落地被捡起来才按目的地的倍率补算。
     * 冷箱那类有倍率修正的容器上，这个差别就是"冰箱天按常温算"。
     *
     * <p>断言靠"组件被推进了"来判：放进去时是 {@code cur=0, ts=now-2400}，箱子所在位置是常温，
     * 2400 刻该攒出十几点；不结算的话掉出来还是 0。
     */
    private static void testDropContentsSettles(ServerLevel level) {
        LOG.info("---------- 砸箱子：掉落前按容器环境结清 ----------");
        BlockPos chestPos = BOX.offset(0, 0, 4);
        level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(chestPos) instanceof Container chest)) {
            check(false, "箱子没建出来");
            return;
        }

        // 两分钟前开始计时、一点都还没烂
        ItemStack inChest = new ItemStack(Items.COD);
        inChest.set(Shelflife.SPOILAGE.get(), new SpoilageData(0, 100, level.getGameTime() - 2400));
        chest.setItem(0, inChest);

        // 走公开入口 —— 它和 onRemove / 矿车那两条一样汇进被钩的那个私有重载
        Containers.dropContents(level, chestPos, chest);

        ItemStack onGround = ItemStack.EMPTY;
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(chestPos).inflate(2.0));
        for (ItemEntity entity : dropped) {
            if (entity.getItem().is(Items.COD)) {
                onGround = entity.getItem();
                break;
            }
        }

        check(!onGround.isEmpty(), "箱子里的鳕鱼掉出来了（附近 " + dropped.size() + " 个掉落物）");
        if (onGround.isEmpty()) return;
        check(spoilageOf(onGround) >= 1,
                "掉出来的食物已按箱子的环境结清（期望 ≥1 点，实际 " + spoilageOf(onGround)
                        + " —— 0 就说明这条路没接上）");
        check(onGround.is(Items.COD), "还是鳕鱼（两分钟的账远不到烂透）");
    }

    /**
     * 双联箱也要能反查到位置。
     *
     * <p>双联箱在菜单里是一个 {@code CompoundContainer}，**不是方块实体** ——
     * "找第一个由方块实体承载的槽位"那一步会整个漏掉，一路退化到按**玩家位置**采样：
     * 两个箱子里的食物都按你站的地方算账（冷箱 / {@code container_rules} 全不生效），
     * 而且只打一条 warn，不报错。
     */
    private static void testDoubleChestLocated(ServerLevel level) {
        LOG.info("---------- 双联箱：反查位置 ----------");
        BlockPos left = BOX.offset(0, 0, 6);
        BlockPos right = left.east();
        level.setBlockAndUpdate(left, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(right, Blocks.CHEST.defaultBlockState());

        if (!(level.getBlockEntity(left) instanceof Container chest1)
                || !(level.getBlockEntity(right) instanceof Container chest2)) {
            check(false, "两个箱子的方块实体没建出来");
            return;
        }

        // 复刻 ChestBlock 造双联箱的方式（acceptDouble 里 new CompoundContainer(左, 右)）
        Container doubleChest = new CompoundContainer(chest1, chest2);
        Optional<InventorySpoilage.ContainerLocation> located =
                InventorySpoilage.locate(new DoubleChestMenu(doubleChest));

        check(located.isPresent(), "双联箱的菜单能反查到位置（以前会退化成按玩家位置采样）");
        check(located.isPresent() && located.get().pos().equals(left),
                "反查到的是箱子的坐标（两半一定相邻、环境一致，取哪个都一样），实际 "
                        + located.map(at -> at.pos().toString()).orElse("<查不到>"));
    }

    /**
     * 槽位挂在 {@code CompoundContainer} 上的菜单 —— 双联箱在菜单里就是这个形态。
     *
     * <p>不直接用 {@code ChestMenu} 是因为它的构造器会走 {@code container.startOpen(playerInventory.player)}，
     * 而自测服务器里没有玩家。要验的是 {@code locate()} 那一步的判断，关键在"槽位容器是什么形态"，
     * 菜单本身是哪一个无所谓。
     */
    private static final class DoubleChestMenu extends AbstractContainerMenu {

        DoubleChestMenu(Container container) {
            super(MenuType.GENERIC_9x6, 0);
            for (int i = 0; i < container.getContainerSize(); i++) {
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

    /**
     * 测试用菜单：槽位挂在一个 {@code SimpleContainer} 上，**不是方块实体**。
     *
     * <p>这正是 {@code SlotItemHandler} 那类机器容器的处境（它的槽位容器是一个共享的空
     * {@code SimpleContainer}），用来验证"默认查不到"和"注册表能救回来"两条路。
     *
     * <p>0 号槽是普通槽；**1 号槽故意做成"只出不进"**，用来验证输出槽不会被写组件。
     */
    private static final class DummyMenu extends AbstractContainerMenu {

        private final SimpleContainer container = new SimpleContainer(9);

        DummyMenu() {
            super(MenuType.GENERIC_9x3, 0);
            for (int i = 0; i < 9; i++) {
                addSlot(i == 1 ? new OutputSlot(container, i, 0, 0) : new Slot(container, i, 0, 0));
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

    /** 只出不进的槽位，模拟输出槽（熔炉的结果槽、合成台的产物槽都是这一类）。 */
    private static final class OutputSlot extends Slot {

        OutputSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
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
