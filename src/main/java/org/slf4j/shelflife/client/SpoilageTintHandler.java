package org.slf4j.shelflife.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.ClientSpoilageCache;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.logic.SpoilageSettlement;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 按保质期给食物染色 —— 食物在背包、快捷栏、乃至掉在地上时都会逐渐变暗发绿。
 *
 * <p><b>为什么用染色而不是阶段贴图：</b>本模组是数据包驱动的，命中哪些物品只有运行时才知道，
 * 而染色作用在渲染层、与物品贴图无关，所以一张图不用画就能覆盖全部物品（含模组物品）。
 *
 * <p><b>深度曲线</b>：剩余保质期 ≥ {@value #TINT_START_REMAINING} 时不染；从那里到保质期耗尽，
 * 深度线性 0 → 1。也就是"掉了 40% 之后开始变色，最后 60% 里逐渐加深"。
 *
 * <p>染色是<b>乘性</b>的，只能压暗/偏色，不能变亮 —— 所以"发绿"是用一个偏绿的中性色乘上去。
 *
 * <p>已知边界：只对 {@code item/generated} 模型（layer0）生效，多层模型的其它层不受影响；
 * 若数据包把带原版染色的物品（药水、皮革护甲之类）写进保质期表，本模组会覆盖掉它的染色。
 */
@EventBusSubscriber(modid = Shelflife.MODID, value = Dist.CLIENT)
public final class SpoilageTintHandler {

    /** 剩余保质期低于这个比例才开始染色 —— 0.60 即"消耗掉 40% 之后"。 */
    private static final float TINT_START_REMAINING = 0.60F;

    /**
     * 新鲜时的颜色：不透明的纯白，乘上去等于什么都没变。
     *
     * <p><b>alpha 必须是 0xFF。</b>这个值会被原版当成 ARGB 拆开、alpha 直接写进顶点，
     * 少写 alpha 位（比如 0xFFFFFF）就是"完全透明"，登记过染色的物品会整个从画面里消失。
     */
    private static final int FRESH_COLOR = 0xFFFFFFFF;

    /** 模型的第 0 层：物品自己的贴图。 */
    private static final int BASE_LAYER = 0;

    /** 第 1 层：霉斑叠加层（只有 {@code overlay} 物品的模型里才有）。 */
    private static final int OVERLAY_LAYER = 1;

    /** 原版约定：这一层不染。 */
    private static final int NO_TINT = -1;

    /** 完全透明。叠加层"还没长霉"时必须用它 —— 用白色会把整张霉斑图直接铺出来。 */
    private static final int TRANSPARENT = 0x00FFFFFF;

    /** 开着界面时深度重算间隔（tick）：1 秒。 */
    private static final int DEPTH_REFRESH_ACTIVE = 20;

    /** 没开界面时：10 秒。屏幕外的东西没人盯着看。 */
    private static final int DEPTH_REFRESH_IDLE = 200;

    /** 按物品堆缓存腐烂深度，见 {@link #rotDepth}。只在客户端主线程访问，不需要同步。 */
    private static final Map<ItemStack, Float> depthCache = new IdentityHashMap<>();

    /** 上次清空缓存时的游戏刻。 */
    private static long depthCacheStamp = Long.MIN_VALUE;

    /** 由 {@link #onRegisterItemColors} 存下来，供配置表变动时补登记。 */
    private static ItemColors itemColors;

    private SpoilageTintHandler() {
    }

    @SubscribeEvent
    public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        itemColors = event.getItemColors();
    }

    /**
     * 给配置表命中的物品登记染色。每次客户端收到新的配置表时调用。
     *
     * <p>{@code ItemColors#register} 在 NeoForge 里标了 {@code @Deprecated}（本意是让人走
     * {@link RegisterColorHandlersEvent}），但那个事件只在启动时触发一次，而配置表是**服务端登录后**
     * 才同步过来、{@code /reload} 还会变 —— 所以只能在运行时补登记。
     *
     * <p>重复登记同一个物品只是用同一个 handler 覆盖，无害；从配置表里移除的物品不会被注销，
     * 但 handler 在渲染时会自己查表返回"不染"，所以残留登记是惰性的。
     */
    public static void refresh(Collection<Item> items) {
        if (itemColors == null || items.isEmpty()) return;
        itemColors.register(SpoilageTintHandler::tint, items.toArray(Item[]::new));
    }

    /**
     * 两种染色模式，由数据包的 {@code overlay} 决定：
     *
     * <ul>
     *   <li><b>普通（默认）</b>：layer0 从白渐变到 {@code tint} —— <b>整块乘性变暗偏色</b>。
     *       对"肉变暗发绿"这种效果够用，但拿不到"保持原色、只长霉"。</li>
     *   <li><b>叠加层（{@code overlay: true}）</b>：layer0 <b>完全不染</b>（物品保持原本的亮色），
     *       layer1 用 {@code tint} 上色、<b>alpha 当腐烂深度用</b> —— 霉从全透明一点点浮现。
     *       物品模型的 JSON 里必须写出 layer1 才会被问到这个索引（见 {@code assets/minecraft/models/item/cookie.json}）。</li>
     * </ul>
     */
    private static int tint(ItemStack stack, int tintIndex) {
        SpoilageConfig config = ClientSpoilageCache.get(stack.getItem());
        if (config == null) {
            // 底层保持原样；其它层默认透明，免得把没在管的叠加层显示出来
            return tintIndex == BASE_LAYER ? FRESH_COLOR : TRANSPARENT;
        }

        float depth = rotDepth(stack, config);
        if (config.overlay()) {
            if (tintIndex != OVERLAY_LAYER) return NO_TINT;   // 底层和其它层一律不动
            return withAlpha(config.tint(), depth);           // depth 0 = 完全透明
        }
        if (tintIndex != BASE_LAYER) return NO_TINT;
        // 烂透了的颜色来自数据包（每条规则可配），所以"曲奇发霉是墨绿的、肉是暗红的"这种事
        // 不需要改代码。默认值见 SpoilageConfig.DEFAULT_TINT
        return lerpRgb(FRESH_COLOR, config.tint(), depth);
    }

    /**
     * 腐烂深度 0~1，带缓存。
     *
     * <p>{@code ItemColor} 是**每帧、每个物品、每个面**都会被问一次的，而深度一秒才变一点点 ——
     * 所以按物品堆缓存，{@link #DEPTH_REFRESH_TICKS} 才重算一次。
     *
     * <p>用 {@link IdentityHashMap} 而不是普通 Map：{@code ItemStack} 的 {@code equals/hashCode}
     * 是按组件逐项比的，拿它当键既慢又会把"内容相同的不同堆"混成一个 —— 这里要的是同一个对象。
     * 缓存整批清空而不是逐条过期：条目数天然被"这轮渲染过的堆"限住，不会无界增长。
     */
    private static float rotDepth(ItemStack stack, SpoilageConfig config) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return 0.0F;

        long now = level.getGameTime();
        if (now - depthCacheStamp >= depthRefreshTicks()) {
            depthCache.clear();
            depthCacheStamp = now;
        }
        Float cached = depthCache.get(stack);
        if (cached != null) return cached;

        float depth = computeRotDepth(stack, config, level, now);
        depthCache.put(stack, depth);
        return depth;
    }

    /**
     * 深度最多多久重算一次。
     *
     * <p>开着界面时 1 秒（玩家正盯着它看），没开时 10 秒 —— 屏幕外的东西颜色变得慢，
     * 没人会注意，而每秒重算整个背包是白烧的。和工具提示的刷新档位保持一致。
     */
    private static int depthRefreshTicks() {
        return Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>
                ? DEPTH_REFRESH_ACTIVE
                : DEPTH_REFRESH_IDLE;
    }

    /**
     * 腐烂深度 0~1：消耗掉 {@link #TINT_START_REMAINING} 之外的部分才开始算。
     *
     * <p>用惰性推算而不是组件里存的值 —— 背包不再定时结算，存的值可能落后。
     * 倍率按物品取（容器里的和背包里的可能是两个倍率），必须和 tooltip 走同一个函数，
     * 否则颜色和数字会对不上。这里用的是带小数的版本，颜色才是平滑渐变而不是一跳一跳。
     */
    private static float computeRotDepth(ItemStack stack, SpoilageConfig config, Level level, long now) {
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null || data.maxSpoilage() <= 0) return 0.0F;

        double consumed = SpoilageSettlement.effectiveFractional(data, config,
                ClientEnvironmentCache.rateFor(stack), now);
        float remaining = 1.0F - (float) (consumed / data.maxSpoilage());
        if (remaining >= TINT_START_REMAINING) return 0.0F;

        return Mth.clamp((TINT_START_REMAINING - remaining) / TINT_START_REMAINING, 0.0F, 1.0F);
    }

    /**
     * 把颜色换成指定 alpha（0 = 全透明，1 = 原样）。
     *
     * <p>这正是当初那个"物品整个隐形"的坑的另一面：alpha 会被原版写进顶点，
     * 所以它既能毁掉一个物品，也能用来做淡入 —— 叠加层要的就是后者。
     */
    private static int withAlpha(int color, float alpha) {
        int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int lerpRgb(int from, int to, float t) {
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
