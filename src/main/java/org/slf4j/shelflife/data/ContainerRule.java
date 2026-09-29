package org.slf4j.shelflife.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.advancements.critereon.NbtPredicate;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * {@code spoilage_env} 里的一条"按方块状态区分"的容器修正。
 *
 * <p><b>为什么需要它：</b>{@code containers} 的键只有方块，表达不了"同一个方块、两个状态"的容器 ——
 * 典型是靠 {@code top} 属性区分上下半的冷冻柜（上半制冷、下半不制，或者反过来）。
 * 以前只能用"给那个方块实现 {@code ContainerClimate}"绕过去，但那本来是数据包能说清的事。
 *
 * <p>JSON 就是<b>原版 {@link BlockPredicate} 的形状</b>加上修正字段，平铺在一个对象里：
 *
 * <pre>
 * "container_rules": [
 *   { "blocks": "mymod:freezer", "state": { "top": "true" },  "temperature": -1.6 },
 *   { "blocks": "mymod:freezer", "state": { "top": "false" }, "rate_override": 0 },
 *   { "blocks": "#mymod:cold_boxes", "rate_override": 0 }
 * ]
 * </pre>
 *
 * <p>用原版那个 {@code BlockPredicate.CODEC} 的字段名（{@code blocks} / {@code state} / {@code nbt}），
 * 所以理解原版进度条件的人不用学新东西。{@code blocks} 既可以是方块 id，也可以是标签，
 * 还可以是列表 —— 全部由 {@code HolderSet} 原生支持，**本模组不自己展开标签**，
 * 于是也就不用等标签绑定、不依赖 reload 顺序。
 *
 * <p><b>不支持的：{@code nbt}</b>。本模组的查询是"给我方块和它的状态"这种纯查询，
 * 拿不到方块实体也就读不了 NBT。写了会被丢弃并打一条 warn（不静默失效）。
 *
 * <p>匹配时 <b>state 可以为 null</b>（调用方拿不到状态时）：那时只有"不带 state 条件"的规则算命中。
 */
public record ContainerRule(BlockPredicate predicate, ContainerModifier modifier) {

    public static final Codec<ContainerRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryCodecs.homogeneousList(Registries.BLOCK).optionalFieldOf("blocks").forGetter(r -> r.predicate().blocks()),
            StatePropertiesPredicate.CODEC.optionalFieldOf("state").forGetter(r -> r.predicate().properties()),
            NbtPredicate.CODEC.optionalFieldOf("nbt").forGetter(r -> r.predicate().nbt()),
            Codec.FLOAT.optionalFieldOf("temperature", 0.0F).forGetter(r -> r.modifier().temperature()),
            Codec.FLOAT.optionalFieldOf("humidity", 0.0F).forGetter(r -> r.modifier().humidity()),
            Codec.FLOAT.optionalFieldOf("rate_override").forGetter(r -> r.modifier().rateOverride())
    ).apply(instance, ContainerRule::of));

    private static ContainerRule of(Optional<HolderSet<Block>> blocks, Optional<StatePropertiesPredicate> state,
                                    Optional<NbtPredicate> nbt,
                                    float temperature, float humidity, Optional<Float> rateOverride) {
        return new ContainerRule(new BlockPredicate(blocks, state, nbt),
                new ContainerModifier(temperature, humidity, rateOverride));
    }

    /**
     * 这个方块（和它的状态）命中这条规则吗。
     *
     * @param state 方块状态；{@code null} 表示调用方拿不到状态，这时只有不带 state 条件的规则算命中
     */
    public boolean matches(Holder<Block> block, @Nullable BlockState state) {
        Optional<HolderSet<Block>> blocks = predicate.blocks();
        if (blocks.isPresent() && !blocks.get().contains(block)) return false;

        Optional<StatePropertiesPredicate> properties = predicate.properties();
        if (properties.isPresent()) {
            // 状态匹配是纯查询（StatePropertiesPredicate#matches 是 public），不需要方块实体
            return state != null && properties.get().matches(state);
        }
        return true;
    }

    /** 既没写 {@code blocks} 也没写 {@code state} 的规则会命中所有方块，几乎肯定是写错了。 */
    public boolean isMatchEverything() {
        return predicate.blocks().isEmpty() && predicate.properties().isEmpty();
    }

    /** {@code blocks} 引用的方块或标签一个都不存在（多半是拼错了），这条永远匹配不上。 */
    public boolean hasEmptyBlocks() {
        return predicate.blocks().isPresent() && predicate.blocks().get().size() == 0;
    }
}
