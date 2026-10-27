package com.createff.createff.field;

import java.util.Locale;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.Position;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Looks at where things are relative to Sable's sub-levels (ships): in the world, or in the plot a ship is stored in.
 * Everything that touches Sable's classes lives here, so nothing else loads them when Sable is not installed; callers
 * go through {@link FieldDebug}, which checks for Sable first.
 */
final class ShipSpike {
    /** Where {@code pos} is: world or plot, which ship (if any) it belongs to, and the world position it maps to. */
    static String describe(Level level, Vec3 pos) {
        SableCompanion sable = SableCompanion.INSTANCE;
        boolean plot = sable.isInPlotGrid(level, pos);
        SubLevelAccess ship = sable.getContaining(level, pos);
        Vec3 world = sable.projectOutOfSubLevel(level, (Position) pos);
        return String.format(Locale.ROOT, "at=%s inPlotGrid=%s ship=%s worldPos=%s", fmt(pos), plot, ship(ship), fmt(world));
    }

    /** Which ship an entity is on or riding, if any, next to where the game says the entity is. */
    static String describe(Entity entity) {
        SableCompanion sable = SableCompanion.INSTANCE;
        return String.format(Locale.ROOT, "%s | inPlotGrid=%s tracking=%s vehicle=%s containing=%s",
                describe(entity.level(), entity.position()), sable.isInPlotGrid(entity),
                ship(sable.getTrackingSubLevel(entity)), ship(sable.getVehicleSubLevel(entity)),
                ship(sable.getContaining(entity)));
    }

    private static String ship(SubLevelAccess ship) {
        if (ship == null) {
            return "none";
        }
        var pose = ship.logicalPose();
        var q = pose.orientation();
        return String.format(Locale.ROOT, "%s[%s] pose(pos=(%.2f, %.2f, %.2f) rot=(%.3f, %.3f, %.3f, %.3f) pivot=(%.2f, %.2f, %.2f) scale=(%.2f, %.2f, %.2f))",
                ship.getName(), ship.getUniqueId().toString().substring(0, 8),
                pose.position().x(), pose.position().y(), pose.position().z(), q.x(), q.y(), q.z(), q.w(),
                pose.rotationPoint().x(), pose.rotationPoint().y(), pose.rotationPoint().z(),
                pose.scale().x(), pose.scale().y(), pose.scale().z());
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    private ShipSpike() {
    }
}
