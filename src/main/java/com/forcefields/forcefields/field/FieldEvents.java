package com.forcefields.forcefields.field;

import com.forcefields.forcefields.Config;
import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.block.FieldProjectorBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.forcefields.forcefields.network.FieldHit;
import com.forcefields.forcefields.network.FieldRipple;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = ForceFields.MODID)
public final class FieldEvents {
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            FieldManager manager = FieldManager.get(level);
            if (level.getGameTime() % 200 == 0) {
                manager.removeOrphans(level);
            }
            if (!manager.all().isEmpty()) {
                manager.tick(level);
                manager.setDirty();
            }
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (FieldProtection.isDenied(event.getPlayer().level(), event.getPos(), event.getPlayer(), FieldAction.BREAK)) {
            event.setCanceled(true);
            deny(event.getPlayer());
        }
    }

    /** Protected blocks cannot even start to be mined, like bedrock: no progress, no cracks. */
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.ABORT) {
            return;
        }
        Player player = event.getEntity();
        if (FieldProtection.isDenied(player.level(), event.getPos(), player, FieldAction.BREAK)) {
            event.setCanceled(true);
            if (event.getAction() == PlayerInteractEvent.LeftClickBlock.Action.START) {
                deny(player);
            }
        }
    }

    /**
     * Using an item (placing a block, a hoe, a bucket...) or a block (a door, a chest) in a field that does not
     * allow it is refused before it starts, so nothing happens, nothing is consumed and the client never plays
     * the animation.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = player.level();
        BlockPos clicked = event.getPos();
        BlockState state = level.getBlockState(clicked);

        boolean blockDenied = FieldProtection.isInteractionDenied(level, clicked, state, player);
        if (blockDenied) {
            event.setUseBlock(TriState.FALSE);
        }
        ItemStack stack = event.getItemStack();
        boolean itemDenied = !stack.isEmpty()
                && FieldProtection.isItemUseDenied(level, clicked, clicked.relative(event.getFace()), stack, player);
        if (itemDenied) {
            event.setUseItem(TriState.FALSE);
        }
        // Most blocks do nothing when used, so only say so for ones that would have.
        if (itemDenied || (blockDenied && looksInteractive(state))) {
            deny(player);
        }
    }

    private static boolean looksInteractive(BlockState state) {
        return state.hasBlockEntity()
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.BUTTONS) || state.is(Blocks.LEVER)
                || state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.ANVIL) || state.is(Blocks.ENCHANTING_TABLE);
    }

    /** Backup for anything that mines without a click, such as tools or addons: zero mining speed. */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        event.getPosition().ifPresent(pos -> {
            if (FieldProtection.isDenied(event.getEntity().level(), pos, event.getEntity(), FieldAction.BREAK)) {
                event.setNewSpeed(0.0F);
            }
        });
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (FieldProtection.isDenied(player.level(), event.getPos(), player, FieldAction.PLACE)) {
            event.setCanceled(true);
            deny(player);
            return;
        }
        if (event.getPlacedBlock().getBlock() instanceof FieldProjectorBlock && event.getLevel() instanceof ServerLevel level) {
            int extent = Config.DEFAULT_EXTENT.get();
            BlockPos pos = event.getPos();
            AABB bounds = new AABB(pos).inflate(extent);
            if (FieldManager.get(level).overlaps(bounds, pos)) {
                event.setCanceled(true);
                player.displayClientMessage(Component.translatable("message.forcefields.overlap"), true);
            }
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FieldManager manager = FieldManager.get(level);
        Explosion explosion = event.getExplosion();
        if (!manager.all().isEmpty()) {
            FieldDebug.ship(level, "explosion " + explosion.getClass().getSimpleName() + " radius " + explosion.radius(), explosion.center());
        }

        if (Config.EXPLOSIONS_DAMAGE_FIELDS.get()) {
            Vec3 center = explosion.center();
            double radius = Config.EXPLOSION_DAMAGE_RADIUS.get();
            double base = Math.min(explosion.radius() * Config.DAMAGE_PER_EXPLOSION_POWER.get(),
                    Config.MAX_DAMAGE_PER_EXPLOSION.get());
            boolean rippled = false;
            for (Field field : manager.all()) {
                double distance = field.distanceTo(center);
                if (distance > radius) {
                    FieldDebug.explosionTooFar(field, explosion, distance);
                    continue;
                }
                double falloff = radius <= 0 ? 1.0 : 2.0 - distance / radius;
                double overloadBefore = field.overload();
                FieldState stateBefore = field.state();
                boolean overheated = field.addDamage(base * falloff, level.getGameTime(), center);
                FieldDebug.explosion(field, explosion, distance, base, falloff, overloadBefore, stateBefore, level.getGameTime());
                if (field.lastHitApplied()) {
                    // Players near the field see where it is being hit, and how worn down that spot already is.
                    PacketDistributor.sendToPlayersNear(level, null, center.x, center.y, center.z, 160.0,
                            new FieldHit(center.x, center.y, center.z, (float) field.lastFocus()));
                }
                if (stateBefore == FieldState.ONLINE && !rippled) {
                    // The wall ripples with the blast, as big as the damage it does before any wearing down at a
                    // spot that was hit before (a TNT next to the wall is about 1, a shell several times that).
                    rippled = true;
                    PacketDistributor.sendToPlayersNear(level, null, center.x, center.y, center.z, 160.0,
                            FieldRipple.anywhere(center.x, center.y, center.z, (float) Math.min(3.0, base * falloff / 32.0)));
                }
                if (overheated) {
                    onOverheated(level, field);
                }
            }
        }

        event.getAffectedBlocks().removeIf(pos -> manager.isCovered(pos));
        FieldWorldEvents.shield(level, event.getAffectedEntities());
    }

    private static void deny(Player player) {
        player.displayClientMessage(Component.translatable("message.forcefields.protected"), true);
    }

    static void onOverheated(ServerLevel level, Field field) {
        BlockPos core = field.core();
        level.playSound(null, core, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 4.0F, 0.5F);
        if (field.owner() != null && level.getServer().getPlayerList().getPlayer(field.owner()) instanceof ServerPlayer owner) {
            owner.sendSystemMessage(Component.translatable("message.forcefields.overheated",
                    core.getX(), core.getY(), core.getZ()));
        }
    }

    private FieldEvents() {
    }
}
