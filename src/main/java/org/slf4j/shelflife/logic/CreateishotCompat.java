package org.slf4j.shelflife.logic;

import io.github.uicdb.createishot.api.ThermalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * createishot 的**唯一**接触面。createishot 是可选依赖，所有对它的引用都必须经过这个类。
 *
 * <p><b>为什么调用方必须能接受 {@code null}：</b>没装 createishot 时这里的每个方法都得有"拿不到值"
 * 的答案，调用方回退到群系温度。只要有一个调用点假设它一定有值，没装模组的玩家就会崩。
 *
 * <p><b>为什么服务端判断要显式写出来：</b>createishot 的温度是服务端权威的，客户端只收两个字节
 * （HUD 档位 + 温度计柱高）。但 {@code ThermalApi} 内部的网格表是按**维度**索引的、没有端判断 ——
 * 单机下客户端和集成的服务端共用一个进程，客户端查询会"碰巧正确"，专用服务器下才退化。
 * 也就是说：写错了在单机测不出来。所以这里显式要求 {@link ServerLevel}，让错误退化成"回退群系"
 * 而不是"单机对、联机错"。
 */
public final class CreateishotCompat {

    public static final String MODID = "createishot";

    /** 三态缓存：{@code null} = 还没查过。ModList 查询不该每 tick 做。 */
    private static volatile Boolean loaded;

    private CreateishotCompat() {
    }

    public static boolean isLoaded() {
        Boolean cached = loaded;
        if (cached == null) {
            cached = ModList.get().isLoaded(MODID);
            loaded = cached;
        }
        return cached;
    }

    /**
     * 这一格的节点温度（摄氏）—— 环境 + 热源，createishot 自己求解好的。
     *
     * <p>拿不到就返回 {@code null}，调用方回退群系温度。三种情况会拿不到：没装模组、
     * 不是服务端、以及位置在未加载的区块里（那种情况下 {@code nodeCelsius} 会静默返回环境温度，
     * 比群系温度还多带了海拔/昼夜/天气，所以**不需要**为此单独处理）。
     */
    @Nullable
    public static Float celsiusAt(Level level, BlockPos pos) {
        if (!isLoaded()) return null;
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return ThermalApi.nodeCelsius(serverLevel, pos);
    }
}
