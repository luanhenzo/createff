package com.forcefields.forcefields.network;

import java.util.List;

import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.field.Access;
import com.forcefields.forcefields.field.Field;
import com.forcefields.forcefields.field.FieldAction;
import com.forcefields.forcefields.field.FieldAdmin;
import com.forcefields.forcefields.field.FieldManager;
import com.forcefields.forcefields.field.FieldShape;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = ForceFields.MODID)
public final class FieldNetwork {
    private static final String PROTOCOL = "1";

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        registrar.playToClient(FieldMessage.TYPE, FieldMessage.CODEC, ClientHandlers::message);
        registrar.playToClient(FieldHit.TYPE, FieldHit.CODEC, ClientHandlers::hit);
        registrar.playToClient(FieldRipple.TYPE, FieldRipple.CODEC, ClientHandlers::ripple);
        registrar.playToServer(FieldChange.TYPE, FieldChange.CODEC, FieldNetwork::handleChange);
    }

    private static void handleChange(FieldChange change, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Field field = FieldManager.get(level).get(change.core());
        if (field == null || !FieldAdmin.inReach(player, field)) {
            return;
        }
        if (!FieldAdmin.canManage(player, field)) {
            send(player, FieldAdmin.Result.error("command.forcefields.not_owner"));
            return;
        }

        FieldAdmin.Result result = apply(player, level, field, change);
        if (result.ok()) {
            FieldAdmin.changed(level, field);
        }
        // Access and size changes show on the screen itself; everything else gets a line of feedback.
        boolean visible = change.kind() == FieldChange.Kind.SET_ACCESS || change.kind() == FieldChange.Kind.RESIZE
                || change.kind() == FieldChange.Kind.POWER || change.kind() == FieldChange.Kind.SHAPE;
        if (!result.ok() || !visible) {
            send(player, result);
        }
    }

    private static FieldAdmin.Result apply(ServerPlayer player, ServerLevel level, Field field, FieldChange change) {
        return switch (change.kind()) {
            case SET_ACCESS -> {
                FieldAction[] actions = FieldAction.values();
                Access[] levels = Access.values();
                if (change.action() < 0 || change.action() >= actions.length
                        || change.access() < 0 || change.access() >= levels.length) {
                    yield FieldAdmin.Result.rawError("Invalid setting");
                }
                yield FieldAdmin.setAccess(field, actions[change.action()], levels[change.access()]);
            }
            case RESIZE -> {
                if (change.action() == FieldChange.RADIUS) {
                    yield FieldAdmin.resizeRadius(level, field, change.access());
                }
                if (change.action() < 0 || change.action() >= Direction.values().length) {
                    yield FieldAdmin.Result.rawError("Invalid setting");
                }
                Direction side = Direction.from3DDataValue(change.action());
                yield FieldAdmin.resize(level, field, side, change.access());
            }
            case SHAPE -> {
                FieldShape.Type[] types = FieldShape.Type.values();
                if (change.access() < 0 || change.access() >= types.length) {
                    yield FieldAdmin.Result.rawError("Invalid setting");
                }
                yield FieldAdmin.setShape(level, field, types[change.access()]);
            }
            case POWER -> FieldAdmin.setPower(field, change.access() != 0);
            case TRUST_ADD -> FieldAdmin.addTrusted(field, FieldAdmin.resolve(player.server, change.text().trim()));
            case TRUST_REMOVE -> FieldAdmin.removeTrustedByName(field, change.text().trim());
            case ADMIN_ADD -> FieldAdmin.addAdmin(field, FieldAdmin.resolve(player.server, change.text().trim()));
            case ADMIN_REMOVE -> FieldAdmin.removeAdminByName(field, change.text().trim());
            case BLOCK_ADD -> FieldAdmin.editList(field, true, true, change.text());
            case BLOCK_REMOVE -> FieldAdmin.editList(field, true, false, change.text());
            case ITEM_ADD -> FieldAdmin.editList(field, false, true, change.text());
            case ITEM_REMOVE -> FieldAdmin.editList(field, false, false, change.text());
        };
    }

    public static void send(ServerPlayer player, FieldAdmin.Result result) {
        PacketDistributor.sendToPlayer(player, new FieldMessage(result.ok(), result.key(), List.copyOf(result.args())));
    }

    private FieldNetwork() {
    }
}
