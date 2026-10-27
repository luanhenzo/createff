package com.createff.createff.block;

import java.util.UUID;

import com.createff.createff.field.Field;
import com.createff.createff.field.FieldAdmin;
import com.createff.createff.field.FieldLimits;
import com.createff.createff.field.FieldManager;
import com.createff.createff.field.FieldState;
import com.createff.createff.registry.Registration;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.createff.createff.menu.FieldProjectorMenu;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class FieldProjectorBlock extends RotatedPillarKineticBlock implements IBE<FieldProjectorBlockEntity> {
    public FieldProjectorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    /** The server refuses a projector that would take a player over the limit of fields they may own. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state != null && context.getPlayer() instanceof ServerPlayer player && !FieldLimits.mayPlace(player)) {
            return null;
        }
        return state;
    }

    @Override
    public Class<FieldProjectorBlockEntity> getBlockEntityClass() {
        return FieldProjectorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FieldProjectorBlockEntity> getBlockEntityType() {
        return Registration.FIELD_PROJECTOR_BE.get();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player) {
            withBlockEntityDo(level, pos, be -> be.setOwner(player.getUUID(), player.getGameProfile().getName()));
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        // A projector that is only being moved (by a piston, or into a ship) takes its force core along in its own data.
        if (!isMoving && !state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof FieldProjectorBlockEntity projector) {
            projector.dropCore(level, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
        if (level instanceof ServerLevel serverLevel && !state.is(newState.getBlock())) {
            FieldManager.get(serverLevel).remove(pos);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (level instanceof ServerLevel serverLevel) {
            Field field = FieldManager.get(serverLevel).get(pos);
            if (field == null) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer serverPlayer && FieldAdmin.canView(player, field)
                    && level.getBlockEntity(pos) instanceof FieldProjectorBlockEntity projector) {
                boolean canEdit = FieldAdmin.canManage(player, field);
                serverPlayer.openMenu(new SimpleMenuProvider(
                        (id, inventory, opener) -> new FieldProjectorMenu(id, inventory, projector, canEdit),
                        Component.translatable("block.createff.field_projector")), buffer -> {
                            buffer.writeBlockPos(pos);
                            buffer.writeBoolean(canEdit);
                        });
            } else {
                player.displayClientMessage(describe(field), false);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static Component describe(Field field) {
        String state = field.state().name().toLowerCase();
        int percent = (int) Math.round(field.overloadFraction() * 100);
        Component owner = Component.literal(field.owner() == null ? "-" : ownerName(field));
        Component line = Component.translatable("message.createff.status", Component.translatable("state.createff." + state),
                percent, Math.round(field.rpm()), owner);
        if (field.state() == FieldState.OVERHEATED) {
            int seconds = field.secondsUntilRestart();
            line = line.copy().append(seconds < 0
                    ? Component.translatable("message.createff.no_power")
                    : Component.translatable("message.createff.restart_in", seconds));
        }
        return line;
    }

    private static String ownerName(Field field) {
        if (field.ownerName() != null) {
            return field.ownerName();
        }
        UUID owner = field.owner();
        return owner.toString().substring(0, 8);
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        Field field = FieldManager.get(serverLevel).get(pos);
        return field == null ? 0 : FieldProjectorBlockEntity.signalFor(field);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        Field field = FieldManager.get(serverLevel).get(pos);
        return field == null ? 0 : FieldProjectorBlockEntity.signalFor(field);
    }
}
