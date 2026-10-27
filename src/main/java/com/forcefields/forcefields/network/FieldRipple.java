package com.forcefields.forcefields.network;

import com.forcefields.forcefields.ForceFields;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: something struck a field here (an explosion, a projectile on the wall, another field). {@code size}
 * says how hard, about 1 for a TNT, and the client makes a ripple on the wall of that size. {@code core} is the
 * projector of the field that was struck as {@code BlockPos#asLong}, for when two walls are at the same place and the
 * client could not tell which one is meant; {@link #ANY} lets the client take the nearest.
 */
public record FieldRipple(double x, double y, double z, float size, long core) implements CustomPacketPayload {
    public static final long ANY = Long.MIN_VALUE;

    public static final Type<FieldRipple> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ForceFields.MODID, "field_ripple"));
    public static final StreamCodec<ByteBuf, FieldRipple> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, FieldRipple::x,
            ByteBufCodecs.DOUBLE, FieldRipple::y,
            ByteBufCodecs.DOUBLE, FieldRipple::z,
            ByteBufCodecs.FLOAT, FieldRipple::size,
            ByteBufCodecs.VAR_LONG, FieldRipple::core,
            FieldRipple::new);

    /** A ripple on whichever field is nearest to the point. */
    public static FieldRipple anywhere(double x, double y, double z, float size) {
        return new FieldRipple(x, y, z, size, ANY);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
