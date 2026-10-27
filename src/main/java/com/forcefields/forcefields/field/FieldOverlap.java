package com.forcefields.forcefields.field;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

/**
 * How far two fields overlap and which way to push them apart, for sphere against sphere, sphere against box and box
 * against box (turned or not). Pure geometry, so it is the same however the fields got where they are.
 */
public final class FieldOverlap {
    /** {@code depth} is how far one reaches into the other, and {@code normal} points from the first towards the second. */
    public record Overlap(double depth, Vec3 normal) {
    }

    /** The overlap of {@code a} and {@code b}, or null if they do not touch. */
    @Nullable
    public static Overlap between(FieldShape a, FieldShape b) {
        Overlap overlap;
        if (a.isSphere() && b.isSphere()) {
            overlap = spheres(a, b);
        } else if (a.isSphere()) {
            Overlap flipped = sphereAgainstBox(a, b);
            overlap = flipped == null ? null : new Overlap(flipped.depth(), flipped.normal().scale(-1));
        } else if (b.isSphere()) {
            overlap = sphereAgainstBox(b, a);
        } else {
            overlap = boxes(a, b);
        }
        return overlap == null || overlap.depth() <= 0 ? null : overlap;
    }

    @Nullable
    private static Overlap spheres(FieldShape a, FieldShape b) {
        Vec3 between = b.center().subtract(a.center());
        double distance = between.length();
        double depth = a.sphereRadius() + b.sphereRadius() - distance;
        if (depth <= 0) {
            return null;
        }
        return new Overlap(depth, distance < 1.0E-9 ? new Vec3(0, 1, 0) : between.scale(1.0 / distance));
    }

    /** {@code sphere} against {@code box}; the normal points from the box towards the sphere. */
    @Nullable
    private static Overlap sphereAgainstBox(FieldShape sphere, FieldShape box) {
        Vec3 middle = sphere.center();
        double depth = sphere.sphereRadius() - box.signedDistance(middle);
        return depth <= 0 ? null : new Overlap(depth, box.gradient(middle));
    }

    /**
     * Two boxes along the 15 directions that decide whether they overlap: the edges of each and the cross products
     * between them. They are apart when some direction separates them, and the smallest overlap is the way out.
     */
    @Nullable
    private static Overlap boxes(FieldShape a, FieldShape b) {
        Vec3 offset = b.boxCenter().subtract(a.boxCenter());
        Vec3[] ua = a.boxAxes();
        Vec3[] ub = b.boxAxes();
        Vec3 ha = a.boxHalfSize();
        Vec3 hb = b.boxHalfSize();
        double[] halfA = { ha.x, ha.y, ha.z };
        double[] halfB = { hb.x, hb.y, hb.z };

        double best = -Double.MAX_VALUE;
        Vec3 bestAxis = new Vec3(0, 1, 0);
        for (int i = 0; i < 15; i++) {
            Vec3 axis = i < 3 ? ua[i] : i < 6 ? ub[i - 3] : ua[(i - 6) / 3].cross(ub[(i - 6) % 3]);
            double length = axis.length();
            if (length < 1.0E-6) {
                continue;
            }
            axis = axis.scale(1.0 / length);
            double reachA = 0, reachB = 0;
            for (int k = 0; k < 3; k++) {
                reachA += halfA[k] * Math.abs(axis.dot(ua[k]));
                reachB += halfB[k] * Math.abs(axis.dot(ub[k]));
            }
            double along = offset.dot(axis);
            double separation = Math.abs(along) - reachA - reachB;
            if (separation > best) {
                best = separation;
                bestAxis = along >= 0 ? axis : axis.scale(-1);
            }
        }
        return best >= 0 ? null : new Overlap(-best, bestAxis);
    }

    private FieldOverlap() {
    }
}
