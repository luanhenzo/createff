package com.forcefields.forcefields.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.forcefields.forcefields.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    /** A dispenser outside a field cannot fire, pour or place anything into it. */
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void forcefields$noFiringIn(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo callback) {
        if (FieldBoundary.entersField(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            callback.cancel();
        }
    }
}
