package com.createff.createff.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.Access;
import com.createff.createff.field.Field;
import com.createff.createff.field.FieldAction;
import com.createff.createff.field.FieldAdmin;
import com.createff.createff.field.FieldSettings;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
import com.createff.createff.menu.FieldProjectorMenu;
import com.createff.createff.network.FieldChange;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The screen of a field projector, in the look of the vanilla inventory screens. Tabs run down the left; the
 * Core tab holds the force core slot with the reach controls around it, the upgrade slots and the player's
 * inventory; the other tabs hold the settings. Everyone allowed to look sees everything, only the owner, admins
 * and operators can change it, and every change goes to the server, so the screen never shows something the
 * server has not confirmed.
 */
public class FieldScreen extends AbstractContainerScreen<FieldProjectorMenu> {
    private static final int TAB_WIDTH = 56;
    private static final int PANEL_W = FieldProjectorMenu.PANEL_W;
    private static final int MAIN_H = FieldProjectorMenu.MAIN_H;
    private static final int OTHER_H = 204;
    private static final int MARGIN = 12;
    private static final int ROWS = 5;
    private static final int ROW_HEIGHT = 18;
    private static final long MESSAGE_MILLIS = 6000;

    // Vanilla container colors.
    private static final int PANEL = 0xFFC6C6C6;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int TEXT = 0xFF404040;
    private static final int TEXT_DIM = 0xFF707070;
    private static final int GOOD = 0xFF00800F;
    private static final int BAD = 0xFFB00000;

    private enum Tab {
        CORE, ACCESS, PEOPLE, ALLOWED
    }

    /** One reach control of the Core tab: an arrow up, the value, an arrow down. [x, y] is its top left corner. */
    private record Reach(@Nullable Direction side, String label, int x, int y) {
    }

    private static final int REACH_WIDTH = 34;

    /** A sphere has one control, for its radius, above the core slot. Its side is null. */
    private static final Reach[] RADIUS_CONTROL = { new Reach(null, "R", 119, 8) };

    /** Around the core slot, whose centre is at x 136, y 47 of the panel. */
    private static final Reach[] COMPASS = {
            new Reach(Direction.NORTH, "N", 119, 8),
            new Reach(Direction.WEST, "W", 87, 34),
            new Reach(Direction.EAST, "E", 151, 34),
            new Reach(Direction.SOUTH, "S", 119, 59),
            new Reach(Direction.UP, "U", 196, 10),
            new Reach(Direction.DOWN, "D", 196, 42)
    };

    /** The arrow buttons of the Core tab, in the order they were built, with whether each points up. */
    private final List<Button> arrowButtons = new ArrayList<>();
    private Button powerButton;
    private Button shapeButton;
    /** The role a person gets when added in the People tab. */
    private boolean addAsAdmin;
    /** Whether a new entry in the Allowed tab is an item rather than a block. */
    private boolean addAsItem;

    // The last answer from the server, shown at the bottom of whichever tab is open.
    private static Component lastMessage;
    private static boolean lastMessageOk;
    private static long lastMessageTime;

    private final BlockPos corePos;
    private Tab tab = Tab.CORE;
    private int page;
    private String draft = "";
    private EditBox input;
    private FieldChange.Kind addKindForEnter;

    private FieldSettings shownSettings;
    private FieldState shownState;
    private int shownReach;
    /** Top left of the panel, in screen coordinates. */
    private int left;
    private int top;

    public FieldScreen(FieldProjectorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.corePos = menu.pos();
        this.imageWidth = FieldProjectorMenu.PANEL_X + PANEL_W;
        this.imageHeight = FieldProjectorMenu.TOTAL_H;
    }

    public static void showMessage(boolean ok, String key, List<String> args) {
        lastMessage = Component.translatable(key, args.toArray());
        lastMessageOk = ok;
        lastMessageTime = System.currentTimeMillis();
    }

    // --- Data -------------------------------------------------------------------------------------------

    @Nullable
    private FieldProjectorBlockEntity core() {
        for (FieldProjectorBlockEntity candidate : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (candidate.getBlockPos().equals(corePos) && candidate.getLevel() == minecraft.level) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canEdit(FieldProjectorBlockEntity core) {
        Player player = minecraft.player;
        return player != null && FieldAdmin.canManage(core.mirrorSettings(), core.ownerUuid(), player);
    }

    private int panelHeight() {
        return tab == Tab.CORE ? MAIN_H : OTHER_H;
    }

    /** The shape and all its sizes folded into one number, to notice when any of them changes. */
    private static int reachSum(FieldProjectorBlockEntity core) {
        int sum = core.fieldShape().type().ordinal() * 31 + core.radius();
        for (Direction side : Direction.values()) {
            sum = sum * 31 + core.extent(side);
        }
        return sum;
    }

    /** The size controls the field has in its current shape: six around the core for a cube, a radius for a sphere. */
    private static Reach[] controls(FieldProjectorBlockEntity core) {
        return core.fieldShape().isSphere() ? RADIUS_CONTROL : COMPASS;
    }

    private static int reachOf(FieldProjectorBlockEntity core, Reach reach) {
        return reach.side() == null ? core.radius() : core.extent(reach.side());
    }

    // --- Layout -----------------------------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        left = leftPos + FieldProjectorMenu.PANEL_X;
        top = topPos;
        menu.setSlotsVisible(tab == Tab.CORE);
        FieldProjectorBlockEntity core = core();
        if (core == null) {
            return;
        }
        shownSettings = core.mirrorSettings();
        shownState = core.mirrorState();
        shownReach = reachSum(core);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab target = tabs[i];
            Button button = Button.builder(Component.translatable("gui.createff.tab." + name(target)), pressed -> {
                tab = target;
                page = 0;
                rebuildWidgets();
            }).bounds(leftPos, top + 4 + i * 21, TAB_WIDTH, 20).build();
            button.active = target != tab;
            addRenderableWidget(button);
        }

        boolean edit = canEdit(core);
        switch (tab) {
            case CORE -> buildCompass(core, edit);
            case ACCESS -> buildAccess(core, edit);
            case PEOPLE -> buildPeople(core, edit);
            case ALLOWED -> buildAllowed(core, edit);
            default -> {
            }
        }
    }

    /** The six reach controls around the core slot: an arrow up, the value, an arrow down. Shift moves by 8. */
    private void buildCompass(FieldProjectorBlockEntity core, boolean edit) {
        arrowButtons.clear();
        powerButton = null;
        int max = Config.MAX_EXTENT.get();
        for (Reach reach : controls(core)) {
            int current = reachOf(core, reach);
            int x = left + reach.x() + 7;
            Button more = Button.builder(Component.empty(), pressed -> resize(reach.side(), hasShiftDown() ? 8 : 1))
                    .bounds(x, top + reach.y(), 20, 8).build();
            more.active = edit && current < max;
            addRenderableWidget(more);
            arrowButtons.add(more);
            Button less = Button.builder(Component.empty(), pressed -> resize(reach.side(), -(hasShiftDown() ? 8 : 1)))
                    .bounds(x, top + reach.y() + 19, 20, 8).build();
            less.active = edit && current > 1;
            addRenderableWidget(less);
            arrowButtons.add(less);
        }

        boolean on = core.mirrorSettings().enabled();
        powerButton = Button.builder(Component.empty(), pressed -> PacketDistributor.sendToServer(FieldChange.power(corePos, !on)))
                .bounds(left + PANEL_W - 32, top + 8, 22, 22).build();
        // Nothing to switch on without a force core.
        powerButton.active = edit && core.mirrorSettings().coreInstalled();
        addRenderableWidget(powerButton);

        // Below the power switch: cycles through the shapes the field can have.
        FieldShape.Type[] types = FieldShape.Type.values();
        FieldShape.Type next = types[(core.fieldShape().type().ordinal() + 1) % types.length];
        shapeButton = Button.builder(Component.empty(), pressed -> PacketDistributor.sendToServer(FieldChange.shape(corePos, next.ordinal())))
                .bounds(left + PANEL_W - 32, top + 36, 22, 22).build();
        shapeButton.active = edit;
        addRenderableWidget(shapeButton);
    }

    private void resize(@Nullable Direction side, int delta) {
        int target = side == null ? FieldChange.RADIUS : side.get3DDataValue();
        PacketDistributor.sendToServer(FieldChange.resize(corePos, target, delta));
    }

    private void buildAccess(FieldProjectorBlockEntity core, boolean edit) {
        FieldAction[] actions = FieldAction.values();
        Access[] levels = Access.values();
        for (int i = 0; i < actions.length; i++) {
            FieldAction action = actions[i];
            Access current = core.mirrorSettings().access(action);
            Button button = Button.builder(Component.translatable("gui.createff.access." + name(current)), pressed -> {
                Access next = levels[(current.ordinal() + 1) % levels.length];
                PacketDistributor.sendToServer(FieldChange.access(corePos, action.ordinal(), next.ordinal()));
            }).bounds(left + PANEL_W - MARGIN - 110, top + 36 + i * 30, 110, 20).build();
            button.active = edit;
            addRenderableWidget(button);
        }
    }

    /** One list of everything anyone may use in the field: blocks first, then items. */
    private void buildAllowed(FieldProjectorBlockEntity core, boolean edit) {
        List<String[]> entries = allowed(core);
        int pages = Math.max(1, (entries.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= entries.size()) {
                break;
            }
            String entry = entries.get(index)[0];
            boolean block = entries.get(index)[1].equals("block");
            Button remove = Button.builder(Component.literal("x"), pressed -> PacketDistributor.sendToServer(
                    FieldChange.text(corePos, block ? FieldChange.Kind.BLOCK_REMOVE : FieldChange.Kind.ITEM_REMOVE, entry)))
                    .bounds(left + PANEL_W - MARGIN - 18, top + 30 + row * ROW_HEIGHT, 18, 16).build();
            remove.active = edit;
            addRenderableWidget(remove);
        }

        Button previous = Button.builder(Component.literal("<"), pressed -> {
            page--;
            rebuildWidgets();
        }).bounds(left + MARGIN, top + 126, 20, 16).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), pressed -> {
            page++;
            rebuildWidgets();
        }).bounds(left + MARGIN + 66, top + 126, 20, 16).build();
        next.active = page < pages - 1;
        addRenderableWidget(next);

        int inputWidth = PANEL_W - 2 * MARGIN - 68 - 56 - 8;
        input = new EditBox(font, left + MARGIN, top + 148, inputWidth, 18, Component.empty());
        input.setMaxLength(128);
        input.setHint(Component.translatable(addAsItem ? "gui.createff.hint.item" : "gui.createff.hint.block"));
        input.setValue(draft);
        input.setResponder(text -> draft = text);
        input.setEditable(edit);
        addRenderableWidget(input);

        // What kind of thing a new entry is: blocks can carry state properties, items cannot.
        Button newType = Button.builder(Component.translatable(addAsItem ? "gui.createff.allowed.item" : "gui.createff.allowed.block"),
                pressed -> {
                    addAsItem = !addAsItem;
                    rebuildWidgets();
                }).bounds(left + MARGIN + inputWidth + 4, top + 147, 56, 20).build();
        newType.active = edit;
        addRenderableWidget(newType);

        addKindForEnter = addAsItem ? FieldChange.Kind.ITEM_ADD : FieldChange.Kind.BLOCK_ADD;
        FieldChange.Kind kind = addKindForEnter;
        Button add = Button.builder(Component.translatable("gui.createff.add"), pressed -> submit(kind))
                .bounds(left + PANEL_W - MARGIN - 68, top + 147, 68, 20).build();
        add.active = edit;
        addRenderableWidget(add);
    }

    /** Blocks first, then items: each an entry and its kind. */
    private static List<String[]> allowed(FieldProjectorBlockEntity core) {
        List<String[]> entries = new ArrayList<>();
        for (String entry : core.mirrorSettings().interactBlocks()) {
            entries.add(new String[] { entry, "block" });
        }
        for (String entry : core.mirrorSettings().interactItems()) {
            entries.add(new String[] { entry, "item" });
        }
        return entries;
    }

    /** One list of everyone with a role in the field: admins first, then trusted players. Each row can change role or be removed. */
    private void buildPeople(FieldProjectorBlockEntity core, boolean edit) {
        List<String[]> people = people(core);
        int pages = Math.max(1, (people.size() + ROWS - 1) / ROWS);
        page = Math.max(0, Math.min(page, pages - 1));

        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= people.size()) {
                break;
            }
            String name = people.get(index)[0];
            boolean admin = people.get(index)[1].equals("admin");
            int y = top + 30 + row * ROW_HEIGHT;

            Button remove = Button.builder(Component.literal("x"), pressed -> PacketDistributor.sendToServer(
                    FieldChange.text(corePos, admin ? FieldChange.Kind.ADMIN_REMOVE : FieldChange.Kind.TRUST_REMOVE, name)))
                    .bounds(left + PANEL_W - MARGIN - 18, y, 18, 16).build();
            remove.active = edit;
            addRenderableWidget(remove);

            // Switches the role. Demoting is two steps the server handles in order: no longer admin, then trusted.
            Button role = Button.builder(Component.translatable(admin ? "gui.createff.people.admin" : "gui.createff.people.trusted"),
                    pressed -> {
                        if (admin) {
                            PacketDistributor.sendToServer(FieldChange.text(corePos, FieldChange.Kind.ADMIN_REMOVE, name));
                            PacketDistributor.sendToServer(FieldChange.text(corePos, FieldChange.Kind.TRUST_ADD, name));
                        } else {
                            PacketDistributor.sendToServer(FieldChange.text(corePos, FieldChange.Kind.ADMIN_ADD, name));
                        }
                    }).bounds(left + PANEL_W - MARGIN - 18 - 4 - 56, y, 56, 16).build();
            role.active = edit;
            addRenderableWidget(role);
        }

        Button previous = Button.builder(Component.literal("<"), pressed -> {
            page--;
            rebuildWidgets();
        }).bounds(left + MARGIN, top + 126, 20, 16).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), pressed -> {
            page++;
            rebuildWidgets();
        }).bounds(left + MARGIN + 66, top + 126, 20, 16).build();
        next.active = page < pages - 1;
        addRenderableWidget(next);

        int inputWidth = PANEL_W - 2 * MARGIN - 68 - 56 - 8;
        input = new EditBox(font, left + MARGIN, top + 148, inputWidth, 18, Component.empty());
        input.setMaxLength(32);
        input.setHint(Component.translatable("gui.createff.hint.player"));
        input.setValue(draft);
        input.setResponder(text -> draft = text);
        input.setEditable(edit);
        addRenderableWidget(input);

        // The role a new person gets.
        Button newRole = Button.builder(Component.translatable(addAsAdmin ? "gui.createff.people.admin" : "gui.createff.people.trusted"),
                pressed -> {
                    addAsAdmin = !addAsAdmin;
                    rebuildWidgets();
                }).bounds(left + MARGIN + inputWidth + 4, top + 147, 56, 20).build();
        newRole.active = edit;
        addRenderableWidget(newRole);

        addKindForEnter = addAsAdmin ? FieldChange.Kind.ADMIN_ADD : FieldChange.Kind.TRUST_ADD;
        FieldChange.Kind kind = addKindForEnter;
        Button add = Button.builder(Component.translatable("gui.createff.add"), pressed -> submit(kind))
                .bounds(left + PANEL_W - MARGIN - 68, top + 147, 68, 20).build();
        add.active = edit;
        addRenderableWidget(add);
    }

    /** Admins first, then trusted players: each a name and its role. */
    private static List<String[]> people(FieldProjectorBlockEntity core) {
        List<String[]> people = new ArrayList<>();
        for (String name : core.mirrorSettings().admins().values()) {
            people.add(new String[] { name, "admin" });
        }
        for (String name : core.mirrorSettings().trusted().values()) {
            people.add(new String[] { name, "trusted" });
        }
        return people;
    }

    private void submit(FieldChange.Kind kind) {
        String text = draft.trim();
        if (text.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(FieldChange.text(corePos, kind, text));
        draft = "";
        if (input != null) {
            input.setValue("");
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // While typing, every key belongs to the text box: otherwise E (the inventory key) would close the screen.
        if (tab != Tab.CORE && input != null && input.isFocused()) {
            if (keyCode == 256) {
                onClose();
                return true;
            }
            if ((keyCode == 257 || keyCode == 335) && addKindForEnter != null) {
                submit(addKindForEnter);
                return true;
            }
            input.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Updating ---------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        FieldProjectorBlockEntity core = core();
        if (core == null) {
            onClose();
            return;
        }
        // The core replaces its copy whenever the server sends a change; rebuild to show it.
        if (core.mirrorSettings() != shownSettings || core.mirrorState() != shownState || reachSum(core) != shownReach) {
            rebuildWidgets();
        }
    }

    // --- Drawing ----------------------------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics, left, top, PANEL_W, panelHeight());
        if (tab == Tab.CORE) {
            int inventoryX = left + (PANEL_W - 176) / 2;
            panel(graphics, inventoryX, top + FieldProjectorMenu.INVENTORY_PANEL_Y, 176, FieldProjectorMenu.INVENTORY_PANEL_H);
            panel(graphics, inventoryX, top + FieldProjectorMenu.HOTBAR_PANEL_Y, 176, FieldProjectorMenu.HOTBAR_PANEL_H);
            // The strip the upgrade slots sit in, drawn as a sunken area like the vanilla slot backgrounds.
            inset(graphics, left + 8, top + FieldProjectorMenu.UPGRADE_BAR_Y, PANEL_W - 16, 26);
        }
        for (Slot slot : menu.slots) {
            if (slot.isActive()) {
                inset(graphics, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
            }
        }
    }

    /** The tabs and panels draw their own text. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        FieldProjectorBlockEntity core = core();
        if (core == null) {
            return;
        }
        switch (tab) {
            case CORE -> drawCore(graphics, core);
            case ACCESS -> drawAccess(graphics);
            case PEOPLE -> drawPeople(graphics, core);
            case ALLOWED -> drawAllowed(graphics, core);
        }
        if (tab != Tab.CORE) {
            if (!canEdit(core)) {
                text(graphics, Component.translatable("gui.createff.view_only"), left + MARGIN, top + OTHER_H - 28, 0xFFB06000);
            }
            drawMessage(graphics, top + OTHER_H - 14);
        }
        if (tab == Tab.CORE && powerButton != null && powerButton.isMouseOver(mouseX, mouseY)) {
            String key = !core.mirrorSettings().coreInstalled() ? "gui.createff.power.nocore"
                    : !powerButton.active ? "gui.createff.power.locked"
                    : core.mirrorSettings().enabled() ? "gui.createff.power.on" : "gui.createff.power.off";
            graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
        }
        if (tab == Tab.CORE && shapeButton != null && shapeButton.isMouseOver(mouseX, mouseY)) {
            String key = !shapeButton.active ? "gui.createff.shape.locked"
                    : "gui.createff.shape." + name(core.fieldShape().type());
            graphics.renderTooltip(font, Component.translatable(key), mouseX, mouseY);
        }
        // Last, so the tooltip of the slot under the mouse is drawn over everything else.
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void drawCore(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        for (Reach reach : controls(core)) {
            // The value sits exactly between the arrows; the label stays out of the way on its left.
            int centerX = left + reach.x() + REACH_WIDTH / 2;
            int y = top + reach.y() + 10;
            String value = String.valueOf(reachOf(core, reach));
            text(graphics, Component.literal(value), centerX - font.width(value) / 2, y, 0xFF000000);
            text(graphics, Component.literal(reach.label()), centerX - 18, y, TEXT);
        }
        for (int i = 0; i < arrowButtons.size(); i++) {
            drawArrow(graphics, arrowButtons.get(i), i % 2 == 0);
        }
        drawInfoColumn(graphics, core);
        drawOverload(graphics, core);
        drawPower(graphics, core);
        drawShape(graphics, core);

        text(graphics, Component.translatable("gui.createff.core.upgrades.label"), left + 12, top + FieldProjectorMenu.UPGRADE_BAR_Y + 9, TEXT);
    }

    /** The numbers about the field, in a column to the left of the reach controls. */
    private void drawInfoColumn(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        AABB bounds = core.fieldBounds();
        long volume = core.fieldShape().volume();
        FieldSettings settings = core.mirrorSettings();
        String[] labels = {
                "gui.createff.info.blocks", "gui.createff.info.size",
                "gui.createff.info.stress", "gui.createff.info.resistance"
        };
        String[] values = {
                String.valueOf(volume),
                core.fieldShape().isSphere()
                        ? "R " + core.radius()
                        : (int) bounds.getXsize() + "x" + (int) bounds.getYsize() + "x" + (int) bounds.getZsize(),
                String.format(Locale.ROOT, "%.1f", FieldProjectorBlockEntity.stressImpact(volume, settings.totalUpgrades())),
                String.valueOf(Math.round(Field.resistanceFor(Math.abs(core.getSpeed()), settings.resistanceUpgrades())))
        };
        for (int i = 0; i < labels.length; i++) {
            int y = top + 9 + i * 19;
            text(graphics, Component.translatable(labels[i]), left + 10, y, TEXT_DIM);
            text(graphics, Component.literal(values[i]), left + 10, y + 9, 0xFF000000);
        }
    }

    /**
     * How much of its resistance the field has used up, under the info column and the reach controls. For a few
     * seconds after a change, or while there is no force core, a line of text takes the place of the bar.
     */
    private void drawOverload(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        boolean message = lastMessage != null && System.currentTimeMillis() - lastMessageTime < MESSAGE_MILLIS;
        if (message || !core.mirrorSettings().coreInstalled()) {
            inset(graphics, left + 8, top + 88, PANEL_W - 16, 11);
            Component line = message ? lastMessage : Component.translatable("gui.createff.core.missing");
            boolean good = message && lastMessageOk;
            text(graphics, line, left + (PANEL_W - font.width(line)) / 2, top + 90, good ? 0xFF004D00 : 0xFF8A0000);
            return;
        }
        if (core.mirrorSettings().aegis()) {
            // With the Aegis the field cannot be overloaded, so there is no meter: a line says why.
            Component line = Component.translatable("gui.createff.status.aegis");
            text(graphics, line, left + (PANEL_W - font.width(line)) / 2, top + 90, 0xFF9A6A00);
            return;
        }
        int percent = Math.round(core.mirrorOverload() * 100);
        text(graphics, Component.translatable("gui.createff.status.overload", percent), left + 10, top + 90, TEXT);
        int barX = left + 92;
        int barY = top + 88;
        int barWidth = PANEL_W - 92 - 10;
        inset(graphics, barX, barY, barWidth, 11);
        int color = core.mirrorState() == FieldState.OVERHEATED ? 0xFFB03030 : (percent > 66 ? 0xFFE08030 : 0xFF38B8E8);
        graphics.fill(barX + 1, barY + 1, barX + 1 + (barWidth - 2) * percent / 100, barY + 10, color);
    }

    /** The power switch, tinted green when the field is switched on, red when off and gray without permission. */
    private void drawPower(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        if (powerButton == null) {
            return;
        }
        int color = !powerButton.active ? 0xFF808080 : core.mirrorSettings().enabled() ? 0xFF2EB82E : 0xFFC02020;
        int x = powerButton.getX();
        int y = powerButton.getY();
        graphics.fill(x + 2, y + 2, x + powerButton.getWidth() - 2, y + powerButton.getHeight() - 2, color);
        // A power symbol: a ring open at the top, and a short bar through the gap.
        int cx = x + powerButton.getWidth() / 2;
        int cy = y + powerButton.getHeight() / 2 + 1;
        int white = 0xFFFFFFFF;
        graphics.fill(cx - 1, cy - 7, cx + 1, cy - 1, white);
        graphics.fill(cx - 5, cy - 3, cx - 3, cy + 3, white);
        graphics.fill(cx + 3, cy - 3, cx + 5, cy + 3, white);
        graphics.fill(cx - 3, cy + 3, cx + 3, cy + 5, white);
        graphics.fill(cx - 4, cy - 4, cx - 2, cy - 2, white);
        graphics.fill(cx + 2, cy - 4, cx + 4, cy - 2, white);
    }

    /** The shape button shows the shape the field has now: a cube, or a sphere. */
    private void drawShape(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        if (shapeButton == null) {
            return;
        }
        int color = shapeButton.active ? 0xFF202020 : 0xFF707070;
        int cx = shapeButton.getX() + shapeButton.getWidth() / 2;
        int cy = shapeButton.getY() + shapeButton.getHeight() / 2;
        if (core.fieldShape().isSphere()) {
            // A disc, drawn row by row.
            for (int dy = -6; dy <= 6; dy++) {
                int half = (int) Math.round(Math.sqrt(36 - dy * dy));
                graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
            }
        } else {
            // A cube in outline: a front square and the back one shifted up and right, joined at the corners.
            graphics.renderOutline(cx - 6, cy - 3, 9, 9, color);
            graphics.renderOutline(cx - 3, cy - 6, 9, 9, color);
            graphics.fill(cx - 6, cy - 3, cx - 2, cy - 2, color);
            graphics.fill(cx + 3, cy - 6, cx + 6, cy - 5, color);
        }
    }

    private void drawAccess(GuiGraphics graphics) {
        FieldAction[] actions = FieldAction.values();
        for (int i = 0; i < actions.length; i++) {
            text(graphics, Component.translatable("action.createff." + name(actions[i])), left + MARGIN, top + 42 + i * 30, TEXT);
        }
        text(graphics, Component.translatable("gui.createff.access.note"), left + MARGIN, top + 134, TEXT_DIM);
    }

    private void drawPeople(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        List<String[]> people = people(core);
        if (people.isEmpty()) {
            text(graphics, Component.translatable("gui.createff.empty.people"), left + MARGIN, top + 34, TEXT_DIM);
        }
        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= people.size()) {
                break;
            }
            String name = font.plainSubstrByWidth(people.get(index)[0], PANEL_W - 2 * MARGIN - 18 - 4 - 56 - 8);
            text(graphics, Component.literal(name), left + MARGIN, top + 34 + row * ROW_HEIGHT, 0xFF000000);
        }
        int pages = Math.max(1, (people.size() + ROWS - 1) / ROWS);
        text(graphics, Component.literal((page + 1) + " / " + pages), left + MARGIN + 28, top + 130, TEXT);
    }

    private void drawAllowed(GuiGraphics graphics, FieldProjectorBlockEntity core) {
        List<String[]> entries = allowed(core);
        if (entries.isEmpty()) {
            text(graphics, Component.translatable("gui.createff.empty.allowed"), left + MARGIN, top + 34, TEXT_DIM);
        }
        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index >= entries.size()) {
                break;
            }
            int y = top + 34 + row * ROW_HEIGHT;
            boolean block = entries.get(index)[1].equals("block");
            text(graphics, Component.translatable(block ? "gui.createff.allowed.block" : "gui.createff.allowed.item"),
                    left + MARGIN, y, TEXT_DIM);
            String entry = font.plainSubstrByWidth(entries.get(index)[0], PANEL_W - 2 * MARGIN - 18 - 40 - 8);
            text(graphics, Component.literal(entry), left + MARGIN + 40, y, 0xFF000000);
        }
        int pages = Math.max(1, (entries.size() + ROWS - 1) / ROWS);
        text(graphics, Component.literal((page + 1) + " / " + pages), left + MARGIN + 28, top + 130, TEXT);
    }

    private void drawMessage(GuiGraphics graphics, int y) {
        if (lastMessage != null && System.currentTimeMillis() - lastMessageTime < MESSAGE_MILLIS) {
            Component message = lastMessage;
            text(graphics, message, left + (PANEL_W - font.width(message)) / 2, y, lastMessageOk ? GOOD : BAD);
        }
    }

    /** A small triangle in the middle of an arrow button. */
    private static void drawArrow(GuiGraphics graphics, Button button, boolean up) {
        int color = button.active ? 0xFF202020 : 0xFF707070;
        int centerX = button.getX() + button.getWidth() / 2;
        int top = button.getY() + (button.getHeight() - 4) / 2;
        // Rows 2, 4, 6 and 8 pixels wide, so the triangle is symmetric around the button centre.
        for (int row = 0; row < 4; row++) {
            int half = (up ? row : 3 - row) + 1;
            graphics.fill(centerX - half, top + row, centerX + half, top + row + 1, color);
        }
    }

    private void text(GuiGraphics graphics, Component text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    // --- Vanilla look -----------------------------------------------------------------------------------

    /** A raised gray panel, like the background of the vanilla inventory screens. */
    private static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + width - 1, y + 2, LIGHT);
        graphics.fill(x, y, x + 2, y + height - 1, LIGHT);
        graphics.fill(x + 2, y + height - 2, x + width, y + height, SHADOW);
        graphics.fill(x + width - 2, y + 2, x + width, y + height, SHADOW);
        graphics.renderOutline(x, y, width, height, 0xFF000000);
    }

    /** A sunken area, like a vanilla slot background. */
    private static void inset(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, SLOT);
        graphics.fill(x, y, x + width - 1, y + 1, SLOT_DARK);
        graphics.fill(x, y, x + 1, y + height - 1, SLOT_DARK);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, LIGHT);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, LIGHT);
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
