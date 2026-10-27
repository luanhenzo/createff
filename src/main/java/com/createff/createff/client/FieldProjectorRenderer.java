package com.createff.createff.client;

import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldState;
import com.createff.createff.item.ForceCoreItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Draws what moves on a field projector: the two shaft ends, turning with the network like any Create shaft, and the
 * force core inside the glass chamber. The core is a few glowing, translucent cubes inside one another, each turning
 * its own way and breathing in and out like a ball of plasma. They spin up and down
 * smoothly with the shaft while the field is on, and sit dim and still when it is not.
 */
public final class FieldProjectorRenderer implements BlockEntityRenderer<FieldProjectorBlockEntity> {
    /** Degrees per tick at 1 RPM, the same turn Create gives its shafts. */
    private static final float DEGREES_PER_TICK_PER_RPM = 0.3F;
    private static final float MAX_DEGREES_PER_TICK = 25.0F;
    /**
     * How much of Create's half shaft (8 pixels long) is used at each end, and how far it sits below the face of the
     * block, like the shaft in the hole of a clutch. A whole half shaft would reach the core.
     */
    private static final float SHAFT_STUB = 2.0F / 8.0F;
    private static final float SHAFT_RECESS = 1.0F / 16.0F;
    /** The chamber is 12 by 8 pixels inside: the biggest cube, corners and breathing included, must stay within 4. */
    private static final float[] HALF_SIZE = { 0.115F, 0.085F, 0.055F };
    /** Per layer: turn around the shaft, turn around the diagonal, a start angle for each, and how the breathing is timed. */
    private static final float[][] LAYERS = {
            { -1.0F, 1.5F, 0.0F, 36.0F, 135.0F, 0.7F },
            { 1.4F, 1.0F, 0.0F, 36.0F, 0.0F, 0.8F },
            { 1.8F, -0.8F, -35.0F, 106.0F, 90.0F, 0.9F },
    };
    private static final Vector3f DIAGONAL = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);

    /** Where each projector's core is in its turn. Weak, so a projector that is unloaded takes its entry with it. */
    private static final Map<FieldProjectorBlockEntity, Spin> SPINS = new WeakHashMap<>();

    private static final class Spin {
        float angle;
        float velocity;
        float time = Float.NaN;
    }

    public FieldProjectorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(FieldProjectorBlockEntity projector, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int light, int overlay) {
        if (projector.getLevel() == null) {
            return;
        }
        Direction.Axis axis = KineticBlockEntityRenderer.getRotationAxisOf(projector);
        renderShafts(projector, axis, pose, buffers, light);
        renderCore(projector, axis, partialTick, pose, buffers);
    }

    /** The shaft ends, drawn by hand because the projector has no Flywheel visual of its own. */
    private static void renderShafts(FieldProjectorBlockEntity projector, Direction.Axis axis, PoseStack pose,
            MultiBufferSource buffers, int light) {
        float time = AnimationTickHolder.getRenderTime(projector.getLevel());
        float offset = KineticBlockEntityRenderer.getRotationOffsetForPosition(projector, projector.getBlockPos(), axis);
        float angle = ((time * projector.getSpeed() * 3.0F / 10.0F) % 360.0F + offset) / 180.0F * (float) Math.PI;

        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != axis) {
                continue;
            }
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, projector.getBlockState(), direction);
            KineticBlockEntityRenderer.kineticRotationTransform(shaft, projector, axis, angle, light);

            // Squash it along its length, keeping the end at the face of the block where it is.
            int x = direction.getStepX();
            int y = direction.getStepY();
            int z = direction.getStepZ();
            pose.pushPose();
            pose.translate(-x * SHAFT_RECESS, -y * SHAFT_RECESS, -z * SHAFT_RECESS);
            pose.translate(0.5 + x * 0.5, 0.5 + y * 0.5, 0.5 + z * 0.5);
            pose.scale(x != 0 ? SHAFT_STUB : 1.0F, y != 0 ? SHAFT_STUB : 1.0F, z != 0 ? SHAFT_STUB : 1.0F);
            pose.translate(-0.5 - x * 0.5, -0.5 - y * 0.5, -0.5 - z * 0.5);
            shaft.renderInto(pose, buffers.getBuffer(RenderType.solid()));
            pose.popPose();
        }
    }

    private static void renderCore(FieldProjectorBlockEntity projector, Direction.Axis axis, float partialTick,
            PoseStack pose, MultiBufferSource buffers) {
        ItemStack core = projector.mirrorCore();
        if (!(core.getItem() instanceof ForceCoreItem item)) {
            return;
        }
        FieldState state = projector.mirrorState();
        boolean on = state == FieldState.ONLINE;

        Spin spin = SPINS.computeIfAbsent(projector, p -> new Spin());
        float now = projector.getLevel().getGameTime() + partialTick;
        if (!Float.isNaN(spin.time)) {
            float elapsed = Mth.clamp(now - spin.time, 0.0F, 5.0F);
            float target = on
                    ? Mth.clamp(Math.abs(projector.getSpeed()) * DEGREES_PER_TICK_PER_RPM, 4.0F, MAX_DEGREES_PER_TICK)
                    : 0.0F;
            spin.velocity += (target - spin.velocity) * Math.min(1.0F, elapsed * 0.1F);
            spin.angle = (spin.angle + spin.velocity * elapsed) % 3600.0F;
        }
        spin.time = now;

        // Blue-white for the basic core, gold for the advanced, violet for the elite; red while the field is shut down by overload.
        float[] tint = state == FieldState.OVERHEATED ? new float[] { 1.0F, 0.2F, 0.15F } : switch (item.tier()) {
            case BASIC -> new float[] { 0.25F, 0.75F, 1.0F };
            case ADVANCED -> new float[] { 1.0F, 0.72F, 0.2F };
            case ELITE -> new float[] { 0.8F, 0.3F, 1.0F };
        };
        float brightness = on ? 1.0F : 0.3F;
        VertexConsumer vc = buffers.getBuffer(FieldRenderTypes.FIELD_CORE);

        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        // The chamber is modelled upright; lay the cubes down the same way the block lies along its axis.
        if (axis == Direction.Axis.X) {
            pose.mulPose(Axis.ZP.rotationDegrees(-90.0F));
        } else if (axis == Direction.Axis.Z) {
            pose.mulPose(Axis.XP.rotationDegrees(90.0F));
        }
        for (int i = 0; i < LAYERS.length; i++) {
            float[] layer = LAYERS[i];
            float turns = spin.angle;
            float breathing = on ? Mth.sin(((turns * layer[5]) % 360.0F + layer[4]) * Mth.DEG_TO_RAD) : 0.0F;
            float half = HALF_SIZE[i] * (1.0F + 0.15F * breathing);

            // Outer layers take the colour of the core, the inner ones go towards white.
            float whiteness = i / (float) (LAYERS.length - 1) * 0.6F;
            float r = Mth.lerp(whiteness, tint[0], 1.0F);
            float g = Mth.lerp(whiteness, tint[1], 1.0F);
            float b = Mth.lerp(whiteness, tint[2], 1.0F);
            float alpha = (0.28F + 0.12F * i) * brightness;

            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(turns * layer[0] + layer[2]));
            pose.mulPose(Axis.of(DIAGONAL).rotationDegrees(turns * layer[1] + layer[3]));
            cube(vc, pose.last().pose(), half, r, g, b, alpha);
            pose.popPose();
        }
        pose.popPose();
    }

    /** Six faces of a cube around the origin. They are drawn from both sides, so the inside shows through. */
    private static void cube(VertexConsumer vc, Matrix4f m, float h, float r, float g, float b, float a) {
        face(vc, m, r, g, b, a, -h, -h, -h, h, -h, -h, h, h, -h, -h, h, -h);
        face(vc, m, r, g, b, a, -h, -h, h, -h, h, h, h, h, h, h, -h, h);
        face(vc, m, r, g, b, a, -h, -h, -h, -h, h, -h, -h, h, h, -h, -h, h);
        face(vc, m, r, g, b, a, h, -h, -h, h, -h, h, h, h, h, h, h, -h);
        face(vc, m, r, g, b, a, -h, -h, -h, -h, -h, h, h, -h, h, h, -h, -h);
        face(vc, m, r, g, b, a, -h, h, -h, h, h, -h, h, h, h, -h, h, h);
    }

    private static void face(VertexConsumer vc, Matrix4f m, float r, float g, float b, float a,
            float x1, float y1, float z1, float x2, float y2, float z2,
            float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(m, x4, y4, z4).setColor(r, g, b, a);
    }
}
