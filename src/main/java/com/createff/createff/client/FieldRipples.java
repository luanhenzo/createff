package com.createff.createff.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.createff.createff.Config;
import com.createff.createff.CreateFF;
import com.createff.createff.block.FieldProjectorBlockEntity;
import com.createff.createff.field.FieldCollisions;
import com.createff.createff.field.FieldShape;
import com.createff.createff.field.FieldState;
import com.createff.createff.field.ShipSupport;
import com.createff.createff.network.FieldRipple;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * A ripple on the wall of a field where something goes through it or is stopped by it, or where a blast or a projectile
 * hits it, like the wake behind a boat. The wall is flat or
 * round, so the ripple lives on a small square of it around the crossing: a grid of heights that obeys the wave
 * equation (each point is pulled towards the average of its neighbours and keeps the motion it had), which makes a
 * ring that spreads, bounces off nothing at the edges (they are damped) and fades out. The grid is drawn as small quads
 * on the wall, lighter where the wave is a crest and darker in a trough.
 *
 * <p>It is all on the client and is only to look at and listen to: each client notices what crosses the wall of the
 * fields it knows (players, mobs, items, vehicles), in the field's own frame, so a crossing caused by a ship moving
 * over something counts like one by walking. Blasts and projectile hits are told to it by the server.
 */
@EventBusSubscriber(modid = CreateFF.MODID, value = Dist.CLIENT)
public final class FieldRipples {
    /** Points along each side of a ripple's grid, and how far apart they are in blocks. */
    private static final int N = 29;
    private static final double SPACING = 0.3;
    private static final int LIFETIME = 40;
    private static final float MAX_SIZE = 3.0F;
    /** Squared wave speed in grid cells per tick: the wave crosses about 0.55 cells a tick. It must stay under 0.5. */
    private static final float SPEED_SQUARED = 0.18F;
    private static final float DAMPING = 0.955F;
    private static final float STRENGTH = 1.3F;
    private static final int MAX_PER_FIELD = 24;
    private static final int COOLDOWN_TICKS = 8;
    /**
     * A player who may not enter touches the wall when the gap between them and it is this small. Walking along it
     * needs a speed in blocks per tick; a bump is as big as how fast they go, up to this size.
     */
    private static final double TOUCH_GAP = 0.06;
    private static final double WALKING_SPEED = 0.04;
    private static final float BUMP_SIZE = 0.45F;
    /** How far a player may move in a tick and still be walking rather than being teleported. */
    private static final double MAX_STEP = 6.0;
    /** How much the wall is lifted off, so the ripple is not buried in the wall itself. */
    private static final double LIFT = 0.04;

    private static final List<Ripple> RIPPLES = new ArrayList<>();
    private static final Map<UUID, Vec3> LAST_POSITION = new HashMap<>();
    private static final Map<BlockPos, FieldShape> LAST_SHAPE = new HashMap<>();
    private static final Map<String, Long> LAST_SPAWN = new HashMap<>();
    private static long tickCount;

    /** One ripple: where it is on the field (in the field's own space, the ship's plot for a ship), and its heights. */
    private static final class Ripple {
        final BlockPos core;
        /** The grid's points on the wall, in the field's own space; null where the grid runs off the wall. */
        final Vec3[][] points = new Vec3[N][N];
        final Vec3[][] normals = new Vec3[N][N];
        float[][] now = new float[N][N];
        float[][] before = new float[N][N];
        int age;
        /** How long it lasts and how strongly it shows: a bigger blow lasts longer and is easier to see. */
        final int lifetime;
        final float gain;
        /** Where it is centred, in the field's own space, and how big the blow was. */
        final Vec3 origin;
        final float size;

        Ripple(BlockPos core, FieldShape plot, Vec3 at, Vec3 normal, float strength, double spacing, float size) {
            this.lifetime = Math.min(90, (int) (LIFETIME + 18 * size));
            this.gain = Mth.clamp(0.7F + 0.45F * size, 0.7F, 2.0F);
            this.core = core;
            this.origin = at;
            this.size = size;
            Vec3 up = Math.abs(normal.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 a = normal.cross(up).normalize();
            Vec3 b = normal.cross(a);
            int c = N / 2;
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    Vec3 flat = at.add(a.scale((i - c) * spacing)).add(b.scale((j - c) * spacing));
                    Vec3[] onWall = onWall(plot, flat, normal);
                    points[i][j] = onWall == null ? null : onWall[0];
                    normals[i][j] = onWall == null ? null : onWall[1];
                }
            }
            // A soft bump at the crossing, which then spreads out as a ring.
            double sigma = 1.3;
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    double r2 = (i - c) * (i - c) + (j - c) * (j - c);
                    float height = (float) (strength * Math.exp(-r2 / (2 * sigma * sigma)));
                    now[i][j] = height;
                    before[i][j] = height;
                }
            }
        }

        void step() {
            float[][] next = new float[N][N];
            for (int i = 1; i < N - 1; i++) {
                for (int j = 1; j < N - 1; j++) {
                    float around = now[i - 1][j] + now[i + 1][j] + now[i][j - 1] + now[i][j + 1] - 4 * now[i][j];
                    // Near the edge of the grid the wave is damped harder, so it dies instead of bouncing back.
                    float edge = Math.min(1.0F, Math.min(Math.min(i, j), Math.min(N - 1 - i, N - 1 - j)) / 6.0F);
                    next[i][j] = (2 * now[i][j] - before[i][j] + SPEED_SQUARED * around) * DAMPING * (0.8F + 0.2F * edge);
                }
            }
            before = now;
            now = next;
            age++;
        }
    }

    /**
     * The point of the wall under {@code flat} (a point of the plane around the crossing) and which way the wall faces
     * there, or null if it is off the wall. On a sphere it is straight out from the centre. On a box the plane is
     * folded over the edges: what lies past an edge goes on down the next face by the same distance, so a ripple near
     * an edge goes round it instead of stopping there, and near a corner it goes round both edges.
     */
    @Nullable
    private static Vec3[] onWall(FieldShape plot, Vec3 flat, Vec3 normal) {
        if (plot.isSphere()) {
            Vec3 away = flat.subtract(plot.center());
            if (away.lengthSqr() < 1.0E-9) {
                return null;
            }
            Vec3 out = away.normalize();
            return new Vec3[] { plot.center().add(out.scale(plot.sphereRadius())), out };
        }

        AABB box = plot.bounds();
        double[] low = { box.minX, box.minY, box.minZ };
        double[] high = { box.maxX, box.maxY, box.maxZ };
        double[] at = { flat.x, flat.y, flat.z };
        double[] facing = { normal.x, normal.y, normal.z };
        int axis = Math.abs(normal.x) > 0.5 ? 0 : Math.abs(normal.y) > 0.5 ? 1 : 2;
        double side = Math.signum(facing[axis]);
        double[] out = new double[3];
        out[axis] = side;

        // How far each of the other two directions runs past the face, and so how far to go on down the next one.
        double past = 0;
        boolean folded = false;
        for (int t = 0; t < 3; t++) {
            if (t == axis) {
                continue;
            }
            if (at[t] > high[t]) {
                past += at[t] - high[t];
                at[t] = high[t];
                out[t] += 1;
                folded = true;
            } else if (at[t] < low[t]) {
                past += low[t] - at[t];
                at[t] = low[t];
                out[t] -= 1;
                folded = true;
            }
        }
        double face = side > 0 ? high[axis] : low[axis];
        at[axis] = folded ? face - side * past : face;
        if (at[axis] < low[axis] - 1.0E-3 || at[axis] > high[axis] + 1.0E-3) {
            // Past the edge and further than the box is deep: nothing of the wall is there.
            return null;
        }
        if (folded) {
            out[axis] = 0;
        }
        Vec3 direction = new Vec3(out[0], out[1], out[2]).normalize();
        return new Vec3[] { new Vec3(at[0], at[1], at[2]), direction };
    }

    /** A field the client is tracking this tick: its shape now and a tick ago, and the box where something is near enough to matter. */
    private record Active(FieldProjectorBlockEntity core, FieldShape now, FieldShape last, AABB near) {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            RIPPLES.clear();
            LAST_POSITION.clear();
            LAST_SHAPE.clear();
            LAST_SPAWN.clear();
            TOUCHES.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        tickCount++;

        for (Iterator<Ripple> iterator = RIPPLES.iterator(); iterator.hasNext();) {
            Ripple ripple = iterator.next();
            ripple.step();
            if (ripple.age >= ripple.lifetime) {
                iterator.remove();
            }
        }

        boolean wanted = Config.CROSSING_RIPPLES.get() || Config.RIPPLE_SOUNDS.get();
        List<Active> active = new ArrayList<>();
        for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (core.getLevel() != level) {
                continue;
            }
            FieldShape now = core.worldShape();
            FieldShape last = LAST_SHAPE.getOrDefault(core.getBlockPos(), now);
            LAST_SHAPE.put(core.getBlockPos(), now);
            if (wanted && core.mirrorState() == FieldState.ONLINE) {
                active.add(new Active(core, now, last, now.bounds().inflate(MAX_STEP)));
            }
        }

        // Everything that moves around the fields: where it was a tick ago against where it is now. What a vehicle
        // carries is left to the vehicle, so a boat with a player in it makes one ripple, not two.
        Map<UUID, Vec3> positions = new HashMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!tracked(entity)) {
                continue;
            }
            Vec3 center = entity.getBoundingBox().getCenter();
            positions.put(entity.getUUID(), center);
            Vec3 before = LAST_POSITION.get(entity.getUUID());
            if (before == null) {
                continue;
            }
            for (Active field : active) {
                if (field.near().contains(center)) {
                    crossing(level, field, entity, before, center);
                    bump(level, field, entity, before, center);
                }
            }
        }
        LAST_POSITION.clear();
        LAST_POSITION.putAll(positions);
    }

    /** Whether to watch this entity: anything that goes through a wall, but not projectiles (they are told about by the server) or passengers. */
    private static boolean tracked(Entity entity) {
        return !entity.isRemoved() && !entity.isPassenger() && !entity.isSpectator()
                && !(entity instanceof Projectile) && !(entity instanceof ExperienceOrb) && !(entity instanceof Marker);
    }

    /** Starts a ripple if the entity went through the wall since the last tick, judged in the frame of the field. */
    private static void crossing(Level level, Active field, Entity entity, Vec3 before, Vec3 center) {
        FieldShape now = field.now();
        boolean wasIn = field.last().contains(before);
        boolean isIn = now.contains(center);
        if (wasIn == isIn) {
            return;
        }
        // Where it was, carried along with the field, so what counts is what it did relative to the wall.
        Vec3 carried = now.carry(field.last(), before);
        double step = carried.distanceTo(center);
        if (step > MAX_STEP || !cooledDown("cross", entity, field.core())) {
            return;
        }

        Vec3 inside = isIn ? center : carried;
        Vec3 hit = (isIn ? now.clip(carried, center) : now.clip(center, carried)).orElseGet(() -> now.surfacePointNear(inside));
        // The ripple grows with the size of what goes through and how fast: a cow makes more than an item.
        AABB box = entity.getBoundingBox();
        float body = (float) Math.cbrt(box.getXsize() * box.getYsize() * box.getZsize());
        strike(level, field.core(), hit, (float) Mth.clamp(0.2 + 0.45 * body + step * 1.5, 0.25, 1.6));
    }

    /**
     * Starts a ripple where a player who may not enter pushes against the wall, so whoever tries sees that the wall
     * is there and the others see who is trying. It needs the player to be at the wall and moving into it.
     */
    private static void bump(Level level, Active field, Entity entity, Vec3 before, Vec3 center) {
        if (!(entity instanceof Player player) || field.now().contains(center)) {
            return;
        }
        if (FieldCollisions.mayPass(player, field.core().mirrorSettings(), field.core().ownerUuid())) {
            return;
        }
        // Touching the wall: the point of the wall nearest the player, and the nearest point of the player to it.
        Vec3 wall = field.now().closestSurfacePoint(center);
        AABB box = player.getBoundingBox();
        Vec3 touch = new Vec3(Mth.clamp(wall.x, box.minX, box.maxX), Mth.clamp(wall.y, box.minY, box.maxY),
                Mth.clamp(wall.z, box.minZ, box.maxZ));
        if (wall.distanceTo(touch) > TOUCH_GAP) {
            return;
        }
        // Only when they do something against it. Pushing into it (the game says so for the local player) or getting
        // nearer to it is a blow, like a ship's: one ripple when it starts, another only for a harder one, not one
        // for every moment they keep pushing. Walking along it, or on top of it, makes a ripple every few steps.
        // Standing still on it, or beside it, makes nothing.
        double speed = Math.hypot(center.x - before.x, center.z - before.z);
        double closingSpeed = Math.max(0.0, field.last().distanceTo(before) - field.now().distanceTo(center));
        boolean pushing = player.horizontalCollision || closingSpeed > 0.01;
        String key = entity.getUUID() + "@" + field.core().getBlockPos().asLong();
        if (pushing) {
            if (!isNewTouch(key, closingSpeed)) {
                return;
            }
        } else if (speed > WALKING_SPEED) {
            TOUCHES.remove(key);
            if (!cooledDown("walk", entity, field.core(), WALK_COOLDOWN_TICKS)) {
                return;
            }
        } else {
            return;
        }
        strike(level, field.core(), wall, (float) Mth.clamp(0.25 + 2.0 * Math.max(speed, closingSpeed), 0.25, BUMP_SIZE));
    }

    /** A touch of the wall, and the closing speed of the blow that last made a ripple in it. */
    private record Touch(long tick, double rippledSpeed) {
    }

    private static final Map<String, Touch> TOUCHES = new HashMap<>();
    /** Ticks without pushing after which the next push is a new touch. */
    private static final int TOUCH_BREAK = 10;
    private static final int WALK_COOLDOWN_TICKS = 12;

    /**
     * Whether pushing on the wall this tick is a new touch, and so worth a ripple: the first after a break, or a harder
     * one than the last while it still goes on. The speed of the last blow fades, so that backing off a little and
     * pushing again is felt.
     */
    private static boolean isNewTouch(String key, double closingSpeed) {
        Touch touch = TOUCHES.get(key);
        boolean fresh = touch == null || tickCount - touch.tick() > TOUCH_BREAK;
        double rippled = fresh ? 0.0 : touch.rippledSpeed() * 0.9;
        boolean blow = fresh || (closingSpeed > 0.05 && closingSpeed > rippled * 1.8);
        TOUCHES.put(key, new Touch(tickCount, blow ? closingSpeed : rippled));
        return blow;
    }

    private static boolean cooledDown(String kind, Entity entity, FieldProjectorBlockEntity core) {
        return cooledDown(kind, entity, core, COOLDOWN_TICKS);
    }

    private static boolean cooledDown(String kind, Entity entity, FieldProjectorBlockEntity core, int ticks) {
        String key = kind + entity.getUUID() + "@" + core.getBlockPos().asLong();
        if (tickCount - LAST_SPAWN.getOrDefault(key, -100L) < ticks) {
            return false;
        }
        LAST_SPAWN.put(key, tickCount);
        return true;
    }

    /**
     * A place of a veiled wall that shows: {@code radius} blocks around {@code center}, as much as {@code strength}
     * (0 to 1) at the middle and fading out to nothing at the edge.
     */
    record Source(Vec3 center, float radius, float strength) {
    }

    /**
     * The parts of a veiled wall that show. A veiled wall is never shown whole: only a patch around the nearest point
     * to the player when they are close, and a patch around each ripple, a little wider than the ripple itself.
     */
    record Reveals(List<Source> sources) {
        boolean isEmpty() {
            return sources.isEmpty();
        }

        /** How much of the wall shows at a point of it, 0 to 1. */
        float at(double x, double y, double z) {
            float shown = 0.0F;
            for (Source source : sources) {
                double dx = x - source.center().x, dy = y - source.center().y, dz = z - source.center().z;
                float fall = 1.0F - (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / source.radius());
                if (fall > 0.0F) {
                    shown = Math.max(shown, source.strength() * fall * fall * (3.0F - 2.0F * fall));
                }
            }
            return shown;
        }

        /** The same sources in another space, for drawing a turned box in its own. */
        Reveals map(UnaryOperator<Vec3> into) {
            List<Source> mapped = new ArrayList<>(sources.size());
            for (Source source : sources) {
                mapped.add(new Source(into.apply(source.center()), source.radius(), source.strength()));
            }
            return new Reveals(mapped);
        }
    }

    /**
     * What shows of a veiled wall (sizes and distances from the config): a patch around the point of the wall nearest the player, as they get close to it
     * (from either side), and a patch around every ripple on it, which are the places it is crossed, touched or hit.
     */
    static Reveals reveals(FieldProjectorBlockEntity core, FieldShape shape, Vec3 camera, float partialTick) {
        List<Source> sources = new ArrayList<>();

        double distance = Math.abs(shape.signedDistance(camera));
        double nearest = Config.VEIL_NEAR_DISTANCE.get();
        double full = Math.min(Config.VEIL_FULL_DISTANCE.get(), nearest - 0.01);
        float near = (float) Mth.clamp((nearest - distance) / (nearest - full), 0.0, 1.0);
        if (near > 0.01F) {
            sources.add(new Source(shape.closestSurfacePoint(camera), Config.VEIL_PATCH_RADIUS.get().floatValue(), near * near * (3.0F - 2.0F * near)));
        }

        UnaryOperator<Vec3> toWorld = null;
        boolean mapped = false;
        for (Ripple ripple : RIPPLES) {
            if (!ripple.core.equals(core.getBlockPos())) {
                continue;
            }
            if (!mapped && core.getLevel() != null) {
                toWorld = ShipSupport.toWorldMapper(core.getLevel(), core.fieldShape(), partialTick);
                mapped = true;
            }
            Vec3 where = toWorld == null ? ripple.origin : toWorld.apply(ripple.origin);
            float left = Mth.clamp(1.6F * (1.0F - (ripple.age + partialTick) / ripple.lifetime), 0.0F, 1.0F);
            sources.add(new Source(where, (float) Math.min(Config.VEIL_RIPPLE_RADIUS_MAX.get(),
                    Config.VEIL_RIPPLE_RADIUS.get() + Config.VEIL_RIPPLE_RADIUS_PER_SIZE.get() * ripple.size), left));
        }
        return new Reveals(sources);
    }

    /** A blow on the wall at a point in the world: the ripple, and the sound, both as big as the blow. */
    private static void strike(Level level, FieldProjectorBlockEntity core, Vec3 worldPoint, float size) {
        // A veiled wall shows where it is struck by the ripple, so it gets one even when ripples are off.
        if (Config.CROSSING_RIPPLES.get() || core.mirrorSettings().veil()) {
            spawn(level, core, worldPoint, size);
        }
        if (Config.RIPPLE_SOUNDS.get()) {
            // Louder and lower the bigger it is: a ping for something small, a deep ring for a shell.
            float volume = Mth.clamp(0.2F + 0.35F * size, 0.15F, 1.4F);
            float pitch = Mth.clamp(1.7F - 0.4F * size, 0.55F, 1.7F);
            level.playLocalSound(worldPoint.x, worldPoint.y, worldPoint.z, SoundEvents.AMETHYST_BLOCK_RESONATE,
                    SoundSource.BLOCKS, volume, pitch, false);
        }
    }

    private static void spawn(Level level, FieldProjectorBlockEntity core, Vec3 worldPoint, float scale) {
        FieldShape plot = core.fieldShape();
        Vec3 local = ShipSupport.toPlot(level, plot, worldPoint);
        if (local == null) {
            local = worldPoint;
        }
        Vec3 normal;
        Vec3 at;
        if (plot.isSphere()) {
            Vec3 away = local.subtract(plot.center());
            if (away.lengthSqr() < 1.0E-9) {
                return;
            }
            normal = away.normalize();
            at = plot.center().add(normal.scale(plot.sphereRadius()));
        } else {
            // The face of the box nearest to the point, and the point put on it.
            AABB box = plot.bounds();
            double[] distance = { Math.abs(local.x - box.minX), Math.abs(local.x - box.maxX), Math.abs(local.y - box.minY),
                    Math.abs(local.y - box.maxY), Math.abs(local.z - box.minZ), Math.abs(local.z - box.maxZ) };
            int face = 0;
            for (int i = 1; i < 6; i++) {
                if (distance[i] < distance[face]) {
                    face = i;
                }
            }
            normal = switch (face) {
                case 0 -> new Vec3(-1, 0, 0);
                case 1 -> new Vec3(1, 0, 0);
                case 2 -> new Vec3(0, -1, 0);
                case 3 -> new Vec3(0, 1, 0);
                case 4 -> new Vec3(0, 0, -1);
                default -> new Vec3(0, 0, 1);
            };
            at = switch (face) {
                case 0 -> new Vec3(box.minX, local.y, local.z);
                case 1 -> new Vec3(box.maxX, local.y, local.z);
                case 2 -> new Vec3(local.x, box.minY, local.z);
                case 3 -> new Vec3(local.x, box.maxY, local.z);
                case 4 -> new Vec3(local.x, local.y, box.minZ);
                default -> new Vec3(local.x, local.y, box.maxZ);
            };
        }

        int own = 0;
        for (Ripple ripple : RIPPLES) {
            if (ripple.core.equals(core.getBlockPos())) {
                own++;
            }
        }
        if (own >= MAX_PER_FIELD) {
            for (Iterator<Ripple> iterator = RIPPLES.iterator(); iterator.hasNext();) {
                if (iterator.next().core.equals(core.getBlockPos())) {
                    iterator.remove();
                    break;
                }
            }
        }
        // A bigger blow makes a taller ripple that also covers more of the wall.
        float size = Math.min(scale, MAX_SIZE);
        RIPPLES.add(new Ripple(core.getBlockPos(), plot, at, normal, STRENGTH * size, SPACING + 0.2 * size, size));
    }

    /**
     * A blast or a projectile hit that the server told us about, at a point in the world: the ripple goes on the wall
     * nearest to it, smaller the farther the blast was from the wall.
     */
    public static void impact(double x, double y, double z, float size, long onlyCore) {
        Level level = Minecraft.getInstance().level;
        if (level == null || !(Config.CROSSING_RIPPLES.get() || Config.RIPPLE_SOUNDS.get())) {
            return;
        }
        Vec3 at = new Vec3(x, y, z);
        FieldProjectorBlockEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (core.getLevel() != level || core.mirrorState() != FieldState.ONLINE) {
                continue;
            }
            // The server says which field when two walls are at the same place, so each gets its own ripple.
            if (onlyCore != FieldRipple.ANY && core.getBlockPos().asLong() != onlyCore) {
                continue;
            }
            FieldShape world = core.worldShape();
            double distance = world.isSphere() ? Math.abs(at.distanceTo(world.center()) - world.sphereRadius()) : world.distanceTo(at);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = core;
            }
        }
        if (nearest == null || nearestDistance > 24.0) {
            return;
        }
        FieldShape world = nearest.worldShape();
        strike(level, nearest, world.closestSurfacePoint(at), (float) (size * Mth.clamp(1.0 - nearestDistance / 24.0, 0.3, 1.0)));
    }

    /**
     * Draws the ripples of one field, lighter than the wall at a crest and darker in a trough. The wall colour is the
     * one the field has now.
     */
    static void draw(VertexConsumer vc, Matrix4f m, FieldProjectorBlockEntity core, float partialTick, float red, float green, float blue) {
        if (RIPPLES.isEmpty() || core.getLevel() == null) {
            return;
        }
        UnaryOperator<Vec3> toWorld = null;
        boolean mapped = false;
        for (Ripple ripple : RIPPLES) {
            if (!ripple.core.equals(core.getBlockPos())) {
                continue;
            }
            if (!mapped) {
                toWorld = ShipSupport.toWorldMapper(core.getLevel(), core.fieldShape(), partialTick);
                mapped = true;
            }
            drawRipple(vc, m, ripple, toWorld, partialTick, red, green, blue);
        }
    }

    private static void drawRipple(VertexConsumer vc, Matrix4f m, Ripple ripple, @Nullable UnaryOperator<Vec3> toWorld,
            float partialTick, float red, float green, float blue) {
        // Halfway between this tick and the next, so the motion is smooth at any frame rate.
        float fade = Mth.clamp(1.0F - (ripple.age + partialTick) / ripple.lifetime, 0.0F, 1.0F);
        for (int i = 0; i < N - 1; i++) {
            for (int j = 0; j < N - 1; j++) {
                Vec3 p00 = ripple.points[i][j], p10 = ripple.points[i + 1][j];
                Vec3 p11 = ripple.points[i + 1][j + 1], p01 = ripple.points[i][j + 1];
                if (p00 == null || p10 == null || p11 == null || p01 == null) {
                    continue;
                }
                float h00 = ripple.now[i][j], h10 = ripple.now[i + 1][j], h11 = ripple.now[i + 1][j + 1], h01 = ripple.now[i][j + 1];
                if (Math.max(Math.max(Math.abs(h00), Math.abs(h10)), Math.max(Math.abs(h11), Math.abs(h01))) * fade < 0.03F) {
                    continue;
                }
                vertex(vc, m, ripple, toWorld, i, j, p00, h00, fade, red, green, blue);
                vertex(vc, m, ripple, toWorld, i + 1, j, p10, h10, fade, red, green, blue);
                vertex(vc, m, ripple, toWorld, i + 1, j + 1, p11, h11, fade, red, green, blue);
                vertex(vc, m, ripple, toWorld, i, j + 1, p01, h01, fade, red, green, blue);
            }
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Ripple ripple, @Nullable UnaryOperator<Vec3> toWorld, int i, int j,
            Vec3 point, float height, float fade, float red, float green, float blue) {
        Vec3 lifted = point.add(ripple.normals[i][j].scale(LIFT));
        Vec3 world = toWorld == null ? lifted : toWorld.apply(lifted);
        float v = Mth.clamp(height * fade, -1.0F, 1.0F);
        float r, g, b;
        if (v >= 0) {
            // A crest is lighter than the wall, towards white.
            r = Mth.lerp(v, red, 1.0F);
            g = Mth.lerp(v, green, 1.0F);
            b = Mth.lerp(v, blue, 1.0F);
        } else {
            // A trough is darker.
            float dim = 1.0F + 0.6F * v;
            r = red * dim;
            g = green * dim;
            b = blue * dim;
        }
        float alpha = Mth.clamp((0.06F + 0.6F * Math.abs(v)) * ripple.gain, 0.0F, 0.95F) * (0.5F + 0.5F * fade);
        vc.addVertex(m, (float) world.x, (float) world.y, (float) world.z).setColor(r, g, b, alpha);
    }

    private FieldRipples() {
    }
}
