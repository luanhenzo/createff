package com.forcefields.forcefields.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.forcefields.forcefields.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    /** Fire next to a block inside a field never burns it away: that is how it destroys what it spreads to. */
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void forcefields$noBurning(Level level, BlockPos pos, int chance, RandomSource random, int age,
            Direction face, CallbackInfo callback) {
        if (FieldBoundary.covered(level, pos)) {
            callback.cancel();
        }
    }
}
