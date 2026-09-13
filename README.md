# mc-gold-golem

A Minecraft mod that adds a Gold Golem companion with various building modes.

To summon a Gold Golem, place a **Carved Pumpkin** on a **Gold Block**. The mode is determined by the configuration of blocks around the gold block.

## Building Modes

### Path Mode (Default)
The default building mode. The golem follows you as you walk and places blocks to create a path along your route. Path segments are queued as you move (every 4 meters traveled creates a new 3-meter segment). The golem alternates between its left and right hands when placing blocks.

**How to summon:** Place a Carved Pumpkin on a single Gold Block with no special configuration.

**How to use:** Walk while the golem is in Path mode to create a trail of blocks behind you.

---

### Wall Mode
Builds walls by tracking your movement and automatically selecting and placing wall modules. The golem chooses modules from available presets and places them as you move away from the anchor point.

**How to summon:** Place a Carved Pumpkin on a Gold Block that is touching any non-air, non-snow block on its sides (NORTH, SOUTH, EAST, WEST, or UP). Build a wall structure with gold marker blocks to define modules.

**How to use:** Walk in the direction you want the wall to be built. The golem will place wall modules at regular intervals.

---

### Tower Mode
Constructs towers at a fixed location using a predefined template. The golem builds the tower layer by layer from bottom to top, placing blocks systematically at each Y level before moving up. The height of the gold column determines the tower height.

**How to summon:** Place a Carved Pumpkin on a Gold Block with another Gold Block below it (vertical stack). Build a tower structure to be scanned as a template.

**How to use:** Set a tower origin and template. The golem will autonomously build the entire tower structure.

---

### Tree Mode
Uses a Wave Function Collapse (WFC) algorithm to procedurally generate organic tree-like structures. The golem follows you and uses your inventory blocks to build unique, natural-looking trees.

**How to summon:** Place a Carved Pumpkin on a Gold Block that has a second Gold Block touching it horizontally (NORTH, SOUTH, EAST, WEST) or above (UP). Build a tree structure with gold blocks separating modules.

**How to use:** Walk to define the tree location. If the golem runs out of inventory, it will wait angrily until you feed it more gold nuggets to resume.

---

### Terraforming Mode
Fills in terrain to create a shell structure layer by layer. The golem waits at a start position until activated with a gold nugget, then systematically places blocks to complete each layer before moving to the next Y level.

**How to summon:** Place a Carved Pumpkin on the center of a 3x3 platform of Gold Blocks. Build a skeleton structure to define the terraforming shell.

**How to use:** Define a terraforming origin and shell. Feed the golem a gold nugget to begin the terraforming process.

---

### Excavation Mode
Excavates (digs up) blocks from a designated start area and deposits them into chests. The golem operates in a state machine:
- Idle at start position (waiting for activation)
- Active excavation (digging blocks)
- Returning to chests to deposit when inventory is full

**How to summon:** Place a Carved Pumpkin on a Gold Block with **2 Chests** on adjacent (non-opposite) sides. The golem will excavate in the opposite diagonal direction.

**How to use:** Set excavation start position and chest locations. Feed the golem a gold nugget to begin excavation.

---

### Mining Mode
Mines ores in a specified direction from a start position. Similar to excavation, the golem returns to a chest to deposit mined resources when its inventory is full.

**How to summon:** Place a Carved Pumpkin on a Gold Block with **1 Chest** on one side. The golem will mine in the opposite direction.

**How to use:** Set a mining start position, direction, and chest location. Feed the golem a gold nugget to begin mining.

---

### Gradient Mode
Material gradients are available in the wall, tower, pyramid, and tree builders.

## Structure Building API

Other server-side mods can load a saved Gold Golem procedural template and build it immediately. The API does not
spawn a golem, consume inventory, mine blocks, or spread placement across ticks. It loads every target chunk and
places blocks only into air or replaceable states.

New wall, tower, pyramid, and tree snapshots contain a stable `publicTemplate` payload. A snapshot can be loaded
directly from its path, or copied into a mod/data pack at:

```text
data/<namespace>/gold-golem/templates/<path>.json
```

For example, `other-mod:oak_tower` resolves to
`data/other-mod/gold-golem/templates/oak_tower.json`.

```java
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import ninja.trek.mc.goldgolem.api.structure.BuildResult;
import ninja.trek.mc.goldgolem.api.structure.GoldGolemStructures;
import ninja.trek.mc.goldgolem.api.structure.TowerBuildRequest;

Identifier templateId = Identifier.fromNamespaceAndPath("other-mod", "oak_tower");
BuildResult result = GoldGolemStructures.loadAndBuild(
        serverLevel,
        templateId,
        new TowerBuildRequest(new BlockPos(100, 72, -40), 24)
);
```

Available requests are:

- `WallBuildRequest(guidePoints, maxModules)`: follows an ordered polyline as if a player walked through its points.
- `TowerBuildRequest(origin, height)`: repeats the captured tower module to a block height of 1–256.
- `PyramidBuildRequest(origin, height, curvature)`: resamples the tower module with curvature from -100 to 100.
- `TreeBuildRequest(origin, seed, maxTiles)`: runs deterministic WFC until its frontier ends or the safety cap is hit.
- `RoomBuildRequest(origin, initialDirection, seed, maxRooms, terrainPolicy)`: creates a small closed room layout.

Large dungeons use a separate two-phase API. Planning is side-effect free and returns local room placements and graph
statistics; building later aligns the unique entrance marker block to an exact world position. Special-room limits count
rooms containing a block, rather than the number of copies of that block.

```java
DungeonGenerationRequest dungeonRequest = DungeonGenerationRequest.builder()
        .seed(42L)
        .roomCount(new RoomCountGoal(40, 60, 80))
        .entranceMarker(ModBlocks.PORTAL_ANCHOR)
        .bossRule(new DungeonBossRule(ModBlocks.BOSS_MARKER, 20, 4))
        .addSpecialRoom(new SpecialRoomQuota(Blocks.ENCHANTING_TABLE, 1, 2))
        .addSpecialRoom(new SpecialRoomQuota(Blocks.ANVIL, 1, 3))
        .layout(DungeonLayoutProfile.defaults(DungeonLayoutStyle.LOOP_WITH_SPURS))
        .build();

DungeonPlanResult planned = GoldGolemDungeons.plan(roomTemplate, dungeonRequest);
if (planned.succeeded()) {
    DungeonEnvelope envelope = DungeonEnvelope.of(
            new DungeonEnvelopeLayer(Blocks.DEEPSLATE.defaultBlockState(), 8),
            new DungeonEnvelopeLayer(Blocks.BEDROCK.defaultBlockState(), 1)
    );
    BuildResult built = GoldGolemDungeons.build(
            serverLevel,
            planned.plan(),
            new DungeonPlacement(portalDestination, Direction.NORTH,
                    RoomTerrainPolicy.REQUIRE_CLEAR, envelope)
    );
}
```

Presets include `LOOP_WITH_SPURS`, `BRANCHING`, `BRAIDED`, `HUB_AND_SPOKE`, `GAUNTLET`, and `ORGANIC`.
Each preset can override compactness, branchiness, loopiness, dead-end frequency, and critical-path length. Every boss
socket is connected, and boss routes must reconnect to the entrance-side maze without using the boss room itself.

Captured material-gradient groups are included and sampled with the same world-seeded noise as golem builds. Empty
gradient slots and mine actions leave that position untouched; the instant API never breaks existing blocks.

Calls must run on the logical server thread. `BuildResult` reports generated and placed blocks, occupied positions that
were skipped, chunks loaded, diagnostics, and whether tree generation ended naturally or at its cap. The v1 format
preserves block IDs and block-state properties, but not block entities, inventories, signs, or entities.

## Summoning Priority

If multiple conditions are met, the golem mode is determined by this priority order:
1. **Excavation** (2 chests on adjacent sides)
2. **Mining** (1 chest)
3. **Tower** (gold block below)
4. **Terraforming** (3x3 gold platform)
5. **Tree** (second gold block touching)
6. **Wall** (touching any non-air, non-snow block)
7. **Path** (default - no special configuration) 
