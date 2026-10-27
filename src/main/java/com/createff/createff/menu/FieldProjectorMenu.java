package com.createff.createff.menu;

import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.item.ForceCoreItem;
import com.createff.createff.item.UpgradeItem;
import com.createff.createff.registry.Registration;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The slots of a field projector: one for the force core, nine for the upgrades inside it (only as many as the
 * core's tier gives are usable), and the player's inventory. The control tabs of the screen are not part of the
 * menu; only the Core tab shows these slots.
 */
public final class FieldProjectorMenu extends AbstractContainerMenu {
    public static final int CORE_SLOT = 0;
    public static final int FIRST_UPGRADE = 1;
    public static final int FIRST_PLAYER = FIRST_UPGRADE + UpgradeContainer.MAX_SLOTS;

    /**
     * Layout of the Core tab, relative to the top left of the whole screen. The screen draws the panels and the
     * controls around these slots, so the two have to agree.
     */
    public static final int PANEL_X = 58;
    public static final int PANEL_W = 272;
    public static final int MAIN_H = 134;
    /** Top of the strip the upgrade slots sit in. */
    public static final int UPGRADE_BAR_Y = 104;
    public static final int INVENTORY_PANEL_Y = MAIN_H + 2;
    public static final int INVENTORY_PANEL_H = 62;
    public static final int HOTBAR_PANEL_Y = INVENTORY_PANEL_Y + INVENTORY_PANEL_H + 2;
    public static final int HOTBAR_PANEL_H = 26;
    public static final int TOTAL_H = HOTBAR_PANEL_Y + HOTBAR_PANEL_H;

    /** Where the slots are: the position of the item, one pixel inside the slot background. */
    public static final int CORE_X = PANEL_X + 127 + 1;
    public static final int CORE_Y = 38 + 1;
    public static final int UPGRADES_X = PANEL_X + 68 + 1;
    public static final int UPGRADES_Y = UPGRADE_BAR_Y + 4 + 1;
    public static final int INVENTORY_X = PANEL_X + (PANEL_W - 176) / 2 + 7 + 1;
    public static final int INVENTORY_Y = INVENTORY_PANEL_Y + 4 + 1;
    public static final int HOTBAR_Y = HOTBAR_PANEL_Y + 4 + 1;

    private final BlockPos pos;
    private final boolean canEdit;
    private final UpgradeContainer upgrades;
    /** Only the client changes this, to hide the slots on tabs that do not use them. */
    private boolean slotsVisible = true;

    /** Client side: the server tells it where the projector is and whether this player may change things. */
    public FieldProjectorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), buffer.readBoolean(), new SimpleContainer(1));
    }

    /** Server side. */
    public FieldProjectorMenu(int id, Inventory inventory, FieldProjectorBlockEntity projector, boolean canEdit) {
        this(id, inventory, projector.getBlockPos(), canEdit, projector.coreContainer());
    }

    private FieldProjectorMenu(int id, Inventory inventory, BlockPos pos, boolean canEdit, Container coreContainer) {
        super(Registration.FIELD_PROJECTOR_MENU.get(), id);
        this.pos = pos;
        this.canEdit = canEdit;
        this.upgrades = new UpgradeContainer(coreContainer);

        addSlot(new CoreSlot(coreContainer, CORE_X, CORE_Y));
        for (int i = 0; i < UpgradeContainer.MAX_SLOTS; i++) {
            addSlot(new UpgradeSlot(upgrades, i, UPGRADES_X + i * 18, UPGRADES_Y));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new ToggledSlot(inventory, 9 + row * 9 + column, INVENTORY_X + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new ToggledSlot(inventory, column, INVENTORY_X + column * 18, HOTBAR_Y));
        }
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public int upgradeSlots() {
        return upgrades.available();
    }

    public void setSlotsVisible(boolean visible) {
        slotsVisible = visible;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(pos) instanceof FieldProjectorBlockEntity
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < FIRST_PLAYER) {
            if (!moveItemStackTo(stack, FIRST_PLAYER, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof ForceCoreItem) {
            if (!moveItemStackTo(stack, CORE_SLOT, CORE_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof UpgradeItem) {
            if (!moveItemStackTo(stack, FIRST_UPGRADE, FIRST_PLAYER, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /** A slot the screen can hide. */
    private class ToggledSlot extends Slot {
        ToggledSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean isActive() {
            return slotsVisible;
        }
    }

    private final class CoreSlot extends ToggledSlot {
        CoreSlot(Container container, int x, int y) {
            super(container, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return canEdit && stack.getItem() instanceof ForceCoreItem;
        }

        @Override
        public boolean mayPickup(Player player) {
            return canEdit;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    private final class UpgradeSlot extends ToggledSlot {
        private final int number;

        UpgradeSlot(Container container, int number, int x, int y) {
            super(container, number, x, y);
            this.number = number;
        }

        private boolean exists() {
            return number < upgrades.available();
        }

        @Override
        public boolean isActive() {
            return slotsVisible && exists();
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return canEdit && exists() && stack.getItem() instanceof UpgradeItem;
        }

        @Override
        public boolean mayPickup(Player player) {
            return canEdit && exists();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
