# GoldGolemEntity Refactoring Plan

## Overview

Refactor the 4,623-line `GoldGolemEntity` God class using the **Strategy Pattern** to separate build mode logic into individual, maintainable classes.

## Current State Analysis

### Problems Identified

1. **God Class**: Single class handles 7+ build modes (Path, Wall, Tower, Mining, Excavation, Terraforming, Tree)
2. **State Explosion**: ~100+ mode-specific fields (lines 66-172)
3. **Massive Serialization**: `writeCustomData`/`readCustomData` span 400+ lines with mode-specific logic interleaved
4. **Duplicated Patterns**: Each mode has similar:
   - Navigation/stuck detection logic
   - Block placement with particles
   - Inventory consumption
   - Hand animation triggers
5. **Difficult to Extend**: Adding a new mode requires modifying multiple sections of a massive file

### Current File Structure
```
world/entity/
└── GoldGolemEntity.java (4,623 lines)
    ├── Lines 44-172: Mode-specific state fields
    ├── Lines 214-500: Mode getters/setters
    ├── Lines 637-856: tick() with mode dispatch
    ├── Lines 1087-1157: tickWallMode()
    ├── Lines 1159-1223: tickTowerMode()
    ├── Lines 1350-1478: tickTreeMode()
    ├── Lines 1592-1742: tickMiningMode()
    ├── Lines 1800-2100: tickExcavationMode() (estimated)
    ├── Lines 2100-2500: tickTerraformingMode() (estimated)
    ├── Lines 2829-3102: writeCustomData()
    └── Lines 3104-3400+: readCustomData()
```

---

## Target Architecture

### New Package Structure
```
world/entity/
├── GoldGolemEntity.java (~800 lines - core entity)
├── strategy/
│   ├── BuildStrategy.java (interface)
│   ├── AbstractBuildStrategy.java (shared logic)
│   ├── PathBuildStrategy.java
│   ├── WallBuildStrategy.java
│   ├── TowerBuildStrategy.java
│   ├── MiningBuildStrategy.java
│   ├── ExcavationBuildStrategy.java
│   ├── TerraformingBuildStrategy.java
│   └── TreeBuildStrategy.java
└── helper/
    ├── GolemNavigationHelper.java
    ├── GolemInventoryHelper.java
    ├── GolemAnimationHelper.java
    ├── GolemBlockPlacementHelper.java
    └── GradientSampler.java
```

---

## Phase 1: Create Infrastructure (No Behavior Changes)

### Step 1.1: Create BuildStrategy Interface

**File**: `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/BuildStrategy.java`

```java
package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public interface BuildStrategy {

    // Identity
    BuildMode getMode();

    // Lifecycle
    void initialize(GoldGolemEntity golem);
    void tick(GoldGolemEntity golem, PlayerEntity owner);
    void cleanup(GoldGolemEntity golem);

    // State
    boolean isComplete();
    void stop(GoldGolemEntity golem);

    // Persistence
    void writeToStorage(WriteView view);
    void readFromStorage(ReadView view);

    // Behavior flags
    default boolean usesPlayerTracking() { return true; }
    default boolean usesGradientUI() { return false; }
    default boolean usesGroupUI() { return false; }
}
```

### Step 1.2: Create AbstractBuildStrategy

**File**: `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/AbstractBuildStrategy.java`

```java
package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public abstract class AbstractBuildStrategy implements BuildStrategy {

    protected int stuckTicks = 0;
    protected int placementTickCounter = 0;
    protected boolean leftHandActive = true;

    // Shared navigation helper
    protected void navigateToWithStuckDetection(GoldGolemEntity golem, Vec3d target,
                                                 double speed, int teleportThreshold) {
        double dx = golem.getX() - target.x;
        double dz = golem.getZ() - target.z;
        double distSq = dx * dx + dz * dz;

        golem.getNavigation().startMovingTo(target.x, target.y, target.z, speed);

        if (golem.getNavigation().isIdle() && distSq > 1.0) {
            stuckTicks++;
            if (stuckTicks >= teleportThreshold) {
                teleportWithParticles(golem, target);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }

    protected void teleportWithParticles(GoldGolemEntity golem, Vec3d target) {
        if (golem.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL,
                golem.getX(), golem.getY() + 0.5, golem.getZ(),
                40, 0.5, 0.5, 0.5, 0.2);
            sw.spawnParticles(ParticleTypes.PORTAL,
                target.x, target.y + 0.5, target.z,
                40, 0.5, 0.5, 0.5, 0.2);
        }
        golem.refreshPositionAndAngles(target.x, target.y, target.z,
            golem.getYaw(), golem.getPitch());
        golem.getNavigation().stop();
    }

    // Shared tick counter for alternating hands
    protected boolean shouldPlaceThisTick() {
        placementTickCounter = (placementTickCounter + 1) % 2;
        return placementTickCounter == 0;
    }

    protected void alternateHand() {
        leftHandActive = !leftHandActive;
    }

    protected boolean isLeftHandActive() {
        return leftHandActive;
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        stuckTicks = 0;
        placementTickCounter = 0;
    }
}
```

### Step 1.3: Create Helper Classes

#### GolemNavigationHelper.java
Extracted methods:
- `computeGroundTargetY(World, Vec3d)` (from line 1062)
- Stuck detection logic
- Teleport with particles

#### GolemInventoryHelper.java
Extracted methods:
- `consumeBlockFromInventory(Inventory, String)`
- `placeBlockFromInventory(World, BlockPos, BlockState, Inventory)`
- `transferToInventory(ItemStack, Inventory)`
- `getBlockIdFromStack(ItemStack)`
- `isFull(Inventory)`

#### GolemAnimationHelper.java
Extracted methods:
- `beginHandAnimation(DataTracker, boolean, BlockPos, BlockPos)`
- `clearHandAnimation(DataTracker, boolean)`
- `advanceHandAnimationTick(DataTracker, boolean)`
- `updateArmAndEyePositions(...)`

#### GradientSampler.java
Extracted methods:
- `sampleGradient(String[], float, BlockPos)`
- `deterministic01(int, int, int)`
- Edge reflection logic

---

## Phase 2: Extract First Strategy (Path Mode)

### Step 2.1: Create PathBuildStrategy

**File**: `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/PathBuildStrategy.java`

Move from GoldGolemEntity:
- Fields: `trackStart`, `pendingLines`, `currentLine`, `recentPlaced`, `placedRing`, etc.
- Inner class: `LineSeg`
- Methods: Path-mode tick logic from `tick()` (lines 723-853)
- Methods: `enqueueLine()`, `placeCornerFill()`, path-related helpers

```java
public class PathBuildStrategy extends AbstractBuildStrategy {

    // State moved from GoldGolemEntity
    private Vec3d trackStart = null;
    private final ArrayDeque<LineSeg> pendingLines = new ArrayDeque<>();
    private LineSeg currentLine = null;
    private final LongOpenHashSet recentPlaced = new LongOpenHashSet(8192);
    private final long[] placedRing = new long[8192];
    private int placedHead = 0;
    private int placedSize = 0;

    // Path configuration (shared with entity for UI)
    private int pathWidth = 3;
    private final String[] gradient = new String[9];
    private final String[] stepGradient = new String[9];
    private float gradientWindow = 1.0f;
    private float stepGradientWindow = 1.0f;

    @Override
    public BuildMode getMode() { return BuildMode.PATH; }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        // Move tick logic from GoldGolemEntity lines 723-853
    }

    // ... rest of implementation
}
```

### Step 2.2: Integrate PathBuildStrategy into GoldGolemEntity

```java
// In GoldGolemEntity
private BuildStrategy activeStrategy;

public void setActiveStrategy(BuildStrategy strategy) {
    if (activeStrategy != null) {
        activeStrategy.cleanup(this);
    }
    activeStrategy = strategy;
    if (activeStrategy != null) {
        activeStrategy.initialize(this);
        setBuildMode(strategy.getMode());
    }
}

@Override
public void tick() {
    super.tick();
    updateCommonAnimations(); // wheel, walking arms

    if (buildingPaths && activeStrategy != null) {
        PlayerEntity owner = getOwnerPlayer();
        activeStrategy.tick(this, owner);

        if (activeStrategy.isComplete()) {
            stopBuilding();
        }
    }
}
```

### Step 2.3: Verify Path Mode Still Works

- Test path building with player movement
- Test gradient sampling
- Test NBT save/load
- Test animation sync

---

## Phase 3: Extract Remaining Strategies

### Step 3.1: WallBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 66-89): `wallUniqueBlockIds`, `wallOrigin`, `wallJsonFile`, `wallJoinSignature`, `wallJoinAxis`, `wallJoinUSize`, `wallModuleCount`, `wallLongestModule`, `wallTemplates`, `currentModulePlacement`, `pendingModules`, `wallLastDirX/Z`, `wallJoinTemplate`, `wallGroupSlots`, `wallGroupWindows`, `wallBlockGroup`
- Inner class: `JoinEntry`, `ModulePlacement`
- Methods: `tickWallMode()` (1087-1157), wall-specific helpers
- Serialization: Wall section from writeCustomData/readCustomData

### Step 3.2: TowerBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 92-103): `towerUniqueBlockIds`, `towerBlockCounts`, `towerOrigin`, `towerJsonFile`, `towerHeight`, `towerTemplate`, `towerGroupSlots`, `towerGroupWindows`, `towerBlockGroup`, `towerCurrentY`, `towerPlacementCursor`
- Methods: `tickTowerMode()` (1159-1223), `getCurrentTowerLayerVoxels()`, `placeTowerBlock()`, `getTowerBlockStateAt()`, `sampleTowerGradient()`
- Serialization: Tower section

### Step 3.3: MiningBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 106-121): `miningChestPos`, `miningDirection`, `miningStartPos`, `miningBranchDepth`, `miningBranchSpacing`, `miningTunnelHeight`, `miningPrimaryProgress`, `miningCurrentBranch`, `miningBranchLeft`, `miningBranchProgress`, `miningReturningToChest`, `miningIdleAtChest`, `miningPendingOres`, `miningBuildingBlockType`, `miningBreakProgress`, `miningCurrentTarget`
- Methods: `tickMiningMode()`, `tickMiningReturn()`, `tickMiningActive()`, `isMiningInventoryFull()`, `depositInventoryToChest()`, mining helpers
- Note: Mining has internal state machine (idle → active → returning) - good candidate for nested State pattern

### Step 3.4: ExcavationBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 124-137): `excavationChestPos1/2`, `excavationDir1/2`, `excavationStartPos`, `excavationHeight`, `excavationDepth`, `excavationCurrentRing`, `excavationRingProgress`, `excavationReturningToChest`, `excavationIdleAtStart`, `excavationBuildingBlockType`, `excavationBreakProgress`, `excavationCurrentTarget`
- Methods: `tickExcavationMode()`, excavation helpers

### Step 3.5: TerraformingBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 139-157): `terraformingOrigin`, `terraformingSkeletonBlocks`, `terraformingSkeletonTypes`, `terraformingShellByLayer`, `terraformingCurrentY`, `terraformingLayerProgress`, `terraformingStartPos`, `terraformingScanRadius`, `terraformingMinY/MaxY`, `terraformingAlpha`, `terraformingGradientVertical/Horizontal/Sloped`, `terraformingGradientVertical/Horizontal/SlopedWindow`
- Methods: `tickTerraformingMode()`, `setTerraformingConfig()`, alpha shape interaction

### Step 3.6: TreeBuildStrategy
Move from GoldGolemEntity:
- Fields (lines 160-172): `treeModules`, `treeUniqueBlockIds`, `treeOrigin`, `treeJsonFile`, `treeTilingPreset`, `treeTileCache`, `treeWFCBuilder`, `treeTilesCached`, `treeWaitingForInventory`, `treeGroupSlots`, `treeGroupWindows`, `treeBlockGroup`
- Methods: `tickTreeMode()` (1350-1478), `placeTreeTile()`, `sampleTreeGradient()`

---

## Phase 4: Refactor Strategy Factory & Registration

### Step 4.1: Create StrategyRegistry

```java
public class BuildStrategyRegistry {
    private static final Map<BuildMode, Supplier<BuildStrategy>> STRATEGIES = new EnumMap<>(BuildMode.class);

    static {
        register(BuildMode.PATH, PathBuildStrategy::new);
        register(BuildMode.WALL, WallBuildStrategy::new);
        register(BuildMode.TOWER, TowerBuildStrategy::new);
        register(BuildMode.MINING, MiningBuildStrategy::new);
        register(BuildMode.EXCAVATION, ExcavationBuildStrategy::new);
        register(BuildMode.TERRAFORMING, TerraformingBuildStrategy::new);
        register(BuildMode.TREE, TreeBuildStrategy::new);
    }

    public static BuildStrategy create(BuildMode mode) {
        var supplier = STRATEGIES.get(mode);
        return supplier != null ? supplier.get() : null;
    }

    public static void register(BuildMode mode, Supplier<BuildStrategy> supplier) {
        STRATEGIES.put(mode, supplier);
    }
}
```

### Step 4.2: Update PumpkinSummoning

The summoning code needs to create and initialize the appropriate strategy based on detected mode.

---

## Phase 5: Refactor Serialization

### Step 5.1: Delegate to Strategies

```java
// GoldGolemEntity
@Override
protected void writeCustomData(WriteView view) {
    // Common data
    view.putString("Mode", getBuildMode().name());
    writeInventory(view);
    writeOwner(view);
    writeCommonState(view);

    // Delegate mode-specific data
    if (activeStrategy != null) {
        activeStrategy.writeToStorage(view);
    }
}

@Override
protected void readCustomData(ReadView view) {
    // Read mode first
    String mode = view.getString("Mode", BuildMode.PATH.name());
    BuildMode buildMode = BuildMode.valueOf(mode);

    // Create appropriate strategy
    activeStrategy = BuildStrategyRegistry.create(buildMode);
    setBuildMode(buildMode);

    // Read common data
    readInventory(view);
    readOwner(view);
    readCommonState(view);

    // Delegate mode-specific data
    if (activeStrategy != null) {
        activeStrategy.readFromStorage(view);
    }
}
```

---

## Phase 6: Clean Up & Polish

### Step 6.1: Remove Dead Code from GoldGolemEntity
- Delete all mode-specific fields (replaced by strategy state)
- Delete all `tick*Mode()` methods
- Delete mode-specific helpers
- Keep only: common entity code, inventory access, animation state, data trackers

### Step 6.2: Update Screen Handlers
- Ensure `GolemInventoryScreenHandler` works with strategy pattern
- UI configuration methods may need to call through to active strategy

### Step 6.3: Update Network Payloads
- Payloads that modify mode-specific state should route through strategy
- Example: `SetGroupSlotC2SPayload` → `activeStrategy.setGroupSlot(...)`

---

## Estimated Line Counts After Refactoring

| File | Lines |
|------|-------|
| GoldGolemEntity.java | ~800 |
| BuildStrategy.java | ~50 |
| AbstractBuildStrategy.java | ~150 |
| PathBuildStrategy.java | ~400 |
| WallBuildStrategy.java | ~500 |
| TowerBuildStrategy.java | ~400 |
| MiningBuildStrategy.java | ~500 |
| ExcavationBuildStrategy.java | ~400 |
| TerraformingBuildStrategy.java | ~450 |
| TreeBuildStrategy.java | ~500 |
| GolemNavigationHelper.java | ~100 |
| GolemInventoryHelper.java | ~150 |
| GolemAnimationHelper.java | ~200 |
| GradientSampler.java | ~100 |
| BuildStrategyRegistry.java | ~50 |
| **Total** | ~4,750 |

Lines similar but spread across 15 focused files instead of 1 monolithic class.

---

## Implementation Order

1. **Phase 1** - Infrastructure (can be done without breaking anything)
2. **Phase 2** - Path mode extraction (simplest, validates approach)
3. **Phase 3.1-3.2** - Wall & Tower (similar "group UI" pattern)
4. **Phase 3.3-3.4** - Mining & Excavation (similar "resource gathering" pattern)
5. **Phase 3.5** - Terraforming
6. **Phase 3.6** - Tree (most complex, WFC integration)
7. **Phase 4-6** - Cleanup and polish

---

## Testing Checklist

For each extracted strategy, verify:
- [ ] Building behavior works identically
- [ ] Player tracking (if applicable) works
- [ ] Block placement with correct materials
- [ ] Hand animations sync to client
- [ ] NBT save/load preserves state
- [ ] UI configuration changes apply
- [ ] Network payloads route correctly
- [ ] Mode switching works
- [ ] Golem can be spawned fresh in this mode

---

## Risk Mitigation

1. **Keep Old Code Commented**: During extraction, comment out old code rather than deleting immediately
2. **Feature Flags**: Consider a config option to use old vs new implementation during transition
3. **Incremental Testing**: Test each strategy thoroughly before moving to next
4. **Git Branches**: Use feature branches for each phase, merge only when stable

---

## Future Benefits

Once refactored, adding a new build mode becomes:

1. Create `NewBuildStrategy extends AbstractBuildStrategy`
2. Implement required methods
3. Register in `BuildStrategyRegistry`
4. Add to `BuildMode` enum
5. Update UI if needed

No changes to GoldGolemEntity required!
