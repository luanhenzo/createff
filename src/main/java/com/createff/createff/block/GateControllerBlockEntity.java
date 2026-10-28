package com.createff.createff.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.field.Field;
import com.createff.createff.field.FieldAdmin;
import com.createff.createff.field.FieldManager;
import com.createff.createff.field.Gate;
import com.createff.createff.registry.Registration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a gate controller remembers: which gate of which field it controls, whether its signal is turned round, and
 * whether it was powered the last time it looked. Power opens the gate and no power shuts it, unless turned round.
 */
public class GateControllerBlockEntity extends BlockEntity {
    /** How far from a field's wall a controller outside it may be and still pick its gates. */
    private static final double REACH = 24.0;

    /** The projector of the field whose gate this controls, or null when it controls none. */
    @Nullable
    private BlockPos core;
    private int gateId;
    private boolean inverted;
    private boolean powered;

    public GateControllerBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.GATE_CONTROLLER_BE.get(), pos, state);
    }

    // --- Redstone ---------------------------------------------------------------------------------------

    /** The block around it gained or lost power: the gate it controls follows. */
    void updatePower(ServerLevel level, boolean now) {
        if (now == powered) {
            return;
        }
        powered = now;
        setChanged();
        apply(level);
    }

    /** Sets the gate to what the signal says: open with power, shut without, the other way round when turned round. */
    private void apply(ServerLevel level) {
        Field field = linkedField(level);
        Gate gate = field == null ? null : field.settings().gateById(gateId);
        if (gate == null) {
            return;
        }
        boolean open = powered != inverted;
        if (gate.open() != open) {
            field.settings().setGateById(gate.withOpen(open));
            FieldAdmin.changed(level, field);
        }
    }

    @Nullable
    private Field linkedField(ServerLevel level) {
        return core == null ? null : FieldManager.get(level).get(core);
    }

    // --- Using it ---------------------------------------------------------------------------------------

    /** Empty hand: picks the next gate of the field. Sneaking: turns the signal round. */
    void use(ServerLevel level, ServerPlayer player, boolean sneaking) {
        if (sneaking) {
            inverted = !inverted;
            setChanged();
            apply(level);
            player.displayClientMessage(Component.translatable(inverted ? "message.createff.controller_inverted" : "message.createff.controller_normal"), true);
            return;
        }
        Field field = linkedField(level);
        if (field == null) {
            field = nearestField(level);
        }
        if (field == null) {
            player.displayClientMessage(Component.translatable("message.createff.controller_no_field"), true);
            return;
        }
        if (!FieldAdmin.canManage(player, field)) {
            player.displayClientMessage(Component.translatable("command.createff.not_owner"), true);
            return;
        }
        List<Gate> gates = field.settings().gates();
        if (gates.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.createff.controller_no_gates"), true);
            return;
        }
        // The gate after the one it has now; after the last it lets go.
        int next = 0;
        if (field.core().equals(core)) {
            next = gates.size();
            for (int i = 0; i < gates.size(); i++) {
                if (gates.get(i).id() == gateId) {
                    next = i + 1;
                    break;
                }
            }
        }
        if (next >= gates.size()) {
            core = null;
            gateId = 0;
            setChanged();
            player.displayClientMessage(Component.translatable("message.createff.controller_unlinked"), true);
            return;
        }
        core = field.core();
        gateId = gates.get(next).id();
        setChanged();
        apply(level);
        player.displayClientMessage(Component.translatable("message.createff.controller_linked", gateId), true);
    }

    /** The field this stands in, or the one whose wall is nearest within reach. */
    @Nullable
    private Field nearestField(ServerLevel level) {
        FieldManager manager = FieldManager.get(level);
        Field covering = manager.coveringField(worldPosition);
        if (covering != null) {
            return covering;
        }
        Vec3 here = Vec3.atCenterOf(worldPosition);
        Field best = null;
        double nearest = REACH;
        for (Field field : manager.all()) {
            double distance = field.distanceTo(here);
            if (distance <= nearest) {
                nearest = distance;
                best = field;
            }
        }
        return best;
    }

    // --- Saving -----------------------------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (core != null) {
            tag.putLong("Core", core.asLong());
        }
        tag.putInt("GateId", gateId);
        tag.putBoolean("Inverted", inverted);
        tag.putBoolean("Powered", powered);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core = tag.contains("Core") ? BlockPos.of(tag.getLong("Core")) : null;
        gateId = tag.getInt("GateId");
        inverted = tag.getBoolean("Inverted");
        powered = tag.getBoolean("Powered");
    }
}
