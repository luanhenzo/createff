package com.forcefields.forcefields.field;

import java.util.Arrays;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.forcefields.forcefields.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Field {
    private final BlockPos core;
    @Nullable
    private UUID owner;
    @Nullable
    private String ownerName;
    /** Blocks the field extends from the core on each side, indexed by {@link Direction#get3DDataValue()}. */
    private final int[] extents = new int[6];
    private FieldShape.Type type = FieldShape.Type.CUBE;
    /** Blocks a sphere reaches from the core. Kept while the field is a cube, so switching back and forth loses nothing. */
    private int radius;
    private FieldShape shape;

    private FieldState state = FieldState.OFFLINE;
    /**
     * How much of its resistance the field has used up, 0 to 1. Kept as a share rather than in points so that
     * changing the RPM (and with it the resistance) does not change how loaded the field looks: damage and cooling are
     * worked out in points and converted at the current resistance.
     */
    private double load;
    private double cooldownProgress;
    private long graceUntil;
    /** When > 0, the current shutdown lasts exactly this many ticks regardless of RPM. */
    private long forcedShutdownTicks;
    private FieldSettings settings = FieldSettings.defaults();
    private int lowPowerTicks;
    private long lastTick = -1;

    /** Effective RPM reported by the core block entity. Not persisted. */
    private double rpm;

    public Field(BlockPos core, @Nullable UUID owner, int extent) {
        this.core = core.immutable();
        this.owner = owner;
        Arrays.fill(extents, extent);
        this.radius = extent;
        rebuildShape();
    }

    public BlockPos core() {
        return core;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    /** Last known name of the owner, for display. */
    @Nullable
    public String ownerName() {
        return ownerName;
    }

    public void setOwner(@Nullable UUID owner, @Nullable String name) {
        this.owner = owner;
        this.ownerName = name;
    }

    public FieldSettings settings() {
        return settings;
    }

    public Role roleOf(@Nullable UUID player) {
        return settings.roleOf(player, owner);
    }

    /** Whether {@code player} may do {@code action} here. A null player is nobody. */
    public boolean allows(FieldAction action, @Nullable UUID player) {
        return settings.allows(action, roleOf(player));
    }

    /**
     * Overloads the field right now. With {@code seconds} > 0 it stays down for exactly that long,
     * whatever the RPM; otherwise it cools down normally.
     */
    public void forceOverheat(int seconds) {
        load = 1.0;
        cooldownProgress = 0;
        forcedShutdownTicks = seconds > 0 ? seconds * 20L : 0;
        state = FieldState.OVERHEATED;
    }

    /** Ends an overload immediately: clears the meter and brings the field back as soon as it has power. */
    public void endOverload() {
        load = 0;
        cooldownProgress = 0;
        graceUntil = 0;
        lowPowerTicks = 0;
        forcedShutdownTicks = 0;
        state = FieldState.ONLINE;
    }

    public int extent(Direction side) {
        return extents[side.get3DDataValue()];
    }

    public int[] extents() {
        return extents.clone();
    }

    /** Overload points this field takes before shutting down. Depends on the RPM only, not on the size. */
    public double capacity() {
        return resistanceFor(rpm, settings.resistanceUpgrades());
    }

    /** Resistance at a given RPM: linear from the base at the minimum RPM up to the maximum effective RPM, times what the resistance upgrades add. */
    public static double resistanceFor(double rpm, int resistanceUpgrades) {
        double min = Config.MIN_RPM.get();
        double clamped = Math.max(min, Math.min(rpm, Config.MAX_EFFECTIVE_RPM.get()));
        double upgrades = 1.0 + Config.RESISTANCE_PER_UPGRADE.get() * resistanceUpgrades;
        return Config.BASE_RESISTANCE.get() * clamped / min * upgrades;
    }

    public void setExtent(Direction side, int value) {
        extents[side.get3DDataValue()] = Math.max(1, Math.min(value, Config.MAX_EXTENT.get()));
        rebuildShape();
    }

    public FieldShape.Type type() {
        return type;
    }

    public void setType(FieldShape.Type type) {
        this.type = type;
        rebuildShape();
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int value) {
        radius = Math.max(1, Math.min(value, Config.MAX_EXTENT.get()));
        rebuildShape();
    }

    /** The geometry of the field as it is now. A new one replaces it whenever the type or a size changes. */
    public FieldShape shape() {
        return shape;
    }

    private void rebuildShape() {
        shape = FieldShape.of(type, core, extents, radius);
    }

    public FieldState state() {
        return state;
    }

    /** The overload in points at the current resistance. */
    public double overload() {
        return load * capacity();
    }

    public double overloadFraction() {
        return Math.min(1.0, load);
    }

    public void setRpm(double rpm) {
        this.rpm = rpm;
    }

    /** The game tick until which the field takes no damage after a restart. */
    public long graceUntil() {
        return graceUntil;
    }

    public double rpm() {
        return rpm;
    }

    /** The smallest box around the field. */
    public AABB bounds() {
        return shape.bounds();
    }

    /** Whether a block is in the field, in the space the field was built in: the plot, if the projector is on a ship. */
    public boolean contains(BlockPos pos) {
        return shape.contains(pos);
    }

    /** The sphere in the world and where it was a tick ago, for a field on a ship. Not saved. */
    @Nullable
    private FieldShape worldNow;
    @Nullable
    private FieldShape worldLast;

    /** Looks up where the ship is, once per tick, so everything that asks about the world agrees. */
    public void updateFrame(ServerLevel level) {
        FieldShape[] frame = ShipSupport.server(level, shape);
        worldNow = frame == null ? null : frame[0];
        worldLast = frame == null ? null : frame[1];
    }

    public boolean onShip() {
        return worldNow != null;
    }

    /** The field as the world around it meets it: the sphere where the ship is, or the field itself on the ground. */
    public FieldShape worldShape() {
        return worldNow != null ? worldNow : shape;
    }

    /** {@link #worldShape()} a tick ago, so something that moved across the wall can be told from the wall moving over it. */
    public FieldShape lastWorldShape() {
        return worldLast != null ? worldLast : worldShape();
    }

    /** Whether a point of an entity, a projectile or an explosion is in the field, in the plot or in the world. */
    public boolean coversPoint(Vec3 point) {
        return shape.contains(point) || (worldNow != null && worldNow.contains(point));
    }

    public long volume() {
        return shape.volume();
    }

    public boolean isOwner(@Nullable UUID uuid) {
        return owner != null && owner.equals(uuid);
    }

    /** Advances the field to {@code now}. Returns true if the state changed. */
    public boolean tick(long now) {
        if (lastTick < 0) {
            lastTick = now;
            return false;
        }
        long dt = now - lastTick;
        if (dt <= 0) {
            return false;
        }
        lastTick = now;

        FieldState before = state;
        double minRpm = Config.MIN_RPM.get();
        // No force core, no field: it cannot be switched on, and cannot cool down while it is missing.
        boolean powered = settings.coreInstalled() && settings.enabled() && rpm >= minRpm;
        double rate = powered ? Math.min(rpm, Config.MAX_EFFECTIVE_RPM.get()) / minRpm : 0;

        switch (state) {
            case ONLINE -> {
                // A field that is up never sits at the limit: only a hit takes it down.
                load = Math.min(load, 0.99);
                if (settings.aegis()) {
                    load = 0;
                }
                if (!settings.coreInstalled() || !settings.enabled()) {
                    // Taking the core out or switching the field off drops the barrier at once, with no grace for flickering power.
                    lowPowerTicks = 0;
                    state = FieldState.OFFLINE;
                } else if (powered) {
                    lowPowerTicks = 0;
                    double cooling = Config.COOLING_PER_SECOND_AT_MIN_RPM.get() / 20.0 * rate * dt;
                    load = Math.max(0, load - cooling / capacity());
                } else {
                    lowPowerTicks += (int) dt;
                    if (lowPowerTicks > Config.POWER_LOSS_GRACE_TICKS.get()) {
                        state = FieldState.OFFLINE;
                    }
                }
            }
            case OFFLINE -> {
                if (powered) {
                    lowPowerTicks = 0;
                    state = FieldState.ONLINE;
                }
            }
            case OVERHEATED -> {
                boolean forced = forcedShutdownTicks > 0;
                cooldownProgress += forced ? dt : rate * dt;
                double needed = forced ? forcedShutdownTicks : Config.SHUTDOWN_SECONDS.get() * 20.0;
                if (cooldownProgress >= needed) {
                    cooldownProgress = 0;
                    forcedShutdownTicks = 0;
                    load = Config.RESTART_OVERLOAD_PERCENT.get() / 100.0;
                    graceUntil = now + Config.RESTART_GRACE_SECONDS.get() * 20L;
                    state = FieldState.ONLINE;
                }
            }
        }
        return state != before;
    }

    private record Hit(Vec3 at, long tick) {
    }

    /** Where and when the field was hit lately, to take more damage from fire that keeps landing on one spot. Not saved. */
    private final java.util.ArrayDeque<Hit> recentHits = new java.util.ArrayDeque<>();
    private static final int MAX_REMEMBERED_HITS = 128;

    /**
     * How much a hit at {@code at} is multiplied by for landing where the field was hit lately: focused fire is
     * rewarded. Every earlier hit still remembered counts by how near it landed (a hit right on the spot counts fully,
     * one at the edge of the radius not at all), and the damage is multiplied by the growth once for each, up to the
     * cap. A hit on a fresh spot is 1. Also remembers this hit.
     */
    private double focus(Vec3 at, long now) {
        long window = Config.REPEAT_DAMAGE_SECONDS.get() * 20L;
        double radius = Config.REPEAT_DAMAGE_RADIUS.get();
        while (!recentHits.isEmpty() && now - recentHits.peekFirst().tick() > window) {
            recentHits.removeFirst();
        }
        double earlier = 0;
        if (radius > 0) {
            for (Hit hit : recentHits) {
                double distance = hit.at().distanceTo(at);
                if (distance < radius) {
                    earlier += 1.0 - distance / radius;
                }
            }
        }
        if (recentHits.size() >= MAX_REMEMBERED_HITS) {
            recentHits.removeFirst();
        }
        recentHits.addLast(new Hit(at, now));
        return Math.min(Config.REPEAT_DAMAGE_CAP.get(), Math.pow(Config.REPEAT_DAMAGE_GROWTH.get(), earlier));
    }

    private double lastFocus = 1.0;
    private boolean lastHitApplied;

    /** What the last hit was multiplied by, 1 for a hit on a fresh spot. For showing where the field is being focused on. */
    public double lastFocus() {
        return lastFocus;
    }

    /** Whether the last explosion actually hurt the field: it did nothing while the field was off or just restarted. */
    public boolean lastHitApplied() {
        return lastHitApplied;
    }

    /** Adds overload from an explosion at {@code at}, less if it lands where the field was hit lately. Returns true if this overheated the field. */
    public boolean addDamage(double amount, long now, Vec3 at) {
        lastHitApplied = false;
        if (state != FieldState.ONLINE || now < graceUntil || amount <= 0) {
            return false;
        }
        lastFocus = focus(at, now);
        lastHitApplied = true;
        return addDamage(amount * lastFocus, now);
    }

    /** Adds overload as it is, for small hits that are not worth attenuating, such as an arrow on the wall. Returns true if this overheated the field. */
    public boolean addDamage(double amount, long now) {
        if (state != FieldState.ONLINE || now < graceUntil || amount <= 0) {
            return false;
        }
        if (settings.aegis()) {
            // The Aegis takes the blow and nothing of it: the field never loads up, whatever hits it.
            return false;
        }
        load += amount / capacity();
        if (load >= 1.0) {
            load = 1.0;
            cooldownProgress = 0;
            state = FieldState.OVERHEATED;
            return true;
        }
        return false;
    }

    /** Seconds until an overheated field restarts at its current RPM, or -1 if it is not cooling. */
    public int secondsUntilRestart() {
        if (state != FieldState.OVERHEATED) {
            return 0;
        }
        if (forcedShutdownTicks > 0) {
            return (int) Math.ceil((forcedShutdownTicks - cooldownProgress) / 20.0);
        }
        double minRpm = Config.MIN_RPM.get();
        if (rpm < minRpm) {
            return -1;
        }
        double rate = Math.min(rpm, Config.MAX_EFFECTIVE_RPM.get()) / minRpm;
        double remaining = Config.SHUTDOWN_SECONDS.get() * 20.0 - cooldownProgress;
        return (int) Math.ceil(remaining / rate / 20.0);
    }

    /** Distance from {@code point}, in the world, to the field; 0 if inside. */
    public double distanceTo(Vec3 point) {
        return worldShape().distanceTo(point);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Core", NbtUtils.writeBlockPos(core));
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (ownerName != null) {
            tag.putString("OwnerName", ownerName);
        }
        tag.putIntArray("Extents", extents);
        tag.putString("Shape", type.name());
        tag.putInt("Radius", radius);
        tag.putString("State", state.name());
        tag.putDouble("Load", load);
        tag.putDouble("Cooldown", cooldownProgress);
        tag.putLong("GraceUntil", graceUntil);
        tag.putLong("ForcedShutdown", forcedShutdownTicks);
        tag.put("Settings", settings.save());
        return tag;
    }

    public static Field load(CompoundTag tag) {
        return load(tag, NbtUtils.readBlockPos(tag, "Core").orElse(BlockPos.ZERO));
    }

    /** The core a saved field was at, which is not where its projector is once the projector has been moved. */
    public static BlockPos savedCore(CompoundTag tag) {
        return NbtUtils.readBlockPos(tag, "Core").orElse(BlockPos.ZERO);
    }

    /** Loads a saved field as the field of the projector at {@code core}, wherever it was saved. */
    public static Field load(CompoundTag tag, BlockPos core) {
        Field field = new Field(core, tag.hasUUID("Owner") ? tag.getUUID("Owner") : null, 1);
        if (tag.contains("OwnerName")) {
            field.ownerName = tag.getString("OwnerName");
        }
        int[] saved = tag.getIntArray("Extents");
        if (saved.length == 6) {
            System.arraycopy(saved, 0, field.extents, 0, 6);
        }
        // Fields saved before spheres existed are cubes.
        field.radius = tag.contains("Radius") ? tag.getInt("Radius") : Arrays.stream(field.extents).min().orElse(1);
        try {
            field.type = FieldShape.Type.valueOf(tag.getString("Shape"));
        } catch (IllegalArgumentException e) {
            field.type = FieldShape.Type.CUBE;
        }
        field.rebuildShape();
        try {
            field.state = FieldState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException e) {
            field.state = FieldState.OFFLINE;
        }
        field.cooldownProgress = tag.getDouble("Cooldown");
        field.graceUntil = tag.getLong("GraceUntil");
        field.forcedShutdownTicks = tag.getLong("ForcedShutdown");
        if (tag.contains("Settings")) {
            field.settings = FieldSettings.load(tag.getCompound("Settings"));
        } else if (tag.getBoolean("AllowEntry")) {
            // Saved before access levels existed.
            field.settings.setAccess(FieldAction.ENTER, Access.EVERYONE);
        }
        if (tag.contains("Load")) {
            field.load = Math.max(0, Math.min(1, tag.getDouble("Load")));
        } else {
            // Saved in points, at a resistance that is not known any more: take the resistance of a field at the minimum RPM.
            double points = tag.getDouble("Overload");
            field.load = Math.max(0, Math.min(1, points / resistanceFor(0, field.settings.resistanceUpgrades())));
        }
        return field;
    }
}
