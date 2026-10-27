package com.createff.createff.client;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.ShipSupport;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Paints the recent hits on the walls of a field: a round, soft spot on the wall nearest to each hit, yellow for a
 * hit that kept all its damage and red for one on a spot already worn down. Spots on top of each other add up, so
 * the places hit most are the strongest, and every spot fades out over the time the server remembers it.
 */
final class FieldHeatRenderer {
    private static final float PEAK_ALPHA = 0.5F;
    private static final int SEGMENTS = 24;
    private static final int RINGS = 4;

    /** A face of the field box: perpendicular to {@code axis} (0 x, 1 y, 2 z), at {@code plane}, spanning the other two axes. */
    private record Face(int axis, double plane, double uMin, double uMax, double vMin, double vMax) {
    }

    /**
     * The hits of this field. A field on a ship shows its own hits, placed where the ship carries them now (drawn at
     * {@code partialTick} between ticks); a field on the ground shows the hits that did not land on a ship.
     */
    static void draw(VertexConsumer vc, Matrix4f m, FieldProjectorBlockEntity core, FieldShape shape, float partialTick, long now) {
        double radius = Config.REPEAT_DAMAGE_RADIUS.get();
        if (radius <= 0) {
            return;
        }
        boolean onShip = core.getLevel() != null && ShipSupport.client(core.getLevel(), core.fieldShape(), null) != null;
        List<FieldHeat.Hit> spots = new ArrayList<>();
        for (FieldHeat.Hit hit : FieldHeat.current(now)) {
            if (onShip) {
                if (hit.core() != null && hit.core().equals(core.getBlockPos()) && hit.onShip() != null) {
                    Vec3 world = ShipSupport.toWorld(core.getLevel(), core.fieldShape(), hit.onShip(), partialTick);
                    if (world != null) {
                        spots.add(new FieldHeat.Hit(world, hit.focus(), hit.tick(), hit.core(), hit.onShip()));
                    }
                }
            } else if (hit.core() == null) {
                spots.add(hit);
            }
        }
        if (spots.isEmpty()) {
            return;
        }
        if (shape.isOriented()) {
            // The matrix already carries and turns the box to where the ship is: the spots go in the box's own space.
            List<FieldHeat.Hit> local = new ArrayList<>(spots.size());
            for (FieldHeat.Hit spot : spots) {
                local.add(new FieldHeat.Hit(shape.toLocal(spot.at()), spot.focus(), spot.tick(), spot.core(), spot.onShip()));
            }
            drawBox(vc, m, shape.localBox(), radius, local, now);
        } else if (shape.isSphere()) {
            drawSphere(vc, m, shape, radius, spots, now);
        } else {
            drawBox(vc, m, shape.bounds(), radius, spots, now);
        }
    }

    /** The spot is the part of the sphere inside the repeat-damage radius of the hit, drawn as rings projected onto the wall. */
    private static void drawSphere(VertexConsumer vc, Matrix4f m, FieldShape shape, double radius,
            Iterable<FieldHeat.Hit> hits, long now) {
        Vec3 center = shape.center();
        double wall = shape.sphereRadius();
        long lifetime = FieldHeat.lifetime();
        for (FieldHeat.Hit hit : hits) {
            Vec3 away = hit.at().subtract(center);
            double distanceToCenter = away.length();
            if (distanceToCenter < 1.0E-6) {
                continue;
            }
            Vec3 normal = away.scale(1.0 / distanceToCenter);
            double distance = Math.abs(distanceToCenter - wall);
            if (distance >= radius) {
                continue;
            }
            float age = Mth.clamp((now - hit.tick()) / (float) lifetime, 0.0F, 1.0F);
            float fade = (1.0F - age) * (1.0F - age);
            float peak = PEAK_ALPHA * fade * (float) (1.0 - distance / radius);
            if (peak <= 0.01F) {
                continue;
            }
            float worn = Mth.clamp((hit.focus() - 1.0F) / 2.0F, 0.0F, 1.0F);
            float r = 1.0F;
            float g = Mth.lerp(worn, 0.9F, 0.12F);
            float b = Mth.lerp(worn, 0.2F, 0.05F);

            double reach = Math.sqrt(radius * radius - distance * distance);
            Vec3 up = Math.abs(normal.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 tangentA = normal.cross(up).normalize();
            Vec3 tangentB = normal.cross(tangentA);
            for (int ring = 0; ring < RINGS; ring++) {
                float innerFraction = ring / (float) RINGS;
                float outerFraction = (ring + 1) / (float) RINGS;
                float innerAlpha = peak * smooth(1.0F - innerFraction);
                float outerAlpha = peak * smooth(1.0F - outerFraction);
                for (int i = 0; i < SEGMENTS; i++) {
                    double a0 = 2 * Math.PI * i / SEGMENTS;
                    double a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
                    onSphere(vc, m, center, wall, normal, tangentA, tangentB, reach * innerFraction, a0, r, g, b, innerAlpha);
                    onSphere(vc, m, center, wall, normal, tangentA, tangentB, reach * innerFraction, a1, r, g, b, innerAlpha);
                    onSphere(vc, m, center, wall, normal, tangentA, tangentB, reach * outerFraction, a1, r, g, b, outerAlpha);
                    onSphere(vc, m, center, wall, normal, tangentA, tangentB, reach * outerFraction, a0, r, g, b, outerAlpha);
                }
            }
        }
    }

    /** A point of a spot: the offset from the spot's middle across the tangent plane, put back onto the sphere. */
    private static void onSphere(VertexConsumer vc, Matrix4f m, Vec3 center, double wall, Vec3 normal, Vec3 tangentA,
            Vec3 tangentB, double distance, double angle, float r, float g, float b, float a) {
        Vec3 offset = tangentA.scale(Math.cos(angle) * distance).add(tangentB.scale(Math.sin(angle) * distance));
        Vec3 direction = normal.scale(wall).add(offset).normalize();
        Vec3 point = center.add(direction.scale(wall));
        vc.addVertex(m, (float) point.x, (float) point.y, (float) point.z).setColor(r, g, b, a);
    }

    private static void drawBox(VertexConsumer vc, Matrix4f m, AABB box, double radius, Iterable<FieldHeat.Hit> hits, long now) {
        Face[] faces = {
                new Face(0, box.minX, box.minY, box.maxY, box.minZ, box.maxZ),
                new Face(0, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ),
                new Face(1, box.minY, box.minX, box.maxX, box.minZ, box.maxZ),
                new Face(1, box.maxY, box.minX, box.maxX, box.minZ, box.maxZ),
                new Face(2, box.minZ, box.minX, box.maxX, box.minY, box.maxY),
                new Face(2, box.maxZ, box.minX, box.maxX, box.minY, box.maxY)
        };

        long lifetime = FieldHeat.lifetime();
        for (FieldHeat.Hit hit : hits) {
            // Only the wall nearest to the hit gets the spot. On every wall in reach, the translucent walls in front
            // and behind would pile their spots up, and the colour would change with where you look from.
            Face nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            double nearestU = 0, nearestV = 0;
            for (Face face : faces) {
                double hitPlane = coordinate(hit, face.axis(), 0);
                double hitU = coordinate(hit, face.axis(), 1);
                double hitV = coordinate(hit, face.axis(), 2);
                double u = Mth.clamp(hitU, face.uMin(), face.uMax());
                double v = Mth.clamp(hitV, face.vMin(), face.vMax());
                double distance = Math.sqrt((hitPlane - face.plane()) * (hitPlane - face.plane())
                        + (hitU - u) * (hitU - u) + (hitV - v) * (hitV - v));
                if (distance < nearestDistance) {
                    nearest = face;
                    nearestDistance = distance;
                    nearestU = u;
                    nearestV = v;
                }
            }
            if (nearest == null || nearestDistance >= radius) {
                continue;
            }

            float age = Mth.clamp((now - hit.tick()) / (float) lifetime, 0.0F, 1.0F);
            float fade = (1.0F - age) * (1.0F - age);
            float peak = PEAK_ALPHA * fade * (float) (1.0 - nearestDistance / radius);
            if (peak <= 0.01F) {
                continue;
            }
            // Wearing down: 0 on a fresh spot, towards 1 as the damage there is cut to the floor.
            float worn = Mth.clamp((hit.focus() - 1.0F) / 2.0F, 0.0F, 1.0F);
            float r = 1.0F;
            float g = Mth.lerp(worn, 0.9F, 0.12F);
            float b = Mth.lerp(worn, 0.2F, 0.05F);

            // The spot is where a sphere of the repeat-damage radius around the hit meets the wall: a circle.
            double reach = Math.sqrt(radius * radius - nearestDistance * nearestDistance);
            disc(vc, m, nearest, nearestU, nearestV, reach, peak, r, g, b);
        }
    }

    /** The coordinate of a hit across a face: 0 along the axis the face is perpendicular to, 1 and 2 along it. */
    private static double coordinate(FieldHeat.Hit hit, int axis, int which) {
        double x = hit.at().x, y = hit.at().y, z = hit.at().z;
        return switch (which) {
            case 0 -> axis == 0 ? x : axis == 1 ? y : z;
            case 1 -> axis == 0 ? y : x;
            default -> axis == 2 ? y : z;
        };
    }

    /**
     * A round spot: rings from the centre outwards, the alpha dropping smoothly from the peak to nothing at the edge.
     * Anything that would stick out past the edge of the wall is pulled back onto it.
     */
    private static void disc(VertexConsumer vc, Matrix4f m, Face face, double cu, double cv, double reach,
            float peak, float r, float g, float b) {
        for (int ring = 0; ring < RINGS; ring++) {
            float innerFraction = ring / (float) RINGS;
            float outerFraction = (ring + 1) / (float) RINGS;
            float innerAlpha = peak * smooth(1.0F - innerFraction);
            float outerAlpha = peak * smooth(1.0F - outerFraction);
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = 2 * Math.PI * i / SEGMENTS;
                double a1 = 2 * Math.PI * (i + 1) / SEGMENTS;
                corner(vc, m, face, cu, cv, reach * innerFraction, a0, r, g, b, innerAlpha);
                corner(vc, m, face, cu, cv, reach * innerFraction, a1, r, g, b, innerAlpha);
                corner(vc, m, face, cu, cv, reach * outerFraction, a1, r, g, b, outerAlpha);
                corner(vc, m, face, cu, cv, reach * outerFraction, a0, r, g, b, outerAlpha);
            }
        }
    }

    /** Eases the fade so the spot is soft at the edge and does not show a hard rim. */
    private static float smooth(float x) {
        return x * x * (3.0F - 2.0F * x);
    }

    private static void corner(VertexConsumer vc, Matrix4f m, Face face, double cu, double cv, double distance,
            double angle, float r, float g, float b, float a) {
        float u = (float) Mth.clamp(cu + distance * Math.cos(angle), face.uMin(), face.uMax());
        float v = (float) Mth.clamp(cv + distance * Math.sin(angle), face.vMin(), face.vMax());
        float p = (float) face.plane();
        switch (face.axis()) {
            case 0 -> vc.addVertex(m, p, u, v).setColor(r, g, b, a);
            case 1 -> vc.addVertex(m, u, p, v).setColor(r, g, b, a);
            default -> vc.addVertex(m, u, v, p).setColor(r, g, b, a);
        }
    }

    private FieldHeatRenderer() {
    }
}
