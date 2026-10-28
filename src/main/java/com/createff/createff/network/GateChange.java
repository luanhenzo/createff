package com.createff.createff.network;

import com.createff.createff.CreateFF;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Client to server: a change to the gates of a field. {@code point} is where the gate goes, for a new one or to move one: where the player
 * aimed, in the field's own axes from its projector, which is how a gate is kept; {@code index} says which gate of the field, and {@code width} and {@code height} how big. The
 * server checks that the player may change the field and that every number is in range.
 */
public record GateChange(BlockPos core, Kind kind, int index, Vec3 point, float width, float height) implements CustomPacketPayload {
    public enum Kind {
        ADD,
        MOVE,
        SIZE,
        SHAPE,
        OPEN,
        REMOVE
    }

    public static final Type<GateChange> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateFF.MODID, "gate_change"));
    private static final StreamCodec<ByteBuf, Vec3> POINT = StreamCodec.of(
            (buffer, point) -> {
                buffer.writeDouble(point.x);
                buffer.writeDouble(point.y);
                buffer.writeDouble(point.z);
            },
            buffer -> new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()));
    public static final StreamCodec<ByteBuf, GateChange> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, GateChange::core,
            ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), GateChange::kind,
            ByteBufCodecs.VAR_INT, GateChange::index,
            POINT, GateChange::point,
            ByteBufCodecs.FLOAT, GateChange::width,
            ByteBufCodecs.FLOAT, GateChange::height,
            GateChange::new);

    public static GateChange add(BlockPos core, Vec3 point) {
        return new GateChange(core, Kind.ADD, 0, point, 0.0F, 0.0F);
    }

    public static GateChange move(BlockPos core, int index, Vec3 point) {
        return new GateChange(core, Kind.MOVE, index, point, 0.0F, 0.0F);
    }

    public static GateChange size(BlockPos core, int index, float width, float height) {
        return new GateChange(core, Kind.SIZE, index, Vec3.ZERO, width, height);
    }

    public static GateChange of(BlockPos core, Kind kind, int index) {
        return new GateChange(core, kind, index, Vec3.ZERO, 0.0F, 0.0F);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
