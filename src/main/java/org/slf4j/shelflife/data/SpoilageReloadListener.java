package org.slf4j.shelflife.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 扫描 {@code data/<namespace>/food_spoilage/*.json}。
 *
 * <p>只做 JSON → 对象的解析，不查注册表、不展开标签（见 {@link SpoilageManager}）。
 */
public class SpoilageReloadListener extends SimpleJsonResourceReloadListener {

    /** 数据包目录名。 */
    public static final String DIRECTORY = "food_spoilage";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /**
     * NeoForge <b>不会</b>给自定义 JSON 自动套用 {@code neoforge:conditions}。必须自己用
     * {@link ConditionalOps#createConditionalCodec} 包一层，否则 {@code mod_loaded} 条件是
     * <b>静默失效</b>的 —— 目标模组不存在时文件照样会被加载，比报错更难查。
     */
    private static final Codec<Optional<SpoilageRule>> CONDITIONAL_CODEC =
            ConditionalOps.createConditionalCodec(SpoilageRule.CODEC);

    private final ConditionalOps<JsonElement> ops;

    public SpoilageReloadListener(RegistryAccess registryAccess, ICondition.IContext conditionContext) {
        super(GSON, DIRECTORY);
        this.ops = new ConditionalOps<>(RegistryOps.create(JsonOps.INSTANCE, registryAccess), conditionContext);
    }

    /**
     * 列举文件（真正干活的解析在 {@link #apply}）。这里插一条耗时日志。
     *
     * <p>列举的是**所有命名空间**下的 {@code food_spoilage/} —— 这是必须的：
     * 第三方数据包用的是它们**自己的**命名空间（`data/mymod/food_spoilage/...`），
     * 只认 {@code shelflife} 会让所有外部规则静默消失。代价就是这条日志要说明的事：
     * 它随「数据包数量 × 命名空间数量」增长，而且**只在数据包 reload 时发生一次**
     * （登录 / `/reload`），不在 tick 上。
     */
    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        long started = System.nanoTime();
        Map<ResourceLocation, JsonElement> files = super.prepare(resourceManager, profiler);
        LOGGER.info("[ShelfLife] 列举 {}：{} 个文件 / {} 个数据包，耗时 {} ms", DIRECTORY, files.size(),
                resourceManager.listPacks().count(), (System.nanoTime() - started) / 1_000_000L);
        return files;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, SpoilageRule> parsed = new HashMap<>();
        files.forEach((id, json) -> CONDITIONAL_CODEC.parse(ops, json)
                // 条件不满足时 parse 返回的是空 Optional，不是错误，所以这里不会刷警告
                .resultOrPartial(error -> LOGGER.error("[ShelfLife] 解析 {} 失败：{}", id, error))
                .ifPresent(maybeRule -> maybeRule.ifPresent(rule -> parsed.put(id, rule))));

        SpoilageManager.setRules(parsed);
    }
}
