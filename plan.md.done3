# Block Placement State Preservation - Implementation Plan

## Objective
Fix block placement so that:
1. When gradient sampling returns the same block type as template → copy exact BlockState
2. When blocks have identical property sets (e.g., stairs → stairs) → copy all properties
3. Otherwise → simulate player placement with deterministic random direction

This ensures:
- Cobblestone walls connect properly to adjacent blocks
- Redstone components maintain correct states
- Material substitutions (oak_stairs → birch_stairs) preserve orientation
- Different block types get proper player-like placement

---

## Problem Analysis

### Current Issues:
1. `getBlockStateFromId()` returns only `getDefaultState()` → loses all properties
2. `world.setBlockState(pos, state, 3)` doesn't call `getPlacementState()` → no auto-connection
3. No distinction between "same block family" and "different block type"

### Root Cause:
Gradient sampling always uses `getDefaultState()`, losing properties like:
- Wall connections (north, south, east, west, up)
- Stair orientation (facing, half, shape)
- Redstone power/connections
- Trapdoor/door states (facing, open, powered)

---

## Solution Design

### Three-Tier Placement Logic:

```
1. Get template BlockState at position
2. Apply gradient sampling → get target Block
3. Compare:

   A. EXACT SAME BLOCK (block types match)?
      → Copy exact BlockState from template
      → Apply rotation/mirroring (wall mode only)
      → Place

   B. SAME PROPERTY SET (different blocks, same properties)?
      → Copy all properties from template to new block
      → Apply rotation/mirroring (wall mode only)
      → Place

   C. DIFFERENT BLOCK FAMILY?
      → Simulate player placement with seeded random
      → Call Block.getPlacementState() for auto-connections
      → Place
```

### Property Set Matching Logic:

```java
Set<Property<?>> templateProps = templateState.getProperties();
Set<Property<?>> newProps = newBlock.getDefaultState().getProperties();

if (templateProps.equals(newProps)) {
    // Copy all properties from template
    BlockState result = newBlock.getDefaultState();
    for (Property<?> prop : templateProps) {
        result = copyProperty(templateState, result, prop);
    }
    return result;
}
```

This handles:
- `oak_stairs` → `birch_stairs` ✓ (same properties)
- `cobblestone_wall` → `brick_wall` ✓ (same properties)
- `oak_trapdoor` → `iron_trapdoor` ✓ (same properties)
- `cobblestone` → `cobblestone_wall` ✗ (different properties, use player placement)

---

## Implementation Steps

### Step 1: Create Helper Method - `getPlacementStateForBlock()`

**Location**: `GoldGolemEntity.java`

**Signature**:
```java
private BlockState getPlacementStateForBlock(
    BlockPos pos,
    Block targetBlock,
    BlockState templateState,
    int rotation,      // 0-3 for wall mode, 0 for others
    boolean mirror     // wall mode only
)
```

**Logic**:
```java
1. Get template block from templateState
2. Get target block properties

3. IF templateBlock == targetBlock:
   - Use templateState directly
   - Apply rotation/mirroring if provided
   - Return

4. IF property sets match:
   - Start with targetBlock.getDefaultState()
   - Copy all properties from templateState
   - Apply rotation/mirroring if provided
   - Return

5. ELSE (different block families):
   - Generate seeded random from: world.getSeed() + pos.asLong()
   - Pick random direction (0-3 for N/E/S/W)
   - Pick random hit side (0-5 for DOWN/UP/NORTH/SOUTH/WEST/EAST)
   - Create fake ItemPlacementContext
   - Call targetBlock.getPlacementState(context)
   - If null, fall back to getDefaultState()
   - Apply rotation/mirroring if provided
   - Return
```

### Step 2: Create Property Copier Helper

**Location**: `GoldGolemEntity.java` (private method)

**Signature**:
```java
@SuppressWarnings("unchecked")
private static <T extends Comparable<T>> BlockState copyProperty(
    BlockState source,
    BlockState target,
    Property<T> property
)
```

**Purpose**: Safely copy a property value from source to target state

### Step 3: Create Fake ItemPlacementContext

**Location**: `GoldGolemEntity.java` (private method)

**Signature**:
```java
private ItemPlacementContext createFakePlacementContext(
    BlockPos pos,
    Direction horizontalFacing,  // NORTH/SOUTH/EAST/WEST
    Direction hitSide            // Which side was "clicked"
)
```

**Purpose**: Create context for `getPlacementState()` call that simulates player placement

### Step 4: Update `placeBlockFromInventory()` Method

**Location**: `GoldGolemEntity.java:1806`

**Changes**:
```java
// Before placing, get proper state
BlockState finalState = getPlacementStateForBlock(
    pos,
    state.getBlock(),
    state,  // templateState
    0,      // no rotation for tower/path mode
    false   // no mirror for tower/path mode
);

// Then place
this.getEntityWorld().setBlockState(pos, finalState);
```

### Step 5: Update `placeBlockStateAt()` Method

**Location**: `GoldGolemEntity.java:3132`

**Changes**:
```java
// After gradient sampling determines the block to place:
BlockState finalState = getPlacementStateForBlock(
    pos,
    block,        // target block (possibly from gradient)
    baseState,    // template state (original)
    rot,          // wall rotation
    mirror        // wall mirror
);

// Apply waterlogging fix
if (finalState.contains(Properties.WATERLOGGED)) {
    finalState = finalState.with(Properties.WATERLOGGED, false);
}

// Then place
world.setBlockState(pos, finalState, 3);
```

### Step 6: Update `placeTowerBlock()` in TowerBuildStrategy

**Location**: `TowerBuildStrategy.java:284`

**Current Flow**:
```java
BlockState targetState = getTowerBlockStateAt(template, origin, pos);
// Apply gradient sampling...
return golem.placeBlockFromInventory(pos, sampledState, nextPos, isLeftHandActive());
```

**Change**: The `placeBlockFromInventory()` method will now receive both:
- `targetState` from template
- `sampledState` from gradient (might be same or different block)

**New signature needed**:
```java
public boolean placeBlockFromInventory(
    BlockPos pos,
    BlockState templateState,  // NEW: original template state
    BlockState targetState,    // NEW: state to place (after gradient)
    BlockPos nextPos,
    boolean isLeft
)
```

Or keep existing and add overload:
```java
public boolean placeBlockFromInventoryWithTemplate(
    BlockPos pos,
    BlockState templateState,
    BlockState gradientState,
    BlockPos nextPos,
    boolean isLeft
)
```

### Step 7: Test Cases

**Test scenarios**:
1. Tower with `cobblestone_wall` template + `cobblestone_wall` gradient → exact copy
2. Tower with `cobblestone` template + `cobblestone_wall` gradient → player placement
3. Wall with `oak_stairs` template + `birch_stairs` gradient → property copy
4. Wall with rotation → properties rotate correctly
5. Redstone components maintain power states
6. Walls auto-connect to adjacent solid blocks

---

## Files to Modify

1. **GoldGolemEntity.java**
   - Add `getPlacementStateForBlock()` helper method
   - Add `copyProperty()` helper method
   - Add `createFakePlacementContext()` helper method
   - Modify `placeBlockFromInventory()` to accept template state
   - Modify `placeBlockStateAt()` to use new helper

2. **TowerBuildStrategy.java**
   - Modify `placeTowerBlock()` to pass template state through

3. **WallBuildStrategy.java** (or inline code in GoldGolemEntity)
   - Verify wall placement passes template state correctly

---

## Edge Cases to Handle

1. **Rotation/Mirroring Order**: Apply to properties AFTER copying, BEFORE placement
2. **Missing Properties**: If property exists in source but not target, skip safely
3. **Null getPlacementState()**: Some blocks return null, fall back to getDefaultState()
4. **Block Entities**: Don't copy NBT data (as specified)
5. **Waterlogged Blocks**: Continue clearing waterlogged state after all processing

---

## Testing Strategy

1. Create test tower with cobblestone_wall template
2. Set gradient to mix cobblestone_wall and air
3. Verify walls connect to each other and adjacent cobblestone
4. Create wall with oak_stairs template
5. Set gradient to mix oak_stairs and birch_stairs
6. Verify stairs maintain orientation through material changes
7. Test redstone components (repeaters, comparators)
8. Verify no crashes on blocks without getPlacementState()

---

## Expected Outcomes

### Before Fix:
- Walls placed by golem appear disconnected
- Stairs lose orientation when material changes
- Redstone components in wrong states

### After Fix:
- Walls connect properly like player placement
- Material substitutions preserve orientation (oak→birch stairs)
- Exact template matches copy states perfectly
- Different block types get proper auto-connection behavior

---

## Notes

- World seed + position ensures deterministic randomness (same build always looks the same)
- Property set matching handles most "material swap" cases automatically
- Player placement simulation gives proper Minecraft behavior for new block types
- Rotation/mirroring still works on wall mode as before
