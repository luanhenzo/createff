package com.createff.createff.field;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.block.FieldProjectorBlockEntity;

import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * The changes an owner can make to a field, shared by the commands and the screen so both enforce the same
 * rules. Operations return a {@link Result} instead of talking to the player; the caller decides how to show it.
 */
public final class FieldAdmin {
    /** Players farther than this from the core cannot change the field (the screen needs them nearby). */
    private static final double REACH = 16.0;

    /** A message key and its arguments, with whether the operation worked. */
    public record Result(boolean ok, String key, List<String> args) {
        public static Result ok(String key, String... args) {
            return new Result(true, key, List.of(args));
        }

        public static Result error(String key, String... args) {
            return new Result(false, key, List.of(args));
        }

        /** For messages that are already readable text, such as a parse error. */
        public static Result rawError(String message) {
            return new Result(false, "gui.createff.raw", List.of(message));
        }
    }

    public record Profile(UUID id, String name) {
    }

    /** Owners and admins may change a field, and operators too unless the server turned that off. Trusted players only use the area. */
    public static boolean canManage(Player player, Field field) {
        return canManage(field.settings(), field.owner(), player);
    }

    /** The same check for a settings copy, used by the client to decide what to enable. */
    public static boolean canManage(FieldSettings settings, @Nullable UUID owner, Player player) {
        Role role = settings.roleOf(player.getUUID(), owner);
        return role == Role.OWNER || role == Role.ADMIN || opMayManage(player);
    }

    /** Owners, admins, trusted players and operators may look at a field.s settings. */
    public static boolean canView(Player player, Field field) {
        return field.roleOf(player.getUUID()) != Role.OTHER || opMayManage(player);
    }

    private static boolean opMayManage(Player player) {
        return player.hasPermissions(2) && Config.OPS_MANAGE_ANY_FIELD.get();
    }

    public static boolean inReach(Player player, Field field) {
        return field.coversPoint(player.position())
                || player.distanceToSqr(field.core().getX() + 0.5, field.core().getY() + 0.5, field.core().getZ() + 0.5) <= REACH * REACH;
    }

    /** The power switch: off keeps the field down, on lets it come up when it has a core and power. */
    public static Result setPower(Field field, boolean on) {
        if (!field.settings().coreInstalled()) {
            return Result.error("command.createff.power_no_core");
        }
        field.settings().setEnabled(on);
        return Result.ok(on ? "command.createff.power_on" : "command.createff.power_off");
    }

    public static Result setAccess(Field field, FieldAction action, Access access) {
        field.settings().setAccess(action, access);
        return Result.ok("command.createff.access_set");
    }

    public static Result addTrusted(Field field, Profile profile) {
        if (profile.id().equals(field.owner())) {
            return Result.error("command.createff.trust_owner");
        }
        if (field.settings().admins().containsKey(profile.id())) {
            return Result.error("command.createff.trust_is_admin", profile.name());
        }
        boolean added = field.settings().addTrusted(profile.id(), profile.name());
        return Result.ok(added ? "command.createff.trust_added" : "command.createff.trust_already", profile.name());
    }

    public static Result removeTrustedByName(Field field, String name) {
        for (Map.Entry<UUID, String> trusted : field.settings().trusted().entrySet()) {
            if (trusted.getValue().equalsIgnoreCase(name)) {
                field.settings().removeTrusted(trusted.getKey());
                return Result.ok("command.createff.trust_removed", trusted.getValue());
            }
        }
        return Result.error("command.createff.trust_missing", name);
    }

    public static Result addAdmin(Field field, Profile profile) {
        if (profile.id().equals(field.owner())) {
            return Result.error("command.createff.trust_owner");
        }
        boolean added = field.settings().addAdmin(profile.id(), profile.name());
        return Result.ok(added ? "command.createff.admin_added" : "command.createff.admin_already", profile.name());
    }

    public static Result removeAdminByName(Field field, String name) {
        for (Map.Entry<UUID, String> admin : field.settings().admins().entrySet()) {
            if (admin.getValue().equalsIgnoreCase(name)) {
                field.settings().removeAdmin(admin.getKey());
                return Result.ok("command.createff.admin_removed", admin.getValue());
            }
        }
        return Result.error("command.createff.admin_missing", name);
    }

    /** Moves one side of the field by {@code delta} blocks. Fails rather than clamp, so the caller can say why. */
    public static Result resize(ServerLevel level, Field field, Direction side, int delta) {
        return setReach(level, field, side, field.extent(side) + delta);
    }

    /** Moves the radius of a sphere by {@code delta} blocks. */
    public static Result resizeRadius(ServerLevel level, Field field, int delta) {
        return setRadius(level, field, field.radius() + delta);
    }

    /** Sets how far the field reaches on one side. Only a cube has sides. */
    public static Result setReach(ServerLevel level, Field field, Direction side, int blocks) {
        if (field.type() != FieldShape.Type.CUBE) {
            return Result.error("command.createff.size_sphere");
        }
        Result invalid = checkReach(blocks);
        if (invalid != null) {
            return invalid;
        }
        int[] extents = field.extents();
        extents[side.get3DDataValue()] = blocks;
        if (overlapsOther(level, field, FieldShape.of(FieldShape.Type.CUBE, field.core(), extents, field.radius()))) {
            return Result.error("command.createff.size_overlap");
        }
        field.setExtent(side, blocks);
        return Result.ok("command.createff.size_changed", side.getName(), String.valueOf(blocks));
    }

    /** Sets the same reach on all six sides of a cube at once, or the radius of a sphere. All or nothing. */
    public static Result setReachAll(ServerLevel level, Field field, int blocks) {
        if (field.type() == FieldShape.Type.SPHERE) {
            return setRadius(level, field, blocks);
        }
        Result invalid = checkReach(blocks);
        if (invalid != null) {
            return invalid;
        }
        int[] extents = new int[6];
        java.util.Arrays.fill(extents, blocks);
        if (overlapsOther(level, field, FieldShape.of(FieldShape.Type.CUBE, field.core(), extents, field.radius()))) {
            return Result.error("command.createff.size_overlap");
        }
        for (Direction side : Direction.values()) {
            field.setExtent(side, blocks);
        }
        return Result.ok("command.createff.size_changed", "every", String.valueOf(blocks));
    }

    /** Sets the radius of a sphere. */
    public static Result setRadius(ServerLevel level, Field field, int blocks) {
        if (field.type() != FieldShape.Type.SPHERE) {
            return Result.error("command.createff.size_cube");
        }
        Result invalid = checkReach(blocks);
        if (invalid != null) {
            return invalid;
        }
        if (overlapsOther(level, field, FieldShape.of(FieldShape.Type.SPHERE, field.core(), field.extents(), blocks))) {
            return Result.error("command.createff.size_overlap");
        }
        field.setRadius(blocks);
        return Result.ok("command.createff.radius_changed", String.valueOf(blocks));
    }

    /**
     * Changes the shape of the field. A cube becomes the sphere of its smallest reach, and a sphere the cube of its
     * radius on every side, so the new field fits inside the old one whenever it can. Fails if that would overlap
     * another field.
     */
    public static Result setShape(ServerLevel level, Field field, FieldShape.Type type) {
        if (field.type() == type) {
            return Result.ok("command.createff.shape_same", shapeName(type));
        }
        int[] extents = field.extents();
        int radius = field.radius();
        if (type == FieldShape.Type.SPHERE) {
            radius = java.util.Arrays.stream(extents).min().orElse(1);
        } else {
            java.util.Arrays.fill(extents, radius);
        }
        if (overlapsOther(level, field, FieldShape.of(type, field.core(), extents, radius))) {
            return Result.error("command.createff.size_overlap");
        }
        if (type == FieldShape.Type.SPHERE) {
            field.setRadius(radius);
        } else {
            for (Direction side : Direction.values()) {
                field.setExtent(side, radius);
            }
        }
        field.setType(type);
        return Result.ok("command.createff.shape_changed", shapeName(type));
    }

    private static String shapeName(FieldShape.Type type) {
        return type.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Nullable
    private static Result checkReach(int blocks) {
        if (blocks < 1) {
            return Result.error("command.createff.size_min");
        }
        int max = Config.MAX_EXTENT.get();
        if (blocks > max) {
            return Result.error("command.createff.size_max", String.valueOf(max));
        }
        return null;
    }

    private static boolean overlapsOther(ServerLevel level, Field field, FieldShape candidate) {
        return FieldManager.get(level).overlaps(candidate.bounds(), field.core());
    }

    public static Result editList(Field field, boolean blocks, boolean add, String entry) {
        FieldSettings settings = field.settings();
        String text = entry.trim();
        boolean changed;
        try {
            changed = add
                    ? (blocks ? settings.addInteractBlock(text) : settings.addInteractItem(text))
                    : (blocks ? settings.removeInteractBlock(text) : settings.removeInteractItem(text));
        } catch (IllegalArgumentException e) {
            return Result.rawError(e.getMessage());
        }
        if (!changed) {
            return Result.error(add ? "command.createff.entry_exists" : "command.createff.entry_missing", text);
        }
        return Result.ok(add ? "command.createff.entry_added" : "command.createff.entry_removed", text);
    }

    /**
     * Finds a player by name: online, then the server's cache of known accounts. A name that is neither gets a
     * placeholder id, which lets operators set up test scenarios with players that do not exist.
     */
    public static Profile resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new Profile(online.getUUID(), online.getGameProfile().getName());
        }
        var cached = server.getProfileCache().get(name);
        if (cached.isPresent()) {
            return new Profile(cached.get().getId(), cached.get().getName());
        }
        return new Profile(UUIDUtil.createOfflinePlayerUUID(name), name);
    }

    /** Saves a change and sends it to clients right away. */
    public static void changed(ServerLevel level, Field field) {
        FieldManager.get(level).setDirty();
        if (level.getBlockEntity(field.core()) instanceof FieldProjectorBlockEntity core) {
            core.refreshStress();
            core.sendData();
        }
    }

    @Nullable
    public static Field fieldAtCore(ServerLevel level, net.minecraft.core.BlockPos core) {
        return FieldManager.get(level).get(core);
    }

    private FieldAdmin() {
    }
}
