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
*Currently not implemented.*

## Summoning Priority

If multiple conditions are met, the golem mode is determined by this priority order:
1. **Excavation** (2 chests on adjacent sides)
2. **Mining** (1 chest)
3. **Tower** (gold block below)
4. **Terraforming** (3x3 gold platform)
5. **Tree** (second gold block touching)
6. **Wall** (touching any non-air, non-snow block)
7. **Path** (default - no special configuration) 
