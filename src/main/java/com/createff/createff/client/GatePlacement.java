package com.createff.createff.client;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.CreateFF;
import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
import com.createff.createff.field.Gate;
import com.createff.createff.field.Gates;
import com.createff.createff.network.GateChange;

import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Putting a gate in the wall: after "Add" or "Move" in the Gates tab the screen closes and the player aims at the wall
 * of the field. Where the look meets it, Create's own outliner (the one that shows what a super glue would hold)
 * draws the outline of the gate as it would be, green when the player is close enough to change the field and red when
 * not. Use confirms, attack cancels.
 */
@EventBusSubscriber(modid = CreateFF.MODID, value = Dist.CLIENT)
public final class GatePlacement {
    private static final double REACH = 64.0;
    private static final int PLACEABLE = 0x68C586;
    private static final int OUT_OF_REACH = 0xC5484F;
    /** How far from the core a player may be and still change the field, as the server judges it. */
    private static final double CORE_REACH = 16.0;
    private static final int HINT_INTERVAL = 30;

    /** The projector whose gate is being put, or null when nothing is. */
    @Nullable
    private static BlockPos core;
    /** The gate being moved, or -1 for a new one. */
    private static int index = -1;
    /** Where the look meets the wall now, as an offset from the centre of the field. */
    @Nullable
    private static Vec3 aimed;
    private static boolean inReach;
    private static int lines;
    private static int hintCountdown;

    /** Starts aiming for a new gate (index -1) or for a new place for the gate at {@code gate}. */
    public static void begin(BlockPos projector, int gate) {
        core = projector.immutable();
        index = gate;
        aimed = null;
        hintCountdown = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (core == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        FieldProjectorBlockEntity projector = projector(minecraft);
        // Anything on screen, the pause menu included, ends it.
        if (player == null || projector == null || minecraft.screen != null || projector.mirrorState() != FieldState.ONLINE) {
            stop();
            return;
        }
        FieldShape shape = projector.worldShape();
        Optional<Vec3> hit = Gates.aim(shape, player.getEyePosition(), player.getLookAngle(), REACH);
        if (hit.isEmpty()) {
            aimed = null;
            clearLines(0);
        } else {
            aimed = snapped(projector, shape, Gates.anchorAt(shape, hit.get()), player.isShiftKeyDown());
            inReach = shape.contains(player.position())
                    || player.distanceToSqr(core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5) <= CORE_REACH * CORE_REACH;
            show(projector, shape);
        }
        if (--hintCountdown <= 0) {
            hintCountdown = HINT_INTERVAL;
            player.displayClientMessage(Component.translatable("message.createff.gate_place_hint"), true);
        }
    }

    /** Use puts the gate where it is aimed, attack gives up. Neither does what it usually does meanwhile. */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        if (core == null) {
            return;
        }
        if (event.isUseItem()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            confirm();
        } else if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            stop();
        }
    }

    /** Whether a gate is being put in the wall. */
    static boolean active() {
        return core != null;
    }

    private static void confirm() {
        Minecraft minecraft = Minecraft.getInstance();
        FieldProjectorBlockEntity projector = projector(minecraft);
        if (aimed == null || projector == null || minecraft.player == null) {
            return;
        }
        if (!inReach) {
            minecraft.player.displayClientMessage(Component.translatable("message.createff.gate_place_far"), true);
            return;
        }
        PacketDistributor.sendToServer(index < 0 ? GateChange.add(core, aimed) : GateChange.move(core, index, aimed));
        stop();
    }

    /**
     * On the wall of a cube the gate is moved so its edges fall on the lines between blocks (its middle on the middle of
     * a block, or on a line between two, as its size is odd or even), unless the player holds shift. A sphere has no
     * lines to fall on.
     */
    private static Vec3 snapped(FieldProjectorBlockEntity projector, FieldShape shape, Vec3 aimedAt, boolean free) {
        if (free || shape.isSphere() || shape.isOriented()) {
            return aimedAt;
        }
        List<Gate> gates = projector.mirrorSettings().gates();
        Gate base = index >= 0 && index < gates.size() ? gates.get(index)
                : new Gate(0, aimedAt, Math.min(3.0, Config.GATE_MAX_WIDTH.get()), Math.min(4.0, Config.GATE_MAX_HEIGHT.get()),
                        Gate.Shape.RECTANGLE, true);
        Gates.Frame frame = Gates.frame(base.withAnchor(aimedAt), shape);
        if (frame == null) {
            return aimedAt;
        }
        double[] at = { aimedAt.x, aimedAt.y, aimedAt.z };
        double[] centre = { shape.center().x, shape.center().y, shape.center().z };
        snapAlong(at, centre, frame.across(), base.width());
        snapAlong(at, centre, frame.up(), base.height());
        return new Vec3(at[0], at[1], at[2]);
    }

    /** Moves the point along the world axis {@code direction} points along, to a block middle or a line between blocks. */
    private static void snapAlong(double[] at, double[] centre, Vec3 direction, double size) {
        int axis = Math.abs(direction.x) > Math.abs(direction.y) && Math.abs(direction.x) > Math.abs(direction.z) ? 0
                : Math.abs(direction.y) > Math.abs(direction.z) ? 1 : 2;
        double world = centre[axis] + at[axis];
        double snapped = Math.round(size) % 2 == 1 ? Math.floor(world) + 0.5 : Math.round(world);
        at[axis] = snapped - centre[axis];
    }

    private static void show(
FieldProjectorBlockEntity projector, FieldShape shape) {
        List<Gate> gates = projector.mirrorSettings().gates();
        Gate base = index >= 0 && index < gates.size() ? gates.get(index)
                : new Gate(0, aimed, Math.min(3.0, Config.GATE_MAX_WIDTH.get()), Math.min(4.0, Config.GATE_MAX_HEIGHT.get()),
                        Gate.Shape.RECTANGLE, true);
        List<Vec3> points = Gates.outline(base.withAnchor(aimed), shape, 0.5);
        int colour = inReach ? PLACEABLE : OUT_OF_REACH;
        for (int i = 0; i < points.size(); i++) {
            Outliner.getInstance().showLine(key(i), points.get(i), points.get((i + 1) % points.size()))
                    .colored(colour)
                    .lineWidth(1 / 16f);
        }
        clearLines(points.size());
    }

    private static void stop() {
        clearLines(0);
        core = null;
        aimed = null;
    }

    /** Takes down the lines of an outline from {@code from} on, once the outline has fewer of them. */
    private static void clearLines(int from) {
        for (int i = from; i < lines; i++) {
            Outliner.getInstance().remove(key(i));
        }
        lines = from;
    }

    private static String key(int line) {
        return "createff_gate_line_" + line;
    }

    @Nullable
    private static FieldProjectorBlockEntity projector(Minecraft minecraft) {
        for (FieldProjectorBlockEntity candidate : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (candidate.getBlockPos().equals(core) && candidate.getLevel() == minecraft.level) {
                return candidate;
            }
        }
        return null;
    }

    private GatePlacement() {
    }
}
