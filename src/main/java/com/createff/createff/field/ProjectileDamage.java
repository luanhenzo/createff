package com.createff.createff.field;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * How much overload a projectile that hits the wall of a field adds. Worked out in this order:
 * <ol>
 * <li>the server's list of damage by projectile type (or tag), for what the owner wants to set by hand;</li>
 * <li>the projectile's own damage, when it can be read: an arrow's is its speed times its base damage, changed by the
 * enchantments of the bow that shot it, plus what a critical hit adds on average; a cannon shell's is the damage its
 * data says, taken at its speed;</li>
 * <li>a default for anything else.</li>
 * </ol>
 * The own damage is then scaled down to overload points, since an arrow that hurts a creature by 6 should not take a
 * big share off a field. A projectile that bounced off and comes back counts only the first time.
 */
public final class ProjectileDamage {
    /** What a hit adds, where the number came from, and the figures behind it for the debug log. */
    public record Result(double amount, String source, String detail) {
    }

    private static final String SPENT = "createff_hit_the_wall";
    private static final Map<Class<?>, Method> CANNON_PROPERTIES = new ConcurrentHashMap<>();
    private static final Method NONE = Object.class.getDeclaredMethods()[0];

    /**
     * @param speed how fast the projectile was going relative to the wall, in blocks per tick
     */
    public static Result of(ServerLevel level, Entity entity, double speed) {
        if (entity.getPersistentData().getBoolean(SPENT)) {
            return new Result(0.0, "spent", "it already hit this wall once");
        }
        entity.getPersistentData().putBoolean(SPENT, true);

        double scale = Config.DAMAGE_PER_NATURAL_POINT.get();
        double speedFactor = speedFactor(speed);

        Listed listed = listed(entity.getType());
        Result result;
        if (listed != null) {
            // The entry says its damage at its own speed if it gives one, otherwise at the reference speed.
            double entryFactor = listed.speed() > 0 ? speedFactor(speed, listed.speed()) : speedFactor;
            result = new Result(listed.damage() * entryFactor, "list",
                    String.format("listed %.2f at speed %.2f x speed factor %.2f", listed.damage(),
                            listed.speed() > 0 ? listed.speed() : Config.PROJECTILE_REFERENCE_SPEED.get(), entryFactor));
        } else if (Config.USE_NATURAL_DAMAGE.get() && entity instanceof AbstractArrow arrow) {
            result = arrow(level, arrow, speed, scale);
        } else if (Config.USE_NATURAL_DAMAGE.get() && cannonDamage(entity) != null) {
            double own = cannonDamage(entity);
            result = new Result(own * scale * speedFactor, "cannon",
                    String.format("entity_damage %.2f x scale %.3f x speed factor %.2f", own, scale, speedFactor));
        } else {
            result = new Result(Config.PROJECTILE_DAMAGE.get() * speedFactor, "default",
                    String.format("default %.2f x speed factor %.2f", Config.PROJECTILE_DAMAGE.get(), speedFactor));
        }
        double capped = Math.min(Config.MAX_PROJECTILE_DAMAGE.get(), Math.max(0.0, result.amount()));
        return new Result(capped, result.source(), result.detail() + (capped < result.amount() ? " (capped)" : ""));
    }

    /** A vanilla arrow or trident: its speed times its base damage, then its bow's enchantments, then the critical hit. */
    private static Result arrow(ServerLevel level, AbstractArrow arrow, double speed, double scale) {
        double raw = speed * arrow.getBaseDamage();
        double enchanted = raw;
        ItemStack weapon = arrow.getWeaponItem();
        if (weapon != null && !weapon.isEmpty()) {
            DamageSource source = level.damageSources().arrow(arrow, arrow.getOwner());
            enchanted = EnchantmentHelper.modifyDamage(level, weapon, arrow, source, (float) raw);
        }
        // The game rounds it up, and a critical hit adds a random amount up to half of it plus one, whose average
        // is taken here.
        double points = Math.ceil(Mth.clamp(enchanted, 0.0, 2.147483647E9));
        double crit = arrow.isCritArrow() ? (Math.floor(points / 2) + 1) / 2.0 : 0.0;
        double natural = points + crit;
        return new Result(natural * scale, "arrow", String.format(
                "speed %.2f x base %.2f = %.2f, with the bow's enchantments %.2f, rounded %.0f, crit average +%.2f -> natural %.2f x scale %.3f%s",
                speed, arrow.getBaseDamage(), raw, enchanted, points, crit, natural, scale,
                weapon == null || weapon.isEmpty() ? " (no weapon)" : ""));
    }

    /** A Create Big Cannons projectile's entity damage from its data, or null for anything else. Read by reflection, since it is optional. */
    @Nullable
    private static Double cannonDamage(Entity entity) {
        Method properties = CANNON_PROPERTIES.computeIfAbsent(entity.getClass(), ProjectileDamage::findProperties);
        if (properties == NONE) {
            return null;
        }
        try {
            Object component = properties.invoke(entity);
            Object damage = component.getClass().getMethod("entityDamage").invoke(component);
            return damage instanceof Number number ? number.doubleValue() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method findProperties(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile")) {
                try {
                    return c.getMethod("getDamageProperties");
                } catch (NoSuchMethodException e) {
                    return NONE;
                }
            }
        }
        return NONE;
    }

    /** How much more or less a projectile counts for going faster or slower than the reference speed. */
    private static double speedFactor(double speed) {
        return speedFactor(speed, Config.PROJECTILE_REFERENCE_SPEED.get());
    }

    private static double speedFactor(double speed, double reference) {
        return reference <= 0 ? 1.0 : Math.pow(Math.max(0.0, speed) / reference, Config.PROJECTILE_SPEED_EXPONENT.get());
    }

    /** What the list says about a type: its damage, and the speed that damage is for (0 for the reference speed). */
    private record Listed(double damage, double speed) {
    }

    /** What the server's list gives this type of projectile, by id or by tag, or null if it is not listed. */
    @Nullable
    private static Listed listed(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        for (String entry : Config.PROJECTILE_DAMAGE_LIST.get()) {
            int equals = entry.lastIndexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = entry.substring(0, equals).trim();
            double value;
            double at = 0;
            try {
                String number = entry.substring(equals + 1).trim();
                int atSign = number.indexOf('@');
                if (atSign >= 0) {
                    at = Double.parseDouble(number.substring(atSign + 1).trim());
                    number = number.substring(0, atSign).trim();
                }
                value = Double.parseDouble(number);
            } catch (NumberFormatException e) {
                continue;
            }
            if (key.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(key.substring(1));
                if (tag != null && type.is(TagKey.create(Registries.ENTITY_TYPE, tag))) {
                    return new Listed(value, at);
                }
            } else if (id != null && id.equals(ResourceLocation.tryParse(key))) {
                return new Listed(value, at);
            }
        }
        return null;
    }

    private ProjectileDamage() {
    }
}
