package org.slf4j.shelflife.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemColors;
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

    private static int tint(ItemStack stack, int tintIndex) {
        // 只染 layer0。其它层给 -1（= 不染），别去动它们原本的样子
        if (tintIndex != 0) return -1;

        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null || data.maxSpoilage() <= 0) return FRESH_COLOR;

        SpoilageConfig config = ClientSpoilageCache.get(stack.getItem());
        if (config == null) return FRESH_COLOR;

        Level level = Minecraft.getInstance().level;
        if (level == null) return FRESH_COLOR;

        // 用惰性推算而不是组件里存的值 —— 背包不再定时结算，存的值可能落后。
        // 倍率按物品取（容器里的物品和玩家背包里的物品可能不是同一个倍率），
        // 必须和 tooltip 走同一个函数，否则颜色和数字会对不上。
        // 这里也用带小数的版本，颜色才是平滑渐变而不是一跳一跳
        double consumed = SpoilageSettlement.effectiveFractional(data, config,
                ClientEnvironmentCache.rateFor(stack), level.getGameTime());
        float remaining = 1.0F - (float) (consumed / data.maxSpoilage());
        if (remaining >= TINT_START_REMAINING) return FRESH_COLOR;

        float depth = Mth.clamp((TINT_START_REMAINING - remaining) / TINT_START_REMAINING, 0.0F, 1.0F);
        // 烂透了的颜色来自数据包（每条规则可配），所以"曲奇发霉是墨绿的、肉是暗红的"这种事
        // 不需要改代码。默认值见 SpoilageConfig.DEFAULT_TINT
        return lerpRgb(FRESH_COLOR, config.tint(), depth);
    }

    private static int lerpRgb(int from, int to, float t) {
        int r = (int) Mth.lerp(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = (int) Mth.lerp(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = (int) Mth.lerp(t, from & 0xFF, to & 0xFF);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
