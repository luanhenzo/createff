package com.createff.createff.field;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The geometry of a field: what it covers and where its wall is. Everything that asks "is this inside?", "how far
 * is it?" or "where does this path cross the wall?" goes through here, so the server and the client agree and a new
 * shape only has to be taught in one place.
 *
 * <p>A cube reaches a number of blocks from the core on each of its six sides. A sphere is centred on the core block
 * and reaches {@code radius} blocks from it in every direction, which makes its bounding box the same as the cube of
 * that reach. A block is covered when its centre is inside.
 *
 * <p>The field of a projector on a ship is also met in the world, where the ship is and turned the way it is turned. A
 * sphere only moves; a cube becomes an <em>oriented box</em>: the same box, carried and rotated by the ship's pose, so
 * its sides stay the sides of the ship.
 */
public final class FieldShape {
    public enum Type {
        CUBE, SPHERE
    }

    private final Type type;
    private final BlockPos core;
    private final int[] extents;
    private final int radius;
    private final AABB bounds;
    private final Vec3 center;
    private final double sphereRadius;

    /** For an oriented box: how the box is turned (local axes to world), the same inverted, and half its size per local axis. */
    @Nullable
    private final Quaterniond orientation;
    @Nullable
    private final Quaterniond inverse;
    private final Vec3 half;
    /** How the ship a sphere is on is turned, which turns whatever is fixed to the field with it; null off a ship. */
    @Nullable
    private final Quaterniond turn;
    /** For a box turned by a ship: where the projector is, from the middle of the box, along the box's own axes. */
    private final Vec3 coreOffset;

    private FieldShape(Type type, BlockPos core, int[] extents, int radius, Vec3 center, double sphereRadius,
            @Nullable Quaterniond orientation, Vec3 half, @Nullable Quaterniond turn, Vec3 coreOffset) {
        this.turn = turn == null ? null : new Quaterniond(turn);
        this.coreOffset = coreOffset;
        this.type = type;
        this.core = core.immutable();
        this.extents = extents.clone();
        this.radius = radius;
        this.center = center;
        this.sphereRadius = sphereRadius;
        this.orientation = orientation;
        this.inverse = orientation == null ? null : new Quaterniond(orientation).invert();
        this.half = half;
        if (type == Type.SPHERE) {
            this.bounds = new AABB(center.x - sphereRadius, center.y - sphereRadius, center.z - sphereRadius,
                    center.x + sphereRadius, center.y + sphereRadius, center.z + sphereRadius);
        } else if (orientation != null) {
            this.bounds = orientedBounds(center, orientation, half);
        } else {
            this.bounds = cubeBounds(core, extents);
        }
    }

    public static FieldShape of(Type type, BlockPos core, int[] extents, int radius) {
        return new FieldShape(type, core, extents, radius, Vec3.atCenterOf(core), radius + 0.5, null, Vec3.ZERO, null, Vec3.ZERO);
    }

    /**
     * A sphere placed anywhere, for the field of a projector on a ship as it is in the world: the centre follows the
     * ship, and the radius its scale. The only thing it is for is answering where things are relative to the wall.
     */
    public static FieldShape sphereAt(Vec3 center, double sphereRadius, @Nullable Quaterniond turn) {
        BlockPos core = BlockPos.containing(center);
        return new FieldShape(Type.SPHERE, core, new int[6], (int) Math.round(sphereRadius - 0.5), center, sphereRadius, null, Vec3.ZERO,
                turn, Vec3.ZERO);
    }

    /**
     * A cube as it is in the world on a ship: centred at {@code center}, turned by {@code orientation} (the ship's
     * rotation) and with {@code half} as half of its size along each of its own axes.
     * {@code coreOffset} is where the projector is from the middle of the box, along the box's own axes.      */
    public static FieldShape orientedBox(BlockPos core, Vec3 center, Quaterniond orientation, Vec3 half, Vec3 coreOffset) {
        return new FieldShape(Type.CUBE, core, new int[6], 0, center, 0, new Quaterniond(orientation), half, null, coreOffset);
    }

    /** The box of a cube with these reaches, indexed by {@link Direction#get3DDataValue()}. */
    public static AABB cubeBounds(BlockPos core, int[] extents) {
        return new AABB(
                core.getX() - extents[Direction.WEST.get3DDataValue()],
                core.getY() - extents[Direction.DOWN.get3DDataValue()],
                core.getZ() - extents[Direction.NORTH.get3DDataValue()],
                core.getX() + extents[Direction.EAST.get3DDataValue()] + 1,
                core.getY() + extents[Direction.UP.get3DDataValue()] + 1,
                core.getZ() + extents[Direction.SOUTH.get3DDataValue()] + 1);
    }

    /** The smallest axis-aligned box around a turned box: its half size along each world axis is what its three edges add up to. */
    private static AABB orientedBounds(Vec3 center, Quaterniond orientation, Vec3 half) {
        Vector3d x = orientation.transform(new Vector3d(half.x, 0, 0));
        Vector3d y = orientation.transform(new Vector3d(0, half.y, 0));
        Vector3d z = orientation.transform(new Vector3d(0, 0, half.z));
        double ex = Math.abs(x.x) + Math.abs(y.x) + Math.abs(z.x);
        double ey = Math.abs(x.y) + Math.abs(y.y) + Math.abs(z.y);
        double ez = Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z);
        return new AABB(center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez);
    }

    public Type type() {
        return type;
    }

    public boolean isSphere() {
        return type == Type.SPHERE;
    }

    /** Whether this is a cube turned by a ship, rather than one with its sides along the world axes. */
    public boolean isOriented() {
        return orientation != null;
    }

    public BlockPos core() {
        return core;
    }

    public int extent(Direction side) {
        return extents[side.get3DDataValue()];
    }

    public int[] extents() {
        return extents.clone();
    }

    /** The reach of a sphere in blocks from the core, whatever the type: it is kept while the field is a cube. */
    public int radius() {
        return radius;
    }

    /** The smallest box around the field. For a cube on the ground, the field itself. */
    public AABB bounds() {
        return bounds;
    }

    /**
     * The middle of the core block for a field on the ground, which is the centre of a sphere; the middle of the box
     * for an oriented one.
     */
    public Vec3 center() {
        return center;
    }

    /** The distance from {@link #center()} to the wall of a sphere. */
    public double sphereRadius() {
        return sphereRadius;
    }

    /** Half the size of an oriented box along each of its own axes. */
    public Vec3 half() {
        return half;
    }

    /** The box in its own space, centred on the origin, for drawing a turned box with the matrix that turns it. */
    public AABB localBox() {
        return new AABB(-half.x, -half.y, -half.z, half.x, half.y, half.z);
    }

    /** The turn of an oriented box, from its own axes to the world's. */
    @Nullable
    public Quaterniond orientation() {
        return orientation == null ? null : new Quaterniond(orientation);
    }

    /**
     * How the ship the field is on is turned: for a box, the turn of the box itself, for a sphere the turn of the ship.
     * Null for a field on the ground. Whatever is fixed to the field, such as a gate, turns with it.
     */
    @Nullable
    public Quaterniond turn() {
        return orientation != null ? orientation() : turn == null ? null : new Quaterniond(turn);
    }

    /** For a box turned by a ship, where the projector is from the middle of the box, along the box's own axes. */
    public Vec3 coreOffset() {
        return coreOffset;
    }

    /** An oriented box's own axes, as directions in the world. */

    public Vec3[] axes() {
        Vector3d x = orientation.transform(new Vector3d(1, 0, 0));
        Vector3d y = orientation.transform(new Vector3d(0, 1, 0));
        Vector3d z = orientation.transform(new Vector3d(0, 0, 1));
        return new Vec3[] { new Vec3(x.x, x.y, x.z), new Vec3(y.x, y.y, y.z), new Vec3(z.x, z.y, z.z) };
    }

    /** A world position in the box's own space, relative to its middle. For a field that is not oriented, relative to {@link #center()}. */
    public Vec3 toLocal(Vec3 world) {
        Vector3d v = new Vector3d(world.x - center.x, world.y - center.y, world.z - center.z);
        if (inverse != null) {
            inverse.transform(v);
        }
        return new Vec3(v.x, v.y, v.z);
    }

    /** The reverse of {@link #toLocal(Vec3)}. */
    public Vec3 fromLocal(Vec3 local) {
        Vector3d v = new Vector3d(local.x, local.y, local.z);
        if (orientation != null) {
            orientation.transform(v);
        }
        return new Vec3(v.x + center.x, v.y + center.y, v.z + center.z);
    }

    /**
     * Where a point that was fixed to the field when the field was {@code last} is now: the same place on it. For a
     * ship that has moved and turned, it is how something is carried along with the wall, so that what it does
     * relative to the wall can be told from what the wall did to it.
     */
    public Vec3 carry(FieldShape last, Vec3 point) {
        return fromLocal(last.toLocal(point));
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    public boolean contains(Vec3 point) {
        return contains(point.x, point.y, point.z);
    }

    public boolean contains(double x, double y, double z) {
        if (type == Type.SPHERE) {
            double dx = x - center.x, dy = y - center.y, dz = z - center.z;
            return dx * dx + dy * dy + dz * dz < sphereRadius * sphereRadius;
        }
        if (orientation != null) {
            Vec3 l = toLocal(new Vec3(x, y, z));
            return Math.abs(l.x) <= half.x && Math.abs(l.y) <= half.y && Math.abs(l.z) <= half.z;
        }
        return bounds.contains(x, y, z);
    }

    /** Distance from {@code point} to the field, 0 if inside. */
    public double distanceTo(Vec3 point) {
        if (type == Type.SPHERE) {
            return Math.max(0.0, point.distanceTo(center) - sphereRadius);
        }
        if (orientation != null) {
            Vec3 l = toLocal(point);
            double dx = Math.max(Math.abs(l.x) - half.x, 0);
            double dy = Math.max(Math.abs(l.y) - half.y, 0);
            double dz = Math.max(Math.abs(l.z) - half.z, 0);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        double dx = Math.max(Math.max(bounds.minX - point.x, 0), point.x - bounds.maxX);
        double dy = Math.max(Math.max(bounds.minY - point.y, 0), point.y - bounds.maxY);
        double dz = Math.max(Math.max(bounds.minZ - point.z, 0), point.z - bounds.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Where the path from {@code from} to {@code to} first meets the wall coming from outside, if it does. */
    public Optional<Vec3> clip(Vec3 from, Vec3 to) {
        if (orientation != null) {
            return clipOriented(from, to);
        }
        if (type != Type.SPHERE) {
            return bounds.clip(from, to);
        }
        Vec3 path = to.subtract(from);
        double a = path.lengthSqr();
        if (a < 1.0E-12) {
            return Optional.empty();
        }
        Vec3 offset = from.subtract(center);
        double b = 2.0 * offset.dot(path);
        double c = offset.lengthSqr() - sphereRadius * sphereRadius;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0) {
            return Optional.empty();
        }
        double t = (-b - Math.sqrt(discriminant)) / (2.0 * a);
        return t >= 0.0 && t <= 1.0 ? Optional.of(from.add(path.scale(t))) : Optional.empty();
    }

    /** The same path as seen from inside the box's own space, where the box is straight and the slabs can be tested one axis at a time. */
    private Optional<Vec3> clipOriented(Vec3 from, Vec3 to) {
        Vec3 start = toLocal(from);
        Vec3 end = toLocal(to);
        if (Math.abs(start.x) <= half.x && Math.abs(start.y) <= half.y && Math.abs(start.z) <= half.z) {
            return Optional.empty();
        }
        double enter = 0.0, leave = 1.0;
        double[] s = { start.x, start.y, start.z };
        double[] e = { end.x, end.y, end.z };
        double[] h = { half.x, half.y, half.z };
        for (int axis = 0; axis < 3; axis++) {
            double move = e[axis] - s[axis];
            if (Math.abs(move) < 1.0E-12) {
                if (Math.abs(s[axis]) > h[axis]) {
                    return Optional.empty();
                }
                continue;
            }
            double t1 = (-h[axis] - s[axis]) / move;
            double t2 = (h[axis] - s[axis]) / move;
            enter = Math.max(enter, Math.min(t1, t2));
            leave = Math.min(leave, Math.max(t1, t2));
            if (enter > leave) {
                return Optional.empty();
            }
        }
        return Optional.of(from.add(to.subtract(from).scale(enter)));
    }

    /** The direction pointing away from the field at a point of its wall. */
    public Vec3 outwardNormal(Vec3 surfacePoint) {
        if (type == Type.SPHERE) {
            Vec3 away = surfacePoint.subtract(center);
            return away.lengthSqr() < 1.0E-12 ? new Vec3(0, 1, 0) : away.normalize();
        }
        if (orientation != null) {
            Vec3 l = toLocal(surfacePoint);
            double rx = half.x > 0 ? Math.abs(l.x) / half.x : 0;
            double ry = half.y > 0 ? Math.abs(l.y) / half.y : 0;
            double rz = half.z > 0 ? Math.abs(l.z) / half.z : 0;
            Vector3d n;
            if (rx >= ry && rx >= rz) {
                n = new Vector3d(Math.signum(l.x), 0, 0);
            } else if (ry >= rz) {
                n = new Vector3d(0, Math.signum(l.y), 0);
            } else {
                n = new Vector3d(0, 0, Math.signum(l.z));
            }
            orientation.transform(n);
            return new Vec3(n.x, n.y, n.z);
        }
        double eps = 1.0E-4;
        if (Math.abs(surfacePoint.x - bounds.minX) < eps) return new Vec3(-1, 0, 0);
        if (Math.abs(surfacePoint.x - bounds.maxX) < eps) return new Vec3(1, 0, 0);
        if (Math.abs(surfacePoint.y - bounds.minY) < eps) return new Vec3(0, -1, 0);
        if (Math.abs(surfacePoint.y - bounds.maxY) < eps) return new Vec3(0, 1, 0);
        if (Math.abs(surfacePoint.z - bounds.minZ) < eps) return new Vec3(0, 0, -1);
        return new Vec3(0, 0, 1);
    }

    /**
     * The point of the wall nearest to {@code point}, which is inside the field, as where something that ended up there
     * without crossing the wall should be put back.
     */
    public Vec3 surfacePointNear(Vec3 point) {
        if (type == Type.SPHERE) {
            Vec3 away = point.subtract(center);
            Vec3 direction = away.lengthSqr() < 1.0E-12 ? new Vec3(0, 1, 0) : away.normalize();
            return center.add(direction.scale(sphereRadius));
        }
        Vec3 middle = orientation != null ? center : bounds.getCenter();
        Vec3 l = orientation != null ? toLocal(point) : point.subtract(middle);
        Vec3 h = orientation != null ? half : new Vec3(bounds.getXsize() / 2, bounds.getYsize() / 2, bounds.getZsize() / 2);
        double dx = h.x - Math.abs(l.x), dy = h.y - Math.abs(l.y), dz = h.z - Math.abs(l.z);
        if (dx <= dy && dx <= dz) {
            l = new Vec3(Math.signum(l.x == 0 ? 1 : l.x) * h.x, l.y, l.z);
        } else if (dy <= dz) {
            l = new Vec3(l.x, Math.signum(l.y == 0 ? 1 : l.y) * h.y, l.z);
        } else {
            l = new Vec3(l.x, l.y, Math.signum(l.z == 0 ? 1 : l.z) * h.z);
        }
        if (orientation != null) {
            return fromLocal(l);
        }
        return middle.add(l);
    }

    /**
     * How far {@code point} is from the wall of the field: positive outside, negative inside (how deep). The field
     * is the shape this measures, so a point is inside it when this is negative.
     */
    public double signedDistance(Vec3 point) {
        if (type == Type.SPHERE) {
            return point.distanceTo(center) - sphereRadius;
        }
        Vec3 l = boxLocal(point);
        Vec3 h = boxHalf();
        double qx = Math.abs(l.x) - h.x, qy = Math.abs(l.y) - h.y, qz = Math.abs(l.z) - h.z;
        double outside = Math.sqrt(sq(Math.max(qx, 0)) + sq(Math.max(qy, 0)) + sq(Math.max(qz, 0)));
        return outside + Math.min(Math.max(qx, Math.max(qy, qz)), 0);
    }

    /** The direction in which {@link #signedDistance(Vec3)} grows at {@code point}: away from the wall on its outer side, towards it from the inside. */
    public Vec3 gradient(Vec3 point) {
        if (type == Type.SPHERE) {
            Vec3 away = point.subtract(center);
            return away.lengthSqr() < 1.0E-12 ? new Vec3(0, 1, 0) : away.normalize();
        }
        Vec3 l = boxLocal(point);
        Vec3 h = boxHalf();
        double qx = Math.abs(l.x) - h.x, qy = Math.abs(l.y) - h.y, qz = Math.abs(l.z) - h.z;
        double px = Math.max(qx, 0), py = Math.max(qy, 0), pz = Math.max(qz, 0);
        Vec3 local;
        if (px > 0 || py > 0 || pz > 0) {
            // Outside: away from the nearest point of the box.
            local = new Vec3(Math.signum(l.x) * px, Math.signum(l.y) * py, Math.signum(l.z) * pz).normalize();
        } else if (qx >= qy && qx >= qz) {
            // Inside: out through the nearest face.
            local = new Vec3(Math.signum(l.x == 0 ? 1 : l.x), 0, 0);
        } else if (qy >= qz) {
            local = new Vec3(0, Math.signum(l.y == 0 ? 1 : l.y), 0);
        } else {
            local = new Vec3(0, 0, Math.signum(l.z == 0 ? 1 : l.z));
        }
        if (orientation == null) {
            return local;
        }
        Vector3d world = orientation.transform(new Vector3d(local.x, local.y, local.z));
        return new Vec3(world.x, world.y, world.z);
    }

    /** A world point in the space of a box field, relative to its middle: turned for an oriented one, only moved otherwise. */
    private Vec3 boxLocal(Vec3 world) {
        return orientation != null ? toLocal(world) : world.subtract(bounds.getCenter());
    }

    private Vec3 boxHalf() {
        return orientation != null ? half : new Vec3(bounds.getXsize() / 2, bounds.getYsize() / 2, bounds.getZsize() / 2);
    }

    /** The middle of a box field, turned or not. */
    public Vec3 boxCenter() {
        return orientation != null ? center : bounds.getCenter();
    }

    /** Half the size of a box field along each of its own axes. */
    public Vec3 boxHalfSize() {
        return boxHalf();
    }

    /** The own axes of a box field, as directions in the world: the world's axes unless it is turned. */
    public Vec3[] boxAxes() {
        return orientation != null ? axes() : new Vec3[] { new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1) };
    }

    private static double sq(double value) {
        return value * value;
    }

    /**
     * The point of the wall nearest to any point: straight out from the centre for a sphere, and for a box the point
     * pulled onto the box if it is outside it.
     */
    public Vec3 closestSurfacePoint(Vec3 point) {
        if (type == Type.SPHERE) {
            return surfacePointNear(point);
        }
        boolean oriented = orientation != null;
        Vec3 middle = oriented ? center : bounds.getCenter();
        Vec3 h = oriented ? half : new Vec3(bounds.getXsize() / 2, bounds.getYsize() / 2, bounds.getZsize() / 2);
        Vec3 l = oriented ? toLocal(point) : point.subtract(middle);
        if (Math.abs(l.x) <= h.x && Math.abs(l.y) <= h.y && Math.abs(l.z) <= h.z) {
            return surfacePointNear(point);
        }
        Vec3 pulled = new Vec3(Math.max(-h.x, Math.min(l.x, h.x)), Math.max(-h.y, Math.min(l.y, h.y)),
                Math.max(-h.z, Math.min(l.z, h.z)));
        return oriented ? fromLocal(pulled) : middle.add(pulled);
    }

    /** Moves a point on the wall outward by {@code distance}, further down when it faces down so the feet end up outside. */
    public Vec3 pushOut(Vec3 surfacePoint, double distance) {
        Vec3 normal = outwardNormal(surfacePoint);
        double below = normal.y < 0 ? normal.y * 1.8 : 0;
        return surfacePoint.add(normal.scale(distance)).add(0, below, 0);
    }

    /** Blocks the field covers, for what the core costs to keep up. */
    public long volume() {
        if (type == Type.SPHERE) {
            return Math.round(4.0 / 3.0 * Math.PI * sphereRadius * sphereRadius * sphereRadius);
        }
        if (orientation != null) {
            return Math.round(8.0 * half.x * half.y * half.z);
        }
        return (long) (bounds.getXsize() * bounds.getYsize() * bounds.getZsize());
    }
}
