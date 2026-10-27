package com.forcefields.forcefields.field;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.forcefields.forcefields.Config;
import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.network.FieldRipple;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Makes the walls of online fields solid for things entering from outside: arrows, thrown items,
 * cannon shells (any {@link Projectile}) and primed TNT.
 */
@EventBusSubscriber(modid = ForceFields.MODID)
public final class FieldProjectiles {
    private static final Map<Class<?>, Method> ON_HIT = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> DETONATE = new ConcurrentHashMap<>();
    private static final Method NO_METHOD = Object.class.getDeclaredMethods()[0];
    /** Moves longer than this in one tick are teleports, not flight. */
    private static final double MAX_SEGMENT = 128.0;

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || !Config.PROJECTILES_COLLIDE.get()) {
            return;
        }
        boolean projectile = entity instanceof Projectile;
        if (!projectile && !(entity instanceof PrimedTnt)) {
            return;
        }

        Vec3 from = new Vec3(entity.xo, entity.yo, entity.zo);
        Vec3 to = entity.position();
        if (from.distanceToSqr(to) < 1.0E-6 || from.distanceToSqr(to) > MAX_SEGMENT * MAX_SEGMENT) {
            return;
        }

        FieldManager manager = FieldManager.get(level);
        for (Field field : manager.all()) {
            if (field.state() != FieldState.ONLINE) {
                continue;
            }
            // On a ship the wall moves: where the projectile started is judged against where the wall was then, and
            // carried along with it, so the path is the one relative to the wall.
            FieldShape shape = field.worldShape();
            FieldShape last = field.lastWorldShape();
            if (last.contains(from)) {
                continue;
            }
            Vec3 carried = shape.carry(last, from);
            Optional<Vec3> hit = shape.clip(carried, to);
            if (hit.isEmpty()) {
                continue;
            }
            // How fast it went at the wall, relative to the wall, which for a field on a ship is not its speed in the world.
            collide(level, field, entity, hit.get(), projectile, to.distanceTo(carried));
            return;
        }
    }

    private static void collide(ServerLevel level, Field field, Entity entity, Vec3 hit, boolean projectile, double speed) {
        // The wall as it is in the world: for a field on a ship, the shape of its own space is far away from the hit.
        Vec3 normal = field.worldShape().outwardNormal(hit);
        Direction face = Direction.getNearest(normal.x, normal.y, normal.z);

        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, hit.x, hit.y, hit.z, 6, 0.2, 0.2, 0.2, 0.05);
        // The wall ripples, and rings, where it was hit, harder the faster the projectile was going (a cannon shell is the biggest);
        // the clients make both from this.
        float size = (float) Math.min(1.0, 0.15 + 0.05 * entity.getDeltaMovement().length());
        PacketDistributor.sendToPlayersNear(level, null, hit.x, hit.y, hit.z, 160.0, FieldRipple.anywhere(hit.x, hit.y, hit.z, size));
        ProjectileDamage.Result damage = ProjectileDamage.of(level, entity, speed);
        double amount = damage.amount();
        FieldDebug.projectile(entity, speed, damage);
        double overloadBefore = field.overload();
        FieldState stateBefore = field.state();
        Vec3 arrival = entity.getDeltaMovement();
        // With the place it hit, so projectiles count for the same spot as blasts do: they are remembered, and a spot
        // hit lately takes more from them.
        if (field.addDamage(amount, level.getGameTime(), hit)) {
            FieldEvents.onOverheated(level, field);
        }
        FieldDebug.wall(field, entity, hit, face, arrival, amount, overloadBefore, stateBefore, level.getGameTime());
        String reaction;

        if (projectile && !(entity instanceof AbstractArrow)) {
            // Fireballs, thrown items and addon shells: stop on the wall and react as if they struck a block there.
            entity.setPos(hit.add(normal.scale(0.05)));
            entity.setDeltaMovement(Vec3.ZERO);
            BlockPos outside = BlockPos.containing(hit.add(normal.scale(0.5)));
            if (invokeOnHit((Projectile) entity, new BlockHitResult(hit, face, outside, false))) {
                reaction = "its own onHit";
            } else {
                // Shells that explode on their own terms (Create Big Cannons) have no impact handler to call here:
                // they go off at the wall, which is what charges the field, or are simply stopped if they cannot.
                reaction = invokeDetonate(entity) ? "detonate()" : "stopped and discarded (nothing to detonate)";
                entity.discard();
            }
        } else {
            reaction = "bounced";
            // Arrows and TNT bounce back, keeping a little of their speed.
            Vec3 motion = entity.getDeltaMovement();
            double along = motion.dot(normal);
            entity.setPos(hit.add(normal.scale(0.1)));
            entity.setDeltaMovement(motion.subtract(normal.scale(along * 1.4)));
            entity.hasImpulse = true;
            entity.hurtMarked = true;
        }
        FieldDebug.reaction(entity, reaction);
    }

    /** Projectile#onHit is protected and overridden by addon projectiles, so it is called reflectively. */
    private static boolean invokeOnHit(Projectile projectile, HitResult result) {
        Method method = ON_HIT.computeIfAbsent(projectile.getClass(), FieldProjectiles::findOnHit);
        if (method == NO_METHOD) {
            return false;
        }
        try {
            method.invoke(projectile, result);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ForceFields.LOGGER.warn("Could not apply impact to {}", projectile.getType(), e);
            return false;
        }
    }

    /** Sets off a shell that knows how to ({@code detonate()}, as cannon shells do). Returns whether it did. */
    private static boolean invokeDetonate(Entity entity) {
        Method method = DETONATE.computeIfAbsent(entity.getClass(), type -> findDeclared(type, "detonate"));
        if (method == NO_METHOD) {
            return false;
        }
        try {
            method.invoke(entity);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ForceFields.LOGGER.warn("Could not detonate {}", entity.getType(), e);
            return false;
        }
    }

    private static Method findDeclared(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException e) {
                // try the superclass
            }
        }
        return NO_METHOD;
    }

    private static Method findOnHit(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c == Projectile.class) {
                // Vanilla's own handler does nothing for a block: only an override is an impact worth calling.
                break;
            }
            try {
                Method method = c.getDeclaredMethod("onHit", HitResult.class);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException e) {
                // try the superclass
            }
        }
        return NO_METHOD;
    }

    private FieldProjectiles() {
    }
}
