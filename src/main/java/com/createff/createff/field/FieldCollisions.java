package com.createff.createff.field;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlockEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Makes fields that deny entry solid for everything outside them, like the world border.
 * Called from a mixin into entity movement on both sides, so the client predicts the wall and
 * nobody gets pulled back. The server reads the {@link FieldManager}, the client the synced cores.
 */
public final class FieldCollisions {
    private static final double EPS = 1.0E-7;

    /** Shortens {@code movement} so that {@code entity} does not enter a field it may not enter. */
    public static Vec3 restrict(@Nullable Entity entity, Vec3 movement, AABB box, Level level) {
        if (entity == null || movement.lengthSqr() < 1.0E-12) {
            return movement;
        }
        // Projectiles and primed TNT meet the wall in FieldProjectiles, which bounces them or sets them off there. If
        // this stopped them first, on a round wall they would slide along it and never be seen crossing it: stuck
        // against the wall instead of bouncing off.
        if (Config.PROJECTILES_COLLIDE.get() && (entity instanceof Projectile || entity instanceof PrimedTnt)) {
            return movement;
        }
        AABB sweep = box.expandTowards(movement);
        List<FieldShape> shapes = null;

        if (level instanceof ServerLevel serverLevel) {
            for (Field field : FieldManager.get(serverLevel).all()) {
                if (field.state() == FieldState.ONLINE && field.settings().access(FieldAction.ENTER) != Access.EVERYONE) {
                    shapes = addWall(shapes, field.worldShape(), field.settings(), field.owner(), entity, box, sweep);
                }
            }
        } else if (level.isClientSide) {
            for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
                if (core.getLevel() == level && core.mirrorState() == FieldState.ONLINE
                        && core.mirrorSettings().access(FieldAction.ENTER) != Access.EVERYONE) {
                    shapes = addWall(shapes, core.worldShape(), core.mirrorSettings(), core.ownerUuid(), entity, box, sweep);
                }
            }
        }
        if (shapes == null) {
            return movement;
        }

        List<AABB> cubes = null;
        for (FieldShape shape : shapes) {
            if (!shape.isSphere() && !shape.isOriented()) {
                if (cubes == null) {
                    cubes = new ArrayList<>(1);
                }
                cubes.add(shape.bounds());
            }
        }
        Vec3 allowed = cubes == null ? movement : collide(movement, box, cubes);
        for (FieldShape shape : shapes) {
            if (shape.isSphere()) {
                allowed = slideAroundShape(allowed, box, shape);
            } else if (shape.isOriented()) {
                allowed = slideAroundShape(allowed, box, shape);
            }
        }
        if (entity instanceof Player && !allowed.equals(movement)) {
            FieldDebug.collision(entity, movement, allowed, box, shapes);
        }
        return allowed;
    }

    @Nullable
    private static List<FieldShape> addWall(@Nullable List<FieldShape> walls, FieldShape shape, FieldSettings settings,
            @Nullable UUID owner, Entity entity, AABB box, AABB sweep) {
        // Not near, or inside already: no wall, so whoever is inside can still walk out. "Inside" means the
        // centre of the body has crossed the wall; judging by any overlap at all lets an entity that is
        // merely pressed a little into the wall (jumping, falling, being pushed) walk straight through.
        if (!sweep.intersects(shape.bounds())) {
            return walls;
        }
        if (shape.contains(box.getCenter()) || isExempt(entity, settings, owner)) {
            return walls;
        }
        if (walls == null) {
            walls = new ArrayList<>(1);
        }
        walls.add(shape);
        return walls;
    }


    /** A surface whose normal points up more than this (a slope of up to about 60 degrees) is stood on, not slid down. */
    private static final double FLOOR_NORMAL = 0.5;

    /**
     * The wall of a sphere or of a box turned by a ship. The entity is treated as an upright capsule, a vertical line
     * with a radius, instead of as the box it really is: a box has corners, and sliding a box along a curved or tilted
     * wall flips between corner and edge and gets stuck on slopes, where a capsule just rolls along. How far the
     * capsule is from the wall is how far the wall's own distance field says its points are, less its radius, which is
     * negative when it is pressed in. The entity may not end up closer than it is, or than touching. See {@link #slide}.
     */
    private static Vec3 slideAroundShape(Vec3 movement, AABB box, FieldShape wall) {
        double floor = Math.min(0.0, clearance(box, wall)) - EPS;
        return slide(movement, box, moved -> clearance(moved, wall), moved -> outwardAt(moved, wall), floor);
    }

    /** The points of the capsule an entity's box stands for: its centre line, from the bottom of its round end to the top of the other. */
    private static Vec3[] spine(AABB box, double radius) {
        double reach = Math.min(radius, box.getYsize() / 2);
        double bottom = box.minY + reach;
        double top = box.maxY - reach;
        double x = (box.minX + box.maxX) / 2, z = (box.minZ + box.maxZ) / 2;
        if (top - bottom < 1.0E-6) {
            return new Vec3[] { new Vec3(x, (box.minY + box.maxY) / 2, z) };
        }
        return new Vec3[] { new Vec3(x, bottom, z), new Vec3(x, (bottom + top) / 2, z), new Vec3(x, top, z) };
    }

    private static double radiusOf(AABB box) {
        return Math.max(box.getXsize(), box.getZsize()) / 2;
    }

    /** How far the entity is from the wall, 0 when it touches it and negative when it is pressed into it. */
    private static double clearance(AABB box, FieldShape wall) {
        double radius = radiusOf(box);
        double nearest = Double.MAX_VALUE;
        for (Vec3 point : spine(box, radius)) {
            nearest = Math.min(nearest, wall.signedDistance(point));
        }
        return nearest - radius;
    }

    /** The direction the wall faces where the entity touches it. */
    private static Vec3 outwardAt(AABB box, FieldShape wall) {
        double radius = radiusOf(box);
        double nearest = Double.MAX_VALUE;
        Vec3 at = null;
        for (Vec3 point : spine(box, radius)) {
            double distance = wall.signedDistance(point);
            if (distance < nearest) {
                nearest = distance;
                at = point;
            }
        }
        return wall.gradient(at);
    }

    /**
     * Cuts a move that would go into a curved or turned wall down to what is allowed. {@code measure} says how far the
     * entity is from the wall (more is farther) and {@code outward} which way the wall faces where it touches; the
     * entity may not end up with less than {@code floor}.
     *
     * <p>A wall that faces up (up to a steep slope) is a floor: what is asked of the entity sideways is slid along it,
     * and the fall that gravity asks is only taken as far as the surface, never turned into a slide down it. Without
     * that, standing on a tilted face would slowly take the entity down the slope, as if it were ice. Anything else
     * is slid along, and if that is still too much, cut to the part that fits.
     */
    private static Vec3 slide(Vec3 movement, AABB box, ToDoubleFunction<AABB> measure, Function<AABB, Vec3> outward, double floor) {
        if (measure.applyAsDouble(box.move(movement)) >= floor) {
            return movement;
        }
        if (movement.y < 0 && outward.apply(box.move(movement)).y > FLOOR_NORMAL) {
            // Walking onto a floor that rises: the sideways move is kept whole and the entity is lifted by as much as
            // the floor needs, as if it climbed a ramp. Sliding along the surface instead would shorten the sideways
            // move, and the game takes the speed away from whatever was cut short, so going up would crawl.
            Vec3 level = new Vec3(movement.x, 0, movement.z);
            if (measure.applyAsDouble(box.move(level)) < floor) {
                double most = Math.hypot(level.x, level.z) * 2.0;
                if (measure.applyAsDouble(box.move(level.add(0, most, 0))) >= floor) {
                    double low = 0.0, high = most;
                    for (int i = 0; i < 16; i++) {
                        double middle = (low + high) / 2;
                        if (measure.applyAsDouble(box.move(level.add(0, middle, 0))) >= floor) {
                            high = middle;
                        } else {
                            low = middle;
                        }
                    }
                    level = level.add(0, high, 0);
                } else {
                    // Too steep to walk onto: slid along instead.
                    level = slideAlong(level, box, measure, outward, floor);
                }
            }
            AABB over = box.move(level);
            double fits = 0.0, tooFar = 1.0;
            for (int i = 0; i < 16; i++) {
                double middle = (fits + tooFar) / 2;
                if (measure.applyAsDouble(over.move(0, movement.y * middle, 0)) >= floor) {
                    fits = middle;
                } else {
                    tooFar = middle;
                }
            }
            return level.add(0, movement.y * fits, 0);
        }
        return slideAlong(movement, box, measure, outward, floor);
    }

    private static Vec3 slideAlong(Vec3 movement, AABB box, ToDoubleFunction<AABB> measure, Function<AABB, Vec3> outward, double floor) {
        if (measure.applyAsDouble(box.move(movement)) >= floor) {
            return movement;
        }
        // First drop the part of the move that goes into the wall. That leaves a move along the surface where the wall
        // was when the move ended, which on a curved or tilted wall still ends a little inside it, so what is left is
        // pushed straight out; a few rounds of that put the entity on the surface, not in it and not at a standstill.
        Vec3 move = movement;
        for (int i = 0; i < 4; i++) {
            double clearance = measure.applyAsDouble(box.move(move));
            if (clearance >= floor) {
                return move;
            }
            Vec3 normal = outward.apply(box.move(move));
            double into = move.dot(normal);
            if (i == 0 && into < 0) {
                move = move.subtract(normal.scale(into));
            } else {
                move = move.add(normal.scale(floor - clearance + 1.0E-6));
            }
        }

        double fits = 0.0, tooFar = 1.0;
        for (int i = 0; i < 16; i++) {
            double middle = (fits + tooFar) / 2;
            if (measure.applyAsDouble(box.move(move.scale(middle))) >= floor) {
                fits = middle;
            } else {
                tooFar = middle;
            }
        }
        return move.scale(fits);
    }

    /** Whether the wall of a field with these settings lets this entity through: a permitted player, or something one rides. */
    public static boolean mayPass(Entity entity, FieldSettings settings, @Nullable UUID owner) {
        return isExempt(entity, settings, owner);
    }

    private static boolean isExempt(Entity entity, FieldSettings settings, @Nullable UUID owner) {
        if (isExemptPlayer(entity, settings, owner)) {
            return true;
        }
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (isExemptPlayer(passenger, settings, owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExemptPlayer(Entity entity, FieldSettings settings, @Nullable UUID owner) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        if (player.isSpectator() || settings.allows(FieldAction.ENTER, settings.roleOf(player.getUUID(), owner))) {
            return true;
        }
        // The client has no copy of the server config, so it assumes the default.
        boolean bypass = entity.level().isClientSide || Config.CREATIVE_OPS_BYPASS.get();
        return bypass && player.isCreative() && player.hasPermissions(2);
    }


    /**
     * Same axis order as vanilla: Y first, then the dominant horizontal axis.
     * Vanilla's shape collision lets an entity through once it is more than 1e-7 into a shape,
     * which happens often enough while jumping or falling at the wall, so the walls use their own.
     */
    private static Vec3 collide(Vec3 movement, AABB box, List<AABB> walls) {
        double dx = movement.x, dy = movement.y, dz = movement.z;
        if (dy != 0) {
            dy = limit(1, box, walls, dy);
            box = box.move(0, dy, 0);
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0) {
            dz = limit(2, box, walls, dz);
            box = box.move(0, 0, dz);
        }
        if (dx != 0) {
            dx = limit(0, box, walls, dx);
            box = box.move(dx, 0, 0);
        }
        if (!zFirst && dz != 0) {
            dz = limit(2, box, walls, dz);
        }
        return new Vec3(dx, dy, dz);
    }

    /**
     * Limits {@code move} along {@code axis} (0 = x, 1 = y, 2 = z) so {@code box} does not go deeper
     * into a wall. An entity whose centre is already past a face is inside and not stopped by it.
     */
    private static double limit(int axis, AABB box, List<AABB> walls, double move) {
        double boxMin = min(box, axis), boxMax = max(box, axis);
        double center = (boxMin + boxMax) / 2;
        for (AABB wall : walls) {
            if (!overlapsOtherAxes(box, wall, axis)) {
                continue;
            }
            if (move > 0 && center <= min(wall, axis)) {
                move = Math.min(move, Math.max(0, min(wall, axis) - boxMax));
            } else if (move < 0 && center >= max(wall, axis)) {
                move = Math.max(move, Math.min(0, max(wall, axis) - boxMin));
            }
        }
        return move;
    }

    private static boolean overlapsOtherAxes(AABB a, AABB b, int axis) {
        for (int i = 0; i < 3; i++) {
            if (i != axis && (min(a, i) >= max(b, i) - EPS || max(a, i) <= min(b, i) + EPS)) {
                return false;
            }
        }
        return true;
    }

    private static double min(AABB box, int axis) {
        return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
    }

    private static double max(AABB box, int axis) {
        return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
    }

    private FieldCollisions() {
    }
}
