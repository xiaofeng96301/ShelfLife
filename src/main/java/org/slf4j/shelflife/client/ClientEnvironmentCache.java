package org.slf4j.shelflife.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.data.TemperatureSource;
import org.slf4j.shelflife.logic.EnvironmentSample;
import org.slf4j.shelflife.logic.EnvironmentSampler;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 客户端缓存"某个物品当前该按什么环境显示"，每 tick 刷新一次。
 *
 * <p>tooltip 和染色都要用它，但两者都在渲染路径上、一帧会调用很多次，而每次采样要做群系查询 ——
 * 所以每 tick 算一次存起来，渲染时直接读。
 *
 * <p><b>环境是分物品的，不是一个全局值</b>：
 * <ul>
 *   <li><b>容器里的物品</b> —— 用服务端发来的那次采样（{@code ContainerRatePayload}）。
 *       客户端自己反查不到容器位置（客户端侧 {@code ChestMenu} 装的是 {@code SimpleContainer}，
 *       不是方块实体），也拿不到数据包给那个方块写的修正。</li>
 *   <li><b>玩家自己周围的物品</b> —— 摄氏模式下也只能用服务端下发的（{@code PlayerEnvironmentPayload}），
 *       因为 createishot 的温度是服务端权威的；群系模式下本地按群系采样就是准的。</li>
 * </ul>
 *
 * <p><b>怎么区分"在容器里"：</b>把当前菜单里不属于玩家背包的槽位上的 {@code ItemStack}
 * <b>按引用</b>收进一个集合，渲染时查引用。tooltip / 染色只拿得到 {@code ItemStack}、
 * 拿不到它属于哪个槽位，但那个对象就是从槽位里取出来的同一个对象，所以引用比对是可靠的。
 */
@EventBusSubscriber(modid = Shelflife.MODID, value = Dist.CLIENT)
public final class ClientEnvironmentCache {

    /** 没有容器打开、也没有服务端数据时用的兜底值（全新、不变快慢）。 */
    private static final EnvironmentSample FALLBACK = EnvironmentSample.ofBiome(0.0F, 0.0F, 1.0F);

    private static volatile EnvironmentSample ambient = FALLBACK;

    /** 服务端下发的玩家环境。摄氏模式才会有；换回群系模式后会被清掉。 */
    private static volatile EnvironmentSample fromServer = null;

    /** 服务端告知的容器采样，以及它属于哪个菜单。{@code -1} 表示当前没有有效的容器数据。 */
    private static volatile int containerRateId = -1;
    private static volatile EnvironmentSample containerSample = null;

    /** 当前打开的容器里那些物品堆的**引用**。 */
    private static volatile Set<ItemStack> containerStacks = Set.of();

    private ClientEnvironmentCache() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            ambient = FALLBACK;
            fromServer = null;
            clearContainer();
            return;
        }

        // 是否采用服务端的值，看的是**同步过来的** source，而不是本地的 ModList ——
        // 没装 createishot 的客户端连装了它的服务器时，两边必须得出同一个倍率；
        // 若按 ModList 判断，这种客户端会走群系分支，显示和服务端结算对不上。
        EnvironmentSample server = null;
        if (EnvironmentManager.settings().source() == TemperatureSource.CREATEISHOT) {
            server = fromServer;
        } else {
            fromServer = null;   // 换回群系模式后别留着上一局的服务端值
        }

        // 服务端的值还没送到时先用本地群系值顶着（登录后那几 tick），到了立刻换成准确的
        ambient = server != null
                ? server
                : EnvironmentSampler.sampleAt(player.level(), player.blockPosition(), null);

        AbstractContainerMenu menu = player.containerMenu;
        if (containerRateId >= 0 && menu != null && menu.containerId == containerRateId) {
            containerStacks = collectContainerStacks(player, menu);
        } else {
            // id 对不上 = 容器已经关了，或者换成了一个没发过采样的界面（比如创造模式物品栏）。
            // 用 id 配对而不是监听关箱事件：漏掉任何一条关闭路径都不会留下脏数据。
            clearContainer();
        }
    }

    /** 服务端在开箱时、以及容器倍率变化时调用（见 {@code ContainerRatePayload}）。 */
    public static void acceptContainerSample(EnvironmentSample sample, int containerId) {
        containerSample = sample;
        containerRateId = containerId;
    }

    /** 服务端在玩家环境变化时调用（见 {@code PlayerEnvironmentPayload}）。 */
    public static void acceptPlayerSample(EnvironmentSample sample) {
        fromServer = sample;
    }

    /**
     * 这个物品堆该按哪次采样显示。
     *
     * <p>用 {@link Object#equals} 而不是引用会错得很隐蔽 —— 两份内容相同、
     * 一份在箱子里一份在背包里的食物必须给出不同的结果。
     */
    public static EnvironmentSample sampleFor(ItemStack stack) {
        EnvironmentSample fromContainer = containerSample;
        if (containerRateId >= 0 && fromContainer != null && containerStacks.contains(stack)) {
            return fromContainer;
        }
        return ambient;
    }

    public static float rateFor(ItemStack stack) {
        return sampleFor(stack).rate();
    }

    /** 收集容器侧的物品堆。判定方式和 {@code SpoilageEvents.settleMenu} 完全一致。 */
    private static Set<ItemStack> collectContainerStacks(LocalPlayer player, AbstractContainerMenu menu) {
        Inventory playerInventory = player.getInventory();
        Set<ItemStack> stacks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Slot slot : menu.slots) {
            // 玩家自己的格子用玩家环境 —— 这正是"开箱子时身上食物不跟着变"的实现点
            if (slot.container == playerInventory) continue;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    private static void clearContainer() {
        containerRateId = -1;
        containerSample = null;
        containerStacks = Set.of();
    }
}
