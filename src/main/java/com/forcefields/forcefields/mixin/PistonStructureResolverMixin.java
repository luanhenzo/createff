package com.forcefields.forcefields.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.forcefields.forcefields.field.FieldBoundary;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

/**
 * Makes a piston unable to move anything across the wall of a field: pushing into one, pulling out of one, or
 * extending its head into one. It is done by having the structure resolver say the move is impossible, which the game
 * already knows how to handle: the piston simply does not extend, and a sticky piston that cannot pull draws back
 * and leaves the block, with the usual animation. Cancelling the piston event instead would also stop the game from
 * telling the clients about the move, so they would not play it.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {
    @Shadow
    @Final
    private Level level;
    @Shadow
    @Final
    private BlockPos pistonPos;
    @Shadow
    @Final
    private boolean extending;
    @Shadow
    @Final
    private Direction pushDirection;
    @Shadow
    @Final
    private Direction pistonDirection;
    @Shadow
    @Final
    private List<BlockPos> toPush;
    @Shadow
    @Final
    private List<BlockPos> toDestroy;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void forcefields$stayInsideField(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue()) {
            return;
        }
        // The head moves into the block in front of the piston when it extends.
        boolean crossing = extending && FieldBoundary.separates(level, pistonPos, pistonPos.relative(pistonDirection));
        // Everything that would be moved or broken, and where the moved blocks would end up.
        for (BlockPos moved : toPush) {
            crossing |= FieldBoundary.separates(level, pistonPos, moved)
                    || FieldBoundary.separates(level, pistonPos, moved.relative(pushDirection));
        }
        for (BlockPos broken : toDestroy) {
            crossing |= FieldBoundary.separates(level, pistonPos, broken);
        }
        if (crossing) {
            callback.setReturnValue(false);
        }
    }
}
