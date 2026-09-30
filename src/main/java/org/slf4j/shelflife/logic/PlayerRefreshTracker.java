package org.slf4j.shelflife.logic;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.shelflife.data.EnvironmentManager;
import org.slf4j.shelflife.network.ContainerRatePayload;
import org.slf4j.shelflife.network.PlayerEnvironmentPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>检测</b>：每个在线玩家上一次看到的环境长什么样，以及据此该不该结算、该不该通知客户端。
 *
 * <p>它自己不写物品 —— 该写的时候交给 {@link InventorySpoilage}。这样"什么时候看一眼"
 * 和"看到了怎么写"可以分开调，也方便单独看性能。
 *
 * <p>存在的原因见 {@link SpoilageEvents} 的类注释：{@code createishot} 的温度会自己变
 * （昼夜、天气、营火熄灭），而单检查点模型要求**倍率一变就结算**，那件事没有任何玩家事件可挂。
 *
 * <p>档位：开着容器 1 秒一次，否则 10 秒一次。
 */
public final class PlayerRefreshTracker {

    /** 开着容器界面时的复查间隔（tick）。 */
    private static final int REFRESH_ACTIVE = 20;

    /**
     * 没开着容器时的复查间隔（tick）：10 秒。
     *
     * <p>不看着的时候没必要一秒一算。注意服务端**分辨不出**玩家有没有打开自己的背包
     * （原版没有这个包），所以只能按"有没有开容器"分档。
     */
    private static final int REFRESH_IDLE = 200;

    /** 倍率变化超过这个幅度才值得结算与发包 —— float 噪声不该触发。 */
    private static final float RATE_CHANGE_EPSILON = 1.0E-3F;

    /** 每个在线玩家上一次观察到的环境。退出时清掉。 */
    private static final Map<UUID, Tracked> tracked = new HashMap<>();

    private PlayerRefreshTracker() {
    }

    /**
     * @param position 上次采样时玩家所在的方块位置（{@code BlockPos.asLong()}）。
     *                 用 long 而不是 {@code BlockPos} 对象：{@code Entity.blockPosition()}
     *                 返回的是可变的内部对象，存引用会被后续移动悄悄改掉
     */
    private record Tracked(long position, EnvironmentSample ambient, int containerId, EnvironmentSample container) {
    }

    /** 玩家退出时清掉状态，免得 Map 里留一份永远不用的引用。 */
    public static void forget(ServerPlayer player) {
        tracked.remove(player.getUUID());
    }

    /** 这个玩家当前该多久复查一次。见 {@link #REFRESH_IDLE}。 */
    public static int interval(ServerPlayer player) {
        return player.containerMenu == player.inventoryMenu ? REFRESH_IDLE : REFRESH_ACTIVE;
    }

    /**
     * 复查一个玩家：倍率变了就用**旧倍率**结清，到期的就地转化，该通知客户端就通知。
     *
     * <p>用旧倍率是对的：这段间隔真实经历的是旧倍率，新倍率从现在才开始生效。
     */
    public static void check(ServerPlayer player) {
        long now = player.level().getGameTime();
        long position = player.blockPosition().asLong();
        Tracked previous = tracked.get(player.getUUID());

        // 群系模式下"玩家周围的环境"只由位置决定（群系 + 容器修正，而背包不吃容器修正），
        // 所以没挪窝就没必要再采一次 —— 省掉一次群系查询。
        // 摄氏模式不能省：createishot 的温度会随昼夜/天气/热源自己变，必须每次都问。
        EnvironmentSample ambient = previous != null && previous.position() == position && !isCelsius()
                ? previous.ambient()
                : EnvironmentSampler.sampleAt(player.level(), player.blockPosition(), null);

        boolean ambientChanged = previous != null && rateChanged(previous.ambient(), ambient);
        if (ambientChanged) {
            InventorySpoilage.settle(player, previous.ambient().rate(), now);
        }
        if ((previous == null || ambientChanged) && isCelsius()) {
            PacketDistributor.sendToPlayer(player, new PlayerEnvironmentPayload(ambient));
        }

        AbstractContainerMenu menu = player.containerMenu;
        int containerId = menu == player.inventoryMenu ? -1 : menu.containerId;
        EnvironmentSample containerSample = null;
        if (containerId >= 0) {
            Optional<InventorySpoilage.ContainerLocation> location = InventorySpoilage.locate(menu);
            if (location.isPresent()) {
                InventorySpoilage.ContainerLocation at = location.get();
                containerSample = EnvironmentSampler.sampleAt(at.level(), at.pos(),
                        at.level().getBlockState(at.pos()).getBlock());

                // 开着的容器倍率也会自己变（营火熄灭、入夜、下雨），所以同样要复查并补发。
                //
                // 这里那次"用旧倍率结清"通常是空操作：动态容器按契约会在改自己状态**之前**
                // 调一次 ShelfLifeApi.settleContainer，而 SpoilageSettlement.advance 只消费整点、
                // 时间戳也只前进已消费的那一段 —— 所以这一趟算出来的 elapsed 不够一个点，直接返回 null。
                // 留着它有两个理由：①倍率**自己**变的时候（营火熄灭、入夜、下雨）没有任何一方
                // 会"改状态"，动态容器那条契约根本轮不到，只有这里能发现并结清；
                // ②**给客户端补发倍率只能由这里做** —— 不补发，tooltip 上的数字就会和实际对不上。
                //
                // 不覆盖：locate() 查不到的容器（末影箱、没注册定位器的模组菜单）——
                // 那种情况下连倍率都取不到，整条分支压根不执行。这是既有的限制。
                if (previous != null && previous.containerId() == containerId && previous.container() != null
                        && rateChanged(previous.container(), containerSample)) {
                    InventorySpoilage.settleMenu(menu, player.getInventory(), previous.container().rate(), now);
                    PacketDistributor.sendToPlayer(player, ContainerRatePayload.of(containerSample, containerId));
                }
            }
        }

        // 判断处理：到期的就地转化。背包用玩家所在位置的倍率，容器用容器自己的 ——
        // 和上面结算、以及显示层用的都是同一个数，两边不能对不上
        InventorySpoilage.refresh(player, ambient.rate(), now);
        if (containerId >= 0) {
            float containerRate = containerSample != null ? containerSample.rate() : ambient.rate();
            InventorySpoilage.refreshMenu(menu, player.getInventory(), containerRate, now);
        }

        tracked.put(player.getUUID(), new Tracked(position, ambient, containerId, containerSample));
    }

    private static boolean rateChanged(EnvironmentSample a, EnvironmentSample b) {
        return Math.abs(a.rate() - b.rate()) > RATE_CHANGE_EPSILON;
    }

    /** 摄氏模式才需要往客户端推环境 —— 群系模式下客户端自己按群系算就是准的。 */
    private static boolean isCelsius() {
        return EnvironmentManager.settings().isCelsius();
    }
}
