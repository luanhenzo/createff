package com.forcefields.forcefields.network;

import com.forcefields.forcefields.ForceFields;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client to server: a change the player made in the screen. The server decides whether they may.
 * {@code action} and {@code access} are ordinals of the matching enums, {@code text} a player name or list entry.
 */
public record FieldChange(BlockPos core, Kind kind, int action, int access, String text) implements CustomPacketPayload {
    public enum Kind {
        SET_ACCESS,
        RESIZE,
        POWER,
        TRUST_ADD,
        TRUST_REMOVE,
        ADMIN_ADD,
        ADMIN_REMOVE,
        BLOCK_ADD,
        BLOCK_REMOVE,
        ITEM_ADD,
        ITEM_REMOVE,
        SHAPE
    }

    /** The {@code side} of a RESIZE that means the radius of a sphere rather than a side of a cube. */
    public static final int RADIUS = 6;

    public static final Type<FieldChange> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ForceFields.MODID, "field_change"));
    public static final StreamCodec<ByteBuf, FieldChange> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FieldChange::core,
            ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), FieldChange::kind,
            ByteBufCodecs.VAR_INT, FieldChange::action,
            ByteBufCodecs.VAR_INT, FieldChange::access,
            ByteBufCodecs.stringUtf8(256), FieldChange::text,
            FieldChange::new);

    public static FieldChange access(BlockPos core, int action, int access) {
        return new FieldChange(core, Kind.SET_ACCESS, action, access, "");
    }

    public static FieldChange resize(BlockPos core, int side, int delta) {
        return new FieldChange(core, Kind.RESIZE, side, delta, "");
    }

    public static FieldChange shape(BlockPos core, int type) {
        return new FieldChange(core, Kind.SHAPE, 0, type, "");
    }

    public static FieldChange power(BlockPos core, boolean on) {
        return new FieldChange(core, Kind.POWER, 0, on ? 1 : 0, "");
    }

    public static FieldChange text(BlockPos core, Kind kind, String text) {
        return new FieldChange(core, kind, 0, 0, text);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
