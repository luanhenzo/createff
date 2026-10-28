package com.createff.createff.field;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where the gates of a field leave its wall open. The gap a gate makes is its outline cut straight through the wall, and
 * a little to either side of it: in the wall of a cube a box, in the wall of a sphere a piece that follows the curve,
 * since the wall is where the sphere is under the outline, not where a flat plane happens to cut it.
 *
 * <p>A gate belongs to the field, so on a ship it is on the hull and moves and turns with it: it is kept in the field's
 * own axes, and turned into the world's by the ship's turn.
 *
 * <p>The server and the clients ask the same questions of the same gates, which come with the settings of the field,
 * so a body is let through on the one side exactly where it is shown to go through on the other.
 */
public final class Gates {
    /** How far to either side of the wall a gap reaches, along the way through it. */
    public static final double DEPTH = 1.5;

    /** Where a gate is on the wall of a shape and which way it faces: its own axes, for measuring against. */
    public record Frame(Vec3 origin, Vec3 wall, Vec3 normal, Vec3 across, Vec3 up, double radius) {
        /** Whether the wall curves, as that of a sphere does; the origin is then the centre of the sphere. */
        boolean curved() {
            return radius > 0.0;
        }

        /** How far along the normal from the origin the wall is, at this spot of the outline, or NaN if it is not there. */
        double surface(double a, double b) {
            if (!curved()) {
                return 0.0;
            }
            double left = radius * radius - a * a - b * b;
            return left <= 0.0 ? Double.NaN : Math.sqrt(left);
        }
    }

    /** The gates that let things through the wall of this shape now: the open ones, in a wall that has a place for them. */
    public static List<Gate> opening(Collection<Gate> gates, FieldShape shape) {
        List<Gate> result = null;
        for (Gate gate : gates) {
            if (gate.open() && frame(gate, shape) != null) {
                if (result == null) {
                    result = new ArrayList<>(2);
                }
                result.add(gate);
            }
        }
        return result == null ? List.of() : result;
    }

    /** True when a body that fills {@code box} is in the gap of a gate that is open. */
    public static boolean opens(Collection<Gate> gates, FieldShape shape, AABB box) {
        for (Gate gate : gates) {
            if (gate.open() && gapsBox(gate, shape, box)) {
                return true;
            }
        }
        return false;
    }

    /** True when a point is in the gap of a gate that is open. */
    public static boolean opensAt(Collection<Gate> gates, FieldShape shape, Vec3 point) {
        return opens(gates, shape, new AABB(point, point).inflate(0.05));
    }

    /**
     * True when something as big as {@code box}, such as a ship, can go through a gate that is open: it is at the gate,
     * and seen along the way through, it is no bigger than the outline, so no part of it meets the wall beside it.
     */
    public static boolean admits(Collection<Gate> gates, FieldShape shape, AABB box) {
        for (Gate gate : gates) {
            if (gate.open() && fits(gate, shape, box)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fits(Gate gate, FieldShape shape, AABB box) {
        Frame frame = frame(gate, shape);
        if (frame == null || !gapBounds(gate, frame).intersects(box)) {
            return false;
        }
        double[] low = { Double.MAX_VALUE, Double.MAX_VALUE };
        double[] high = { -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (int corner = 0; corner < 8; corner++) {
            Vec3 offset = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                    (corner & 4) == 0 ? box.minZ : box.maxZ).subtract(frame.origin());
            double a = offset.dot(frame.across());
            double b = offset.dot(frame.up());
            low[0] = Math.min(low[0], a);
            high[0] = Math.max(high[0], a);
            low[1] = Math.min(low[1], b);
            high[1] = Math.max(high[1], b);
        }
        for (double a : new double[] { low[0], high[0] }) {
            for (double b : new double[] { low[1], high[1] }) {
                if (!inside(gate, a, b)) {
                    return false;
                }
            }
        }
        return true;
    }

    // --- The frame of a gate ----------------------------------------------------------------------

    /**
     * Where the gate is on the wall of {@code shape} in the world, or null if there is no place for it: a gate with no
     * direction (at the centre) points nowhere.
     */
    @Nullable
    public static Frame frame(Gate gate, FieldShape shape) {
        return build(gate, shape, false);
    }

    /**
     * The same in the space a turned box is drawn in, straight and centred on its middle, which is where the wall is
     * drawn from. For anything else it is the same as {@link #frame}.
     */
    @Nullable
    public static Frame localFrame(Gate gate, FieldShape shape) {
        return build(gate, shape, true);
    }

    @Nullable
    private static Frame build(Gate gate, FieldShape shape, boolean local) {
        boolean oriented = shape.isOriented();
        // Everything is worked out from the middle of the field in its own axes, then turned and moved to where it is.
        Vec3 centre = local && oriented ? Vec3.ZERO : shape.center();
        Quaterniond turn = local ? null : shape.turn();
        Vec3 anchor = oriented ? gate.anchor().add(shape.coreOffset()) : gate.anchor();
        Vec3 normal;
        Vec3 wall;
        double radius = 0.0;
        if (shape.isSphere()) {
            if (anchor.lengthSqr() < 1.0E-6) {
                return null;
            }
            normal = anchor.normalize();
            radius = shape.sphereRadius();
            wall = normal.scale(radius);
        } else {
            double[] low;
            double[] high;
            if (oriented) {
                Vec3 half = shape.half();
                low = new double[] { half.x, half.y, half.z };
                high = low;
            } else {
                AABB box = shape.bounds();
                Vec3 middle = shape.center();
                low = new double[] { middle.x - box.minX, middle.y - box.minY, middle.z - box.minZ };
                high = new double[] { box.maxX - middle.x, box.maxY - middle.y, box.maxZ - middle.z };
            }
            double[] offset = { anchor.x, anchor.y, anchor.z };
            int face = 0;
            double best = -1.0;
            for (int axis = 0; axis < 3; axis++) {
                double reach = Math.max(0.5, offset[axis] >= 0.0 ? high[axis] : low[axis]);
                double ratio = Math.abs(offset[axis]) / reach;
                if (ratio > best) {
                    best = ratio;
                    face = axis;
                }
            }
            if (best <= 0.0) {
                return null;
            }
            double[] point = offset.clone();
            double[] unit = new double[3];
            unit[face] = offset[face] >= 0.0 ? 1.0 : -1.0;
            point[face] = offset[face] >= 0.0 ? high[face] : -low[face];
            normal = new Vec3(unit[0], unit[1], unit[2]);
            wall = new Vec3(point[0], point[1], point[2]);
        }
        // Across is level, up is the way the wall climbs: the opening stands the way a door does. A wall that faces
        // straight up or down has no up, so its outline is turned to the east.
        Vec3 across = Math.abs(normal.y) < 0.95 ? new Vec3(0.0, 1.0, 0.0).cross(normal).normalize() : new Vec3(1.0, 0.0, 0.0);
        Vec3 up = normal.cross(across).normalize();
        Vec3 worldWall = centre.add(turned(turn, wall));
        Vec3 worldNormal = turned(turn, normal);
        Vec3 worldAcross = turned(turn, across);
        Vec3 worldUp = turned(turn, up);
        if (shape.isSphere()) {
            return new Frame(centre, worldWall, worldNormal, worldAcross, worldUp, radius);
        }
        return new Frame(worldWall, worldWall, worldNormal, worldAcross, worldUp, 0.0);
    }

    private static Vec3 turned(@Nullable Quaterniond turn, Vec3 vector) {
        if (turn == null) {
            return vector;
        }
        Vector3d result = turn.transform(new Vector3d(vector.x, vector.y, vector.z));
        return new Vec3(result.x, result.y, result.z);
    }

    // --- The gap -------------------------------------------------------------------------------------

    /** Whether {@code point} is in the gap this gate makes in the wall of {@code shape}. */
    public static boolean gaps(Gate gate, FieldShape shape, Vec3 point) {
        Frame frame = frame(gate, shape);
        return frame != null && gaps(gate, frame, point);
    }

    /** Whether {@code point} is in the gap of this gate, whose place on the wall is {@code frame}. */
    public static boolean gaps(Gate gate, Frame frame, Vec3 point) {
        Vec3 offset = point.subtract(frame.origin());
        double a = offset.dot(frame.across());
        double b = offset.dot(frame.up());
        if (!inside(gate, a, b)) {
            return false;
        }
        double surface = frame.surface(a, b);
        return !Double.isNaN(surface) && Math.abs(offset.dot(frame.normal()) - surface) <= DEPTH;
    }

    /** Whether the outline of the gate takes in the spot {@code a} across and {@code b} up from its middle. */
    private static boolean inside(Gate gate, double a, double b) {
        double halfWidth = gate.width() * 0.5;
        double halfHeight = gate.height() * 0.5;
        if (Math.abs(a) > halfWidth || Math.abs(b) > halfHeight) {
            return false;
        }
        if (gate.shape() == Gate.Shape.OVAL) {
            double x = a / halfWidth;
            double y = b / halfHeight;
            return x * x + y * y <= 1.0;
        }
        return true;
    }

    /** Whether a body that fills {@code box} reaches into the gap this gate makes in the wall of {@code shape}. */
    public static boolean gapsBox(Gate gate, FieldShape shape, AABB box) {
        Frame frame = frame(gate, shape);
        if (frame == null || !gapBounds(gate, frame).intersects(box)) {
            return false;
        }
        double[] low = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
        double[] high = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (int corner = 0; corner < 8; corner++) {
            Vec3 offset = new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                    (corner & 4) == 0 ? box.minZ : box.maxZ).subtract(frame.origin());
            double[] at = { offset.dot(frame.across()), offset.dot(frame.up()), offset.dot(frame.normal()) };
            for (int i = 0; i < 3; i++) {
                low[i] = Math.min(low[i], at[i]);
                high[i] = Math.max(high[i], at[i]);
            }
        }
        // The part of the body that is over the outline, and the wall where its middle is.
        double halfWidth = gate.width() * 0.5;
        double halfHeight = gate.height() * 0.5;
        double fromA = Math.max(low[0], -halfWidth);
        double toA = Math.min(high[0], halfWidth);
        double fromB = Math.max(low[1], -halfHeight);
        double toB = Math.min(high[1], halfHeight);
        if (fromA > toA || fromB > toB) {
            return false;
        }
        if (gate.shape() == Gate.Shape.OVAL) {
            // The spot of the overlap nearest the middle has to be inside the oval.
            double nearA = Math.max(fromA, Math.min(toA, 0.0));
            double nearB = Math.max(fromB, Math.min(toB, 0.0));
            if (!inside(gate, nearA, nearB)) {
                return false;
            }
        }
        double surface = frame.surface(0.5 * (fromA + toA), 0.5 * (fromB + toB));
        return !Double.isNaN(surface) && low[2] <= surface + DEPTH && high[2] >= surface - DEPTH;
    }

    /** A box around the whole gap, to know cheaply where it can be. */
    public static AABB gapBounds(Gate gate, FieldShape shape) {
        Frame frame = frame(gate, shape);
        if (frame == null) {
            Vec3 centre = shape.center();
            return new AABB(centre, centre);
        }
        return gapBounds(gate, frame);
    }

    /** A box around the whole gap of a gate whose place on the wall is {@code frame}. */
    public static AABB gapBounds(Gate gate, Frame frame) {
        double halfWidth = gate.width() * 0.5;
        double halfHeight = gate.height() * 0.5;
        double[] low = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
        double[] high = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
        for (double a : new double[] { -halfWidth, 0.0, halfWidth }) {
            for (double b : new double[] { -halfHeight, 0.0, halfHeight }) {
                double surface = frame.surface(a, b);
                if (Double.isNaN(surface)) {
                    continue;
                }
                for (double along : new double[] { surface - DEPTH, surface + DEPTH }) {
                    Vec3 point = frame.origin().add(frame.across().scale(a)).add(frame.up().scale(b)).add(frame.normal().scale(along));
                    low[0] = Math.min(low[0], point.x);
                    low[1] = Math.min(low[1], point.y);
                    low[2] = Math.min(low[2], point.z);
                    high[0] = Math.max(high[0], point.x);
                    high[1] = Math.max(high[1], point.y);
                    high[2] = Math.max(high[2], point.z);
                }
            }
        }
        if (low[0] > high[0]) {
            return new AABB(frame.wall(), frame.wall());
        }
        // The corners of the outline are the extremes of a straight gate; a turned one needs the room its edges take.
        return new AABB(low[0], low[1], low[2], high[0], high[1], high[2]).inflate(0.05);
    }

    // --- Putting a gate on the wall ---------------------------------------------------------------

    /**
     * Where the player at {@code eye} looking along {@code look} meets the wall of {@code shape}, from inside or from
     * outside, as a point in the world. Empty when the look misses the wall.
     */
    public static Optional<Vec3> aim(FieldShape shape, Vec3 eye, Vec3 look, double reach) {
        Vec3 end = eye.add(look.scale(reach));
        // From inside the wall is met going out, which is the same walk seen from the end.
        return shape.contains(eye) ? shape.clip(end, eye) : shape.clip(eye, end);
    }

    /** What to keep as the anchor of a gate put at {@code world} on the wall: the place in the field's own axes, from its projector. */
    public static Vec3 anchorAt(FieldShape shape, Vec3 world) {
        if (shape.isOriented()) {
            return shape.toLocal(world).subtract(shape.coreOffset());
        }
        Vec3 offset = world.subtract(shape.center());
        Quaterniond turn = shape.turn();
        if (turn == null) {
            return offset;
        }
        Vector3d result = new Quaterniond(turn).invert().transform(new Vector3d(offset.x, offset.y, offset.z));
        return new Vec3(result.x, result.y, result.z);
    }

    /**
     * The outline of a gate on the wall of {@code shape}, as a closed line of points about {@code step} blocks apart,
     * lifted a little off the wall so it can be seen against it. Empty if the gate has no place on the wall.
     */
    public static List<Vec3> outline(Gate gate, FieldShape shape, double step) {
        Frame frame = frame(gate, shape);
        if (frame == null) {
            return List.of();
        }
        double halfWidth = gate.width() * 0.5;
        double halfHeight = gate.height() * 0.5;
        List<double[]> flat = new ArrayList<>();
        if (gate.shape() == Gate.Shape.OVAL) {
            int count = Math.max(16, (int) Math.ceil(Math.PI * (halfWidth + halfHeight) / step));
            for (int i = 0; i < count; i++) {
                double angle = 2.0 * Math.PI * i / count;
                flat.add(new double[] { halfWidth * Math.cos(angle), halfHeight * Math.sin(angle) });
            }
        } else {
            double[][] corners = { { -halfWidth, -halfHeight }, { halfWidth, -halfHeight }, { halfWidth, halfHeight }, { -halfWidth, halfHeight } };
            for (int i = 0; i < 4; i++) {
                double[] from = corners[i];
                double[] to = corners[(i + 1) % 4];
                int parts = Math.max(1, (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1]) / step));
                for (int part = 0; part < parts; part++) {
                    double t = part / (double) parts;
                    flat.add(new double[] { from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t });
                }
            }
        }
        List<Vec3> points = new ArrayList<>(flat.size());
        for (double[] at : flat) {
            double surface = frame.surface(at[0], at[1]);
            if (!Double.isNaN(surface)) {
                points.add(frame.origin().add(frame.across().scale(at[0])).add(frame.up().scale(at[1]))
                        .add(frame.normal().scale(surface + 0.03)));
            }
        }
        return points;
    }

    private Gates() {
    }
}
