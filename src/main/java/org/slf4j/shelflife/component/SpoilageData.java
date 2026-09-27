package org.slf4j.shelflife.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 单个物品堆的保质期状态。
 *
 * <p>三个字段都会持久化到存档并同步到客户端 —— 客户端 tooltip 需要 {@code currentSpoilage} 和
 * {@code maxSpoilage} 才能算出剩余时间，所以 {@code networkSynchronized} 是必需的，不是可选项。
 *
 * <p>{@code storedTimestamp} 只在物品位于容器内时有意义：容器的腐烂是按"距上次结算过了多久"算的，
 * 而不是按 tick。物品离开容器时时间戳必须清掉（见 {@code ContainerSpoilageHandler}），否则
 * 在背包里靠 tick 腐烂的那段时间会被容器再算一遍。
 */
public record SpoilageData(int currentSpoilage, int maxSpoilage, long storedTimestamp) {

    /** 时间戳的哨兵值：表示"不在任何容器里"。游戏时间从 0 开始，所以 0 不会和真实时间戳撞。 */
    public static final long NO_TIMESTAMP = 0L;

    public static final Codec<SpoilageData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("current_spoilage").forGetter(SpoilageData::currentSpoilage),
            Codec.INT.fieldOf("max_spoilage").forGetter(SpoilageData::maxSpoilage),
            Codec.LONG.optionalFieldOf("stored_timestamp", NO_TIMESTAMP).forGetter(SpoilageData::storedTimestamp)
    ).apply(instance, SpoilageData::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpoilageData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpoilageData::currentSpoilage,
            ByteBufCodecs.VAR_INT, SpoilageData::maxSpoilage,
            ByteBufCodecs.VAR_LONG, SpoilageData::storedTimestamp,
            SpoilageData::new
    );

    /** 按数据包配置新建一个未腐烂的实例。 */
    public static SpoilageData fresh(int maxSpoilage) {
        return new SpoilageData(0, maxSpoilage, NO_TIMESTAMP);
    }

    public boolean isSpoiled() {
        return maxSpoilage > 0 && currentSpoilage >= maxSpoilage;
    }

    /** 是否处于容器内（有有效时间戳）。 */
    public boolean isStored() {
        return storedTimestamp != NO_TIMESTAMP;
    }

    /** 加保质期并在上限处截断。 */
    public SpoilageData addSpoilage(int amount) {
        if (amount <= 0) return this;
        int next = maxSpoilage > 0 ? Math.min(currentSpoilage + amount, maxSpoilage) : currentSpoilage + amount;
        return new SpoilageData(next, maxSpoilage, storedTimestamp);
    }

    public SpoilageData withTimestamp(long timestamp) {
        return new SpoilageData(currentSpoilage, maxSpoilage, timestamp);
    }
}
