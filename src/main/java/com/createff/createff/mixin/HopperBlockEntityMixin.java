package com.createff.createff.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createff.createff.Config;
import com.createff.createff.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hoppers move items between containers, and a container inside a field must not be reached across its wall. A hopper
 * may work with a container in the field it stands in, or outside it, but not with one in a field it is not in. Both
 * entry points are cut off before they run, so the loose-item and modded-inventory paths are covered as well.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    /** A hopper outside a field cannot push items into a container in it. */
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void createff$noPushingIn(Level level, BlockPos pos, HopperBlockEntity hopper,
            CallbackInfoReturnable<Boolean> callback) {
        BlockState state = level.getBlockState(pos);
        if (Config.BLOCK_TRANSFERS_ACROSS_WALLS.get() && state.hasProperty(HopperBlock.FACING)
                && FieldBoundary.entersField(level, pos, pos.relative(state.getValue(HopperBlock.FACING)))) {
            callback.setReturnValue(false);
        }
    }

    /** A hopper, or a hopper minecart, outside a field cannot pull items out of a container or the ground in it. */
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void createff$noPullingOut(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> callback) {
        BlockPos at = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        if (Config.BLOCK_TRANSFERS_ACROSS_WALLS.get() && FieldBoundary.entersField(level, at, at.above())) {
            callback.setReturnValue(false);
        }
    }
}
