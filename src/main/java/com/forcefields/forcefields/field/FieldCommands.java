package com.forcefields.forcefields.field;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.forcefields.forcefields.Config;
import com.forcefields.forcefields.ForceFields;
import com.forcefields.forcefields.block.FieldProjectorBlockEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Commands for managing the field the executing player is standing in. Owners (and operators) manage trusted
 * players and rules; a few testing commands are operator-only.
 */
@EventBusSubscriber(modid = ForceFields.MODID)
public final class FieldCommands {
    private static final SimpleCommandExceptionType NOT_IN_FIELD = new SimpleCommandExceptionType(
            Component.translatable("command.forcefields.not_in_field"));
    private static final SimpleCommandExceptionType NOT_MANAGER = new SimpleCommandExceptionType(
            Component.translatable("command.forcefields.not_owner"));

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("forcefields");

        root.then(Commands.literal("info").executes(context -> info(context.getSource())));

        root.then(Commands.literal("trust")
                .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        context.getSource().getServer().getPlayerNames(), builder))
                                .executes(context -> trustAdd(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(trustedNames(context), builder))
                                .executes(context -> trustRemove(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("list").executes(context -> trustList(context.getSource()))));

        LiteralArgumentBuilder<CommandSourceStack> size = Commands.literal("size")
                .executes(context -> sizeInfo(context.getSource()))
                .then(Commands.literal("all")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1))
                                .executes(context -> setSize(context.getSource(), null, IntegerArgumentType.getInteger(context, "blocks")))));
        for (Direction side : Direction.values()) {
            size.then(Commands.literal(side.getName())
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1))
                            .executes(context -> setSize(context.getSource(), side, IntegerArgumentType.getInteger(context, "blocks")))));
        }
        root.then(size);

        LiteralArgumentBuilder<CommandSourceStack> shape = Commands.literal("shape");
        for (FieldShape.Type type : FieldShape.Type.values()) {
            shape.then(Commands.literal(type.name().toLowerCase(Locale.ROOT))
                    .executes(context -> setShape(context.getSource(), type)));
        }
        root.then(shape);

        root.then(Commands.literal("admin")
                .then(Commands.literal("add")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        context.getSource().getServer().getPlayerNames(), builder))
                                .executes(context -> adminAdd(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(adminNames(context), builder))
                                .executes(context -> adminRemove(context.getSource(), StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("list").executes(context -> adminList(context.getSource()))));

        LiteralArgumentBuilder<CommandSourceStack> rule = Commands.literal("rule");
        rule.then(accessRule("place", FieldAction.PLACE));
        rule.then(accessRule("break", FieldAction.BREAK));
        rule.then(accessRule("entry", FieldAction.ENTER));
        rule.then(listRule("interact", "block", true));
        rule.then(listRule("items", "item", false));
        root.then(rule);

        root.then(Commands.literal("owner").requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                context.getSource().getServer().getPlayerNames(), builder))
                        .executes(context -> setOwner(context.getSource(), StringArgumentType.getString(context, "player")))));
        root.then(Commands.literal("reset").requires(source -> source.hasPermission(2))
                .executes(context -> reset(context.getSource())));
        root.then(Commands.literal("overload").requires(source -> source.hasPermission(2))
                .executes(context -> overload(context.getSource(), 0))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                        .executes(context -> overload(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))));

        root.then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                .then(Commands.literal("damage")
                        .executes(context -> debugDamage(context.getSource(), !Config.DEBUG_LOG_DAMAGE.get()))
                        .then(Commands.literal("on").executes(context -> debugDamage(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> debugDamage(context.getSource(), false))))
                .then(Commands.literal("ships")
                        .executes(context -> debugShips(context.getSource(), !Config.DEBUG_LOG_SHIPS.get()))
                        .then(Commands.literal("on").executes(context -> debugShips(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> debugShips(context.getSource(), false))))
                .then(Commands.literal("where").executes(context -> where(context.getSource()))));

        event.getDispatcher().register(root);
    }

    private static int debugShips(CommandSourceStack source, boolean on) {
        Config.DEBUG_LOG_SHIPS.set(on);
        Config.DEBUG_LOG_SHIPS.save();
        source.sendSuccess(() -> Component.literal("Ship logging is " + (on ? "on" : "off")), true);
        return 1;
    }

    /**
     * Reports where the player, the block they look at and every field are relative to Sable's ships, in chat and in
     * the log, for working out which space things live in.
     */
    private static int where(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        report(source, "player", FieldDebug.describeShip(player));
        net.minecraft.world.phys.HitResult hit = player.pick(8.0, 1.0F, false);
        if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            report(source, "looking at " + block.getBlockPos().toShortString(),
                    FieldDebug.describeShip(level, net.minecraft.world.phys.Vec3.atCenterOf(block.getBlockPos())));
        }
        for (Field field : FieldManager.get(level).all()) {
            FieldShape world = field.worldShape();
            report(source, "field core " + field.core().toShortString() + " " + field.type() + " " + field.state()
                    + " owner " + field.ownerName() + (field.onShip()
                            ? String.format(Locale.ROOT, " ON SHIP, world %s centre (%.2f, %.2f, %.2f) %s",
                                    world.isSphere() ? "sphere" : "box", world.center().x, world.center().y, world.center().z,
                                    world.isSphere() ? String.format(Locale.ROOT, "radius %.2f", world.sphereRadius())
                                            : String.format(Locale.ROOT, "half size (%.1f, %.1f, %.1f)", world.half().x, world.half().y, world.half().z))
                            : " (not on a ship)"),
                    FieldDebug.describeShip(level, net.minecraft.world.phys.Vec3.atCenterOf(field.core())));
        }
        return 1;
    }

    private static void report(CommandSourceStack source, String what, String where) {
        ForceFields.LOGGER.info("[ship] {} | {}", what, where);
        source.sendSuccess(() -> Component.literal(what + ": " + where), false);
    }

    /** Switches the damage log on or off, in the config as well so it survives a restart. */
    private static int debugDamage(CommandSourceStack source, boolean on) {
        Config.DEBUG_LOG_DAMAGE.set(on);
        Config.DEBUG_LOG_DAMAGE.save();
        source.sendSuccess(() -> Component.translatable(on ? "command.forcefields.debug_damage_on" : "command.forcefields.debug_damage_off"), true);
        return 1;
    }

    // --- Rules ------------------------------------------------------------------------------------------

    private static ArgumentBuilder<CommandSourceStack, ?> accessRule(String name, FieldAction action) {
        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(name);
        for (Access access : Access.values()) {
            literal.then(Commands.literal(access.name().toLowerCase(Locale.ROOT))
                    .executes(context -> setAccess(context.getSource(), action, access)));
        }
        // The lowest level is shown as "admins" everywhere; "owner" keeps working as an alias.
        literal.then(Commands.literal("admins")
                .executes(context -> setAccess(context.getSource(), action, Access.OWNER)));
        return literal;
    }

    /** {@code rule <name> add|remove|list}. */
    private static ArgumentBuilder<CommandSourceStack, ?> listRule(String name, String kind, boolean blocks) {
        return Commands.literal(name)
                .then(Commands.literal("add")
                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                .executes(context -> editList(context, blocks, true))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("entry", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(entries(context, blocks), builder))
                                .executes(context -> editList(context, blocks, false))))
                .then(Commands.literal("list")
                        .executes(context -> showList(context.getSource(), kind, blocks)));
    }

    private static int setAccess(CommandSourceStack source, FieldAction action, Access access) throws CommandSyntaxException {
        Field field = managedField(source);
        FieldAdmin.setAccess(field, action, access);
        changed(source.getLevel(), field);
        source.sendSuccess(() -> Component.translatable("command.forcefields.access_set",
                Component.translatable("action.forcefields." + action.name().toLowerCase(Locale.ROOT)),
                Component.translatable("access.forcefields." + access.name().toLowerCase(Locale.ROOT))), false);
        return 1;
    }

    private static int editList(CommandContext<CommandSourceStack> context, boolean blocks, boolean add) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Field field = managedField(source);
        String entry = StringArgumentType.getString(context, "entry").trim();
        FieldAdmin.Result result = FieldAdmin.editList(field, blocks, add, entry);
        return finish(source, field, result);
    }

    private static int showList(CommandSourceStack source, String kind, boolean blocks) throws CommandSyntaxException {
        Field field = visibleField(source);
        List<String> entries = blocks ? field.settings().interactBlocks() : field.settings().interactItems();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.forcefields.list_empty." + kind), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.forcefields.list." + kind, String.join(", ", entries)), false);
        }
        return entries.size();
    }

    private static List<String> entries(CommandContext<CommandSourceStack> context, boolean blocks) {
        try {
            Field field = visibleField(context.getSource());
            return blocks ? field.settings().interactBlocks() : field.settings().interactItems();
        } catch (CommandSyntaxException e) {
            return List.of();
        }
    }

    // --- Trusted players --------------------------------------------------------------------------------

    private static int trustAdd(CommandSourceStack source, String name) throws CommandSyntaxException {
        Field field = managedField(source);
        FieldAdmin.Result result = FieldAdmin.addTrusted(field, FieldAdmin.resolve(source.getServer(), name));
        return finish(source, field, result);
    }

    private static int trustRemove(CommandSourceStack source, String name) throws CommandSyntaxException {
        Field field = managedField(source);
        FieldAdmin.Result result = FieldAdmin.removeTrustedByName(field, name);
        return finish(source, field, result);
    }

    private static int sizeInfo(CommandSourceStack source) throws CommandSyntaxException {
        Field field = visibleField(source);
        long volume = field.volume();
        if (field.type() == FieldShape.Type.SPHERE) {
            source.sendSuccess(() -> Component.translatable("command.forcefields.size_info_sphere",
                    field.radius(), volume, Math.round(field.capacity()),
                    String.format(Locale.ROOT, "%.1f", FieldProjectorBlockEntity.stressImpact(volume, field.settings().totalUpgrades()))), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("command.forcefields.size_info",
                field.extent(Direction.UP), field.extent(Direction.DOWN), field.extent(Direction.NORTH),
                field.extent(Direction.SOUTH), field.extent(Direction.EAST), field.extent(Direction.WEST),
                volume, Math.round(field.capacity()),
                String.format(Locale.ROOT, "%.1f", FieldProjectorBlockEntity.stressImpact(volume, field.settings().totalUpgrades()))), false);
        return 1;
    }

    private static int setShape(CommandSourceStack source, FieldShape.Type type) throws CommandSyntaxException {
        Field field = managedField(source);
        return finish(source, field, FieldAdmin.setShape(source.getLevel(), field, type));
    }

    /** Sets how far the field reaches on one side, or on all sides when {@code side} is null. */
    private static int setSize(CommandSourceStack source, @Nullable Direction side, int blocks) throws CommandSyntaxException {
        Field field = managedField(source);
        FieldAdmin.Result result = side == null
                ? FieldAdmin.setReachAll(source.getLevel(), field, blocks)
                : FieldAdmin.setReach(source.getLevel(), field, side, blocks);
        return finish(source, field, result);
    }

    private static int adminAdd(CommandSourceStack source, String name) throws CommandSyntaxException {
        Field field = managedField(source);
        return finish(source, field, FieldAdmin.addAdmin(field, FieldAdmin.resolve(source.getServer(), name)));
    }

    private static int adminRemove(CommandSourceStack source, String name) throws CommandSyntaxException {
        Field field = managedField(source);
        return finish(source, field, FieldAdmin.removeAdminByName(field, name));
    }

    private static int adminList(CommandSourceStack source) throws CommandSyntaxException {
        Field field = visibleField(source);
        List<String> names = List.copyOf(field.settings().admins().values());
        source.sendSuccess(() -> names.isEmpty()
                ? Component.translatable("command.forcefields.admin_none")
                : Component.translatable("command.forcefields.admin_list", String.join(", ", names)), false);
        return names.size();
    }

    private static List<String> adminNames(CommandContext<CommandSourceStack> context) {
        try {
            return List.copyOf(visibleField(context.getSource()).settings().admins().values());
        } catch (CommandSyntaxException e) {
            return List.of();
        }
    }

    private static int trustList(CommandSourceStack source) throws CommandSyntaxException {
        Field field = visibleField(source);
        List<String> names = List.copyOf(field.settings().trusted().values());
        source.sendSuccess(() -> names.isEmpty()
                ? Component.translatable("command.forcefields.trust_none")
                : Component.translatable("command.forcefields.trust_list", String.join(", ", names)), false);
        return names.size();
    }

    private static List<String> trustedNames(CommandContext<CommandSourceStack> context) {
        try {
            return List.copyOf(visibleField(context.getSource()).settings().trusted().values());
        } catch (CommandSyntaxException e) {
            return List.of();
        }
    }

    /** Turns an operation result into command feedback, saving and syncing the field when it worked. */
    private static int finish(CommandSourceStack source, Field field, FieldAdmin.Result result) throws CommandSyntaxException {
        Component message = Component.translatable(result.key(), result.args().toArray());
        if (!result.ok()) {
            throw new SimpleCommandExceptionType(message).create();
        }
        changed(source.getLevel(), field);
        source.sendSuccess(() -> message, false);
        return 1;
    }

    // --- Info -------------------------------------------------------------------------------------------

    private static int info(CommandSourceStack source) throws CommandSyntaxException {
        Field field = visibleField(source);
        FieldSettings settings = field.settings();
        Function<FieldAction, Component> access = action -> Component.translatable(
                "access.forcefields." + settings.access(action).name().toLowerCase(Locale.ROOT));
        source.sendSuccess(() -> Component.translatable("command.forcefields.info",
                field.ownerName() == null ? "-" : field.ownerName(),
                access.apply(FieldAction.PLACE), access.apply(FieldAction.BREAK), access.apply(FieldAction.ENTER),
                settings.admins().size(), settings.trusted().size(), settings.interactBlocks().size(), settings.interactItems().size()), false);
        return 1;
    }

    // --- Field lookup -----------------------------------------------------------------------------------

    private static Field fieldAround(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer self = source.getPlayerOrException();
        for (Field field : FieldManager.get(source.getLevel()).all()) {
            if (field.coversPoint(self.position())) {
                return field;
            }
        }
        throw NOT_IN_FIELD.create();
    }

    /** The field the player stands in, if they may change it: its owner, or an operator. */
    private static Field managedField(CommandSourceStack source) throws CommandSyntaxException {
        Field field = fieldAround(source);
        if (!FieldAdmin.canManage(source.getPlayerOrException(), field)) {
            throw NOT_MANAGER.create();
        }
        return field;
    }

    /** The field the player stands in, if they may look at its settings: owner, trusted or an operator. */
    private static Field visibleField(CommandSourceStack source) throws CommandSyntaxException {
        Field field = fieldAround(source);
        if (!FieldAdmin.canView(source.getPlayerOrException(), field)) {
            throw NOT_MANAGER.create();
        }
        return field;
    }

    /** Saves a change and sends it to clients right away. */
    private static void changed(ServerLevel level, Field field) {
        FieldAdmin.changed(level, field);
    }

    // --- Operator testing tools -------------------------------------------------------------------------

    private static int setOwner(CommandSourceStack source, String name) throws CommandSyntaxException {
        Field field = fieldAround(source);
        ServerLevel level = source.getLevel();
        FieldAdmin.Profile profile = FieldAdmin.resolve(source.getServer(), name);

        if (level.getBlockEntity(field.core()) instanceof FieldProjectorBlockEntity core) {
            core.setOwner(profile.id(), profile.name());
        } else {
            field.setOwner(profile.id(), profile.name());
        }
        FieldManager.get(level).setDirty();
        source.sendSuccess(() -> Component.translatable("command.forcefields.owner_set",
                profile.name(), field.core().getX(), field.core().getY(), field.core().getZ()), true);
        return 1;
    }

    /** Clears the overload meter and ends any shutdown right away. */
    private static int reset(CommandSourceStack source) throws CommandSyntaxException {
        Field field = fieldAround(source);
        field.endOverload();
        FieldManager.get(source.getLevel()).setDirty();
        source.sendSuccess(() -> Component.translatable("command.forcefields.reset",
                field.core().getX(), field.core().getY(), field.core().getZ()), true);
        return 1;
    }

    /** Overloads the field at once. With {@code seconds} it stays down exactly that long; otherwise it cools normally. */
    private static int overload(CommandSourceStack source, int seconds) throws CommandSyntaxException {
        Field field = fieldAround(source);
        ServerLevel level = source.getLevel();
        field.forceOverheat(seconds);
        FieldManager.get(level).setDirty();
        FieldEvents.onOverheated(level, field);
        source.sendSuccess(() -> Component.translatable(
                seconds > 0 ? "command.forcefields.overload_timed" : "command.forcefields.overload",
                field.core().getX(), field.core().getY(), field.core().getZ(), seconds), true);
        return 1;
    }

    private FieldCommands() {
    }
}
