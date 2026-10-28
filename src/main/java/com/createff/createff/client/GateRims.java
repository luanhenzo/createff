package com.createff.createff.client;

import java.util.List;

import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.Gate;
import com.createff.createff.field.Gates;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The glowing border drawn around every gate of a field, open or shut, so it can be seen from afar where the way
 * through is. An open gate's border is the colour of the field, brightened, with lights running round it; a shut one is a
 * steady dim red. It burns brighter while something is in the opening, and brightest on the gate the player has picked.
 */
final class GateRims {
    /** Half the width of the line, in blocks. */
    private static final double HALF_WIDTH = 0.06;
    /** The shut gate's colour. */
    private static final float[] SHUT = { 1.0F, 0.28F, 0.2F };

    /** Draws the borders of the gates of {@code core}. {@code reveals} dims them with the wall of a Veiled field. */
    static void draw(VertexConsumer vc, Matrix4f m, FieldProjectorBlockEntity core, FieldShape shape, float time,
            float red, float green, float blue, FieldRipples.Reveals reveals) {
        List<Gate> gates = core.mirrorSettings().gates();
        if (gates.isEmpty()) {
            return;
        }
        Level level = core.getLevel();
        for (Gate gate : gates) {
            List<Vec3> line = Gates.outline(gate, shape, 0.5);
            Gates.Frame frame = Gates.frame(gate, shape);
            if (line.size() < 3 || frame == null) {
                continue;
            }
            float strength = gate.open() ? 0.55F : 0.3F;
            if (GateSelection.isPicked(core.getBlockPos(), gate.id())) {
                strength = 1.0F;
            } else if (gate.open() && level != null && occupied(level, gate, shape)) {
                strength = 0.95F;
            }
            float[] colour = gate.open()
                    ? new float[] { Mth.lerp(0.55F, red, 1.0F), Mth.lerp(0.55F, green, 1.0F), Mth.lerp(0.55F, blue, 1.0F) }
                    : SHUT;
            for (int i = 0; i < line.size(); i++) {
                Vec3 from = line.get(i);
                Vec3 to = line.get((i + 1) % line.size());
                // Lights that run round the border of an open gate; a shut one does not move.
                float pulse = gate.open() ? 0.65F + 0.35F * Mth.sin(time * 0.12F - i * 0.45F) : 1.0F;
                float alpha = Mth.clamp(strength * pulse, 0.0F, 1.0F);
                float shown = reveals == null ? 1.0F : reveals.at(from.x, from.y, from.z);
                float[] shade = { colour[0], colour[1], colour[2], alpha * shown };
                ribbon(vc, m, from, to, normalAt(shape, frame, from), normalAt(shape, frame, to), shade);
            }
        }
    }

    /** Whether something that can pass is in the opening of the gate. */
    private static boolean occupied(Level level, Gate gate, FieldShape shape) {
        Entity camera = Minecraft.getInstance().getCameraEntity();
        for (Entity entity : level.getEntities(camera, Gates.gapBounds(gate, shape).inflate(0.5),
                other -> !other.isSpectator() && other != camera)) {
            if (Gates.gaps(gate, shape, entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0))
                    || Gates.gapsBox(gate, shape, entity.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 normalAt(FieldShape shape, Gates.Frame frame, Vec3 point) {
        if (shape.isSphere()) {
            Vec3 out = point.subtract(shape.center());
            return out.lengthSqr() < 1.0E-9 ? frame.normal() : out.normalize();
        }
        return frame.normal();
    }

    /**
     * A line from one point to the next as two thin strips crossing each other: one lying on the wall and one standing
     * out of it, so the border shows from any side and does not vanish when seen along the wall.
     */
    private static void ribbon(VertexConsumer vc, Matrix4f m, Vec3 from, Vec3 to, Vec3 normalFrom, Vec3 normalTo, float[] shade) {
        Vec3 along = to.subtract(from);
        if (along.lengthSqr() < 1.0E-9) {
            return;
        }
        Vec3 normal = normalFrom.add(normalTo).normalize();
        Vec3 side = along.cross(normal);
        if (side.lengthSqr() < 1.0E-9) {
            return;
        }
        side = side.normalize().scale(HALF_WIDTH);
        Vec3 lift = normal.scale(HALF_WIDTH);
        quad(vc, m, from.add(side), to.add(side), to.subtract(side), from.subtract(side), shade);
        quad(vc, m, from.add(lift), to.add(lift), to.subtract(lift), from.subtract(lift), shade);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float[] shade) {
        FieldWallRenderer.emit(vc, m, a.x, a.y, a.z, shade);
        FieldWallRenderer.emit(vc, m, b.x, b.y, b.z, shade);
        FieldWallRenderer.emit(vc, m, c.x, c.y, c.z, shade);
        FieldWallRenderer.emit(vc, m, d.x, d.y, d.z, shade);
    }

    private GateRims() {
    }
}
