package com.createff.createff.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createff.createff.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Create Big Cannons changes the blocks along the path of a shell explosion as it works out the blast, before the
 * explosion hands over the list of blocks it will break. The list is what {@code FieldEvents} protects, so that
 * change had to be stopped here, or shells would still turn the ground under a field into something else.
 *
 * <p>{@code @Pseudo} makes this a no-op when Big Cannons is not installed. The method is the same in all three
 * classes, which are the ones that change blocks; the others leave it empty.
 */
@Pseudo
@Mixin(targets = {
        "rbasamoyai.createbigcannons.munitions.ShellExplosion",
        "rbasamoyai.createbigcannons.munitions.ImpactExplosion",
        "rbasamoyai.createbigcannons.munitions.big_cannon.mortar_stone.MortarStoneExplosion"
}, remap = false)
public abstract class CannonExplosionMixin {
    @Inject(method = "editBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void createff$sparePlacesUnderAField(Level level, BlockPos pos, BlockState state, FluidState fluid,
            float power, CallbackInfo callback) {
        if (FieldBoundary.covered(level, pos)) {
            callback.cancel();
        }
    }
}
