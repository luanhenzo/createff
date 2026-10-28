package com.createff.createff.client;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.CreateFF;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldAdmin;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
import com.createff.createff.field.Gate;
import com.createff.createff.field.Gates;
import com.createff.createff.field.ShipSupport;
import com.createff.createff.network.GateChange;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.gui.AllIcons;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Working on a gate by looking at it, the way Create's schematic tools work: with the wrench in hand, the gate in the
 * wall the player is looking at, in a field they may change, is picked and its border burns brightest. A list of what can
 * be done to it shows on the screen; the scroll wheel goes through the list and use does the one chosen. A block between
 * the player and the wall is in the way, as it would be for anything else.
 */
@EventBusSubscriber(modid = CreateFF.MODID, value = Dist.CLIENT)
public final class GateSelection {
    private static final double REACH = 48.0;

    /** What can be done to a picked gate. */
    private enum Tool {
        MOVE, TOGGLE, SHAPE, REMOVE
    }

    @Nullable
    private static BlockPos pickedCore;
    private static int pickedId = -1;
    private static int tool;
    /** When the description of the chosen tool began to appear, in milliseconds. */
    private static long revealStart;
    private static final long MILLIS_PER_LETTER = 18;
    /** Removing asks to be used twice: until when, in milliseconds, the second use removes the gate. */
    private static long armedUntil;
    private static final long CONFIRM_MILLIS = 4000;

    /** Whether the gate with this id in the field of this projector is the one the player has picked. */
    static boolean isPicked(BlockPos core, int id) {
        return pickedId == id && core.equals(pickedCore);
    }

    @SubscribeEvent
    public static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(CreateFF.MODID, "gate_tools"),
                (graphics, deltaTracker) -> render(graphics));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null || GatePlacement.active()
                || !player.getMainHandItem().is(AllItems.WRENCH.get())) {
            clear();
            return;
        }
        Vec3 eye = player.getEyePosition();
        double nearest = Double.MAX_VALUE;
        BlockPos foundCore = null;
        int foundId = -1;
        for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (core.getLevel() != minecraft.level || core.mirrorState() != FieldState.ONLINE
                    || !FieldAdmin.canManage(core.mirrorSettings(), core.ownerUuid(), player)) {
                continue;
            }
            FieldShape shape = core.worldShape();
            Optional<Vec3> aimed = Gates.aim(shape, eye, player.getLookAngle(), REACH);
            if (aimed.isEmpty()) {
                continue;
            }
            Vec3 point = aimed.get();
            double distance = eye.distanceTo(point);
            // A block in front of the wall is in the way, however far it is: the pick the game makes only reaches as far as
            // the hands do, so the line to the wall is followed here.
            BlockHitResult blocked = minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (blocked.getType() == HitResult.Type.BLOCK && eye.distanceTo(blocked.getLocation()) < distance - 0.05) {
                continue;
            }
            // The blocks of a ship are in the way too, though the game's ray does not see them.
            double ship = ShipSupport.firstShipBlock(minecraft.level, eye, point, player);
            if (ship >= 0.0 && ship < distance - 0.05) {
                continue;
            }
            for (Gate gate : core.mirrorSettings().gates()) {
                if (distance < nearest && Gates.gaps(gate, shape, point)) {
                    nearest = distance;
                    foundCore = core.getBlockPos();
                    foundId = gate.id();
                }
            }
        }
        if (foundId != pickedId || (foundCore != null && !foundCore.equals(pickedCore))) {
            // Another gate: the list starts over at the first thing to do.
            tool = 0;
            armedUntil = 0;
            revealStart = System.currentTimeMillis();
        }
        pickedCore = foundCore;
        pickedId = foundId;
    }

    /** With alt held, the wheel goes through what can be done to the picked gate, instead of through the hotbar. */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pickedCore == null || minecraft.screen != null || minecraft.player == null || GatePlacement.active()
                || !Screen.hasAltDown() || !minecraft.player.getMainHandItem().is(AllItems.WRENCH.get())) {
            return;
        }
        double delta = event.getScrollDeltaY();
        if (delta == 0.0) {
            return;
        }
        event.setCanceled(true);
        int count = Tool.values().length;
        tool = Math.floorMod(tool + (delta < 0.0 ? 1 : -1), count);
        armedUntil = 0;
        revealStart = System.currentTimeMillis();
    }

    /** Use does what is chosen in the list. */
    @SubscribeEvent
    public static void onInteract(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (pickedCore == null || !event.isUseItem() || minecraft.player == null || GatePlacement.active()
                || !minecraft.player.getMainHandItem().is(AllItems.WRENCH.get())) {
            return;
        }
        int index = indexOf(pickedCore, pickedId);
        if (index < 0) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        switch (Tool.values()[tool]) {
            case MOVE -> GatePlacement.begin(pickedCore, index);
            case TOGGLE -> PacketDistributor.sendToServer(GateChange.of(pickedCore, GateChange.Kind.OPEN, index));
            case SHAPE -> PacketDistributor.sendToServer(GateChange.of(pickedCore, GateChange.Kind.SHAPE, index));
            case REMOVE -> {
                // Taking a gate out cannot be undone, so it takes a second use, soon after the first.
                if (armed()) {
                    armedUntil = 0;
                    PacketDistributor.sendToServer(GateChange.of(pickedCore, GateChange.Kind.REMOVE, index));
                } else {
                    armedUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
                    revealStart = System.currentTimeMillis();
                }
            }
        }
    }

    // --- The list on the screen -------------------------------------------------------------------

    private static final int BOX = 24;
    private static final int DESCRIPTION_WIDTH = 230;
    private static final int GAP = 4;

    private static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        Gate gate = pickedCore == null || minecraft.options.hideGui ? null : gateOf(pickedCore, pickedId);
        if (gate == null) {
            return;
        }
        Font font = minecraft.font;
        Tool[] tools = Tool.values();
        int width = tools.length * BOX + (tools.length - 1) * GAP;
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 128;
        Component title = Component.translatable("gui.createff.gates.tool.title", gate.id(),
                Component.translatable(gate.open() ? "gui.createff.gates.open" : "gui.createff.gates.shut"));
        graphics.drawCenteredString(font, title, graphics.guiWidth() / 2, y - 24, 0xFFFFD27A);
        graphics.drawCenteredString(font, labelOf(tools[tool], gate), graphics.guiWidth() / 2, y - 12, armed() ? 0xFFFF6060 : 0xFFFFFFFF);
        for (int i = 0; i < tools.length; i++) {
            int boxX = x + i * (BOX + GAP);
            boolean chosen = i == tool;
            graphics.fill(boxX, y, boxX + BOX, y + BOX, chosen ? 0xD0303040 : 0x90101018);
            if (chosen) {
                graphics.renderOutline(boxX - 1, y - 1, BOX + 2, BOX + 2, 0xFFFFFFFF);
            }
            iconOf(tools[i], gate).render(graphics, boxX + (BOX - 16) / 2, y + (BOX - 16) / 2);
        }
        // What the chosen one does, written out a letter at a time.
        String text = descriptionOf(tools[tool], gate).getString();
        int shown = (int) Math.min(text.length(), (System.currentTimeMillis() - revealStart) / MILLIS_PER_LETTER);
        int lineY = y + BOX + 8;
        for (net.minecraft.network.chat.FormattedText line : font.getSplitter().splitLines(text, DESCRIPTION_WIDTH, net.minecraft.network.chat.Style.EMPTY)) {
            String whole = line.getString();
            String part = whole.substring(0, Math.min(whole.length(), Math.max(0, shown)));
            shown -= whole.length() + 1;
            graphics.drawString(font, part, (graphics.guiWidth() - font.width(whole)) / 2, lineY, 0xFFD8D8D8, false);
            lineY += 10;
        }
        graphics.drawCenteredString(font, Component.translatable("gui.createff.gates.tool.hint"), graphics.guiWidth() / 2, lineY + 4, 0xFF808080);
    }

    private static AllIcons iconOf(
Tool tool, Gate gate) {
        return switch (tool) {
            case MOVE -> AllIcons.I_TOOL_MOVE_XZ;
            case TOGGLE -> gate.open() ? AllIcons.I_PAUSE : AllIcons.I_PLAY;
            case SHAPE -> AllIcons.I_FLIP;
            case REMOVE -> AllIcons.I_TRASH;
        };
    }

    private static Component labelOf(Tool tool, Gate gate) {
        return switch (tool) {
            case MOVE -> Component.translatable("gui.createff.gates.tool.move");
            case TOGGLE -> Component.translatable(gate.open() ? "gui.createff.gates.tool.shut" : "gui.createff.gates.tool.open");
            case SHAPE -> Component.translatable("gui.createff.gates.tool.shape", Component.translatable("gate.createff.shape." + gate.shape().key()));
            case REMOVE -> Component.translatable(armed() ? "gui.createff.gates.tool.remove.sure" : "gui.createff.gates.tool.remove");
        };
    }

    private static Component descriptionOf(Tool tool, Gate gate) {
        return switch (tool) {
            case MOVE -> Component.translatable("gui.createff.gates.tool.move.desc");
            case TOGGLE -> Component.translatable(gate.open() ? "gui.createff.gates.tool.shut.desc" : "gui.createff.gates.tool.open.desc");
            case SHAPE -> Component.translatable("gui.createff.gates.tool.shape.desc");
            case REMOVE -> Component.translatable(armed() ? "gui.createff.gates.tool.remove.confirm" : "gui.createff.gates.tool.remove.desc");
        };
    }

    /** Whether removing the picked gate has been asked for once and waits for the second use. */
    private static boolean armed() {
        return Tool.values()[tool] == Tool.REMOVE && System.currentTimeMillis() < armedUntil;
    }

    private static void clear() {

        pickedCore = null;
        pickedId = -1;
    }

    @Nullable
    private static Gate gateOf(BlockPos core, int id) {
        FieldProjectorBlockEntity projector = projector(core);
        return projector == null ? null : projector.mirrorSettings().gateById(id);
    }

    private static int indexOf(BlockPos core, int id) {
        FieldProjectorBlockEntity projector = projector(core);
        if (projector == null) {
            return -1;
        }
        List<Gate> gates = projector.mirrorSettings().gates();
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    @Nullable
    private static FieldProjectorBlockEntity projector(BlockPos core) {
        for (FieldProjectorBlockEntity candidate : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (candidate.getBlockPos().equals(core) && candidate.getLevel() == Minecraft.getInstance().level) {
                return candidate;
            }
        }
        return null;
    }

    private GateSelection() {
    }
}
