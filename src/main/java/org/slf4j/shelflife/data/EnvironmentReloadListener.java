package org.slf4j.shelflife.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.common.conditions.ConditionalOps;
import net.neoforged.neoforge.common.conditions.ICondition;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 扫描 {@code data/<namespace>/spoilage_env/*.json}，读环境曲线和容器修正。
 *
 * <pre>
 * {
 *   "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "..." } ],
 *   "settings":   { "reference_temperature": 0.8, "doubling_per": 0.8, ... },
 *   "containers": { "shelflife:fridge": { "temperature": -2.0 }, "#shelflife:insulated": { ... } }
 * }
 * </pre>
 *
 * <p>两节都可以省略；多个文件时按文件 id 排序依次覆盖（后写的赢）。
 */
public class EnvironmentReloadListener extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "spoilage_env";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setLenient().create();

    /** 和食物规则一样要自己包一层，否则 {@code neoforge:conditions} 静默失效。 */
    private static final Codec<Optional<EnvDocument>> CONDITIONAL_CODEC =
            ConditionalOps.createConditionalCodec(EnvDocument.CODEC);

    private final ConditionalOps<JsonElement> ops;

    public EnvironmentReloadListener(RegistryAccess registryAccess, ICondition.IContext conditionContext) {
        super(GSON, DIRECTORY);
        this.ops = new ConditionalOps<>(RegistryOps.create(JsonOps.INSTANCE, registryAccess), conditionContext);
    }

    /** 同 {@code SpoilageReloadListener#prepare} —— 列举所有命名空间，代价与理由都写在那里。 */
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
        List<ResourceLocation> ids = new ArrayList<>(files.keySet());
        Collections.sort(ids);

        List<EnvDocument> docs = new ArrayList<>();
        for (ResourceLocation id : ids) {
            CONDITIONAL_CODEC.parse(ops, files.get(id))
                    .resultOrPartial(error -> LOGGER.error("[ShelfLife] 解析 {} 失败：{}", id, error))
                    .ifPresent(maybeDoc -> maybeDoc.ifPresent(docs::add));
        }

        EnvironmentSettings settings = null;
        Map<String, ContainerModifier> containers = new HashMap<>();
        for (EnvDocument doc : docs) {
            if (doc.settings().isPresent()) settings = doc.settings().get();
            containers.putAll(doc.containers());
        }

        EnvironmentManager.setRaw(settings, containers);
    }

    private record EnvDocument(Optional<EnvironmentSettings> settings, Map<String, ContainerModifier> containers) {
        static final Codec<EnvDocument> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EnvironmentSettings.CODEC.optionalFieldOf("settings").forGetter(EnvDocument::settings),
                Codec.unboundedMap(Codec.STRING, ContainerModifier.CODEC)
                        .optionalFieldOf("containers", Map.of()).forGetter(EnvDocument::containers)
        ).apply(instance, EnvDocument::new));
    }
}
