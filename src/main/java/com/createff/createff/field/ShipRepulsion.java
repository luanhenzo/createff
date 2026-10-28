package com.createff.createff.field;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createff.createff.Config;
import com.createff.createff.network.FieldRipple;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * Keeps ships out of fields that do not let them in. A ship is not an entity, so the wall that stops players and mobs
 * does not see it: it is pushed back through Sable's physics instead, as if the wall were a soft, springy surface.
 * Only a ship that is moving into the field is pushed, so one that is already parked inside stays where it is.
 *
 * <p>A ship may come in when it has someone aboard and everyone aboard may enter; an empty ship may not, since there is
 * nobody to ask. Everything that touches Sable's physics is here, and only {@link ShipSupport} calls it, after checking
 * that Sable is installed.
 */
final class ShipRepulsion {
    /** Ticks without contact after which the next touch is a new one; and the closing speed below which a touch makes no ripple. */
    private static final int CONTACT_GAP = 10;
    private static final double MIN_BLOW_SPEED = 0.3;

    /** Per contact: the tick it was last pushing and the closing speed of the blow it last rippled for. */
    private record Contact(long tick, double rippledSpeed) {
    }

    private static final Map<String, Contact> CONTACTS = new HashMap<>();

    static void tick(ServerLevel level, Collection<Field> fields) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        List<ServerSubLevel> ships = container.getAllSubLevels();
        if (ships.isEmpty()) {
            return;
        }
        collideFields(level, fields, ships);
        for (Field field : fields) {
            if (field.state() != FieldState.ONLINE || field.settings().access(FieldAction.ENTER) == Access.EVERYONE) {
                continue;
            }
            FieldShape shape = field.worldShape();
            SubLevelAccess own = SableCompanion.INSTANCE.getContaining(level, field.shape().center());
            for (ServerSubLevel ship : ships) {
                if (ship.isRemoved() || (own != null && own.getUniqueId().equals(ship.getUniqueId()))) {
                    continue;
                }
                if (!ship.boundingBox().intersects(shape.bounds()) || mayEnter(level, ship, field) || fitsGate(field, shape, ship)) {
                    continue;
                }
                push(level, field, shape, ship);
            }
        }
    }

    /**
     * Fields do not go through each other. When the walls of two fields that are not friends meet and at least one is
     * on a ship, the ships (or the one ship, if the other field is on the ground) are pushed apart along the line of
     * the overlap, each by its share of the mass, like two bumpers. Fields of the same owner, or whose owners are
     * trusted by each other, may overlap, so a ship can dock inside its own base.
     */
    static void collideFields(ServerLevel level, Collection<Field> fields, List<ServerSubLevel> ships) {
        List<Field> online = fields.stream().filter(field -> field.state() == FieldState.ONLINE).toList();
        for (int i = 0; i < online.size(); i++) {
            for (int j = i + 1; j < online.size(); j++) {
                Field a = online.get(i);
                Field b = online.get(j);
                if ((!a.onShip() && !b.onShip()) || friends(a, b)) {
                    continue;
                }
                FieldShape first = a.worldShape();
                FieldShape second = b.worldShape();
                if (!first.bounds().intersects(second.bounds())) {
                    continue;
                }
                FieldOverlap.Overlap overlap = FieldOverlap.between(first, second);
                if (overlap != null) {
                    separate(level, a, b, first, second, overlap, ships);
                }
            }
        }
    }

    private static boolean friends(Field a, Field b) {
        return (b.owner() != null && a.roleOf(b.owner()) != Role.OTHER) || (a.owner() != null && b.roleOf(a.owner()) != Role.OTHER);
    }

    @Nullable
    private static ServerSubLevel shipOf(ServerLevel level, Field field, List<ServerSubLevel> ships) {
        if (!field.onShip()) {
            return null;
        }
        SubLevelAccess on = SableCompanion.INSTANCE.getContaining(level, field.shape().center());
        if (on == null) {
            return null;
        }
        for (ServerSubLevel ship : ships) {
            if (ship.getUniqueId().equals(on.getUniqueId())) {
                return ship;
            }
        }
        return null;
    }

    private static void separate(ServerLevel level, Field a, Field b, FieldShape first, FieldShape second,
            FieldOverlap.Overlap overlap, List<ServerSubLevel> ships) {
        ServerSubLevel shipA = shipOf(level, a, ships);
        ServerSubLevel shipB = shipOf(level, b, ships);
        RigidBodyHandle handleA = shipA == null ? null : RigidBodyHandle.of(shipA);
        RigidBodyHandle handleB = shipB == null ? null : RigidBodyHandle.of(shipB);
        if (handleA == null && handleB == null) {
            return;
        }
        Vec3 normal = overlap.normal();
        // The speed of each along the line from the first towards the second; a field on the ground is not moving.
        double speedA = handleA == null ? 0 : along(handleA, normal);
        double speedB = handleB == null ? 0 : along(handleB, normal);
        double approach = speedB - speedA;
        double target = Mth.clamp(Config.REPEL_STIFFNESS.get() * overlap.depth(), Config.REPEL_MIN_SPEED.get(), Config.REPEL_MAX_SPEED.get());
        if (approach >= target) {
            return;
        }
        double change = Math.min(Config.REPEL_MAX_CHANGE.get(), target - approach);

        // The push is split by mass: with both free the pair acts as one body of the reduced mass, and a ship against a
        // field on the ground takes all of it.
        double massA = shipA == null ? 0 : shipA.getMassTracker().getMass();
        double massB = shipB == null ? 0 : shipB.getMassTracker().getMass();
        double impulse = handleA != null && handleB != null ? massA * massB / (massA + massB) * change : (handleA != null ? massA : massB) * change;
        if (handleA != null) {
            push(shipA, handleA, normal.scale(-impulse));
        }
        if (handleB != null) {
            push(shipB, handleB, normal.scale(impulse));
        }

        // Both walls ripple where they meet, bigger the harder they came together.
        long now = level.getGameTime();
        String key = a.core().asLong() + "x" + b.core().asLong();
        if (isNewBlow(key, now, -approach)) {
            Vec3 meeting = first.center().add(second.center()).scale(0.5);
            float size = (float) Math.min(2.5, 0.5 + Math.abs(approach) * 0.12);
            Vec3 onFirst = first.closestSurfacePoint(meeting);
            Vec3 onSecond = second.closestSurfacePoint(meeting);
            PacketDistributor.sendToPlayersNear(level, null, onFirst.x, onFirst.y, onFirst.z, 160.0,
                    new FieldRipple(onFirst.x, onFirst.y, onFirst.z, size, a.core().asLong()));
            PacketDistributor.sendToPlayersNear(level, null, onSecond.x, onSecond.y, onSecond.z, 160.0,
                    new FieldRipple(onSecond.x, onSecond.y, onSecond.z, size, b.core().asLong()));
        }
    }

    /**
     * Whether a closing speed is a new blow on a contact that is being pushed this tick, and so deserves a ripple. A
     * ship that keeps pressing on a wall makes one ripple when it arrives, not one every moment; a harder blow while
     * they are still touching, or a touch after a break, makes another. The speed of the last blow fades out, so that
     * bouncing off and coming back is felt again.
     */
    private static boolean isNewBlow(String key, long now, double speed) {
        Contact contact = CONTACTS.get(key);
        double rippled = contact == null || now - contact.tick() > CONTACT_GAP ? 0.0 : contact.rippledSpeed() * 0.9;
        boolean blow = speed > MIN_BLOW_SPEED && speed > rippled * 1.8;
        CONTACTS.put(key, new Contact(now, blow ? speed : rippled));
        return blow;
    }

    private static double along(RigidBodyHandle handle, Vec3 direction) {
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        return velocity.x * direction.x + velocity.y * direction.y + velocity.z * direction.z;
    }

    /** Adds a push, given in the world, to a ship's momentum; the physics takes it in the ship's own space. */
    private static void push(ServerSubLevel ship, RigidBodyHandle handle, Vec3 impulse) {
        Vector3d local = ship.logicalPose().transformNormalInverse(new Vector3d(impulse.x, impulse.y, impulse.z), new Vector3d());
        handle.applyLinearImpulse(local);
    }

    /** Whether the ship is at a gate that is open and is no bigger than the outline of it, so it may go through. */
    private static boolean fitsGate(Field field, FieldShape shape, ServerSubLevel ship) {
        if (field.settings().gates().isEmpty()) {
            return false;
        }
        BoundingBox3dc box = ship.boundingBox();
        return Gates.admits(field.settings().gates(), shape,
                new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
    }

    /** Whether the people aboard let the ship in: someone is aboard, and each of them may enter. */

    private static boolean mayEnter(ServerLevel level, ServerSubLevel ship, Field field) {
        boolean anyone = false;
        UUID id = ship.getUniqueId();
        for (ServerPlayer player : level.players()) {
            SubLevelAccess on = SableCompanion.INSTANCE.getTrackingSubLevel(player);
            SubLevelAccess riding = SableCompanion.INSTANCE.getVehicleSubLevel(player);
            if ((on != null && on.getUniqueId().equals(id)) || (riding != null && riding.getUniqueId().equals(id))) {
                anyone = true;
                if (!FieldCollisions.mayPass(player, field.settings(), field.owner())) {
                    return false;
                }
            }
        }
        return anyone;
    }

    private static void push(ServerLevel level, Field field, FieldShape shape, ServerSubLevel ship) {
        BoundingBox3dc box = ship.boundingBox();
        Vec3 center = new Vec3((box.minX() + box.maxX()) / 2, (box.minY() + box.maxY()) / 2, (box.minZ() + box.maxZ()) / 2);

        // How far the middle of the ship is from the wall (negative inside), and which way is out.
        boolean inside = shape.contains(center);
        Vec3 surface = shape.closestSurfacePoint(center);
        Vec3 toSurface = surface.subtract(center);
        double distance = inside ? -toSurface.length() : shape.distanceTo(center);
        Vec3 out = inside ? toSurface : center.subtract(surface);
        out = out.lengthSqr() < 1.0E-9 ? shape.outwardNormal(surface) : out.normalize();

        // The ship overlaps the wall by how far its own size reaches past its middle, in the direction of the wall.
        double reach = (box.maxX() - box.minX()) / 2 * Math.abs(out.x) + (box.maxY() - box.minY()) / 2 * Math.abs(out.y)
                + (box.maxZ() - box.minZ()) / 2 * Math.abs(out.z);
        double depth = reach - distance;
        if (depth <= 0) {
            return;
        }

        RigidBodyHandle handle = RigidBodyHandle.of(ship);
        if (handle == null || !handle.isValid()) {
            return;
        }
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        double along = velocity.x * out.x + velocity.y * out.y + velocity.z * out.z;
        if (FieldDebug.shipsEnabled() && level.getGameTime() % 10 == 0) {
            FieldDebug.sync(String.format(java.util.Locale.ROOT,
                    "repel ship %s: centre (%.2f, %.2f, %.2f) inside=%s distance=%.2f reach=%.2f depth=%.2f out=(%.2f, %.2f, %.2f) velocity=(%.2f, %.2f, %.2f) along=%.2f mass=%.1f -> %s",
                    ship.getUniqueId().toString().substring(0, 8), center.x, center.y, center.z, inside, distance, reach, depth,
                    out.x, out.y, out.z, velocity.x, velocity.y, velocity.z, along, ship.getMassTracker().getMass(),
                    out.y <= 0.5 && along > -0.02 ? "not moving in, left alone" : out.y > 0.5 ? "held as a floor" : "pushed"));
        }
        // A wall that faces up (up to a steep slope) is a floor for the ship to rest on; any other only matters when
        // something goes into it.
        boolean floor = out.y > 0.5;
        if (!floor && along > -0.02) {
            return;
        }

        double mass = ship.getMassTracker().getMass();
        Vector3d world = new Vector3d();
        if (floor) {
            // Held up like a floor: what sinks is stopped, and the ship is lifted out of the wall without bouncing, more
            // the deeper it is. And it is held back sideways a little, so that it does not skid over the top like ice.
            double change = Math.max(0.0, Math.min(Config.REPEL_MAX_CHANGE.get(), Mth.clamp(Config.FLOOR_STIFFNESS.get() * depth, 0.0, Config.FLOOR_MAX_SPEED.get()) - along));
            Vector3d sideways = new Vector3d(velocity.x - out.x * along, velocity.y - out.y * along, velocity.z - out.z * along);
            world.set(out.x * change - sideways.x * Config.FLOOR_FRICTION.get(), out.y * change - sideways.y * Config.FLOOR_FRICTION.get(),
                    out.z * change - sideways.z * Config.FLOOR_FRICTION.get()).mul(mass);
        } else {
            // Stop what goes in, and leave the ship moving out at a speed that grows with how deep it is.
            double target = Mth.clamp(Config.REPEL_STIFFNESS.get() * depth, Config.REPEL_MIN_SPEED.get(), Config.REPEL_MAX_SPEED.get());
            double change = Math.min(Config.REPEL_MAX_CHANGE.get(), target - along);
            world.set(out.x * change, out.y * change, out.z * change).mul(mass);
        }
        // The physics takes impulses in the ship's own space.
        Vector3d local = ship.logicalPose().transformNormalInverse(world, new Vector3d());
        handle.applyLinearImpulse(local);

        String key = ship.getUniqueId() + "@" + field.core().asLong();
        long now = level.getGameTime();
        if (isNewBlow(key, now, -along)) {
            float size = (float) Math.min(2.5, 0.5 + Math.abs(along) * 0.12);
            PacketDistributor.sendToPlayersNear(level, null, surface.x, surface.y, surface.z, 160.0,
                    new FieldRipple(surface.x, surface.y, surface.z, size, field.core().asLong()));
        }
    }

    private ShipRepulsion() {
    }
}
