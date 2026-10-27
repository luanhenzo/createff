package com.createff.createff.field;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.block.FieldProjectorBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** All fields of one dimension. The authority for field state; core block entities only mirror it. */
public class FieldManager extends SavedData {
    private static final String NAME = "createff_fields";

    private final Map<BlockPos, Field> fields = new HashMap<>();

    public static FieldManager get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FieldManager::new, FieldManager::load, null), NAME);
    }

    private static FieldManager load(CompoundTag tag, HolderLookup.Provider registries) {
        FieldManager manager = new FieldManager();
        for (Tag entry : tag.getList("Fields", Tag.TAG_COMPOUND)) {
            Field field = Field.load((CompoundTag) entry);
            manager.fields.put(field.core(), field);
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Field field : fields.values()) {
            list.add(field.save());
        }
        tag.put("Fields", list);
        return tag;
    }

    @Nullable
    public Field get(BlockPos core) {
        return fields.get(core);
    }

    public Collection<Field> all() {
        return fields.values();
    }

    public Field add(Field field) {
        fields.put(field.core(), field);
        setDirty();
        return field;
    }

    public void remove(BlockPos core) {
        if (fields.remove(core) != null) {
            setDirty();
        }
    }

    /** True when an online field covering {@code pos} does not let {@code who} do {@code action}. */
    public boolean isDenied(BlockPos pos, @Nullable UUID who, FieldAction action) {
        for (Field field : fields.values()) {
            if (field.state().protects() && field.contains(pos) && !field.allows(action, who)) {
                return true;
            }
        }
        return false;
    }

    /** The online field that covers {@code pos}, if any. Fields never overlap, so there is at most one. */
    @Nullable
    public Field coveringField(BlockPos pos) {
        for (Field field : fields.values()) {
            if (field.state().protects() && field.contains(pos)) {
                return field;
            }
        }
        return null;
    }

    /** The online field that covers a point of an entity or an explosion, in the world or in a ship's plot. */
    @Nullable
    public Field coveringFieldAt(Vec3 point) {
        for (Field field : fields.values()) {
            if (field.state().protects() && field.coversPoint(point)) {
                return field;
            }
        }
        return null;
    }

    public boolean isCoveredAt(Vec3 point) {
        return coveringFieldAt(point) != null;
    }

    /** True when an online field covers {@code pos}, whoever is asking. */
    public boolean isCovered(BlockPos pos) {
        for (Field field : fields.values()) {
            if (field.state().protects() && field.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** True if a field with these bounds would overlap an existing field. */
    public boolean overlaps(AABB bounds, @Nullable BlockPos ignoreCore) {
        for (Field field : fields.values()) {
            if (!field.core().equals(ignoreCore) && field.bounds().intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets fields whose projector is gone, for example from a world saved before the projector existed. */
    public void removeOrphans(ServerLevel level) {
        boolean removed = fields.values().removeIf(field -> {
            boolean orphan = level.isLoaded(field.core())
                    && !(level.getBlockState(field.core()).getBlock() instanceof FieldProjectorBlock);
            if (orphan) {
                FieldDebug.ship(level, "orphan field removed: no projector at its core (owner " + field.ownerName()
                        + ", " + field.state() + ", overload " + Math.round(field.overload()) + ")",
                        net.minecraft.world.phys.Vec3.atCenterOf(field.core()));
            }
            return orphan;
        });
        if (removed) {
            setDirty();
        }
    }

    public void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (Field field : fields.values()) {
            field.updateFrame(level);
            if (field.tick(now)) {
                setDirty();
            }
        }
        ShipSupport.repel(level, fields.values());
    }
}
