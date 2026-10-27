package com.forcefields.forcefields.item;

import java.util.List;

import com.forcefields.forcefields.Config;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Goes in a field projector. Its tier decides how many upgrades it holds; the upgrades live inside the item,
 * so whoever takes the core out of a projector takes them too.
 */
public class ForceCoreItem extends Item {
    public enum Tier {
        BASIC, ADVANCED, ELITE;

        public int slots() {
            return switch (this) {
                case BASIC -> Config.BASIC_CORE_SLOTS.get();
                case ADVANCED -> Config.ADVANCED_CORE_SLOTS.get();
                case ELITE -> Config.ELITE_CORE_SLOTS.get();
            };
        }
    }

    /** What the upgrades inside a core add up to. */
    public record Upgrades(int resistance, int total, boolean aegis, boolean veil) {
        public static final Upgrades NONE = new Upgrades(0, 0, false, false);
    }

    private final Tier tier;

    public ForceCoreItem(Properties properties, Tier tier) {
        super(properties.stacksTo(1));
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }

    public int slots() {
        return tier.slots();
    }

    /** Upgrade slots of whatever is in a core slot: 0 for anything that is not a force core. */
    public static int slotsOf(ItemStack stack) {
        return stack.getItem() instanceof ForceCoreItem core ? core.slots() : 0;
    }

    /** Counts the upgrades inside a force core, ignoring anything beyond its slots. */
    public static Upgrades upgradesIn(ItemStack stack) {
        if (!(stack.getItem() instanceof ForceCoreItem core)) {
            return Upgrades.NONE;
        }
        ItemContainerContents contents = stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        int resistance = 0;
        int total = 0;
        boolean aegis = false;
        boolean veil = false;
        for (int slot = 0; slot < Math.min(core.slots(), contents.getSlots()); slot++) {
            if (contents.getStackInSlot(slot).getItem() instanceof UpgradeItem upgrade) {
                total++;
                switch (upgrade.type()) {
                    case RESISTANCE -> resistance++;
                    case AEGIS -> aegis = true;
                    case VEIL -> veil = true;
                }
            }
        }
        return new Upgrades(resistance, total, aegis, veil);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Upgrades installed = upgradesIn(stack);
        tooltip.add(Component.translatable("item.forcefields.force_core.slots", installed.total(), slots())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        if (installed.resistance() > 0) {
            tooltip.add(Component.translatable("item.forcefields.force_core.resistance", installed.resistance())
                    .withStyle(net.minecraft.ChatFormatting.AQUA));
        }
        if (installed.aegis()) {
            tooltip.add(Component.translatable("item.forcefields.force_core.aegis").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
        }
        if (installed.veil()) {
            tooltip.add(Component.translatable("item.forcefields.force_core.veil").withStyle(net.minecraft.ChatFormatting.AQUA));
        }
    }
}
