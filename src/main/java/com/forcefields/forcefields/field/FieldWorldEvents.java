package com.forcefields.forcefields.field;

import java.util.List;
import java.util.function.Predicate;

import com.forcefields.forcefields.ForceFields;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
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
@EventBusSubscriber(modid = ForceFields.MODID)
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
                || FieldProtection.bypasses(level, player) || field.allows(FieldAction.ENTER, player.getUUID())) {
            return;
        }
        event.setCanceled(true);
        player.displayClientMessage(Component.translatable("message.forcefields.no_entry"), true);
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
            player.displayClientMessage(Component.translatable("message.forcefields.protected"), true);
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
            player.displayClientMessage(Component.translatable("message.forcefields.protected"), true);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        Player player = event.getEntity();
        if (FieldProtection.isEntityDenied(player.level(), event.getTarget(), player, false)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            player.displayClientMessage(Component.translatable("message.forcefields.protected"), true);
        }
    }

    /** Takes the protected entities out of an explosion, so a blast at the wall does not wreck what stands behind it. */
    static void shield(ServerLevel level, List<Entity> affected) {
        FieldManager manager = FieldManager.get(level);
        Predicate<Entity> protectedInside = entity ->
                FieldProtection.isProtectedEntity(entity) && manager.isCoveredAt(entity.position());
        affected.removeIf(protectedInside);
    }

    private FieldWorldEvents() {
    }
}
