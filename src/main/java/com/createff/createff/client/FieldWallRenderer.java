package com.createff.createff.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.CreateFF;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
import com.createff.createff.field.Gate;
import com.createff.createff.field.Gates;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the translucent walls of every loaded field, blue when calm and red as it overloads, with soft waves of
 * light running across them. Done from the level renderer so the walls stay visible with the core out of view.
 *
 * <p>The waves come from varying the alpha and the brightness from one point of the wall to the next, so each wall
 * is cut into a grid of small cells whose corners are shaded separately. Calm fields shimmer slowly; the more
 * overloaded the field, the faster and stronger the waves; an overheated field pulses red.
 */
@EventBusSubscriber(modid = CreateFF.MODID, value = Dist.CLIENT)
public final class FieldWallRenderer {
    private static final float INSIDE_ALPHA = 0.5F;
    private static final double MAX_DISTANCE = 320.0;
    /** Wall cells are about this many blocks across, but never more than {@link #MAX_CELLS} on a side. */
    private static final double CELL_SIZE = 2.0;
    private static final int MAX_CELLS = 40;

    /** Everything a wall vertex needs to work out its colour. */
    private record Look(float r, float g, float b, float a, float time, float energy, boolean overheated,
            double originX, double originY, double originZ, @Nullable FieldRipples.Reveals reveals, List<Hole> holes) {
    }

    /** A gap in the wall where a gate is. */
    private record Hole(Gate gate, FieldShape shape, Gates.Frame frame) {
    }

    /** The gaps the open gates of the field make in its wall. */
    private static List<Hole> holes(FieldProjectorBlockEntity core, FieldShape shape) {
        List<Hole> holes = new ArrayList<>();
        for (Gate gate : Gates.opening(core.mirrorSettings().gates(), shape)) {
            // A box turned by a ship is drawn in its own straight space, so its gates are placed in that.
            holes.add(new Hole(gate, shape, shape.isOriented() ? Gates.localFrame(gate, shape) : Gates.frame(gate, shape)));
        }
        return holes;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || FieldProjectorBlockEntity.CLIENT_CORES.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        long gameTime = Minecraft.getInstance().level.getGameTime();
        // Ticks, with the part of the current tick that has gone by, so the waves move smoothly between ticks.
        float time = gameTime + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        VertexConsumer vc = null;

        for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
            FieldState state = core.mirrorState();
            if (state == FieldState.OFFLINE) {
                continue;
            }
            FieldShape shape = core.renderShape(event.getPartialTick().getGameTimeDeltaPartialTick(false));
            AABB box = shape.bounds();
            if (box.distanceToSqr(camera) > MAX_DISTANCE * MAX_DISTANCE) {
                continue;
            }

            float r, g, b, a;
            boolean overheated = state == FieldState.OVERHEATED;
            float energy = overheated ? 1.0F : core.mirrorOverload();
            if (overheated) {
                r = 0.55F;
                g = 0.1F;
                b = 0.1F;
                a = 0.2F;
            } else {
                r = Mth.lerp(energy, 0.2F, 1.0F);
                g = Mth.lerp(energy, 0.8F, 0.15F);
                b = Mth.lerp(energy, 1.0F, 0.1F);
                a = 0.16F + 0.2F * energy;
                // Fainter from the inside so a small field does not tint the whole screen.
                if (shape.contains(camera)) {
                    a *= INSIDE_ALPHA;
                }
            }

            if (core.mirrorSettings().aegis() && !overheated) {
                // The Aegis: gold, and never loaded up, so never reddened.
                r = 1.0F;
                g = 0.82F;
                b = 0.4F;
                energy = 0.0F;
                a = shape.contains(camera) ? 0.16F * INSIDE_ALPHA : 0.16F;
            }

            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            // The Veil: the wall is never shown whole. Only the patch nearest the player, when they are close, and a
            // patch around each ripple (where it is crossed, touched or hit) show; the rest is not drawn.
            FieldRipples.Reveals reveals = core.mirrorSettings().veil()
                    ? FieldRipples.reveals(core, shape, camera, partialTick) : null;
            boolean wall = reveals == null || !reveals.isEmpty();

            if (vc == null) {
                vc = buffers.getBuffer(FieldRenderTypes.FIELD_WALL);
            }
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            Matrix4f matrix = poseStack.last().pose();
            if (shape.isOriented()) {
                // A cube on a ship is drawn in its own space, straight, with the matrix that carries and turns it to
                // where the ship is. That keeps the waves fixed to the hull instead of to the world.
                Vec3 middle = shape.center();
                Matrix4f carried = new Matrix4f(matrix).translate((float) middle.x, (float) middle.y, (float) middle.z)
                        .rotate(new Quaternionf(shape.orientation()));
                AABB local = shape.localBox();
                if (wall) {
                    // The patches are worked out in the world; the box is drawn in its own space.
                    FieldRipples.Reveals inside = reveals == null ? null : reveals.map(shape::toLocal);
                    drawBox(vc, carried, local,
                            new Look(r, g, b, a, time, energy, overheated, local.minX, local.minY, local.minZ, inside, holes(core, shape)));
                    GateRims.draw(vc, matrix, core, shape, time, r, g, b, reveals);
                }
                FieldHeatRenderer.draw(vc, carried, core, shape, partialTick, gameTime);
            } else {
                Look look = new Look(r, g, b, a, time, energy, overheated, box.minX, box.minY, box.minZ, reveals,
                        holes(core, shape));
                if (wall && shape.isSphere()) {
                    drawSphere(vc, matrix, shape, look);
                } else if (wall) {
                    drawBox(vc, matrix, box, look);
                }
                if (wall) {
                    GateRims.draw(vc, matrix, core, shape, time, r, g, b, reveals);
                }
                FieldHeatRenderer.draw(vc, matrix, core, shape, partialTick, gameTime);
            }
            FieldRipples.draw(vc, matrix, core, event.getPartialTick().getGameTimeDeltaPartialTick(false), r, g, b);
            poseStack.popPose();
        }

        if (vc != null) {
            buffers.endBatch(FieldRenderTypes.FIELD_WALL);
        }
    }

    private static void drawBox(VertexConsumer vc, Matrix4f m, AABB box, Look look) {
        face(vc, m, look, 0, box.minX, box.minY, box.maxY, box.minZ, box.maxZ);
        face(vc, m, look, 0, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
        face(vc, m, look, 1, box.minY, box.minX, box.maxX, box.minZ, box.maxZ);
        face(vc, m, look, 1, box.maxY, box.minX, box.maxX, box.minZ, box.maxZ);
        face(vc, m, look, 2, box.minZ, box.minX, box.maxX, box.minY, box.maxY);
        face(vc, m, look, 2, box.maxZ, box.minX, box.maxX, box.minY, box.maxY);
    }

    /** The wall of a sphere, cut into bands of latitude and longitude about as big as the cells of a cube wall. */
    private static void drawSphere(VertexConsumer vc, Matrix4f m, FieldShape shape, Look look) {
        Vec3 center = shape.center();
        double radius = shape.sphereRadius();
        int rings = Mth.clamp((int) Math.ceil(Math.PI * radius / CELL_SIZE), 6, MAX_CELLS);
        int slices = Mth.clamp((int) Math.ceil(2 * Math.PI * radius / CELL_SIZE), 12, 2 * MAX_CELLS);
        for (int i = 0; i < rings; i++) {
            double theta0 = Math.PI * i / rings;
            double theta1 = Math.PI * (i + 1) / rings;
            for (int j = 0; j < slices; j++) {
                double phi0 = 2 * Math.PI * j / slices;
                double phi1 = 2 * Math.PI * (j + 1) / slices;
                if (look.holes().isEmpty() || !meetsHole(look, center, radius, theta0, theta1, phi0, phi1)) {
                    onSphere(vc, m, look, center, radius, theta0, phi0);
                    onSphere(vc, m, look, center, radius, theta1, phi0);
                    onSphere(vc, m, look, center, radius, theta1, phi1);
                    onSphere(vc, m, look, center, radius, theta0, phi1);
                    continue;
                }
                // A cell that meets a gap is cut into small pieces, and the ones in the gap are left out. The pieces lie on the
                // surface the whole cell would, straight between its corners, and are shaded from those corners, so a cut cell
                // joins the cells around it with no step or crack and looks like them.
                float[] s00 = shadeSphere(look, center, radius, theta0, phi0);
                float[] s10 = shadeSphere(look, center, radius, theta1, phi0);
                float[] s11 = shadeSphere(look, center, radius, theta1, phi1);
                float[] s01 = shadeSphere(look, center, radius, theta0, phi1);
                Vec3 c00 = onSphereAt(center, radius, theta0, phi0);
                Vec3 c10 = onSphereAt(center, radius, theta1, phi0);
                Vec3 c11 = onSphereAt(center, radius, theta1, phi1);
                Vec3 c01 = onSphereAt(center, radius, theta0, phi1);
                for (int a = 0; a < SPHERE_CUT; a++) {
                    double f0 = a / (double) SPHERE_CUT;
                    double f1 = (a + 1) / (double) SPHERE_CUT;
                    double t0 = Mth.lerp(f0, theta0, theta1);
                    double t1 = Mth.lerp(f1, theta0, theta1);
                    for (int b = 0; b < SPHERE_CUT; b++) {
                        double g0 = b / (double) SPHERE_CUT;
                        double g1 = (b + 1) / (double) SPHERE_CUT;
                        double p0 = Mth.lerp(g0, phi0, phi1);
                        double p1 = Mth.lerp(g1, phi0, phi1);
                        if (inHole(look, center, radius, 0.5 * (t0 + t1), 0.5 * (p0 + p1))) {
                            continue;
                        }
                        emitPiece(vc, m, c00, c10, c11, c01, s00, s10, s11, s01, f0, g0);
                        emitPiece(vc, m, c00, c10, c11, c01, s00, s10, s11, s01, f1, g0);
                        emitPiece(vc, m, c00, c10, c11, c01, s00, s10, s11, s01, f1, g1);
                        emitPiece(vc, m, c00, c10, c11, c01, s00, s10, s11, s01, f0, g1);
                    }
                }

            }
        }
    }

    /** How many pieces each way a cell of a sphere is cut into where it meets a gap: the finer, the smoother the edge. */
    private static final int SPHERE_CUT = 16;


    private static Vec3 onSphereAt(Vec3 center, double radius, double theta, double phi) {
        double sinTheta = Math.sin(theta);
        return new Vec3(center.x + radius * sinTheta * Math.cos(phi), center.y + radius * Math.cos(theta),
                center.z + radius * sinTheta * Math.sin(phi));
    }

    /** Whether a cell of the sphere reaches the box a gap is in. */
    private static boolean meetsHole(Look look, Vec3 center, double radius, double theta0, double theta1, double phi0, double phi1) {
        Vec3 a = onSphereAt(center, radius, theta0, phi0);
        Vec3 b = onSphereAt(center, radius, theta1, phi0);
        Vec3 c = onSphereAt(center, radius, theta1, phi1);
        Vec3 d = onSphereAt(center, radius, theta0, phi1);
        AABB cell = new AABB(a, c).minmax(new AABB(b, d));
        for (Hole hole : look.holes()) {
            if (Gates.gapBounds(hole.gate(), hole.frame()).intersects(cell)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the middle of a piece of the sphere is in a gap of the wall. */
    private static boolean inHole(Look look, Vec3 center, double radius, double theta, double phi) {
        if (look.holes().isEmpty()) {
            return false;
        }
        Vec3 at = onSphereAt(center, radius, theta, phi);
        for (Hole hole : look.holes()) {
            if (Gates.gaps(hole.gate(), hole.frame(), at)) {
                return true;
            }
        }
        return false;
    }

    private static float[] shadeSphere(Look look, Vec3 center, double radius, double theta, double phi) {
        Vec3 at = onSphereAt(center, radius, theta, phi);
        return shade(look, at.x, at.y, at.z);
    }

    /** A corner of a piece of a cut cell: where it is and its shade, from the four corners of the whole cell. */
    private static void emitPiece(VertexConsumer vc, Matrix4f m, Vec3 c00, Vec3 c10, Vec3 c11, Vec3 c01,
            float[] s00, float[] s10, float[] s11, float[] s01, double first, double second) {
        double x = (1.0 - first) * (1.0 - second) * c00.x + first * (1.0 - second) * c10.x + first * second * c11.x + (1.0 - first) * second * c01.x;
        double y = (1.0 - first) * (1.0 - second) * c00.y + first * (1.0 - second) * c10.y + first * second * c11.y + (1.0 - first) * second * c01.y;
        double z = (1.0 - first) * (1.0 - second) * c00.z + first * (1.0 - second) * c10.z + first * second * c11.z + (1.0 - first) * second * c01.z;
        emit(vc, m, x, y, z, blend(s00, s10, s11, s01, first, second));
    }



    /** The shade at {@code first} and {@code second} (0 to 1) across a cell, from the shades of its four corners. */
    private static float[] blend(float[] s00, float[] s10, float[] s11, float[] s01, double first, double second) {
        float[] result = new float[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (float) ((1.0 - first) * (1.0 - second) * s00[i] + first * (1.0 - second) * s10[i]
                    + first * second * s11[i] + (1.0 - first) * second * s01[i]);
        }
        return result;
    }

    private static void onSphere(

VertexConsumer vc, Matrix4f m, Look look, Vec3 center, double radius, double theta, double phi) {
        double sinTheta = Math.sin(theta);
        vertex(vc, m, look,
                center.x + radius * sinTheta * Math.cos(phi),
                center.y + radius * Math.cos(theta),
                center.z + radius * sinTheta * Math.sin(phi));
    }

    /**
     * One wall, cut into cells. {@code axis} is the axis the wall is perpendicular to (0 x, 1 y, 2 z) and {@code plane}
     * where it sits; it spans [uMin, uMax] x [vMin, vMax] along the other two axes, in order.
     */
    private static void face(VertexConsumer vc, Matrix4f m, Look look, int axis, double plane,
            double uMin, double uMax, double vMin, double vMax) {
        List<double[]> gaps = gapsOn(look, axis, plane);
        List<Hole> ovals = ovalsOn(look, axis, plane);
        int cellsU = cells(uMax - uMin);
        int cellsV = cells(vMax - vMin);
        for (int i = 0; i < cellsU; i++) {
            double u0 = Mth.lerp(i / (double) cellsU, uMin, uMax);
            double u1 = Mth.lerp((i + 1) / (double) cellsU, uMin, uMax);
            for (int j = 0; j < cellsV; j++) {
                double v0 = Mth.lerp(j / (double) cellsV, vMin, vMax);
                double v1 = Mth.lerp((j + 1) / (double) cellsV, vMin, vMax);
                if (gaps.isEmpty() && ovals.isEmpty()) {
                    quad(vc, m, look, axis, plane, u0, u1, v0, v1);
                } else {
                    cutCell(vc, m, look, axis, plane, new double[] { u0, u1, v0, v1 }, gaps, ovals);
                }
            }
        }
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Look look, int axis, double plane,
            double u0, double u1, double v0, double v1) {
        onFace(vc, m, look, axis, plane, u0, v0);
        onFace(vc, m, look, axis, plane, u1, v0);
        onFace(vc, m, look, axis, plane, u1, v1);
        onFace(vc, m, look, axis, plane, u0, v1);
    }

    /**
     * The gaps in this wall as rectangles {uMin, uMax, vMin, vMax} in the wall's own two axes: the open gates with a
     * rectangular outline that are in this wall. Along x the wall's axes are y and z; along y they are x and z; along z
     * they are x and y.
     */
    private static List<double[]> gapsOn(Look look, int axis, double plane) {
        List<double[]> gaps = null;
        for (Hole hole : look.holes()) {
            Gates.Frame frame = hole.gate().shape() == Gate.Shape.RECTANGLE && !hole.shape().isSphere() ? hole.frame() : null;
            if (frame == null || !inWall(frame, axis, plane)) {
                continue;
            }
            double[] gap = { Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE };
            for (int corner = 0; corner < 4; corner++) {
                Vec3 point = frame.wall()
                        .add(frame.across().scale((corner & 1) == 0 ? -0.5 * hole.gate().width() : 0.5 * hole.gate().width()))
                        .add(frame.up().scale((corner & 2) == 0 ? -0.5 * hole.gate().height() : 0.5 * hole.gate().height()));
                double u = axis == 0 ? point.y : point.x;
                double v = axis == 2 ? point.y : point.z;
                gap[0] = Math.min(gap[0], u);
                gap[1] = Math.max(gap[1], u);
                gap[2] = Math.min(gap[2], v);
                gap[3] = Math.max(gap[3], v);
            }
            if (gaps == null) {
                gaps = new ArrayList<>(2);
            }
            gaps.add(gap);
        }
        return gaps == null ? List.of() : gaps;
    }

    /** The open gates with an oval outline that are in this wall of a cube. */
    private static List<Hole> ovalsOn(Look look, int axis, double plane) {
        List<Hole> ovals = null;
        for (Hole hole : look.holes()) {
            Gates.Frame frame = hole.gate().shape() == Gate.Shape.OVAL && !hole.shape().isSphere() ? hole.frame() : null;
            if (frame != null && inWall(frame, axis, plane)) {
                if (ovals == null) {
                    ovals = new ArrayList<>(1);
                }
                ovals.add(hole);
            }
        }
        return ovals == null ? List.of() : ovals;
    }

    /** Whether a gate faces out of the wall of a cube that is perpendicular to {@code axis}, at {@code plane}. */
    private static boolean inWall(Gates.Frame frame, int axis, double plane) {
        double wall = axis == 0 ? frame.wall().x : axis == 1 ? frame.wall().y : frame.wall().z;
        double along = axis == 0 ? frame.normal().x : axis == 1 ? frame.normal().y : frame.normal().z;
        return Math.abs(along) > 0.5 && Math.abs(wall - plane) < 1.0E-3;
    }

    /** How many pieces each way a piece of a wall is cut into where an oval gap is: the finer, the rounder the edge. */
    private static final int OVAL_CUT = 16;



    /** One cell of the wall with the gaps taken out of it: whatever of it is left is drawn in pieces. */
    private static void cutCell(VertexConsumer vc, Matrix4f m, Look look, int axis, double plane, double[] cell,
            List<double[]> gaps, List<Hole> ovals) {
        float[][] corners = {
                shadeFace(look, axis, plane, cell[0], cell[2]), shadeFace(look, axis, plane, cell[1], cell[2]),
                shadeFace(look, axis, plane, cell[1], cell[3]), shadeFace(look, axis, plane, cell[0], cell[3]) };
        List<double[]> pieces = new ArrayList<>(4);
        pieces.add(cell);
        for (double[] gap : gaps) {
            List<double[]> next = new ArrayList<>(pieces.size() + 3);
            for (double[] piece : pieces) {
                double u0 = Math.max(piece[0], gap[0]);
                double u1 = Math.min(piece[1], gap[1]);
                double v0 = Math.max(piece[2], gap[2]);
                double v1 = Math.min(piece[3], gap[3]);
                if (u0 >= u1 || v0 >= v1) {
                    next.add(piece);
                    continue;
                }
                if (piece[0] < u0) {
                    next.add(new double[] { piece[0], u0, piece[2], piece[3] });
                }
                if (u1 < piece[1]) {
                    next.add(new double[] { u1, piece[1], piece[2], piece[3] });
                }
                if (piece[2] < v0) {
                    next.add(new double[] { u0, u1, piece[2], v0 });
                }
                if (v1 < piece[3]) {
                    next.add(new double[] { u0, u1, v1, piece[3] });
                }
            }
            pieces = next;
        }
        for (double[] piece : pieces) {
            if (ovals.isEmpty()) {
                shadedQuad(vc, m, axis, plane, piece[0], piece[1], piece[2], piece[3], cell, corners);
                continue;
            }
            // What is left is cut into small pieces, and the ones in an oval are left out.
            for (int a = 0; a < OVAL_CUT; a++) {
                double u0 = Mth.lerp(a / (double) OVAL_CUT, piece[0], piece[1]);
                double u1 = Mth.lerp((a + 1) / (double) OVAL_CUT, piece[0], piece[1]);
                for (int b = 0; b < OVAL_CUT; b++) {
                    double v0 = Mth.lerp(b / (double) OVAL_CUT, piece[2], piece[3]);
                    double v1 = Mth.lerp((b + 1) / (double) OVAL_CUT, piece[2], piece[3]);
                    if (!inOval(ovals, axis, plane, 0.5 * (u0 + u1), 0.5 * (v0 + v1))) {
                        shadedQuad(vc, m, axis, plane, u0, u1, v0, v1, cell, corners);
                    }
                }
            }
        }
    }

    /** Whether the spot of the wall at {@code u}, {@code v} is in the gap of one of these oval gates. */
    private static boolean inOval(List<Hole> ovals, int axis, double plane, double u, double v) {
        Vec3 point = new Vec3(axis == 0 ? plane : u, axis == 1 ? plane : axis == 0 ? u : v, axis == 2 ? plane : v);
        for (Hole hole : ovals) {
            if (Gates.gaps(hole.gate(), hole.frame(), point)) {
                return true;
            }
        }
        return false;
    }

    private static int cells(double size) {

        return Mth.clamp((int) Math.ceil(size / CELL_SIZE), 1, MAX_CELLS);
    }

    private static float[] shadeFace(Look look, int axis, double plane, double u, double v) {
        return shade(look, axis == 0 ? plane : u, axis == 1 ? plane : axis == 0 ? u : v, axis == 2 ? plane : v);
    }

    /** A piece of a cell of a wall, shaded from the corners of the whole cell it was cut from. */
    private static void shadedQuad(VertexConsumer vc, Matrix4f m, int axis, double plane,
            double u0, double u1, double v0, double v1, double[] cell, float[][] corners) {
        double[][] at = { { u0, v0 }, { u1, v0 }, { u1, v1 }, { u0, v1 } };
        for (double[] corner : at) {
            double fu = (corner[0] - cell[0]) / (cell[1] - cell[0]);
            double fv = (corner[1] - cell[2]) / (cell[3] - cell[2]);
            emit(vc, m, axis == 0 ? plane : corner[0], axis == 1 ? plane : axis == 0 ? corner[0] : corner[1],
                    axis == 2 ? plane : corner[1], blend(corners[0], corners[1], corners[2], corners[3], fu, fv));
        }
    }

    private static void onFace(
VertexConsumer vc, Matrix4f m, Look look, int axis, double plane, double u, double v) {
        double x = axis == 0 ? plane : u;
        double y = axis == 1 ? plane : axis == 0 ? u : v;
        double z = axis == 2 ? plane : v;
        vertex(vc, m, look, x, y, z);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Look look, double x, double y, double z) {
        emit(vc, m, x, y, z, shade(look, x, y, z));
    }

    static void emit(VertexConsumer vc, Matrix4f m, double x, double y, double z, float[] shade) {
        vc.addVertex(m, (float) x, (float) y, (float) z).setColor(shade[0], shade[1], shade[2], shade[3]);
    }

    /** The colour and opacity of the wall at a point: red, green, blue and alpha. */
    private static float[] shade(Look look, double x, double y, double z) {
        // Positions inside the field, so the waves do not lose precision far from the world origin.
        float px = (float) (x - look.originX());
        float py = (float) (y - look.originY());
        float pz = (float) (z - look.originZ());

        float speed = 0.09F * (1.0F + 1.2F * look.energy());
        float amplitude = 0.5F + 0.3F * look.energy();
        float first = Mth.sin(0.45F * (px + 0.6F * py + pz) - look.time() * speed);
        float second = Mth.sin(0.75F * (px - 1.3F * pz + py) + look.time() * speed * 0.7F);
        float shimmer = 0.5F * (first + second);

        // A thin bright band that sweeps across the wall. It shows up even on a calm field, whose walls are very
        // faint, so the field never looks like a flat colour.
        float sweep = Mth.sin(0.3F * (px + 0.5F * py + 0.7F * pz) - look.time() * speed * 1.3F);
        float band = sweep > 0 ? (float) Math.pow(sweep, 6) : 0.0F;

        float alpha = look.a() * (1.0F + amplitude * shimmer + 1.4F * band);
        float shown = look.reveals() == null ? 1.0F : look.reveals().at(x, y, z);
        if (look.overheated()) {
            // A slow red pulse: the field is failing.
            float pulse = 0.5F + 0.5F * Mth.sin(look.time() * 0.2F);
            alpha = look.a() * (0.4F + 1.2F * pulse) * (1.0F + 0.15F * shimmer);
        }
        float r = Mth.clamp(look.r() + 0.18F * shimmer + 0.25F * band, 0.0F, 1.0F);
        float g = Mth.clamp(look.g() + 0.18F * shimmer + 0.25F * band, 0.0F, 1.0F);
        float b = Mth.clamp(look.b() + 0.18F * shimmer + 0.25F * band, 0.0F, 1.0F);
        return new float[] { r, g, b, Mth.clamp(alpha * shown, 0.0F, 1.0F) };
    }

    private FieldWallRenderer() {
    }
}
