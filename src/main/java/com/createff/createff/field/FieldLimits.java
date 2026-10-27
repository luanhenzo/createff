package com.createff.createff.field;

import com.createff.createff.Config;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * How many fields one player may own at once, counted over every dimension. The projector that would be one too many
 * cannot be placed. Operators in creative mode are not held back, like everywhere else.
 */
public final class FieldLimits {
    /** True if {@code player} may place another projector; tells them why when not. */
    public static boolean mayPlace(ServerPlayer player) {
        int max = Config.MAX_FIELDS_PER_PLAYER.get();
        if (max <= 0 || FieldProtection.bypasses(player.level(), player)) {
            return true;
        }
        int owned = owned(player);
        if (owned < max) {
            return true;
        }
        player.displayClientMessage(Component.translatable("message.createff.field_limit", max), true);
        return false;
    }

    /** The fields owned by {@code player}, in every dimension. */
    public static int owned(ServerPlayer player) {
        int count = 0;
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Field field : FieldManager.get(level).all()) {
                if (player.getUUID().equals(field.owner())) {
                    count++;
                }
            }
        }
        return count;
    }

    private FieldLimits() {
    }
}
