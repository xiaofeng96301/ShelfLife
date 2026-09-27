package org.slf4j.shelflife.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.shelflife.Shelflife;

/**
 * 冷箱的方块实体：27 格，9×3。
 *
 * <p>界面用的是 {@link ChestMenu#threeRows}，它内部套 {@code MenuType.GENERIC_9x3} —— 于是
 * 客户端那边原版就已经为 {@code ChestMenu} 注册好屏幕了，一行 GUI 代码都不用写。
 *
 * <p>物品栏的存档/读档要自己写：{@link BaseContainerBlockEntity} 只管名字和锁，不管内容物。
 */
public class ColdBoxBlockEntity extends BaseContainerBlockEntity {

    /** 9×3。改这个数字还要同步换 {@link #createMenu} 里的行数，以及数据包/配方那边的心智模型。 */
    private static final int SLOTS = 27;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public ColdBoxBlockEntity(BlockPos pos, BlockState state) {
        super(Shelflife.COLD_BOX_BE.get(), pos, state);
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.shelflife.cold_box");
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, items, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ContainerHelper.loadAllItems(tag, items, registries);
    }
}
