package com.createff.createff.block;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.field.Field;
import com.createff.createff.field.FieldAdmin;
import com.createff.createff.field.FieldDebug;
import com.createff.createff.field.FieldManager;
import com.createff.createff.item.ForceCoreItem;
import com.createff.createff.field.FieldSettings;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.ShipSupport;
import com.createff.createff.field.FieldState;
import com.createff.createff.registry.Registration;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The kinetic core of a force field. The {@link Field} in {@link FieldManager} holds the real state;
 * this block entity feeds it the current RPM and mirrors it to clients for rendering.
 */
public class FieldProjectorBlockEntity extends KineticBlockEntity {
    private static final int SYNC_INTERVAL = 10;

    /**
     * Cores loaded on the client. The walls are drawn from the level renderer for all of them,
     * because block entity renderers only run for chunk sections that are in view.
     */
    public static final java.util.Set<FieldProjectorBlockEntity> CLIENT_CORES = new java.util.HashSet<>();

    @Nullable
    private UUID owner;
    @Nullable
    private String ownerName;

    // Client-side mirror of the field, filled from the update packet.
    private FieldShape.Type mirrorType = FieldShape.Type.CUBE;
    private int[] mirrorExtents = filled(Config.DEFAULT_EXTENT.getDefault());
    private int mirrorRadius = Config.DEFAULT_EXTENT.getDefault();
    private FieldShape mirrorShape;
    private FieldState mirrorState = FieldState.OFFLINE;
    private float mirrorOverload;
    private FieldSettings mirrorSettings = new FieldSettings();
    /** The force core as the client knows it, to draw it inside the glass. */
    private ItemStack mirrorCore = ItemStack.EMPTY;

    // Server-side bookkeeping for change detection.
    /** The force core. Its upgrades live in the item; the field gets a summary whenever this changes. */
    private final SimpleContainer coreSlot = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            onCoreChanged();
        }
    };
    private boolean loadingCore;
    /** The field as it was when this projector was saved, to rebuild it if the manager has none at this position. */
    @Nullable
    private CompoundTag savedField;

    private int syncCountdown;
    private FieldState lastSyncedState;
    private int lastSyncedPercent = -1;
    private int lastSyncedSettings = -1;
    private int lastSignal = -1;

    public FieldProjectorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public FieldProjectorBlockEntity(BlockPos pos, BlockState state) {
        this(Registration.FIELD_PROJECTOR_BE.get(), pos, state);
    }

    private static int[] filled(int value) {
        int[] array = new int[6];
        java.util.Arrays.fill(array, value);
        return array;
    }

    public Container coreContainer() {
        return coreSlot;
    }

    /** Sends what the core gives to the field, and the new stress to the network. */
    private void onCoreChanged() {
        setChanged();
        if (loadingCore || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Field field = ensureField();
        ForceCoreItem.Upgrades upgrades = ForceCoreItem.upgradesIn(coreSlot.getItem(0));
        boolean installed = coreSlot.getItem(0).getItem() instanceof ForceCoreItem;
        field.settings().setCore(installed, upgrades.resistance(), upgrades.total(), upgrades.aegis(), upgrades.veil());
        FieldAdmin.changed(serverLevel, field);
    }

    /** Drops the force core, upgrades and all, when the projector is broken. */
    public void dropCore(Level level, BlockPos pos) {
        ItemStack core = coreSlot.getItem(0);
        if (!core.isEmpty()) {
            loadingCore = true;
            coreSlot.setItem(0, ItemStack.EMPTY);
            loadingCore = false;
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, core);
        }
    }

    public void setOwner(UUID owner, String name) {
        this.owner = owner;
        this.ownerName = name;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            ensureField().setOwner(owner, name);
            FieldManager.get(serverLevel).setDirty();
            // Clients decide who the wall lets through, so they need the new owner right away.
            sendData();
        }
    }

    @Override
    public void initialize() {
        super.initialize();
        if (level instanceof ServerLevel) {
            ensureField();
            onCoreChanged();
        }
    }

    private Field ensureField() {
        ServerLevel serverLevel = (ServerLevel) level;
        FieldManager manager = FieldManager.get(serverLevel);
        Field field = manager.get(worldPosition);
        if (field == null && savedField != null) {
            // The projector was moved, for example into a ship: it brings its field along.
            field = adoptSavedField(serverLevel, manager);
        }
        if (field == null) {
            FieldDebug.ship(serverLevel, "projector has no field: creating a new one with default settings (owner " + ownerName + ")",
                    net.minecraft.world.phys.Vec3.atCenterOf(worldPosition));
            field = manager.add(new Field(worldPosition, owner, Config.DEFAULT_EXTENT.get()));
            field.setOwner(owner, ownerName);
        } else if (savedField != null && field != null) {
            savedField = null;
        }
        if (field != null && field.owner() == null && owner != null) {
            field.setOwner(owner, ownerName);
            manager.setDirty();
        }
        return field;
    }

    /**
     * Builds the field of this projector from the data it carries in its own NBT. The old field, at the place the
     * projector was moved from, is dropped if nothing is left there; otherwise the usual cleanup gets to it.
     */
    private Field adoptSavedField(ServerLevel serverLevel, FieldManager manager) {
        CompoundTag saved = savedField;
        savedField = null;
        Field field = Field.load(saved, worldPosition);
        BlockPos oldCore = Field.savedCore(saved);
        if (!oldCore.equals(worldPosition)) {
            Field old = manager.get(oldCore);
            if (old != null && serverLevel.isLoaded(oldCore)
                    && !(serverLevel.getBlockState(oldCore).getBlock() instanceof FieldProjectorBlock)) {
                manager.remove(oldCore);
            }
            FieldDebug.ship(serverLevel, "projector moved from " + oldCore.toShortString() + ": its field came along ("
                    + field.state() + ", overload " + Math.round(field.overload()) + ", owner " + field.ownerName() + ")",
                    net.minecraft.world.phys.Vec3.atCenterOf(worldPosition));
        }
        return manager.add(field);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level instanceof ServerLevel)) {
            return;
        }
        Field field = ensureField();
        field.setRpm(Math.abs(getSpeed()));

        if (--syncCountdown > 0) {
            return;
        }
        syncCountdown = SYNC_INTERVAL;
        int percent = (int) Math.round(field.overloadFraction() * 100);
        if (field.state() != lastSyncedState || percent != lastSyncedPercent
                || field.settings().version() != lastSyncedSettings) {
            lastSyncedState = field.state();
            lastSyncedPercent = percent;
            lastSyncedSettings = field.settings().version();
            FieldDebug.sync("server sends update for " + worldPosition.toShortString() + ": state " + field.state()
                    + ", overload " + percent + "%, settings v" + lastSyncedSettings);
            sendData();
        }
        int signal = signalFor(field);
        if (signal != lastSignal) {
            lastSignal = signal;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            // Also power what touches the projector directly, for lamps and redstone dust.
            level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        }
    }

    /**
     * Redstone output: the overload split into the 15 levels of a signal, so level n means n of 15 shares of the
     * resistance are used up. Any overload at all is at least 1; 0 means none or an offline field, and 15 means
     * the field is down or about to be.
     */
    public static int signalFor(Field field) {
        return switch (field.state()) {
            case OFFLINE -> 0;
            case OVERHEATED -> 15;
            case ONLINE -> Math.min(15, (int) Math.ceil(field.overloadFraction() * 15));
        };
    }

    @Override
    public float calculateStressApplied() {
        long volume;
        if (level instanceof ServerLevel serverLevel) {
            Field field = FieldManager.get(serverLevel).get(worldPosition);
            volume = field != null ? field.volume() : defaultVolume();
        } else {
            volume = fieldShape().volume();
        }
        float impact = stressImpact(volume, level instanceof ServerLevel serverLevel
                ? upgradesOnServer(serverLevel)
                : mirrorSettings.totalUpgrades());
        this.lastStressApplied = impact;
        return impact;
    }

    /**
     * Stress the core adds per RPM. It depends on the volume only (the resistance comes from the RPM), so a
     * small field and a big one are equally resistant at the same RPM and differ in what they cost to keep up.
     */
    public static float stressImpact(long volume, int upgrades) {
        double shield = Config.STRESS_PER_1000_BLOCKS.get() * Math.pow(volume / 1000.0, Config.VOLUME_COST_EXPONENT.get());
        shield *= 1.0 + Config.UPGRADE_STRESS_FACTOR.get() * upgrades;
        return (float) (Config.STRESS_BASE_IMPACT.get() + shield);
    }

    private int upgradesOnServer(ServerLevel serverLevel) {
        Field field = FieldManager.get(serverLevel).get(worldPosition);
        return field == null ? 0 : field.settings().totalUpgrades();
    }

    private static long defaultVolume() {
        long side = 2L * Config.DEFAULT_EXTENT.get() + 1;
        return side * side * side;
    }

    /** Tells the kinetic network the core's stress changed, for example because the field was resized. */
    public void refreshStress() {
        if (hasNetwork()) {
            getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
        }
    }

    /** How far the field reaches from the core on {@code side}, as last synced. */
    public int extent(net.minecraft.core.Direction side) {
        return mirrorExtents[side.get3DDataValue()];
    }

    /** The shape of the field as last synced. */
    public FieldShape fieldShape() {
        if (mirrorShape == null) {
            mirrorShape = FieldShape.of(mirrorType, worldPosition, mirrorExtents, mirrorRadius);
        }
        return mirrorShape;
    }

    public AABB fieldBounds() {
        return fieldShape().bounds();
    }

    /**
     * The field as the world around it meets it. On the ground that is the field itself; for a sphere on a ship it is
     * the sphere where the ship is, since the projector's own position is in the ship's plot.
     */
    public FieldShape worldShape() {
        FieldShape plot = fieldShape();
        FieldShape onShip = level == null ? null : ShipSupport.client(level, plot, null);
        return onShip != null ? onShip : plot;
    }

    /** The same, where the ship is drawn at {@code partialTick} between ticks. */
    public FieldShape renderShape(float partialTick) {
        FieldShape plot = fieldShape();
        FieldShape onShip = level == null ? null : ShipSupport.client(level, plot, partialTick);
        return onShip != null ? onShip : plot;
    }

    /** Whether a point of an entity or a projectile is in the field, in the plot or in the world. */
    public boolean coversPoint(net.minecraft.world.phys.Vec3 point) {
        return fieldShape().contains(point) || worldShape().contains(point);
    }

    /** The sphere radius as last synced. */
    public int radius() {
        return mirrorRadius;
    }

    public FieldState mirrorState() {
        return mirrorState;
    }

    public float mirrorOverload() {
        return mirrorOverload;
    }

    @Nullable
    public String ownerName() {
        return ownerName;
    }

    public FieldSettings mirrorSettings() {
        return mirrorSettings;
    }

    public ItemStack mirrorCore() {
        return mirrorCore;
    }

    @Nullable
    public UUID ownerUuid() {
        return owner;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide) {
            CLIENT_CORES.add(this);
        }
    }

    @Override
    public void remove() {
        if (level instanceof ServerLevel serverLevel) {
            FieldDebug.ship(serverLevel, "projector block entity removed (field registered: "
                    + (FieldManager.get(serverLevel).get(worldPosition) != null) + ")", net.minecraft.world.phys.Vec3.atCenterOf(worldPosition));
        }
        CLIENT_CORES.remove(this);
        super.remove();
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (owner != null) {
            compound.putUUID("Owner", owner);
        }
        if (ownerName != null) {
            compound.putString("OwnerName", ownerName);
        }
        if (!clientPacket && !coreSlot.getItem(0).isEmpty()) {
            compound.put("Core", coreSlot.getItem(0).save(registries));
        }
        if (clientPacket && !coreSlot.getItem(0).isEmpty()) {
            // Only the item itself: the client draws it, and the upgrades inside are not its business.
            compound.put("CoreShown", new ItemStack(coreSlot.getItem(0).getItem()).save(registries));
        }
        if (!clientPacket && level instanceof ServerLevel serverLevel) {
            // The field travels with the projector, so moving it (into a ship, say) does not lose the settings.
            Field field = FieldManager.get(serverLevel).get(worldPosition);
            if (field != null) {
                compound.put("FieldData", field.save());
            }
        }
        if (clientPacket && level instanceof ServerLevel serverLevel) {
            Field field = FieldManager.get(serverLevel).get(worldPosition);
            if (field != null) {
                compound.putIntArray("FieldExtents", field.extents());
                compound.putString("FieldShape", field.type().name());
                compound.putInt("FieldRadius", field.radius());
                compound.putString("FieldState", field.state().name());
                compound.putFloat("FieldOverload", (float) field.overloadFraction());
                compound.put("FieldSettings", field.settings().save());
            }
        }
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        owner = compound.hasUUID("Owner") ? compound.getUUID("Owner") : null;
        ownerName = compound.contains("OwnerName") ? compound.getString("OwnerName") : null;
        if (!clientPacket) {
            savedField = compound.contains("FieldData") ? compound.getCompound("FieldData") : null;
            loadingCore = true;
            coreSlot.setItem(0, compound.contains("Core")
                    ? ItemStack.parseOptional(registries, compound.getCompound("Core"))
                    : ItemStack.EMPTY);
            loadingCore = false;
        }
        if (clientPacket) {
            mirrorCore = compound.contains("CoreShown")
                    ? ItemStack.parseOptional(registries, compound.getCompound("CoreShown"))
                    : ItemStack.EMPTY;
        }
        if (clientPacket && compound.contains("FieldState")) {
            int[] extents = compound.getIntArray("FieldExtents");
            if (extents.length == 6) {
                mirrorExtents = extents;
            }
            mirrorRadius = compound.getInt("FieldRadius");
            try {
                mirrorType = FieldShape.Type.valueOf(compound.getString("FieldShape"));
            } catch (IllegalArgumentException e) {
                mirrorType = FieldShape.Type.CUBE;
            }
            mirrorShape = null;
            try {
                mirrorState = FieldState.valueOf(compound.getString("FieldState"));
            } catch (IllegalArgumentException e) {
                mirrorState = FieldState.OFFLINE;
            }
            mirrorOverload = compound.getFloat("FieldOverload");
            FieldDebug.sync("client got update for " + worldPosition.toShortString() + ": state " + mirrorState
                    + ", overload " + Math.round(mirrorOverload * 100) + "%");
            mirrorSettings = FieldSettings.load(compound.getCompound("FieldSettings"));
        }
    }
}
