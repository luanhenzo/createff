package com.createff.createff.field;

import java.util.List;
import java.util.Optional;

import com.createff.createff.CreateFF;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The ways around a field that are not a player clicking on a block: teleports, mobs and what a field keeps
 * standing in it, such as item frames, vehicles and animals. Pistons, fluids, fire and dispensers have no event that
 * does the job, so they are handled by mixins that ask {@link FieldBoundary}.
 */
@EventBusSubscriber(modid = CreateFF.MODID)
public final class FieldWorldEvents {
    /** Ender pearls and chorus fruit must not carry anyone over a wall. */
    @SubscribeEvent
    public static void onTeleport(EntityTeleportEvent event) {
        if (event instanceof EntityTeleportEvent.TeleportCommand || event instanceof EntityTeleportEvent.SpreadPlayersCommand) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        Field field = FieldManager.get(level).coveringFieldAt(event.getTarget());
        if (field == null || field.coversPoint(player.position())
                || FieldProtection.bypasses(level, player) || field.allows(FieldAction.ENTER, player.getUUID())
                || Gates.opensAt(field.settings().gates(), field.worldShape(), event.getTarget())) {
            return;
        }
        event.setCanceled(true);
        player.displayClientMessage(Component.translatable("message.createff.no_entry"), true);
    }

    /** Monsters do not get to break blocks, take them or open doors inside a field. */
    @SubscribeEvent
    public static void onMobGriefing(EntityMobGriefingEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Enemy && entity.level() instanceof ServerLevel level
                && FieldManager.get(level).isCoveredAt(entity.position())) {
            event.setCanGrief(false);
        }
    }

    /** Nobody tramples the farmland of a field, and visitors do not get to either. */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !FieldManager.get(level).isCovered(event.getPos())) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            if (FieldProtection.isDenied(level, event.getPos(), player, FieldAction.BREAK)) {
                event.setCanceled(true);
            }
        } else {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (FieldProtection.isEntityDenied(player.level(), event.getTarget(), player, true)) {
            event.setCanceled(true);
            player.displayClientMessage(Component.translatable("message.createff.protected"), true);
        }
    }

    /** Covers what the attack event does not: arrows, tridents and anything else a player hurts a protected entity with. */
    @SubscribeEvent
    public static void onInvulnerabilityCheck(EntityInvulnerabilityCheckEvent event) {
        DamageSource source = event.getSource();
        if (source.getEntity() instanceof Player player
                && FieldProtection.isEntityDenied(player.level(), event.getEntity(), player, true)) {
            event.setInvulnerable(true);
        }
    }

    /** Right-clicking a protected entity (turning an item frame, riding a boat, shearing a sheep) needs a role in the field. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (FieldProtection.isEntityDenied(player.level(), event.getTarget(), player, false)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.translatable("message.createff.protected"), true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        Player player = event.getEntity();
        if (FieldProtection.isEntityDenied(player.level(), event.getTarget(), player, false)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.translatable("message.createff.protected"), true);
        }
    }

    /**
     * Takes the protected entities out of an explosion, so a blast at the wall does not wreck what stands behind it.
     * A blast that comes in through the opening of a gate meets no wall, so what it reaches that way is not shielded.
     */
    static void shield(ServerLevel level, List<Entity> affected, Vec3 center) {
        FieldManager manager = FieldManager.get(level);
        affected.removeIf(entity -> {
            if (!FieldProtection.isProtectedEntity(entity)) {
                return false;
            }
            Field field = manager.coveringFieldAt(entity.position());
            if (field == null) {
                return false;
            }
            if (!field.settings().gates().isEmpty() && !field.coversPoint(center)) {
                Optional<Vec3> crossing = field.worldShape().clip(center, entity.position());
                return crossing.isEmpty() || !Gates.opensAt(field.settings().gates(), field.worldShape(), crossing.get());
            }
            return true;
        });
    }

    private FieldWorldEvents() {
    }
}
