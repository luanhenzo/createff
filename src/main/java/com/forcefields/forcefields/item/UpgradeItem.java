package com.forcefields.forcefields.item;

import java.util.List;
import java.util.Locale;

import com.forcefields.forcefields.Config;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;

/** An upgrade for a force core. Each one takes a slot; several of the same kind add up, except those that only switch something on. */
public class UpgradeItem extends Item {
    public enum Type {
        /** More resistance, so the field takes more before it overloads. */
        RESISTANCE,
        /** The Aegis: the barrier cannot be overloaded. Only creative players get it. */
        AEGIS,
        /** The Veil: the barrier is only seen from close up, by touching it, or when it is attacked. */
        VEIL
    }

    private final Type type;

    public UpgradeItem(Properties properties, Type type) {
        super(properties.stacksTo(type == Type.RESISTANCE ? 16 : 1).rarity(type == Type.AEGIS ? Rarity.EPIC : Rarity.COMMON));
        this.type = type;
    }

    public Type type() {
        return type;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String key = "item.forcefields.upgrade." + type.name().toLowerCase(Locale.ROOT);
        if (type == Type.RESISTANCE) {
            tooltip.add(Component.translatable(key + ".effect", Math.round(Config.RESISTANCE_PER_UPGRADE.get() * 100))
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable(key + ".effect").withStyle(ChatFormatting.AQUA));
        }
        if (type == Type.AEGIS) {
            tooltip.add(Component.translatable("item.forcefields.upgrade.creative_only").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        tooltip.add(Component.translatable("item.forcefields.upgrade.cost", Math.round(Config.UPGRADE_STRESS_FACTOR.get() * 100))
                .withStyle(ChatFormatting.GOLD));
    }
}
