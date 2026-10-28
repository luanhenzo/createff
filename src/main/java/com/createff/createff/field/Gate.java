package com.createff.createff.field;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/**
 * An opening an owner cuts in the wall of a field. It is a piece of the field's own settings, not something built: the
 * wall is simply not there inside its outline, for everything that would be held back by it. Where it is and how it
 * is turned is worked out from the shape of the field by {@link Gates}.
 *
 * @param id     what the gate is called in its field: a number that is never used again, so a redstone controller
 *               or a message can refer to it when the others are removed
 * @param anchor where the opening is, as an offset from the centre of the field: for a sphere the direction from the
 *               centre to the middle of the opening, for a cube a point on the face it is in
 * @param width  how wide the outline is, in blocks, across the wall
 * @param height how tall, in blocks, up the wall
 * @param shape  the outline
 * @param open   whether it is letting things through; a shut one leaves the wall whole
 */
public record Gate(int id, Vec3 anchor, double width, double height, Shape shape, boolean open) {
    public enum Shape {
        RECTANGLE, OVAL;

        public Shape next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Gate withId(int id) {
        return new Gate(id, anchor, width, height, shape, open);
    }

    public Gate withAnchor(Vec3 anchor) {
        return new Gate(id, anchor, width, height, shape, open);
    }

    public Gate withSize(double width, double height) {
        return new Gate(id, anchor, width, height, shape, open);
    }

    public Gate withShape(Shape shape) {
        return new Gate(id, anchor, width, height, shape, open);
    }

    public Gate withOpen(boolean open) {
        return new Gate(id, anchor, width, height, shape, open);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putDouble("X", anchor.x);
        tag.putDouble("Y", anchor.y);
        tag.putDouble("Z", anchor.z);
        tag.putDouble("Width", width);
        tag.putDouble("Height", height);
        tag.putString("Shape", shape.name());
        tag.putBoolean("Open", open);
        return tag;
    }

    public static Gate load(CompoundTag tag) {
        Shape shape;
        try {
            shape = Shape.valueOf(tag.getString("Shape"));
        } catch (IllegalArgumentException e) {
            shape = Shape.RECTANGLE;
        }
        return new Gate(tag.getInt("Id"), new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                Math.max(1.0, tag.getDouble("Width")), Math.max(1.0, tag.getDouble("Height")), shape,
                !tag.contains("Open") || tag.getBoolean("Open"));
    }
}
