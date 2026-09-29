package org.slf4j.shelflife.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.shelflife.Shelflife;
import org.slf4j.shelflife.component.SpoilageData;
import org.slf4j.shelflife.data.SpoilageConfig;
import org.slf4j.shelflife.data.SpoilageManager;

/**
 * 调试指令：直接改<b>手上那件物品</b>的保质期状态。
 *
 * <pre>
 * /shelflife spoilage get              看当前组件（当前腐坏点数 / 上限 / 时间戳）
 * /shelflife spoilage set &lt;点数&gt;       直接设定已腐坏点数
 * /shelflife spoilage add &lt;点数&gt;       在当前值上累加
 * /shelflife spoilage rot &lt;百分比&gt;      设成"烂了这么多"——测"快烂的鱼"用这个最快
 * /shelflife spoilage max &lt;点数&gt;       改保质期上限
 * /shelflife spoilage clear            摘掉组件（物品回到"没有保质期"的原始状态）
 * </pre>
 *
 * <p>为什么要它：不做这个就得靠 {@code /give @s minecraft:cod[shelflife:spoilage={...}]} 手写组件 NBT，
 * 而测"快烂的鱼被漏斗搬进冷箱"这种事需要精确控制腐坏程度，手写太容易写错。
 *
 * <p><b>只对手持物品生效</b>，且只改组件本身 —— 不碰时间戳（{@code set} 时若物品还没有组件，
 * 才顺手把时间戳设成当前时刻，否则新组件的时间戳是 0，会被当成"不在任何容器里"）。
 * 权限等级 2，和 {@code /give} 同级。
 */
@EventBusSubscriber(modid = Shelflife.MODID)
public final class SpoilageCommand {

    private static final SimpleCommandExceptionType ERR_NO_PLAYER =
            new SimpleCommandExceptionType(Component.translatable("commands.shelflife.spoilage.no_player"));
    private static final SimpleCommandExceptionType ERR_NOT_MANAGED =
            new SimpleCommandExceptionType(Component.translatable("commands.shelflife.spoilage.not_managed"));
    private static final SimpleCommandExceptionType ERR_EMPTY_HAND =
            new SimpleCommandExceptionType(Component.translatable("commands.shelflife.spoilage.empty_hand"));
    private static final SimpleCommandExceptionType ERR_NO_DATA =
            new SimpleCommandExceptionType(Component.translatable("commands.shelflife.spoilage.no_data"));

    private SpoilageCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("shelflife")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spoilage")
                        .then(Commands.literal("get").executes(SpoilageCommand::get))
                        .then(Commands.literal("clear").executes(SpoilageCommand::clear))
                        .then(Commands.literal("set")
                                .then(Commands.argument("points", IntegerArgumentType.integer(0))
                                        .executes(ctx -> write(ctx, Write.SET))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("points", IntegerArgumentType.integer())
                                        .executes(ctx -> write(ctx, Write.ADD))))
                        .then(Commands.literal("rot")
                                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> write(ctx, Write.ROT))))
                        .then(Commands.literal("max")
                                .then(Commands.argument("points", IntegerArgumentType.integer(1))
                                        .executes(ctx -> write(ctx, Write.MAX)))));
        event.getDispatcher().register(root);
    }

    private enum Write { SET, ADD, ROT, MAX }

    // ------------------------------------------------------------------ 读

    private static int get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ItemStack stack = held(source);
        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        if (data == null) {
            source.sendSuccess(() -> Component.translatable("commands.shelflife.spoilage.get.none",
                    stack.getHoverName()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.shelflife.spoilage.get.ok",
                stack.getHoverName(), data.currentSpoilage(), data.maxSpoilage(), data.storedTimestamp()), false);
        return data.maxSpoilage() - data.currentSpoilage();
    }

    // ------------------------------------------------------------------ 写

    private static int write(CommandContext<CommandSourceStack> ctx, Write mode) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ItemStack stack = held(source);
        SpoilageConfig config = SpoilageManager.get(stack.getItem());
        if (config == null) throw ERR_NOT_MANAGED.create();

        SpoilageData data = stack.get(Shelflife.SPOILAGE.get());
        // 没有组件时按"全新"起步，并把时间戳落到当前时刻：时间戳 0 是"不在任何容器里"的哨兵值，
        // 留 0 的话这件物品在容器里不会被结算（见 SpoilageData.NO_TIMESTAMP）
        int max = data != null ? data.maxSpoilage() : config.maxSpoilage();
        long timestamp = data != null ? data.storedTimestamp() : source.getLevel().getGameTime();
        int current = data != null ? data.currentSpoilage() : 0;

        int arg = IntegerArgumentType.getInteger(ctx, mode == Write.MAX ? "points"
                : mode == Write.ROT ? "percent" : "points");

        int nextCurrent = current;
        int nextMax = max;
        switch (mode) {
            case SET -> nextCurrent = arg;
            case ADD -> nextCurrent = current + arg;
            case ROT -> nextCurrent = Math.round(max * (arg / 100.0F));
            case MAX -> nextMax = arg;
        }
        nextCurrent = Mth.clamp(nextCurrent, 0, nextMax);

        SpoilageData next = new SpoilageData(nextCurrent, nextMax, timestamp);
        stack.set(Shelflife.SPOILAGE.get(), next);

        int writtenCurrent = nextCurrent;
        int writtenMax = nextMax;
        source.sendSuccess(() -> Component.translatable("commands.shelflife.spoilage.set.ok",
                stack.getHoverName(), writtenCurrent, writtenMax), true);
        return nextCurrent;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ItemStack stack = held(source);
        if (stack.get(Shelflife.SPOILAGE.get()) == null) throw ERR_NO_DATA.create();
        // 用 remove 而不是 set(null)：后者在某些版本会把"空组件"写进去
        stack.remove(Shelflife.SPOILAGE.get());
        source.sendSuccess(() -> Component.translatable("commands.shelflife.spoilage.clear.ok",
                stack.getHoverName()), true);
        return 1;
    }

    /** 手上那件物品。返回的是<b>玩家背包里那个实例本身</b>，改它就是改真东西。 */
    private static ItemStack held(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayer();
        if (player == null) throw ERR_NO_PLAYER.create();
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) throw ERR_EMPTY_HAND.create();
        return stack;
    }

}
