package com.createff.createff.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.createff.createff.field.FieldTransfers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Notes which block entity is ticking, so that what it asks of its neighbours can be told who asked. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @Shadow
    @Final
    private BlockEntity blockEntity;

    @Inject(method = "tick", at = @At("HEAD"))
    private void createff$actorStarts(CallbackInfo callback) {
        Level level = blockEntity.getLevel();
        if (level instanceof ServerLevel) {
            FieldTransfers.begin(level, blockEntity.getBlockPos());
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void createff$actorStops(CallbackInfo callback) {
        FieldTransfers.end();
    }
}
