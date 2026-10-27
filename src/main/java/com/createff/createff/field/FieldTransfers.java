package com.createff.createff.field;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Keeps blocks outside a field from reaching the items and fluids of a container inside it, whatever mod they come
 * from: hoppers, funnels, chutes, belts, mechanical arms, pipes. They all ask the game for the container's inventory
 * (its capability) and the game gives it to whoever asks, so the answer is refused here when the asker is a block
 * entity outside a field that the container is in.
 *
 * <p>The question does not say who asks, so the game is told which block entity is ticking: that is the one acting.
 * Something that acts outside a block entity's tick, such as an entity, is not seen and is not stopped.
 */
public final class FieldTransfers {
    @Nullable
    private static Level actorLevel;
    @Nullable
    private static BlockPos actor;

    /** What was last known of a field, to tell when the answers given about its containers have gone stale. */
    record Snapshot(boolean protects, AABB bounds) {
    }

    /** A block entity starts its tick. */
    public static void begin(Level level, BlockPos pos) {
        actorLevel = level;
        actor = pos;
    }

    /** The block entity that was ticking is done. */
    public static void end() {
        actor = null;
        actorLevel = null;
    }

    /** True when the block entity ticking now is not allowed to reach the containers at {@code target}. */
    public static boolean denied(Level level, BlockPos target) {
        BlockPos from = actor;
        return from != null && level == actorLevel && Config.BLOCK_TRANSFERS_ACROSS_WALLS.get()
                && FieldBoundary.entersField(level, from, target);
    }

    /**
     * Blocks keep the inventory they were given until told it changed, so when a field goes up, goes down or changes
     * size, everything near it has to be asked again.
     */
    static void invalidateChanged(ServerLevel level, Map<BlockPos, Field> fields, Map<BlockPos, Snapshot> seen) {
        for (Field field : fields.values()) {
            Snapshot now = new Snapshot(field.state().protects(), field.bounds());
            Snapshot before = seen.put(field.core(), now);
            if (before == null ? now.protects() : !before.equals(now)) {
                invalidate(level, now.bounds());
                if (before != null) {
                    invalidate(level, before.bounds());
                }
            }
        }
        if (seen.size() > fields.size()) {
            seen.entrySet().removeIf(entry -> {
                if (fields.containsKey(entry.getKey())) {
                    return false;
                }
                if (entry.getValue().protects()) {
                    invalidate(level, entry.getValue().bounds());
                }
                return true;
            });
        }
    }

    private static void invalidate(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX) >> 4;
        int maxX = (int) Math.floor(bounds.maxX) >> 4;
        int minZ = (int) Math.floor(bounds.minZ) >> 4;
        int maxZ = (int) Math.floor(bounds.maxZ) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.invalidateCapabilities(new ChunkPos(x, z));
            }
        }
    }

    private FieldTransfers() {
    }
}
