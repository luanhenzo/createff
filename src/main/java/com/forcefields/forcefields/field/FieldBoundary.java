package com.forcefields.forcefields.field;

import org.jetbrains.annotations.Nullable;

import com.forcefields.forcefields.block.FieldProjectorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * Asks whether something crosses the wall of an online field: a piston reaching across, a fluid flowing in, a
 * dispenser firing into it. Anything that stays on one side, or inside the same field, is none of its business.
 *
 * <p>The server answers from its fields, and the client from the cores it has been sent. Both matter for pistons,
 * which both sides simulate: if only the server refused a move, the client would play it anyway and show a block
 * where it is not.
 */
public final class FieldBoundary {
    /** True when {@code a} and {@code b} are not in the same online field, and at least one of them is in one. */
    public static boolean separates(@Nullable LevelAccessor accessor, BlockPos a, BlockPos b) {
        return fieldAt(accessor, a) != fieldAt(accessor, b);
    }

    /** True when {@code to} is in an online field that {@code from} is not in: something getting in. */
    public static boolean entersField(@Nullable LevelAccessor accessor, BlockPos from, BlockPos to) {
        Object target = fieldAt(accessor, to);
        return target != null && target != fieldAt(accessor, from);
    }

    /** True when an online field covers {@code pos}. */
    public static boolean covered(@Nullable LevelAccessor accessor, BlockPos pos) {
        return fieldAt(accessor, pos) != null;
    }

    /**
     * Something that stands for the online field covering {@code pos}, or null: the field itself on the server, the
     * core block entity on the client. Only meant to be compared with another result of this method.
     */
    @Nullable
    private static Object fieldAt(@Nullable LevelAccessor accessor, BlockPos pos) {
        if (accessor instanceof ServerLevel level) {
            return FieldManager.get(level).coveringField(pos);
        }
        if (accessor instanceof Level level && level.isClientSide) {
            for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
                if (core.getLevel() == level && core.mirrorState().protects()
                        && core.fieldShape().contains(pos)) {
                    return core;
                }
            }
        }
        return null;
    }

    private FieldBoundary() {
    }
}
