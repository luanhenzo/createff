package com.createff.createff.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.createff.createff.field.FieldCollisions;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /**
     * Adds the walls of fields that deny entry to the movement every entity resolves. Hooked on the final
     * result of collide(): its step-up branch calls collideWithShapes directly, so hooking
     * collideBoundingBox would let a blocked entity on the ground slip through the wall.
     */
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void createff$fieldWalls(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Entity self = (Entity) (Object) this;
        Vec3 restricted = FieldCollisions.restrict(self, cir.getReturnValue(), self.getBoundingBox(), self.level());
        if (restricted != cir.getReturnValue()) {
            cir.setReturnValue(restricted);
        }
    }
}
