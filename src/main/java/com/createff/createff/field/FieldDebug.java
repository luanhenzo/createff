package com.createff.createff.field;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.CreateFF;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.minecraft.world.phys.Vec3;

/**
 * Logs every hit a field takes, with the numbers that went into it, when {@code debug.logDamage} is on in the
 * config (or switched on with {@code /cff debug damage on}). Meant for working out why a field took more
 * or less overload than expected, not for normal play.
 */
public final class FieldDebug {
    public static boolean enabled() {
        return Config.DEBUG_LOG_DAMAGE.get();
    }

    private static long lastCollisionLog;

    /** How a projectile's damage on the wall was worked out, to check the numbers against what it does in the game. */
    public static void projectile(Entity entity, double speed, ProjectileDamage.Result damage) {
        if (!enabled() || damage.source().equals("spent")) {
            // A projectile that already hit the wall and bounces back to it adds nothing, and says nothing.
            return;
        }
        CreateFF.LOGGER.info("[projectile] {} | relative speed {} blocks/tick | {} -> overload {} | {}",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), fmt(speed), damage.source(), fmt(damage.amount()),
                damage.detail());
    }

    /** A wall cut a player's move short, with what was asked and what was allowed. At most a few lines a second. */
    public static void collision(Entity entity, Vec3 asked, Vec3 allowed, net.minecraft.world.phys.AABB box, java.util.List<FieldShape> walls) {
        if (!enabled() || System.currentTimeMillis() - lastCollisionLog < 250) {
            return;
        }
        lastCollisionLog = System.currentTimeMillis();
        StringBuilder shapes = new StringBuilder();
        for (FieldShape wall : walls) {
            net.minecraft.world.phys.AABB b = wall.bounds();
            shapes.append(String.format(Locale.ROOT, " [%s bounds y %.2f..%.2f x %.2f..%.2f z %.2f..%.2f]",
                    wall.isSphere() ? "sphere" : wall.isOriented() ? "oriented" : "cube", b.minY, b.maxY, b.minX, b.maxX, b.minZ, b.maxZ));
        }
        CreateFF.LOGGER.info("[collision] {} {} | box feet y {} centre y {} | asked ({}, {}, {}) allowed ({}, {}, {}) | onGround={} |{}",
                entity.level().isClientSide ? "client" : "server", entity.getName().getString(),
                fmt(box.minY), fmt((box.minY + box.maxY) / 2), fmt(asked.x), fmt(asked.y), fmt(asked.z),
                fmt(allowed.x), fmt(allowed.y), fmt(allowed.z), entity.onGround(), shapes);
    }

    /** A line about the projector's data travelling to the client, for finding where it gets lost. */
    public static void sync(String what) {
        if (Config.DEBUG_LOG_SHIPS.get()) {
            CreateFF.LOGGER.info("[sync] {}", what);
        }
    }

    /** Whether ship logging is on and there is a Sable to ask. */
    public static boolean shipsEnabled() {
        return Config.DEBUG_LOG_SHIPS.get() && ModList.get().isLoaded("sable");
    }

    /** Logs what happened and where {@code pos} is relative to Sable's ships. */
    public static void ship(Level level, String what, Vec3 pos) {
        if (shipsEnabled()) {
            CreateFF.LOGGER.info("[ship] {} | {}", what, ShipSpike.describe(level, pos));
        }
    }

    /** The same for an entity, with the ship it is on or riding. */
    public static void ship(String what, Entity entity) {
        if (shipsEnabled()) {
            CreateFF.LOGGER.info("[ship] {} | {} {}", what, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    ShipSpike.describe(entity));
        }
    }

    /** The lines of a spike report on a position, for a command to show. Empty without Sable. */
    public static String describeShip(Level level, Vec3 pos) {
        return ModList.get().isLoaded("sable") ? ShipSpike.describe(level, pos) : "Sable is not installed";
    }

    public static String describeShip(Entity entity) {
        return ModList.get().isLoaded("sable") ? ShipSpike.describe(entity) : "Sable is not installed";
    }

    /** An explosion that was weighed against a field, whether or not it ended up hurting it. */
    public static void explosion(Field field, Explosion explosion, double distance, double base, double falloff,
            double overloadBefore, FieldState stateBefore, long now) {
        if (!enabled()) {
            return;
        }
        Vec3 center = explosion.center();
        CreateFF.LOGGER.info(
                "[damage] EXPLOSION {} | explosion={} radius={} at={} | direct={} indirect={} | distance={}/{} base={} falloff={} (power {}, cap {}) | {}",
                fieldName(field), explosion.getClass().getSimpleName(), fmt(explosion.radius()), pos(center),
                entity(explosion.getDirectSourceEntity()), entity(explosion.getIndirectSourceEntity()),
                fmt(distance), fmt(Config.EXPLOSION_DAMAGE_RADIUS.get()), fmt(base), fmt(falloff),
                Config.DAMAGE_PER_EXPLOSION_POWER.get(), Config.MAX_DAMAGE_PER_EXPLOSION.get(),
                outcome(field, overloadBefore, stateBefore, now, fmt(field.lastFocus())));
    }

    /** An explosion too far from the field to count. */
    public static void explosionTooFar(Field field, Explosion explosion, double distance) {
        if (!enabled()) {
            return;
        }
        CreateFF.LOGGER.info("[damage] EXPLOSION {} | explosion={} radius={} at={} | direct={} | distance={} is beyond {}: ignored",
                fieldName(field), explosion.getClass().getSimpleName(), fmt(explosion.radius()), pos(explosion.center()),
                entity(explosion.getDirectSourceEntity()), fmt(distance), fmt(Config.EXPLOSION_DAMAGE_RADIUS.get()));
    }

    /** A projectile or primed TNT that reached the wall. {@code reaction} says what the wall did to it. */
    public static void wall(Field field, Entity entity, Vec3 hit, Direction face, Vec3 arrival, double amount,
            double overloadBefore, FieldState stateBefore, long now) {
        if (!enabled()) {
            return;
        }
        CreateFF.LOGGER.info("[damage] WALL {} | {} | hit={} face={} speed={} | amount={} | {}",
                fieldName(field), entity(entity), pos(hit), face, fmt(arrival.length()),
                fmt(amount), outcome(field, overloadBefore, stateBefore, now, fmt(field.lastFocus())));
    }

    /** What the wall did to the projectile it just stopped. Logged after the explosion it causes, if any. */
    public static void reaction(Entity entity, String reaction) {
        if (enabled()) {
            CreateFF.LOGGER.info("[damage]   -> {} reaction: {}", entity(entity), reaction);
        }
    }

    private static String outcome(Field field, double overloadBefore, FieldState stateBefore, long now, String focus) {
        String result;
        if (stateBefore != FieldState.ONLINE) {
            result = "NOT APPLIED, field was " + stateBefore;
        } else if (now < field.graceUntil()) {
            result = "NOT APPLIED, restart grace for " + (field.graceUntil() - now) + " more ticks";
        } else if (field.state() == FieldState.OVERHEATED) {
            result = "APPLIED, field OVERHEATED";
        } else if (field.overload() > overloadBefore) {
            result = "APPLIED";
        } else {
            result = "NOT APPLIED, no damage";
        }
        return String.format(Locale.ROOT, "%s added=%s | focus=%s | overload %s -> %s of %s (%s%%) | rpm=%s upgrades=%d",
                result, fmt(field.overload() - overloadBefore), focus, fmt(overloadBefore), fmt(field.overload()),
                fmt(field.capacity()), fmt(field.overloadFraction() * 100.0), fmt(field.rpm()), field.settings().resistanceUpgrades());
    }

    private static String fieldName(Field field) {
        BlockPos core = field.core();
        return "field@" + core.getX() + "," + core.getY() + "," + core.getZ();
    }

    private static String entity(@Nullable Entity entity) {
        if (entity == null) {
            return "none";
        }
        String owner = entity instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() != null
                ? " owner=" + projectile.getOwner().getName().getString()
                : "";
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) +"#" + entity.getId() + owner;
    }

    private static String pos(Vec3 at) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", at.x, at.y, at.z);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private FieldDebug() {
    }
}
