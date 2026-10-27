package com.createff.createff.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createff.createff.field.FieldTransfers;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * The one place every block asks for another block's inventory. Items and fluids of a container inside a field are
 * not handed to a block outside it; see {@link FieldTransfers}. Energy is left alone, so a cable can still bring
 * power in.
 */
@Mixin(BlockCapability.class)
public abstract class BlockCapabilityMixin<T, C> {
    @Inject(method = "getCapability", at = @At("HEAD"), cancellable = true)
    private void createff$noReachingIn(Level level, BlockPos pos, BlockState state, BlockEntity blockEntity, C context,
            CallbackInfoReturnable<T> callback) {
        Object self = this;
        if ((self == Capabilities.ItemHandler.BLOCK || self == Capabilities.FluidHandler.BLOCK)
                && level instanceof ServerLevel && FieldTransfers.denied(level, pos)) {
            callback.setReturnValue(null);
        }
    }
}
