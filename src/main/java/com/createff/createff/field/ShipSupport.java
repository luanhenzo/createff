package com.createff.createff.field;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Support for fields on ships, from Sable (the physics behind Create Aeronautics). Without Sable nothing here does
 * anything, and no class of Sable's is loaded.
 */
public final class ShipSupport {
    private static Boolean loaded;

    public static boolean available() {
        if (loaded == null) {
            loaded = ModList.get() != null && ModList.get().isLoaded("sable");
        }
        return loaded;
    }

    /**
     * The field as it is in the world when its core is on a ship, now and a tick ago, or null if it is not on
     * one. A sphere only moves with the ship; a cube is carried and turned (an oriented box).
     */
    @Nullable
    public static FieldShape[] server(Level level, FieldShape plot) {
        return available() ? ShipFrames.server(level, plot) : null;
    }

    /** The same for the client, drawn with the interpolated pose when {@code renderPartial} is given. */
    @Nullable
    public static FieldShape client(Level level, FieldShape plot, @Nullable Float renderPartial) {
        return available() ? ShipFrames.client(level, plot, renderPartial) : null;
    }

    /** A world position as a position on the ship of a field, or null if the field is not on one. */
    @Nullable
    public static net.minecraft.world.phys.Vec3 toPlot(Level level, FieldShape plot, net.minecraft.world.phys.Vec3 world) {
        return available() ? ShipFrames.toPlot(level, plot, world) : null;
    }

    /** A position on the ship as it is in the world, at {@code renderPartial} between ticks if given, or null off a ship. */
    @Nullable
    public static net.minecraft.world.phys.Vec3 toWorld(Level level, FieldShape plot, net.minecraft.world.phys.Vec3 onShip,
            @Nullable Float renderPartial) {
        return available() ? ShipFrames.toWorld(level, plot, onShip, renderPartial) : null;
    }

    /**
     * A function from positions on the ship to the world (at {@code renderPartial} between ticks if given), for a field
     * on a ship, or null off a ship. Meant for mapping many points of one drawing without looking the ship up each time.
     */
    @Nullable
    public static java.util.function.UnaryOperator<net.minecraft.world.phys.Vec3> toWorldMapper(Level level, FieldShape plot,
            @Nullable Float renderPartial) {
        return available() ? ShipFrames.toWorldMapper(level, plot, renderPartial) : null;
    }

    /** Pushes ships that may not enter back out of the fields that do not let them in. Does nothing without Sable. */
    public static void repel(net.minecraft.server.level.ServerLevel level, java.util.Collection<Field> fields) {
        if (available() && com.createff.createff.Config.REPEL_SHIPS.get()) {
            ShipRepulsion.tick(level, fields);
        }
    }

    private ShipSupport() {
    }
}
