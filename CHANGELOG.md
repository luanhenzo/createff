# Changelog

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
