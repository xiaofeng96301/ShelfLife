package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * 腐烂倍率的温度从哪来。
 *
 * <p>默认 {@link #AUTO}：装了 createishot 就用它的温度系统，没装就用群系温度。
 * 想强制固定成某一种（比如排查问题、或者整合包里就是不想要热源影响）就写死。
 *
 * <p><b>{@code auto} 只会存在于服务端。</b>{@link EnvironmentManager#setRaw} 在解析数据包时
 * 就把它落到具体来源上再存起来 —— 否则客户端会按<b>自己的</b>模组列表各解析一遍，
 * 没装 createishot 的客户端和装了它的服务器就会算出两个不同的倍率。
 */
public enum TemperatureSource implements StringRepresentable {

    /** 默认值。装了 createishot 用它，没装用群系。 */
    AUTO("auto"),
    /** 固定用群系温度（原版行为）。 */
    BIOME("biome"),
    /** 固定用 createishot 的节点温度；没装时回退成群系。 */
    CREATEISHOT("createishot");

    private static final TemperatureSource[] VALUES = values();

    public static final Codec<TemperatureSource> CODEC = StringRepresentable.fromEnum(TemperatureSource::values);

    /**
     * 按序号传输。越界的序号退回 {@link #AUTO} 而不是抛异常 —— 这是客户端会读的数据，
     * 一个坏包不该把客户端打崩。
     */
    public static final StreamCodec<ByteBuf, TemperatureSource> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> i >= 0 && i < VALUES.length ? VALUES[i] : AUTO, TemperatureSource::ordinal);

    private final String name;

    TemperatureSource(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** 把 {@link #AUTO} 落到具体来源上。服务端解析数据包时调用一次，之后不该再出现 {@code auto}。 */
    public TemperatureSource resolve(boolean createishotLoaded) {
        if (this != AUTO) return this;
        return createishotLoaded ? CREATEISHOT : BIOME;
    }
}
