# Create Force Fields

**createff**: force fields as tangible claims for factions servers. NeoForge 1.21.1, powered by Create.

A **Field Projector** is a Create kinetic block. Spin it with enough rotation and it projects a cube or a sphere
around itself that blocks and protects everything inside. There is no health bar to chew through: explosions and
projectiles that hit the wall add **overload**, and at 100% the field shuts down, the claim is lifted and the
projector can be broken. Defenders keep it alive by spinning it faster and by making sure nobody hammers the same spot.

> **Status: beta (0.1.0).** It works and has been played, but see [Known issues](#known-issues).

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.264 or newer |
| Create | 6.0.x (required) |

Optional, detected at runtime:

- **Sable** (and Create Aeronautics / Simulated, which build on it): fields on a ship follow it, protect it and can be
  attacked. Without Sable the mod does not load any of its classes.
- **Create Big Cannons**: cannon shells are weighed by their own damage when they hit a wall.

Install the jar on **both the server and the clients**: the wall is predicted on the client.

## How it works

- **Field Projector** needs a **Force Core** in its slot; without one the field is offline. Cores come in three
  tiers (3 / 6 / 9 upgrade slots) and hold the upgrades inside them.
- **Shape and size** are set in the projector screen (click it): a cube with a reach per side, or a sphere with one
  radius centred on the projector.
- **Resistance** comes from the rotation speed. The more RPM, the more overload the field takes before shutting
  down, and the faster it cools. Size only changes the stress cost, never the resistance. Dropping the RPM keeps the
  overload percentage where it was.
- **Overload** is added by explosions (by power) and by projectiles that reach the wall (by their own damage and
  speed). Hits that keep landing on the same spot grow stronger, so attackers are rewarded for focusing a point and
  defenders are given a reason to stop them.
- At 100% the field shuts down for a while, then restarts at a lower overload with a short grace.
- **Who gets through**: the owner, admins and trusted players pass; everyone else meets a solid wall. Inside, blocks
  behave like bedrock for outsiders, with per-field rules for who may place, break and enter, plus allow-lists of
  blocks and items anyone may use. Pistons, fluids, dispensers, fire, ender pearls and mob griefing are kept out of
  claimed land.
- **Ships**: with Sable, a field on a ship moves with it. Ships that are not allowed in are pushed back, and fields of
  different ships push each other apart.
- **Visuals**: heat map where the wall was hit, ripples when something crosses or hits it, scaled to the blow.

### Upgrades

| Upgrade | Effect |
|---|---|
| Resistance | +25% resistance each; every installed upgrade adds 20% to the stress cost |
| Veil | The wall is only visible near the player, on touch or when attacked, and only as a local patch |
| Aegis | The wall cannot be overloaded. **Creative only** (no recipe) |

### Crafting

| Item | Recipe |
|---|---|
| Field Projector | obsidian, ender pearl, brass casing, shaft |
| Basic Force Core | obsidian, diamonds, brass casing |
| Advanced Force Core | precision mechanisms, diamonds, basic core |
| Elite Force Core | precision mechanisms, netherite ingots, advanced core |
| Resistance Upgrade | obsidian, diamonds, ender pearl |
| Veil Upgrade | glass, phantom membrane, ender pearl |

## Commands

`/cff` works on the field you are looking at or standing in.

| Command | Who | What |
|---|---|---|
| `info` | anyone | Shows the field's state |
| `trust add/remove/list`, `admin add/remove/list` | owner / admins | Manage people |
| `size`, `shape` | admins | Change the field |
| `rule ...` | admins | Access levels and block/item allow-lists |
| `owner`, `reset`, `overload` | ops | Administrative tools |
| `debug damage/ships on/off`, `debug where` | ops | Log how overload and ships are worked out |

## Configuration

Everything is in `createff-common.toml` (in `config/`): field size and stress, overload and cooling, explosion
and projectile damage, the per-type projectile damage list, ship push strength, ripple and veil options, and
whether ops manage any field. Projectile damage can be set by hand per entity type or tag (`id=damage@speed`).

Debug logging is on in a dev environment and off in a release; switch it with `debug.logDamage` / `debug.logShips`
or the `/cff debug` commands. With `logDamage` on, every hit prints the numbers behind it, which is the way
to tune the balance for your server.

## Known issues

See the [changelog](CHANGELOG.md) for the current list.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

All rights reserved for now.
