package com.forcefields.forcefields.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.forcefields.forcefields.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    /** Water and lava do not flow into a field from outside it. What flows inside, or out of it, is left alone. */
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void forcefields$keepOut(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
            FluidState fluid, CallbackInfo callback) {
        if (FieldBoundary.entersField(level, pos.relative(direction.getOpposite()), pos)) {
            callback.cancel();
        }
    }
}
