package com.createff.createff.network;

import java.util.List;

import com.createff.createff.CreateFF;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: the outcome of a change, as a translation key so the client shows it in its own language. */
public record FieldMessage(boolean ok, String key, List<String> args) implements CustomPacketPayload {
    public static final Type<FieldMessage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateFF.MODID, "field_message"));
    public static final StreamCodec<ByteBuf, FieldMessage> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FieldMessage::ok,
            ByteBufCodecs.stringUtf8(256), FieldMessage::key,
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(8)), FieldMessage::args,
            FieldMessage::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
