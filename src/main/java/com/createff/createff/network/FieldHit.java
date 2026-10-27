package com.createff.createff.network;

import com.createff.createff.CreateFF;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server to client: an explosion hurt a field here. {@code focus} is what its damage was multiplied by for landing
 * on a spot that was hit lately: 1 for a fresh spot, more the more it was hit. The client paints it on the wall.
 */
public record FieldHit(double x, double y, double z, float focus) implements CustomPacketPayload {
    public static final Type<FieldHit> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateFF.MODID, "field_hit"));
    public static final StreamCodec<ByteBuf, FieldHit> CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, FieldHit::x,
            ByteBufCodecs.DOUBLE, FieldHit::y,
            ByteBufCodecs.DOUBLE, FieldHit::z,
            ByteBufCodecs.FLOAT, FieldHit::focus,
            FieldHit::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
