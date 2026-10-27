package com.forcefields.forcefields.menu;

import com.forcefields.forcefields.item.ForceCoreItem;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * A view of the upgrades stored inside the force core that sits in the core slot. Nothing is kept here:
 * reads and writes go to the core's item data, so the upgrades travel with the core and the same view works
 * on the client, where the core arrives with its data from the server.
 */
public final class UpgradeContainer implements Container {
    public static final int MAX_SLOTS = 9;

    private final Container coreSlot;

    public UpgradeContainer(Container coreSlot) {
        this.coreSlot = coreSlot;
    }

    private ItemStack core() {
        return coreSlot.getItem(0);
    }

    /** How many of the slots the current core actually has. */
    public int available() {
        return Math.min(MAX_SLOTS, ForceCoreItem.slotsOf(core()));
    }

    @Override
    public int getContainerSize() {
        return MAX_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            if (!getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        ItemStack core = core();
        ItemContainerContents contents = core.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (slot >= available() || slot >= contents.getSlots()) {
            return ItemStack.EMPTY;
        }
        return contents.getStackInSlot(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        ItemStack current = getItem(slot);
        if (current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack removed = current.split(amount);
        write(slot, current);
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        write(slot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        write(slot, stack);
    }

    private void write(int slot, ItemStack stack) {
        ItemStack core = core();
        int slots = available();
        if (core.isEmpty() || slot < 0 || slot >= slots) {
            return;
        }
        ItemContainerContents contents = core.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        NonNullList<ItemStack> items = NonNullList.withSize(slots, ItemStack.EMPTY);
        for (int i = 0; i < Math.min(slots, contents.getSlots()); i++) {
            items.set(i, contents.getStackInSlot(i));
        }
        items.set(slot, stack.copy());
        core.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void setChanged() {
        coreSlot.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
    }
}
