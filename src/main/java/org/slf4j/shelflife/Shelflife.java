package org.slf4j.shelflife;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.shelflife.block.ColdBoxBlock;
import org.slf4j.shelflife.block.ColdBoxBlockEntity;
import org.slf4j.shelflife.component.SpoilageData;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Shelflife.MODID)
public class Shelflife {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "shelflife";
    // Create a Deferred Register to hold Blocks which will all be registered under the "shelflife" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "shelflife" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold BlockEntityTypes which will all be registered under the "shelflife" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "shelflife" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    // Create a Deferred Register to hold DataComponentTypes which will all be registered under the "shelflife" namespace
    public static final DeferredRegister.DataComponents DATA_COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    /**
     * 物品上的保质期状态。
     *
     * <p>{@code persistent} 保证进存档，{@code networkSynchronized} 保证客户端 tooltip 拿得到数据 ——
     * 两个都不能省，少任何一个都会表现为"功能静默失效"，而不是报错。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<SpoilageData>> SPOILAGE =
            DATA_COMPONENTS.registerComponentType("spoilage", builder -> builder
                    .persistent(SpoilageData.CODEC)
                    .networkSynchronized(SpoilageData.STREAM_CODEC)
                    .cacheEncoding());

    /**
     * 冷箱：27 格容器方块。
     *
     * <p><b>方块本身不带温度数据</b> —— 冷源来自数据包给 {@code shelflife:cold_box} 写的
     * {@code spoilage_env} 修正。所以"多冷"是数据包的事，这里只负责"能装东西"。
     */
    public static final DeferredBlock<ColdBoxBlock> COLD_BOX =
            BLOCKS.registerBlock("cold_box", ColdBoxBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.5F)
                    .sound(SoundType.WOOD));

    public static final DeferredItem<BlockItem> COLD_BOX_ITEM = ITEMS.registerSimpleBlockItem("cold_box", COLD_BOX);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColdBoxBlockEntity>> COLD_BOX_BE =
            BLOCK_ENTITIES.register("cold_box",
                    () -> BlockEntityType.Builder.of(ColdBoxBlockEntity::new, COLD_BOX.get()).build(null));

    // 保质期耗尽后的产物。这两个物品本身**没有**保质期 —— 它们不在任何数据包规则里，
    // 所以配置表查不到、不打戳、不腐烂，也就不会有 tooltip。
    public static final DeferredItem<Item> ROTTEN_LEFTOVERS =
            ITEMS.registerSimpleItem("rotten_leftovers", new Item.Properties().food(rottenFood()));
    public static final DeferredItem<Item> ROTTEN_MEAT =
            ITEMS.registerSimpleItem("rotten_meat", new Item.Properties().food(rottenFood()));

    /**
     * 吃下腐烂食物的代价：饥饿 amplifier 2、反胃 amplifier 3，各 30 秒，100% 触发。
     *
     * <p>饱和度系数 0.1 对齐原版腐肉 —— 营养 5 只换到 1.0 饱和度，吃了几乎不顶饱。
     */
    /**
     * 本模组的创造模式标签页。
     *
     * <p>物品列表用的是 {@code ITEMS.getEntries()} 的**实时视图**，在标签页构建时（所有物品注册完之后）
     * 才遍历 —— 所以以后往 {@link #ITEMS} 里注册任何物品（含方块物品）都会自动出现在这里，不用回来改这个标签页。
     *
     * <p>图标用的是腐烂肉类。{@code CreativeModeTab.Builder#icon} 只接受 {@code ItemStack}，
     * 想用一张独立的图标贴图就得额外注册一个专门承载它的物品，所以直接用已有物品更干净。
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SPOILAGE_TAB =
            CREATIVE_MODE_TABS.register("spoilage", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.shelflife"))
                    .icon(() -> new ItemStack(ROTTEN_MEAT.get()))
                    .displayItems((parameters, output) -> ITEMS.getEntries().forEach(item -> output.accept(item.get())))
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .build());

    private static FoodProperties rottenFood() {
        return new FoodProperties.Builder()
                .nutrition(5)
                .saturationModifier(0.1F)
                .effect(() -> new MobEffectInstance(MobEffects.HUNGER, 600, 2), 1.0F)
                .effect(() -> new MobEffectInstance(MobEffects.CONFUSION, 600, 3), 1.0F)
                .build();
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Shelflife(IEventBus modEventBus, ModContainer modContainer) {
        // Register the Deferred Registers to the mod event bus so their contents get registered
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
    }
}
