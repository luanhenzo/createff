# Changelog

## 0.2.0 (beta)

### Added

- Gates: the owner and admins cut openings in the wall of a field from the Gates tab of the projector screen. Aim at
  the wall, use to put the gate there, attack to cancel; the outline is drawn with Create's outliner, green when the
  player is close enough to change the field and red when not. A gate is a rectangle or an oval, up to
  `gateMaxWidth` by `gateMaxHeight` blocks, on a cube or a sphere, in a wall, a roof or a floor. Where it is open the
  wall is not there: people, mobs, arrows and blasts go through, whatever the entry rule says, while the protection of
  blocks and the rules for using things inside stay. The wall does not ripple, or ring, in the opening. A gate can be
  moved, resized, changed between the outlines, shut and opened again, and removed; `maxGatesPerField` in the config
  limits how many a field has (4 by default, 0 turns them off).
  On a cube a new gate snaps to the grid of blocks (hold shift to place it freely). Every gate has a glowing border
  that burns brighter while something is in the opening, and a dim red one when the gate is shut. With Create's
  wrench in hand, looking at a gate picks it: a list on the screen, like the one of the schematic tools, offers to move
  it, shut or open it, change its outline and remove it (it asks to be used twice, to be sure); the scroll wheel,
  with alt held, goes through it and use does the one chosen.
  Gates work on a field carried by a Sable ship too, on a cube or a sphere, and turn with the hull. A ship goes
  through an open gate when it is at the gate and no bigger than its outline seen along the way through; one that
  is bigger, or beside the gate, is held back by the wall as before.- Gate Controller: a block that opens and shuts one gate of a field with redstone. Used with an empty hand it goes
  through the gates of the field it stands in or near, and used while sneaking it turns its signal round. Only
  someone who may change the field can pick its gate.
- Brazilian Portuguese translation (`pt_br`). Shape and side names in messages and commands are translated too.
- Hoppers, hopper minecarts and any block that asks for a container's items or fluids (funnels, chutes, belts,
  mechanical arms, pipes of other mods) cannot reach a container inside a field they are not in. Blocks inside the
  field, and blocks taking things from inside to outside, are not affected. `blockTransfersAcrossWalls` in the config
  turns it off.
- A limit of force fields per player: 3 by default, `maxFieldsPerPlayer` in the config, 0 for no limit. Operators in
  creative mode are exempt. It is per player, not per faction, because the mod has no factions of its own.

### Internal

- Unit tests of where a gate leaves the wall open (cube, sphere, a box turned by a ship, a sphere on a ship, which
  ships fit through, and how gates are saved) run with the build.

### Known issues

- A projector on a Create contraption (bearing, piston, gantry, cart) has no field while the contraption moves, and
  gets it back when it stops. Fields on Sable ships are not affected.
- Transfers that do not happen during a block entity's tick (entities, moving contraptions, redstone pulses) are not
  stopped from reaching a container inside a field. Energy is not blocked, so cables can still bring power in.

## 0.1.0 (beta)

First playable version.

### Added

- Field Projector, Force Cores (3 tiers), Resistance, Veil and Aegis upgrades.
- A Field Projector with a glass chamber in a brass frame. It can lie along any axis, is placed like other Create
  shafts, is turned with a wrench, and passes rotation through from one shaft end to the other.
- The force core shows inside the glass as glowing, nested cubes in the colour of its tier. They spin and pulse with
  the shaft while the field is on, and sit dim and still when it is off or overloaded.
- Cube and sphere fields, sized in the projector screen or with commands.
- Overload model: explosions and projectiles add it, RPM cools it, 100% shuts the field down.
- Growing damage on a spot that is attacked again and again.
- Per-projectile damage: configurable list, with the projectile's own damage for arrows, tridents and Create Big
  Cannons shells.
- Roles, trusted players, per-field access rules and block/item allow-lists; protection of blocks, entities,
  pistons, fluids, dispensers, fire, teleports and mob griefing.
- Fields on Sable ships (cube as an oriented box): they follow the ship, protect it and can be attacked; ships
  that may not enter are pushed back, and fields of different ships collide.
- Ripples and heat map on the wall, with sounds, scaled to the blow.
- Mod id `createff`, command `/cff`.

### Known issues

- Hoppers (and other item transport) can still cross the wall.
- A vehicle driving over the top of a field's wall has little traction on it.
- Create Big Cannons munitions other than the common shells have not been tested against a wall, and the damage
  values for Power-enchanted bows, crossbows, tridents and shells are rough and need tuning in play.
- The number of fields per player or faction is not limited yet.
- Not done yet: more upgrades (cooling, shutdown time, explosion damage, reach), war declaration windows, an
  offline capacitor, an intruder redstone alert and gates in the wall.
- English only.
- Beta: configuration keys may change between versions.
