package com.createff.createff.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Where a gate leaves the wall of a field open, tried without the game on the shapes it has to work for: the wall of a
 * cube, of a sphere, of a box turned by a ship and of a sphere on a ship.
 */
class GatesTest {
    private static final BlockPos CORE = new BlockPos(0, 64, 0);
    /** The middle of the projector block, which is the middle of a field around it. */
    private static final Vec3 CENTRE = new Vec3(0.5, 64.5, 0.5);

    private static FieldShape cube() {
        return FieldShape.of(FieldShape.Type.CUBE, CORE, new int[] { 8, 8, 8, 8, 8, 8 }, 8);
    }

    /** A sphere of radius 6, which reaches 6.5 blocks from the middle of the projector. */
    private static FieldShape sphere() {
        return FieldShape.of(FieldShape.Type.SPHERE, CORE, new int[6], 6);
    }

    private static Gate gate(Vec3 anchor, double width, double height, Gate.Shape shape) {
        return new Gate(1, anchor, width, height, shape, true);
    }

    // --- The wall of a cube ---------------------------------------------------------------------

    @Test
    void cubeGateLeavesTheWallOpenInsideItsOutlineOnly() {
        FieldShape shape = cube();
        // On the south face, 4 wide and 4 tall: the wall is at z = 9.
        Gate gate = gate(new Vec3(0, 0, 8), 4, 4, Gate.Shape.RECTANGLE);
        assertTrue(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 9.0)), "the middle of the gate");
        assertTrue(Gates.gaps(gate, shape, new Vec3(2.4, 66.4, 9.0)), "near a corner, inside");
        assertFalse(Gates.gaps(gate, shape, new Vec3(2.6, 64.5, 9.0)), "beside the gate");
        assertFalse(Gates.gaps(gate, shape, new Vec3(0.5, 66.6, 9.0)), "above the gate");
        assertFalse(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 11.0)), "far in front of the wall");
        assertFalse(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 6.0)), "far behind the wall");
        assertTrue(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 10.2)), "a little in front of the wall");
    }

    @Test
    void ovalGateCutsTheCornersOfItsRectangle() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 0, 8), 4, 4, Gate.Shape.OVAL);
        assertTrue(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 9.0)));
        assertTrue(Gates.gaps(gate, shape, new Vec3(2.4, 64.5, 9.0)), "the middle of a side");
        assertFalse(Gates.gaps(gate, shape, new Vec3(2.3, 66.3, 9.0)), "a corner of the rectangle is outside the oval");
    }

    @Test
    void gateInTheRoofLiesFlat() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 8, 0), 4, 4, Gate.Shape.RECTANGLE);
        Gates.Frame frame = Gates.frame(gate, shape);
        assertNotNull(frame);
        assertEquals(1.0, frame.normal().y, 1.0E-9, "faces up");
        assertTrue(Gates.gaps(gate, shape, new Vec3(0.5, 73.0, 0.5)), "the middle of the gate in the roof");
        assertFalse(Gates.gaps(gate, shape, new Vec3(5.0, 73.0, 0.5)), "beside it");
    }

    @Test
    void aShutGateLeavesTheWallWhole() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 0, 8), 4, 4, Gate.Shape.RECTANGLE).withOpen(false);
        Vec3 point = new Vec3(0.5, 64.5, 9.0);
        // The point is in the outline, but the gate is shut: only the open ones let anything through.
        assertFalse(Gates.opensAt(List.of(gate), shape, point));
        assertTrue(Gates.opensAt(List.of(gate.withOpen(true)), shape, point));
        assertTrue(Gates.opening(List.of(gate), shape).isEmpty());
    }

    @Test
    void aBodyAtTheGateIsLetThroughAndOneBesideItIsNot() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 0, 8), 4, 4, Gate.Shape.RECTANGLE);
        // A player, 0.6 wide and 1.8 tall, standing in the doorway on the ground of the gate.
        AABB inTheGate = new AABB(0.2, 63.0, 8.7, 0.8, 64.8, 9.3);
        AABB besideTheGate = new AABB(3.5, 63.0, 8.7, 4.1, 64.8, 9.3);
        assertTrue(Gates.opens(List.of(gate), shape, inTheGate));
        assertFalse(Gates.opens(List.of(gate), shape, besideTheGate));
    }

    // --- The wall of a sphere -------------------------------------------------------------------

    @Test
    void sphereGateFollowsTheCurveOfTheWall() {
        FieldShape shape = sphere();
        Gate gate = gate(new Vec3(0, 0, 6.5), 4, 4, Gate.Shape.RECTANGLE);
        Gates.Frame frame = Gates.frame(gate, shape);
        assertNotNull(frame);
        assertEquals(7.0, frame.wall().z, 1.0E-9);
        assertTrue(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, 7.0)), "the middle of the gate");
        // 1.5 blocks across, on the wall: the sphere is a little closer to the middle there.
        double z = 0.5 + Math.sqrt(6.5 * 6.5 - 1.5 * 1.5);
        assertTrue(Gates.gaps(gate, shape, new Vec3(2.0, 64.5, z)), "on the wall inside the outline");
        double farZ = 0.5 + Math.sqrt(6.5 * 6.5 - 3.0 * 3.0);
        assertFalse(Gates.gaps(gate, shape, new Vec3(3.5, 64.5, farZ)), "on the wall outside the outline");
        assertFalse(Gates.gaps(gate, shape, new Vec3(0.5, 64.5, -6.0)), "the other side of the sphere");
    }

    @Test
    void sphereGateBoundsHoldTheWholeGap() {
        FieldShape shape = sphere();
        Gate gate = gate(new Vec3(0, 0, 6.5), 4, 4, Gate.Shape.OVAL);
        AABB bounds = Gates.gapBounds(gate, shape);
        // Points of the gap on the wall: the middle and the ends of both axes of the oval.
        for (double[] at : new double[][] { { 0, 0 }, { 1.9, 0 }, { -1.9, 0 }, { 0, 1.9 }, { 0, -1.9 } }) {
            double z = 0.5 + Math.sqrt(6.5 * 6.5 - at[0] * at[0] - at[1] * at[1]);
            Vec3 point = new Vec3(0.5 + at[0], 64.5 + at[1], z);
            assertTrue(Gates.gaps(gate, shape, point), "in the gap");
            assertTrue(bounds.contains(point), "in the bounds of the gap");
        }
    }

    @Test
    void aimingFromOutsideGivesTheAnchorThatPutsTheGateWhereTheLookMeetsTheWall() {
        FieldShape shape = sphere();
        Vec3 eye = new Vec3(0.5, 64.5, 20.0);
        Vec3 hit = Gates.aim(shape, eye, new Vec3(0, 0, -1), 64.0).orElseThrow();
        assertEquals(7.0, hit.z, 1.0E-9);
        Vec3 anchor = Gates.anchorAt(shape, hit);
        assertEquals(0.0, anchor.x, 1.0E-9);
        assertEquals(6.5, anchor.z, 1.0E-9);
        Gates.Frame frame = Gates.frame(gate(anchor, 3, 3, Gate.Shape.RECTANGLE), shape);
        assertNotNull(frame);
        assertEquals(hit.z, frame.wall().z, 1.0E-9);
        // From inside the look meets the same wall going out.
        Vec3 fromInside = Gates.aim(shape, new Vec3(0.5, 64.5, 0.5), new Vec3(0, 0, 1), 64.0).orElseThrow();
        assertEquals(7.0, fromInside.z, 1.0E-9);
    }

    // --- A field on a ship ----------------------------------------------------------------------

    @Test
    void gateOnABoxTurnedByAShipTurnsWithIt() {
        Quaterniond quarterTurn = new Quaterniond().rotateY(Math.PI / 2);
        Vec3 middle = new Vec3(100, 70, 100);
        FieldShape ship = FieldShape.orientedBox(CORE, middle, quarterTurn, new Vec3(5, 5, 5), Vec3.ZERO);
        // On the box's own +z face, which the turn puts on the +x side.
        Gate gate = gate(new Vec3(0, 0, 5), 4, 4, Gate.Shape.RECTANGLE);
        Gates.Frame frame = Gates.frame(gate, ship);
        assertNotNull(frame);
        assertEquals(1.0, frame.normal().x, 1.0E-9);
        assertTrue(Gates.gaps(gate, ship, new Vec3(105, 70, 100)), "where the gate is now");
        assertFalse(Gates.gaps(gate, ship, new Vec3(100, 70, 105)), "where it would be if the ship had not turned");
        // In the box's own space, which is how its wall is drawn, it is on the +z face.
        Gates.Frame drawn = Gates.localFrame(gate, ship);
        assertNotNull(drawn);
        assertEquals(1.0, drawn.normal().z, 1.0E-9);
        assertEquals(5.0, drawn.wall().z, 1.0E-9);
    }

    @Test
    void gateOnABoxKeepsItsPlaceFromTheProjectorWhenTheBoxIsNotCentredOnIt() {
        // The box reaches 4 blocks to the west of the projector and 8 to the east, so its middle is 2 east of it.
        FieldShape ship = FieldShape.orientedBox(CORE, new Vec3(100, 70, 100), new Quaterniond(), new Vec3(6, 6, 6), new Vec3(-2, 0, 0));
        Gate gate = gate(new Vec3(0, 0, 6), 2, 2, Gate.Shape.RECTANGLE);
        // From the projector the gate is straight south of it, so 2 west of the middle of the box.
        assertTrue(Gates.gaps(gate, ship, new Vec3(98, 70, 106)));
        assertFalse(Gates.gaps(gate, ship, new Vec3(100, 70, 106)));
    }

    @Test
    void anchorAtGivesBackThePlaceOnABoxTurnedByAShip() {
        Quaterniond turn = new Quaterniond().rotateY(Math.PI / 2);
        FieldShape ship = FieldShape.orientedBox(CORE, new Vec3(100, 70, 100), turn, new Vec3(5, 5, 5), new Vec3(-1, 0, 0));
        Gate gate = gate(new Vec3(1, 1, 5), 3, 3, Gate.Shape.RECTANGLE);
        Gates.Frame frame = Gates.frame(gate, ship);
        assertNotNull(frame);
        Vec3 anchor = Gates.anchorAt(ship, frame.wall());
        assertEquals(1.0, anchor.x, 1.0E-9);
        assertEquals(1.0, anchor.y, 1.0E-9);
        assertEquals(5.0, anchor.z, 1.0E-9);
    }

    @Test
    void gateOnASphereOnAShipTurnsWithTheShip() {
        Quaterniond quarterTurn = new Quaterniond().rotateY(Math.PI / 2);
        FieldShape ship = FieldShape.sphereAt(new Vec3(100, 70, 100), 6.5, quarterTurn);
        Gate gate = gate(new Vec3(0, 0, 6.5), 4, 4, Gate.Shape.RECTANGLE);
        assertTrue(Gates.gaps(gate, ship, new Vec3(106.5, 70, 100)), "the gate is on the +x side now");
        assertFalse(Gates.gaps(gate, ship, new Vec3(100, 70, 106.5)), "not where it was before the ship turned");
        Vec3 anchor = Gates.anchorAt(ship, new Vec3(106.5, 70, 100));
        assertEquals(6.5, anchor.z, 1.0E-9);
        assertEquals(0.0, anchor.x, 1.0E-9);
    }

    // --- Ships through a gate --------------------------------------------------------------------

    @Test
    void aShipThatFitsTheOutlineGoesThroughAndOneThatDoesNotIsHeldBack() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 0, 8), 6, 6, Gate.Shape.RECTANGLE);
        // Boxes across the wall at z = 9, 4 and 8 wide.
        AABB small = new AABB(-1.5, 62.5, 5.0, 2.5, 66.5, 13.0);
        AABB wide = new AABB(-3.5, 62.5, 5.0, 4.5, 66.5, 13.0);
        AABB beside = new AABB(1.5, 62.5, 5.0, 5.5, 66.5, 13.0);
        assertTrue(Gates.admits(List.of(gate), shape, small));
        assertFalse(Gates.admits(List.of(gate), shape, wide), "wider than the gate");
        assertFalse(Gates.admits(List.of(gate), shape, beside), "part of it would meet the wall beside the gate");
        assertFalse(Gates.admits(List.of(gate.withOpen(false)), shape, small), "a shut gate lets nothing through");
        // Not at the gate at all.
        assertFalse(Gates.admits(List.of(gate), shape, small.move(0, 0, -12)));
    }

    @Test
    void anOvalGateOnlyAdmitsWhatFitsInsideTheOval() {
        FieldShape shape = cube();
        Gate gate = gate(new Vec3(0, 0, 8), 6, 6, Gate.Shape.OVAL);
        AABB square = new AABB(-1.4, 63.1, 5.0, 2.4, 66.9, 13.0);
        AABB narrow = new AABB(-0.4, 62.0, 5.0, 1.4, 67.0, 13.0);
        assertFalse(Gates.admits(List.of(gate), shape, square), "the corners of a square stick out of an oval");
        assertTrue(Gates.admits(List.of(gate), shape, narrow.inflate(0.0, -1.2, 0.0)), "a tall narrow one fits");
    }

    // --- Saving ---------------------------------------------------------------------------------

    @Test
    void aGateComesBackFromItsSavedForm() {
        Gate gate = new Gate(7, new Vec3(1.25, -2.5, 6.0), 3.5, 4.0, Gate.Shape.OVAL, false);
        Gate loaded = Gate.load(gate.save());
        assertEquals(gate, loaded);
    }

    @Test
    void gateIdsAreNeverUsedTwiceInAField() {
        FieldSettings settings = FieldSettings.load(new net.minecraft.nbt.CompoundTag());
        Gate first = settings.addGate(gate(new Vec3(0, 0, 1), 3, 3, Gate.Shape.RECTANGLE));
        Gate second = settings.addGate(gate(new Vec3(0, 0, -1), 3, 3, Gate.Shape.RECTANGLE));
        assertEquals(1, first.id());
        assertEquals(2, second.id());
        assertTrue(settings.removeGate(0));
        Gate third = settings.addGate(gate(new Vec3(1, 0, 0), 3, 3, Gate.Shape.RECTANGLE));
        assertEquals(3, third.id(), "the id of a removed gate is not given again");
        assertNotNull(settings.gateById(2));
        assertEquals(null, settings.gateById(1));

        FieldSettings loaded = FieldSettings.load(settings.save());
        assertEquals(2, loaded.gates().size());
        assertEquals(4, loaded.addGate(gate(new Vec3(0, 1, 0), 3, 3, Gate.Shape.RECTANGLE)).id(), "the next id is kept");
    }

    @Test
    void gatesSavedBeforeTheyHadIdsGetOne() {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < 2; i++) {
            net.minecraft.nbt.CompoundTag old = gate(new Vec3(0, 0, 1 + i), 3, 3, Gate.Shape.RECTANGLE).save();
            old.remove("Id");
            list.add(old);
        }
        tag.put("Gates", list);
        FieldSettings loaded = FieldSettings.load(tag);
        assertEquals(2, loaded.gates().size());
        assertFalse(loaded.gates().get(0).id() == loaded.gates().get(1).id());
        assertTrue(loaded.gates().get(0).id() > 0 && loaded.gates().get(1).id() > 0);
    }
}
