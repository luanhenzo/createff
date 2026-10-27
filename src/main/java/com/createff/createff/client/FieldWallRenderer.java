package com.createff.createff.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.CreateFF;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
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
            double originX, double originY, double originZ, @Nullable FieldRipples.Reveals reveals) {
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
                            new Look(r, g, b, a, time, energy, overheated, local.minX, local.minY, local.minZ, inside));
                }
                FieldHeatRenderer.draw(vc, carried, core, shape, partialTick, gameTime);
            } else {
                Look look = new Look(r, g, b, a, time, energy, overheated, box.minX, box.minY, box.minZ, reveals);
                if (wall && shape.isSphere()) {
                    drawSphere(vc, matrix, shape, look);
                } else if (wall) {
                    drawBox(vc, matrix, box, look);
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
                onSphere(vc, m, look, center, radius, theta0, phi0);
                onSphere(vc, m, look, center, radius, theta1, phi0);
                onSphere(vc, m, look, center, radius, theta1, phi1);
                onSphere(vc, m, look, center, radius, theta0, phi1);
            }
        }
    }

    private static void onSphere(VertexConsumer vc, Matrix4f m, Look look, Vec3 center, double radius, double theta, double phi) {
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
        int cellsU = cells(uMax - uMin);
        int cellsV = cells(vMax - vMin);
        for (int i = 0; i < cellsU; i++) {
            double u0 = Mth.lerp(i / (double) cellsU, uMin, uMax);
            double u1 = Mth.lerp((i + 1) / (double) cellsU, uMin, uMax);
            for (int j = 0; j < cellsV; j++) {
                double v0 = Mth.lerp(j / (double) cellsV, vMin, vMax);
                double v1 = Mth.lerp((j + 1) / (double) cellsV, vMin, vMax);
                onFace(vc, m, look, axis, plane, u0, v0);
                onFace(vc, m, look, axis, plane, u1, v0);
                onFace(vc, m, look, axis, plane, u1, v1);
                onFace(vc, m, look, axis, plane, u0, v1);
            }
        }
    }

    private static int cells(double size) {
        return Mth.clamp((int) Math.ceil(size / CELL_SIZE), 1, MAX_CELLS);
    }

    private static void onFace(VertexConsumer vc, Matrix4f m, Look look, int axis, double plane, double u, double v) {
        double x = axis == 0 ? plane : u;
        double y = axis == 1 ? plane : axis == 0 ? u : v;
        double z = axis == 2 ? plane : v;
        vertex(vc, m, look, x, y, z);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Look look, double x, double y, double z) {
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
        vc.addVertex(m, (float) x, (float) y, (float) z).setColor(r, g, b, Mth.clamp(alpha * shown, 0.0F, 1.0F));
    }

    private FieldWallRenderer() {
    }
}
