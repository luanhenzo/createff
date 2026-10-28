package com.createff.createff.field;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * Where the sphere of a field on a ship is in the world. A ship's blocks live in a plot far from the world, and the
 * ship's pose says where they are drawn and felt; mapping the centre and the radius through it gives the sphere the
 * rest of the world meets. A sphere does not change when it turns, so the centre and the scale are all that is needed.
 *
 * <p>Everything that touches Sable's classes is here, and only {@link ShipSupport} calls it, after checking that Sable
 * is installed.
 */
final class ShipFrames {
    /** The sphere now and one tick ago, or null when the core is not on a ship. */
    @Nullable
    static FieldShape[] server(Level level, FieldShape plot) {
        SubLevelAccess ship = SableCompanion.INSTANCE.getContaining(level, plot.center());
        if (ship == null) {
            return null;
        }
        return new FieldShape[] { sphere(ship.logicalPose(), plot), sphere(ship.lastPose(), plot) };
    }

    /** The sphere on the client: where the ship is for physics, or where it is drawn with {@code renderPartial} given. */
    @Nullable
    static FieldShape client(Level level, FieldShape plot, @Nullable Float renderPartial) {
        ClientSubLevelAccess ship = SableCompanion.INSTANCE.getContainingClient(plot.center());
        if (ship == null) {
            return null;
        }
        return sphere(renderPartial == null ? ship.logicalPose() : ship.renderPose(renderPartial), plot);
    }

    /** A world position as a position on the ship the field is on (in its plot), or null off a ship. */
    @Nullable
    static Vec3 toPlot(Level level, FieldShape plot, Vec3 world) {
        ClientSubLevelAccess ship = SableCompanion.INSTANCE.getContainingClient(plot.center());
        return ship == null ? null : ship.logicalPose().transformPositionInverse(world);
    }

    /** A position on the ship as it is in the world now, or drawn at {@code renderPartial} between ticks. */
    @Nullable
    static Vec3 toWorld(Level level, FieldShape plot, Vec3 onShip, @Nullable Float renderPartial) {
        ClientSubLevelAccess ship = SableCompanion.INSTANCE.getContainingClient(plot.center());
        if (ship == null) {
            return null;
        }
        return (renderPartial == null ? ship.logicalPose() : ship.renderPose(renderPartial)).transformPosition(onShip);
    }

    /** Maps a position on the ship to the world as it is now or drawn at {@code renderPartial}, or null off a ship. For many points at once. */
    @Nullable
    static UnaryOperator<Vec3> toWorldMapper(Level level, FieldShape plot, @Nullable Float renderPartial) {
        ClientSubLevelAccess ship = SableCompanion.INSTANCE.getContainingClient(plot.center());
        if (ship == null) {
            return null;
        }
        Pose3dc pose = renderPartial == null ? ship.logicalPose() : ship.renderPose(renderPartial);
        return onShip -> pose.transformPosition(onShip);
    }

    /**
     * How far from {@code from} the line to {@code to} first meets a block of a ship, or -1 if it meets none. The blocks of
     * a ship are kept in a plot far from the world, so the game's own ray does not find them: the line is followed in
     * the space of each ship it passes near instead.
     */
    static double firstShipBlock(Level level, Vec3 from, Vec3 to, Entity context) {
        BoundingBox3d around = new BoundingBox3d(Math.min(from.x, to.x), Math.min(from.y, to.y), Math.min(from.z, to.z),
                Math.max(from.x, to.x), Math.max(from.y, to.y), Math.max(from.z, to.z));
        double nearest = -1.0;
        for (SubLevelAccess ship : SableCompanion.INSTANCE.getAllIntersecting(level, around)) {
            Pose3dc pose = ship.logicalPose();
            BlockHitResult hit = level.clip(new ClipContext(pose.transformPositionInverse(from), pose.transformPositionInverse(to),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
            if (hit.getType() == HitResult.Type.BLOCK) {
                double distance = from.distanceTo(pose.transformPosition(hit.getLocation()));
                nearest = nearest < 0.0 ? distance : Math.min(nearest, distance);
            }
        }
        return nearest;
    }

    /** The field as the ship's pose puts it in the world: a sphere only moves, a cube is carried and turned. */

    private static FieldShape sphere(Pose3dc pose, FieldShape plot) {
        double scale = pose.scale().x();
        if (plot.isSphere()) {
            return FieldShape.sphereAt(pose.transformPosition(plot.center()), plot.sphereRadius() * scale, new Quaterniond(pose.orientation()));
        }
        AABB box = plot.bounds();
        Vec3 center = pose.transformPosition(box.getCenter());
        Vec3 half = new Vec3(box.getXsize() / 2 * scale, box.getYsize() / 2 * scale, box.getZsize() / 2 * scale);
        // Where the projector is in the box, so a gate placed from it stays where it was put when the box is not centred on it.
        Vec3 coreOffset = Vec3.atCenterOf(plot.core()).subtract(box.getCenter()).scale(scale);
        return FieldShape.orientedBox(plot.core(), center, new Quaterniond(pose.orientation()), half, coreOffset);
    }

    private ShipFrames() {
    }
}
