package org.slf4j.shelflife.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 冷箱：27 格的容器方块，放在世界里的"冰箱"。
 *
 * <p><b>它本身不含任何温度逻辑。</b>低温完全来自数据包 —— {@code data/shelflife/spoilage_env/environment.json}
 * 里给 {@code shelflife:cold_box} 写了一条 {@code temperature} 偏移。所以：
 * <ul>
 *   <li>做成"冰箱还是速腐箱"是改一个 json 数字的事，不用动代码；</li>
 *   <li>别人想让自己的容器也冷，写自己的一行就行，不需要本模组的方块。</li>
 * </ul>
 *
 * <p>容器界面直接复用原版的 {@code GENERIC_9x3}（桶/箱子同一套），所以没有任何自定义 GUI 代码，
 * 也不需要注册客户端渲染器 —— 方块走普通模型（{@code cube_bottom_top}）。
 *
 * <p><b>和原版箱子的区别：不会连成双联箱</b>（那需要 {@code ChestBlock} 的朝向/type 状态机），
 * 所以它是个独立的小箱子，而不是箱子的换皮。
 */
public class ColdBoxBlock extends Block implements EntityBlock {

    public static final MapCodec<ColdBoxBlock> CODEC = simpleCodec(ColdBoxBlock::new);

    public ColdBoxBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * 空手右键打开。逻辑只在服务端执行 —— {@code openMenu} 在客户端调用会立刻被服务端关掉。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ColdBoxBlockEntity box) {
            player.openMenu(box);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ColdBoxBlockEntity(pos, state);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }

    /**
     * 被拆掉时把里面的东西吐出来。
     *
     * <p>{@code !state.is(newState.getBlock())} 是必要判断 —— 同方块的状态变化（比如以后加开盖状态）也会走这里，
     * 那种情况不该掉东西。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof Container container) {
                Containers.dropContents(level, pos, container);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }
}
