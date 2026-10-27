package com.createff.createff;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue DEFAULT_EXTENT;
    public static final ModConfigSpec.IntValue MAX_EXTENT;

    public static final ModConfigSpec.DoubleValue MIN_RPM;
    public static final ModConfigSpec.DoubleValue MAX_EFFECTIVE_RPM;
    public static final ModConfigSpec.IntValue POWER_LOSS_GRACE_TICKS;
    public static final ModConfigSpec.DoubleValue STRESS_BASE_IMPACT;
    public static final ModConfigSpec.DoubleValue STRESS_PER_1000_BLOCKS;

    public static final ModConfigSpec.IntValue BASIC_CORE_SLOTS;
    public static final ModConfigSpec.IntValue ADVANCED_CORE_SLOTS;
    public static final ModConfigSpec.IntValue ELITE_CORE_SLOTS;
    public static final ModConfigSpec.DoubleValue RESISTANCE_PER_UPGRADE;
    public static final ModConfigSpec.DoubleValue UPGRADE_STRESS_FACTOR;
    public static final ModConfigSpec.DoubleValue BASE_RESISTANCE;
    public static final ModConfigSpec.DoubleValue VOLUME_COST_EXPONENT;
    public static final ModConfigSpec.DoubleValue COOLING_PER_SECOND_AT_MIN_RPM;
    public static final ModConfigSpec.IntValue SHUTDOWN_SECONDS;
    public static final ModConfigSpec.IntValue RESTART_OVERLOAD_PERCENT;
    public static final ModConfigSpec.IntValue RESTART_GRACE_SECONDS;

    public static final ModConfigSpec.BooleanValue EXPLOSIONS_DAMAGE_FIELDS;
    public static final ModConfigSpec.DoubleValue DAMAGE_PER_EXPLOSION_POWER;
    public static final ModConfigSpec.DoubleValue MAX_DAMAGE_PER_EXPLOSION;
    public static final ModConfigSpec.DoubleValue EXPLOSION_DAMAGE_RADIUS;
    public static final ModConfigSpec.DoubleValue REPEAT_DAMAGE_RADIUS;
    public static final ModConfigSpec.IntValue REPEAT_DAMAGE_SECONDS;
    public static final ModConfigSpec.DoubleValue REPEAT_DAMAGE_GROWTH;
    public static final ModConfigSpec.DoubleValue REPEAT_DAMAGE_CAP;

    public static final ModConfigSpec.BooleanValue PROJECTILES_COLLIDE;
    public static final ModConfigSpec.DoubleValue PROJECTILE_DAMAGE;
    public static final ModConfigSpec.BooleanValue USE_NATURAL_DAMAGE;
    public static final ModConfigSpec.DoubleValue DAMAGE_PER_NATURAL_POINT;
    public static final ModConfigSpec.DoubleValue PROJECTILE_REFERENCE_SPEED;
    public static final ModConfigSpec.DoubleValue PROJECTILE_SPEED_EXPONENT;
    public static final ModConfigSpec.DoubleValue MAX_PROJECTILE_DAMAGE;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROJECTILE_DAMAGE_LIST;

    public static final ModConfigSpec.BooleanValue REPEL_SHIPS;
    public static final ModConfigSpec.DoubleValue REPEL_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue REPEL_MIN_SPEED;
    public static final ModConfigSpec.DoubleValue REPEL_STIFFNESS;
    public static final ModConfigSpec.DoubleValue REPEL_MAX_CHANGE;
    public static final ModConfigSpec.DoubleValue FLOOR_STIFFNESS;
    public static final ModConfigSpec.DoubleValue FLOOR_MAX_SPEED;
    public static final ModConfigSpec.DoubleValue FLOOR_FRICTION;
    public static final ModConfigSpec.BooleanValue CROSSING_RIPPLES;
    public static final ModConfigSpec.BooleanValue RIPPLE_SOUNDS;
    public static final ModConfigSpec.DoubleValue VEIL_NEAR_DISTANCE;
    public static final ModConfigSpec.DoubleValue VEIL_FULL_DISTANCE;
    public static final ModConfigSpec.DoubleValue VEIL_PATCH_RADIUS;
    public static final ModConfigSpec.DoubleValue VEIL_RIPPLE_RADIUS;
    public static final ModConfigSpec.DoubleValue VEIL_RIPPLE_RADIUS_PER_SIZE;
    public static final ModConfigSpec.DoubleValue VEIL_RIPPLE_RADIUS_MAX;

    public static final ModConfigSpec.BooleanValue DEBUG_LOG_DAMAGE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_SHIPS;

    public static final ModConfigSpec.EnumValue<com.createff.createff.field.Access> DEFAULT_PLACE;
    public static final ModConfigSpec.EnumValue<com.createff.createff.field.Access> DEFAULT_BREAK;
    public static final ModConfigSpec.EnumValue<com.createff.createff.field.Access> DEFAULT_ENTRY;
    public static final ModConfigSpec.BooleanValue OPS_MANAGE_ANY_FIELD;
    public static final ModConfigSpec.BooleanValue CREATIVE_OPS_BYPASS;

    static {
        BUILDER.push("field");
        DEFAULT_EXTENT = BUILDER.comment("Blocks the field extends from the core on every side when first placed")
                .defineInRange("defaultExtent", 8, 1, 256);
        MAX_EXTENT = BUILDER.comment("Maximum blocks the field may extend from the core on any side")
                .defineInRange("maxExtent", 64, 1, 256);
        BUILDER.pop();

        BUILDER.push("power");
        MIN_RPM = BUILDER.comment("Minimum RPM the core needs to keep the field online")
                .defineInRange("minRpm", 16.0, 1.0, 256.0);
        MAX_EFFECTIVE_RPM = BUILDER.comment("RPM above this value gives no extra cooling")
                .defineInRange("maxEffectiveRpm", 256.0, 1.0, 256.0);
        POWER_LOSS_GRACE_TICKS = BUILDER.comment("Ticks below minRpm before the field goes offline (absorbs kinetic network flicker)")
                .defineInRange("powerLossGraceTicks", 60, 0, 6000);
        STRESS_BASE_IMPACT = BUILDER.comment("Base stress impact of the core, per RPM")
                .defineInRange("stressBaseImpact", 4.0, 0.0, 100000.0);
        STRESS_PER_1000_BLOCKS = BUILDER.comment("Stress impact per RPM for every 1000 blocks of protected volume, before volumeCostExponent is applied")
                .defineInRange("stressPer1000Blocks", 2.0, 0.0, 100000.0);
        VOLUME_COST_EXPONENT = BUILDER.comment("How fast the stress cost grows with volume. 1 is linear; above 1 big fields cost proportionally more, below 1 less")
                .defineInRange("volumeCostExponent", 1.0, 0.25, 4.0);
        BUILDER.pop();

        BUILDER.push("upgrades");
        BASIC_CORE_SLOTS = BUILDER.comment("Upgrade slots of a basic force core (0 to 9)")
                .defineInRange("basicCoreSlots", 3, 0, 9);
        ADVANCED_CORE_SLOTS = BUILDER.comment("Upgrade slots of an advanced force core (0 to 9)")
                .defineInRange("advancedCoreSlots", 6, 0, 9);
        ELITE_CORE_SLOTS = BUILDER.comment("Upgrade slots of an elite force core (0 to 9)")
                .defineInRange("eliteCoreSlots", 9, 0, 9);
        RESISTANCE_PER_UPGRADE = BUILDER.comment("Resistance each resistance upgrade adds, as a share of the base (0.25 = +25 percent, stacking)")
                .defineInRange("resistancePerUpgrade", 0.25, 0.0, 100.0);
        UPGRADE_STRESS_FACTOR = BUILDER.comment("Extra stress each installed upgrade adds to the volume cost, as a share (0.2 = +20 percent, stacking)")
                .defineInRange("upgradeStressFactor", 0.2, 0.0, 100.0);
        BUILDER.pop();

        BUILDER.push("overload");
        BASE_RESISTANCE = BUILDER.comment("Overload points a field takes at minRpm before shutting down. Resistance grows linearly with RPM up to maxEffectiveRpm, whatever the size of the field")
                .defineInRange("baseResistance", 100.0, 1.0, 1000000.0);
        COOLING_PER_SECOND_AT_MIN_RPM = BUILDER.comment("Overload points removed per second at minRpm. Scales linearly with RPM")
                .defineInRange("coolingPerSecondAtMinRpm", 1.0, 0.0, 100000.0);
        SHUTDOWN_SECONDS = BUILDER.comment("Seconds the field stays down after overloading, at minRpm. Shorter with higher RPM")
                .defineInRange("shutdownSeconds", 300, 1, 86400);
        RESTART_OVERLOAD_PERCENT = BUILDER.comment("Overload percentage the field restarts with")
                .defineInRange("restartOverloadPercent", 30, 0, 99);
        RESTART_GRACE_SECONDS = BUILDER.comment("Seconds of damage immunity after a restart")
                .defineInRange("restartGraceSeconds", 10, 0, 3600);
        BUILDER.pop();

        BUILDER.push("explosions");
        EXPLOSIONS_DAMAGE_FIELDS = BUILDER.comment("Whether explosions overload fields")
                .define("explosionsDamageFields", true);
        DAMAGE_PER_EXPLOSION_POWER = BUILDER.comment("Overload added per point of explosion power (TNT is 4)")
                .defineInRange("damagePerExplosionPower", 4.0, 0.0, 100000.0);
        MAX_DAMAGE_PER_EXPLOSION = BUILDER.comment("Cap on the overload a single explosion can add")
                .defineInRange("maxDamagePerExplosion", 40.0, 0.0, 100000.0);
        EXPLOSION_DAMAGE_RADIUS = BUILDER.comment("Explosions farther than this from the field box deal no damage. Closer explosions deal up to double")
                .defineInRange("damageRadius", 16.0, 0.0, 256.0);
        REPEAT_DAMAGE_RADIUS = BUILDER.comment("Hits within this distance of an earlier hit count as landing on the same spot, the nearer the more")
                .defineInRange("repeatDamageRadius", 5.0, 0.0, 64.0);
        REPEAT_DAMAGE_SECONDS = BUILDER.comment("How long a hit is remembered when judging whether the next one lands on the same spot")
                .defineInRange("repeatDamageSeconds", 15, 0, 600);
        REPEAT_DAMAGE_GROWTH = BUILDER.comment("Damage is multiplied by this for each earlier hit on the same spot (1.25 = 25 percent more per hit), so attackers are rewarded for focusing fire and defenders have to stop an attack on one spot. 1 turns the effect off")
                .defineInRange("repeatDamageGrowth", 1.25, 1.0, 10.0);
        REPEAT_DAMAGE_CAP = BUILDER.comment("The most a hit on a worn spot is multiplied by (3 = up to triple damage)")
                .defineInRange("repeatDamageCap", 3.0, 1.0, 100.0);
        PROJECTILES_COLLIDE = BUILDER.comment("Arrows, cannon shells and primed TNT coming from outside hit the wall of an online field instead of passing through")
                .define("projectilesCollide", true);
        PROJECTILE_DAMAGE = BUILDER.comment("Overload a projectile (or TNT) adds on hitting the wall when its own damage cannot be read and it is not in projectileDamageList, at referenceSpeed")
                .defineInRange("projectileDamage", 1.0, 0.0, 100000.0);
        USE_NATURAL_DAMAGE = BUILDER.comment("Work out a projectile's overload from its own damage when it can be read: arrows and tridents (speed times base damage, the bow's enchantments and the average of a critical hit) and Create Big Cannons projectiles (their entity damage)")
                .define("useNaturalDamage", true);
        DAMAGE_PER_NATURAL_POINT = BUILDER.comment("Overload per point of a projectile's own damage (0.15 makes a full-draw arrow, about 6, add about 0.9)")
                .defineInRange("damagePerNaturalPoint", 0.15, 0.0, 1000.0);
        PROJECTILE_REFERENCE_SPEED = BUILDER.comment("Speed in blocks per tick at which listed, default and cannon damage count in full (a full-draw arrow flies at about 3). Faster counts for more, slower for less, by speedExponent")
                .defineInRange("referenceSpeed", 3.0, 0.0, 1000.0);
        PROJECTILE_SPEED_EXPONENT = BUILDER.comment("How damage grows with speed: 1 in proportion, 2 like kinetic energy, 0 not at all")
                .defineInRange("speedExponent", 1.0, 0.0, 4.0);
        MAX_PROJECTILE_DAMAGE = BUILDER.comment("The most overload a single projectile hit may add")
                .defineInRange("maxProjectileDamage", 50.0, 0.0, 1000000.0);
        PROJECTILE_DAMAGE_LIST = BUILDER.comment("Base damage by projectile, taking priority over everything else. Each entry is an entity id or #tag, an equals sign and the overload at referenceSpeed, for example minecraft:trident=2.5 or #minecraft:arrows=1. Add @speed to give the damage at that speed in blocks per tick instead, for example createbigcannons:machine_gun_bullet=1.2@7")
                .defineListAllowEmpty("projectileDamageList", java.util.List.of("minecraft:snowball=0.1", "minecraft:egg=0.1", "minecraft:ender_pearl=0.1"), () -> "minecraft:snowball=0.1", entry -> entry instanceof String);
        BUILDER.pop();

        BUILDER.push("ships");
        REPEL_SHIPS = BUILDER.comment("Ships (Create Aeronautics, Simulated) that may not enter a field are pushed back out of it. A ship may enter when someone is aboard and everyone aboard may")
                .define("repelShips", true);
        REPEL_MAX_SPEED = BUILDER.comment("Fastest a push may get a ship moving away from a wall or from another field, in blocks per second, however deep it is in")
                .defineInRange("repelMaxSpeed", 8.0, 0.0, 200.0);
        REPEL_MIN_SPEED = BUILDER.comment("Slowest the push leaves a ship moving away, in blocks per second, even for a light touch")
                .defineInRange("repelMinSpeed", 0.5, 0.0, 200.0);
        REPEL_STIFFNESS = BUILDER.comment("How hard the wall pushes back as a ship goes deeper: blocks per second of speed away per block of depth. Higher is a harder wall, lower a softer one")
                .defineInRange("repelStiffness", 2.0, 0.0, 100.0);
        REPEL_MAX_CHANGE = BUILDER.comment("Most speed one tick of push may add or take from a ship, in blocks per second. The force limit: a fast ship is slowed over a few ticks instead of being stopped dead")
                .defineInRange("repelMaxChangePerTick", 20.0, 0.1, 1000.0);
        FLOOR_STIFFNESS = BUILDER.comment("The same for a ship resting on top of a wall, which is held up like a floor: blocks per second of lift per block of depth")
                .defineInRange("floorStiffness", 6.0, 0.0, 100.0);
        FLOOR_MAX_SPEED = BUILDER.comment("Fastest a ship resting on a wall is lifted out of it, in blocks per second")
                .defineInRange("floorMaxSpeed", 3.0, 0.0, 200.0);
        FLOOR_FRICTION = BUILDER.comment("Share of its sideways speed a ship resting on top of a wall loses each tick, so it does not skid across it like ice (0 for none, 1 stops it at once)")
                .defineInRange("floorFriction", 0.1, 0.0, 1.0);
        BUILDER.pop();

        BUILDER.push("visual");
        CROSSING_RIPPLES = BUILDER.comment("Draw a ripple on the wall of a field where a player crosses it (seen by each player on their own client)")
                .define("crossingRipples", true);
        RIPPLE_SOUNDS = BUILDER.comment("Play a sound where the wall of a field is crossed, bumped or hit, louder and lower for a bigger blow")
                .define("rippleSounds", true);
        VEIL_NEAR_DISTANCE = BUILDER.comment("Veil upgrade: how near the player has to be to the wall, in blocks, for the part of it closest to them to start to show")
                .defineInRange("veilNearDistance", 6.0, 0.0, 128.0);
        VEIL_FULL_DISTANCE = BUILDER.comment("Veil upgrade: how near, in blocks, the wall shows at full strength")
                .defineInRange("veilFullDistance", 1.5, 0.0, 128.0);
        VEIL_PATCH_RADIUS = BUILDER.comment("Veil upgrade: radius in blocks of the patch of wall that shows around the point nearest the player")
                .defineInRange("veilPatchRadius", 5.0, 0.5, 128.0);
        VEIL_RIPPLE_RADIUS = BUILDER.comment("Veil upgrade: radius in blocks of the patch that shows around a ripple (a crossing, a bump or a hit), for a small one")
                .defineInRange("veilRippleRadius", 4.0, 0.5, 128.0);
        VEIL_RIPPLE_RADIUS_PER_SIZE = BUILDER.comment("Veil upgrade: blocks added to the radius around a ripple for each point of its size (a TNT is about 1)")
                .defineInRange("veilRippleRadiusPerSize", 3.0, 0.0, 128.0);
        VEIL_RIPPLE_RADIUS_MAX = BUILDER.comment("Veil upgrade: the most the patch around a ripple may grow to, in blocks")
                .defineInRange("veilRippleRadiusMax", 11.0, 0.5, 256.0);
        BUILDER.pop();

        BUILDER.push("debug");
        // On by default in a development run, off in a release; either way it can be changed here or with the commands.
        DEBUG_LOG_DAMAGE = BUILDER.comment("Log every hit a field takes (what hit it, the numbers behind the damage, the overload before and after). Also toggled with /cff debug damage. On by default only in a development run")
                .define("logDamage", !FMLEnvironment.production);
        DEBUG_LOG_SHIPS = BUILDER.comment("Log where fields, explosions and projectors are relative to Sable ships (world or plot), and what happens to a field when its projector is assembled into a ship. Also toggled with /cff debug ships. On by default only in a development run")
                .define("logShips", !FMLEnvironment.production);
        BUILDER.pop();

        BUILDER.push("permissions");
        DEFAULT_PLACE = BUILDER.comment("Who may place blocks in a new field: OWNER, TRUSTED or EVERYONE")
                .defineEnum("defaultPlace", com.createff.createff.field.Access.OWNER);
        DEFAULT_BREAK = BUILDER.comment("Who may break blocks in a new field")
                .defineEnum("defaultBreak", com.createff.createff.field.Access.OWNER);
        DEFAULT_ENTRY = BUILDER.comment("Who may walk into a new field")
                .defineEnum("defaultEntry", com.createff.createff.field.Access.OWNER);
        OPS_MANAGE_ANY_FIELD = BUILDER.comment("Operators may open and change the settings of any field, not only their own")
                .define("opsManageAnyField", true);
        CREATIVE_OPS_BYPASS = BUILDER.comment("Operators in creative mode ignore field protection")
                .define("creativeOpsBypass", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
