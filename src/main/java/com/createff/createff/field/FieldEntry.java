package com.createff.createff.field;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.createff.createff.Config;
import com.createff.createff.CreateFF;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Keeps players out of online fields that do not allow entry. Movement is client-driven, so a
 * player who crosses a wall is put back at the point where they crossed it.
 * Players already inside when a field comes online may leave, but cannot come back in.
 */
@EventBusSubscriber(modid = CreateFF.MODID)
public final class FieldEntry {
    private static final double PUSHBACK = 0.35;

    private static final Map<UUID, Vec3> LAST_POSITION = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Vec3 now = player.position();
        Vec3 before = LAST_POSITION.put(player.getUUID(), now);
        if (before == null || before.distanceToSqr(now) < 1.0E-6 || before.distanceToSqr(now) > 128 * 128) {
            return;
        }
        if (player.isSpectator()
                || (Config.CREATIVE_OPS_BYPASS.get() && player.isCreative() && player.hasPermissions(2))) {
            return;
        }

        ServerLevel level = player.serverLevel();
        for (Field field : FieldManager.get(level).all()) {
            if (field.state() != FieldState.ONLINE || field.allows(FieldAction.ENTER, player.getUUID())) {
                continue;
            }
            FieldShape shape = field.worldShape();
            FieldShape last = field.lastWorldShape();
            // Only a real crossing of the feet counts; touching the wall with the body is the collision's job. A
            // field on a ship moves, so the start of the step is judged against where the field was then.
            if (last.contains(before) || !shape.contains(now)) {
                continue;
            }
            // The start of the step as it is carried along with the field, which makes the path relative to the wall.
            Vec3 carried = shape.carry(last, before);
            Optional<Vec3> hit = shape.clip(carried, now);
            // Through the opening of a gate there is no wall, so nothing to be put back from.
            if (hit.isPresent() && Gates.opensAt(field.settings().gates(), shape, hit.get())) {
                continue;
            }
            // A ship that moved over a player who stood still never crossed the wall: put them on the nearest side.
            Vec3 back = hit.map(point -> shape.pushOut(point, PUSHBACK))
                    .orElseGet(() -> shape.isSphere() || shape.isOriented()
                            ? shape.pushOut(shape.surfacePointNear(now), PUSHBACK)
                            : before);

            player.connection.teleport(back.x, back.y, back.z, player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
            player.hurtMarked = true;
            LAST_POSITION.put(player.getUUID(), back);
            player.displayClientMessage(Component.translatable("message.createff.no_entry"), true);
            return;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_POSITION.remove(event.getEntity().getUUID());
    }

    private FieldEntry() {
    }
}
