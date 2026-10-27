package com.forcefields.forcefields.client;

import java.util.ArrayDeque;
import java.util.Iterator;

import org.jetbrains.annotations.Nullable;

import com.forcefields.forcefields.Config;
import com.forcefields.forcefields.block.FieldProjectorBlockEntity;
import com.forcefields.forcefields.field.FieldShape;
import com.forcefields.forcefields.field.ShipSupport;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The hits the client was told about lately, which the wall renderer paints as a heat map. A hit on the field of a
 * ship is remembered as a place on the ship, so the spot moves with it instead of staying where the hit was.
 */
public final class FieldHeat {
    /**
     * {@code at} is where the hit was in the world. For a hit on a field on a ship, {@code core} is the projector of
     * that field and {@code onShip} the place on the ship; both are null for a field on the ground.
     */
    public record Hit(Vec3 at, float focus, long tick, @Nullable BlockPos core, @Nullable Vec3 onShip) {
    }

    private static final int MAX_HITS = 256;
    private static final ArrayDeque<Hit> HITS = new ArrayDeque<>();

    public static void record(double x, double y, double z, float focus) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (HITS.size() >= MAX_HITS) {
            HITS.removeFirst();
        }
        Vec3 at = new Vec3(x, y, z);

        // The field the hit belongs to is the one whose wall is nearest to it; if that is a field on a ship, the hit
        // is kept as a place on the ship.
        BlockPos shipCore = null;
        Vec3 onShip = null;
        double nearest = Double.MAX_VALUE;
        BlockPos nearestCore = null;
        for (FieldProjectorBlockEntity core : FieldProjectorBlockEntity.CLIENT_CORES) {
            if (core.getLevel() != level) {
                continue;
            }
            FieldShape world = core.worldShape();
            double distance = world.isSphere() ? Math.abs(at.distanceTo(world.center()) - world.sphereRadius()) : world.distanceTo(at);
            if (distance < nearest) {
                nearest = distance;
                nearestCore = core.getBlockPos();
                Vec3 local = ShipSupport.toPlot(level, core.fieldShape(), at);
                shipCore = local == null ? null : core.getBlockPos();
                onShip = local;
            }
        }
        HITS.addLast(new Hit(at, focus, level.getGameTime(), shipCore, onShip));
    }

    /** How long a hit stays visible, the same time the server remembers it for. */
    public static long lifetime() {
        return Math.max(40L, Config.REPEAT_DAMAGE_SECONDS.get() * 20L);
    }

    /** The hits still showing at {@code now}, dropping the ones that have faded out. */
    public static Iterable<Hit> current(long now) {
        long lifetime = lifetime();
        Iterator<Hit> iterator = HITS.iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().tick() > lifetime) {
                iterator.remove();
            }
        }
        return HITS;
    }

    private FieldHeat() {
    }
}
