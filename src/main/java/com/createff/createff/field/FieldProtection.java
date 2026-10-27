package com.createff.createff.field;

import java.util.UUID;
import java.util.function.BiPredicate;


import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlock;
import com.createff.createff.block.FieldProjectorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a player may do to a spot covered by fields. Answered on both sides: the server from the
 * {@link FieldManager}, the client from the synced cores, so a refused action never even starts on the
 * client (no mining cracks, no placing animation) instead of being undone after the fact.
 */
public final class FieldProtection {
    /** Whether a field covering {@code pos} stops {@code player} from doing {@code action} there. */
    public static boolean isDenied(Level level, BlockPos pos, Player player, FieldAction action) {
        // Only the owner takes the core down, whatever the break rule says.
        boolean core = action == FieldAction.BREAK && level.getBlockState(pos).getBlock() instanceof FieldProjectorBlock;
        return anyCoveringField(level, pos, player, (settings, owner) -> {
            Role role = settings.roleOf(player.getUUID(), owner);
            return !settings.allows(action, role) || (core && role != Role.OWNER);
        });
    }

    /** Whether a field stops {@code player} from using the block at {@code pos} (opening it, pressing it...). */
    public static boolean isInteractionDenied(Level level, BlockPos pos, BlockState state, Player player) {
        return anyCoveringField(level, pos, player, (settings, owner) ->
                settings.roleOf(player.getUUID(), owner) == Role.OTHER && !settings.canInteract(state));
    }

    /**
     * Whether a field stops {@code player} from using {@code stack} on the block at {@code clicked}, which would
     * act on {@code clicked} and, for blocks, on {@code placed} next to it. Blocks need the place rule, other
     * items (buckets, hoes, flint and steel...) need both place and break, unless the owner listed the item.
     */
    public static boolean isItemUseDenied(Level level, BlockPos clicked, BlockPos placed, ItemStack stack, Player player) {
        BiPredicate<FieldSettings, UUID> denies = (settings, owner) -> {
            if (settings.canUseItem(stack)) {
                return false;
            }
            Role role = settings.roleOf(player.getUUID(), owner);
            boolean canPlace = settings.allows(FieldAction.PLACE, role);
            return stack.getItem() instanceof BlockItem
                    ? !canPlace
                    : !(canPlace && settings.allows(FieldAction.BREAK, role));
        };
        return anyCoveringField(level, clicked, player, denies) || anyCoveringField(level, placed, player, denies);
    }

    /** Operators in creative mode are not held back by fields, unless the server turned that off. */
    public static boolean bypasses(Level level, Player player) {
        return player.isCreative() && player.hasPermissions(2) && (level.isClientSide || Config.CREATIVE_OPS_BYPASS.get());
    }

    /**
     * Things a field looks after: decorations, vehicles and the animals and villagers kept inside. Not players, not
     * hostile mobs, and not the loose things that fly around (projectiles, items, falling blocks, primed TNT).
     */
    public static boolean isProtectedEntity(Entity entity) {
        if (entity instanceof Player || entity instanceof Enemy || entity instanceof Projectile || entity instanceof ItemEntity
                || entity instanceof ExperienceOrb || entity instanceof PrimedTnt || entity instanceof FallingBlockEntity) {
            return false;
        }
        return entity instanceof HangingEntity || entity instanceof VehicleEntity || entity instanceof LivingEntity;
    }

    /**
     * Whether a field stops {@code player} from hurting ({@code attack}) or using (right-clicking) {@code target}.
     * Hurting needs the break rule; using it needs to be at least trusted, like using a block.
     */
    public static boolean isEntityDenied(Level level, Entity target, Player player, boolean attack) {
        if (!isProtectedEntity(target)) {
            return false;
        }
        // An entity is where it is in the world, which for something standing on a ship is not where the ship's blocks are.
        Vec3 point = target.position();
        if (attack) {
            return anyCoveringFieldAt(level, point, player,
                    (settings, owner) -> !settings.allows(FieldAction.BREAK, settings.roleOf(player.getUUID(), owner)));
        }
        return anyCoveringFieldAt(level, point, player, (settings, owner) -> settings.roleOf(player.getUUID(), owner) == Role.OTHER);
    }

    private static boolean anyCoveringFieldAt(Level level, Vec3 point, Player player, BiPredicate<FieldSettings, UUID> denies) {
        if (bypasses(level, player)) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            for (Field field : FieldManager.get(serverLevel).all()) {
                if (field.state().protects() && field.coversPoint(point) && denies.test(field.settings(), field.owner())) {
                    return true;
                }
            }
        } else if (level.isClientSide) {
            for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
                if (core.getLevel() == level && core.mirrorState().protects() && core.coversPoint(point)
                        && denies.test(core.mirrorSettings(), core.ownerUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyCoveringField(Level level, BlockPos pos, Player player, BiPredicate<FieldSettings, UUID> denies) {
        if (bypasses(level, player)) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            for (Field field : FieldManager.get(serverLevel).all()) {
                if (field.state().protects() && field.contains(pos) && denies.test(field.settings(), field.owner())) {
                    return true;
                }
            }
        } else if (level.isClientSide) {
            for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
                if (core.getLevel() == level
                        && core.mirrorState().protects()
                        && core.fieldShape().contains(pos)
                        && denies.test(core.mirrorSettings(), core.ownerUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    private FieldProtection() {
    }
}
