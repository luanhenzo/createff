package com.forcefields.forcefields.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.forcefields.forcefields.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {
    /** Droppers do not use the dispenser code, so they need the same rule: nothing is dropped into a field from outside. */
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void forcefields$noDroppingIn(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo callback) {
        if (FieldBoundary.entersField(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            callback.cancel();
        }
    }
}
