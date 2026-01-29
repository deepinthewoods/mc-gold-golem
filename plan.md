# Gold Golem Codebase Improvement Plan

This plan organizes 23 improvements into parallelizable work streams for concurrent agent execution.

---

## Overview

### Work Streams (Can Run Concurrently)

| Stream | Focus Area | Issues Addressed |
|--------|------------|------------------|
| A | GoldGolemEntity Refactoring | #1, #8, #11, #13, #14, #23 |
| B | Mining/Excavation Strategies | #6 |
| C | GUI Thread Safety & Consolidation | #2, #7, #18, #22 |
| D | Network Payload Validation | #9 |
| E | WFC/Tree System Fixes | #3, #4, #15, #16, #19, #24, #25 |
| F | Exception Handling | #10 |
| G | Ring Buffer Thread Safety | #5 |
| H | String Optimizations | #17 |
| I | Inventory Caching | #20 |

### Dependency Graph

```
Phase 1 (Foundation) - Run in parallel:
├── Stream B: BaseMiningStrategy extraction
├── Stream D: Payload validation base class
├── Stream E: WFC algorithm fixes
├── Stream F: Exception handling
├── Stream G: Ring buffer fix
├── Stream H: String optimizations
└── Stream I: Inventory caching

Phase 2 (Core Refactoring) - After Phase 1:
├── Stream A: GoldGolemEntity refactoring (uses Stream B output)
└── Stream C: GUI refactoring

Phase 3 (Integration) - After Phase 2:
└── Final integration testing and cleanup
```

---

## Stream A: GoldGolemEntity Refactoring

**Files:** `src/main/java/ninja/trek/mc/goldgolem/world/entity/GoldGolemEntity.java`

**Depends on:** Stream B (BaseMiningStrategy must exist first)

### Task A1: Extract Magic Numbers to Constants
**Issue #11**

Create constants section at top of class:
```java
// Animation constants
private static final int ANIMATION_DURATION_TICKS = 12;
private static final float ARM_SWING_MIN_ANGLE = 15.0f;
private static final float ARM_SWING_MAX_ANGLE = 70.0f;

// Inventory constants
private static final int GRADIENT_SIZE = 9;
private static final int INVENTORY_SIZE = 27;

// Ring buffer constants
private static final int PLACED_RING_BUFFER_SIZE = 8192;

// Timing constants
private static final int STUCK_TICK_THRESHOLD = 20;
private static final int EYE_UPDATE_COOLDOWN_MIN = 5;
private static final int EYE_UPDATE_COOLDOWN_MAX = 10;
```

Replace all hardcoded values with constants.

### Task A2: Extract GradientGroupManager Utility Class
**Issue #8**

Create new file: `src/main/java/ninja/trek/mc/goldgolem/util/GradientGroupManager.java`

```java
public class GradientGroupManager {
    private final List<String[]> groupSlots = new ArrayList<>();
    private final List<Float> groupWindows = new ArrayList<>();
    private final List<Integer> groupNoiseScales = new ArrayList<>();
    private final Map<String, Integer> blockGroups = new HashMap<>();

    public void initializeFromUniqueBlocks(List<String> uniqueBlocks) { ... }
    public void setGroupWindow(int group, float window) { ... }
    public void setGroupNoiseScale(int group, int scale) { ... }
    public void setGroupSlot(int group, int slot, String id) { ... }
    public void setBlockGroup(String blockId, int group) { ... }

    // NBT serialization
    public void writeToNbt(NbtCompound nbt, String prefix) { ... }
    public void readFromNbt(NbtCompound nbt, String prefix) { ... }
}
```

Replace Wall/Tower/Tree group fields in GoldGolemEntity with:
```java
private final GradientGroupManager wallGroups = new GradientGroupManager();
private final GradientGroupManager towerGroups = new GradientGroupManager();
private final GradientGroupManager treeGroups = new GradientGroupManager();
```

### Task A3: Cache Owner Player Reference
**Issue #13**

Add cached reference with invalidation:
```java
private WeakReference<PlayerEntity> cachedOwner = null;
private int ownerCacheTicksRemaining = 0;
private static final int OWNER_CACHE_DURATION = 100; // 5 seconds

public PlayerEntity getOwnerPlayer() {
    if (ownerUuid == null) return null;

    // Check cache first
    if (ownerCacheTicksRemaining > 0 && cachedOwner != null) {
        PlayerEntity cached = cachedOwner.get();
        if (cached != null && cached.getUuid().equals(ownerUuid)) {
            return cached;
        }
    }

    // Cache miss - do lookup
    for (PlayerEntity p : this.getEntityWorld().getPlayers()) {
        if (ownerUuid.equals(p.getUuid())) {
            cachedOwner = new WeakReference<>(p);
            ownerCacheTicksRemaining = OWNER_CACHE_DURATION;
            return p;
        }
    }
    return null;
}
```

Decrement `ownerCacheTicksRemaining` in `tick()`.

### Task A4: Replace System.out with LOGGER
**Issue #14**

In `damage()` method (lines 2918-2955), replace:
```java
System.out.println("[GoldGolem] Taking damage...");
```
With:
```java
LOGGER.debug("Taking damage - Source: {}, Amount: {}", source.getName(), amount);
```

Same for `onDeath()` and any other `System.out.println` calls.

### Task A5: Extract ArmAnimationState Class
**Issue #23**

Create new file: `src/main/java/ninja/trek/mc/goldgolem/world/entity/ArmAnimationState.java`

```java
public class ArmAnimationState {
    private int leftHandAnimationTick = -1;
    private int rightHandAnimationTick = -1;
    private boolean leftHandJustActivated = false;
    private boolean rightHandJustActivated = false;
    private Vec3d leftArmTargetBlock = null;
    private Vec3d rightArmTargetBlock = null;
    private Vec3d nextLeftBlock = null;
    private Vec3d nextRightBlock = null;
    private float leftArmYaw, leftArmPitch;
    private float rightArmYaw, rightArmPitch;
    private float eyeYaw, eyePitch;
    private int eyeUpdateCooldown = 0;

    public void tick(GoldGolemEntity entity) { ... }
    public void beginAnimation(boolean isLeft, Vec3d target) { ... }
    public void updateArmAndEyePositions(GoldGolemEntity entity) { ... }

    // Getters for renderer
    public float getLeftArmYaw() { ... }
    // etc.
}
```

Move animation logic from GoldGolemEntity to this class.

### Task A6: Move Mode-Specific Configuration to Strategies
**Issue #1 - Partial**

For each build mode, move its configuration fields to the corresponding strategy:

**TowerBuildStrategy should own:**
- `towerOrigin`
- `towerJsonFile`
- `towerHeight`
- `towerTemplate`
- `towerCurrentY`
- `towerPlacementCursor`
- `towerGroups` (GradientGroupManager)

**WallBuildStrategy should own:**
- `wallOrigin`
- Similar wall-specific fields
- `wallGroups` (GradientGroupManager)

**TreeBuildStrategy should own:**
- `treeOrigin`
- Similar tree-specific fields
- `treeGroups` (GradientGroupManager)

Update GoldGolemEntity to delegate to strategies:
```java
public GradientGroupManager getWallGroups() {
    return ((WallBuildStrategy) getStrategy(BuildMode.WALL)).getGroups();
}
```

---

## Stream B: Mining/Excavation Strategy Refactoring

**Files:**
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/MiningBuildStrategy.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/ExcavationBuildStrategy.java`
- NEW: `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/BaseMiningStrategy.java`

**Depends on:** None

### Task B1: Create BaseMiningStrategy Abstract Class
**Issue #6**

Create new file with shared functionality:

```java
public abstract class BaseMiningStrategy extends AbstractBuildStrategy {

    // Shared state
    protected BlockPos leftHandTarget = null;
    protected BlockPos rightHandTarget = null;
    protected float leftBreakProgress = 0f;
    protected float rightBreakProgress = 0f;
    protected ItemStack leftTool = ItemStack.EMPTY;
    protected ItemStack rightTool = ItemStack.EMPTY;

    // Shared methods extracted from both strategies
    protected void mineBlockWithHand(boolean isLeft, BlockPos target) { ... }
    protected ToolPair findTwoTools(BlockState leftState, BlockState rightState) { ... }
    protected ItemStack findBestTool(BlockState state) { ... }
    protected boolean addToInventory(ItemStack stack) { ... }
    protected void depositInventoryToChest(BlockPos chestPos) { ... }
    protected void transferToInventory(Inventory targetInv) { ... }
    protected void placeBlocksUnderFeet() { ... }

    // Block classification utilities
    protected boolean isOreBlock(BlockState state) { ... }
    protected boolean isChestBlock(BlockState state) { ... }
    protected boolean isGravityBlock(BlockState state) { ... }
    protected String getBlockIdFromStack(ItemStack stack) { ... }

    // NBT helpers
    protected void writeBaseMiningNbt(NbtCompound nbt) { ... }
    protected void readBaseMiningNbt(NbtCompound nbt) { ... }

    // Record for tool pairs
    public record ToolPair(ItemStack left, ItemStack right) {}
}
```

### Task B2: Refactor MiningBuildStrategy to Extend Base

Remove duplicated methods, keep only mining-specific logic:
- `scanForOres()`
- `getNextMiningTarget()`
- `getNextBranchMiningTarget()`
- Mining-specific state (pendingOres, startPos, etc.)

### Task B3: Refactor ExcavationBuildStrategy to Extend Base

Remove duplicated methods, keep only excavation-specific logic:
- `tickExcavationMode()`
- `skipCompletedRings()`
- `ringHasBlocksToMine()`
- Excavation-specific state (currentRing, returningToChest, etc.)

---

## Stream C: GUI Thread Safety & Consolidation

**Files:**
- `src/client/java/ninja/trek/mc/goldgolem/client/screen/GolemHandledScreen.java`
- NEW: `src/client/java/ninja/trek/mc/goldgolem/client/screen/ModeState.java`

**Depends on:** None (but should coordinate with Stream A on naming)

### Task C1: Make Sync Flag Volatile
**Issue #2 - Quick fix**

```java
private volatile boolean updatingTowerLayersField = false;
```

### Task C2: Add Synchronization to Sync Methods
**Issue #2**

Wrap field mutations in synchronized blocks:
```java
private final Object stateLock = new Object();

public void setWallGroupsState(List<Float> windows, List<Integer> noiseScales, List<String> flatSlots) {
    synchronized (stateLock) {
        this.wallGroupWindows = (windows == null) ? Collections.emptyList() : new ArrayList<>(windows);
        this.wallGroupNoiseScales = (noiseScales == null) ? Collections.emptyList() : new ArrayList<>(noiseScales);
        this.wallGroupFlatSlots = (flatSlots == null) ? Collections.emptyList() : new ArrayList<>(flatSlots);
    }
    // Schedule UI update on render thread
    MinecraftClient.getInstance().execute(this::refreshLayoutIfNeeded);
}
```

### Task C3: Create ModeState Class for Consolidation
**Issue #7**

Create new file:
```java
public class ModeState {
    private List<String> uniqueBlocks = Collections.emptyList();
    private List<Integer> blockGroups = Collections.emptyList();
    private List<Float> groupWindows = Collections.emptyList();
    private List<Integer> groupNoiseScales = Collections.emptyList();
    private List<String> groupFlatSlots = Collections.emptyList();
    private Map<String, Integer> blockCounts = Collections.emptyMap();

    private int scroll = 0;
    private String draggingBlockId = null;
    private String pendingAssignBlockId = null;

    private final List<WindowSlider> rowSliders = new ArrayList<>();
    private final int[] sliderToGroup = new int[6];
    private final List<IconHit> iconHits = new ArrayList<>();

    // Getters and setters with proper synchronization
    public synchronized void setUniqueBlocks(List<String> blocks) { ... }
    public synchronized List<String> getUniqueBlocks() { return new ArrayList<>(uniqueBlocks); }
    // etc.
}
```

Replace mode-specific fields in GolemHandledScreen:
```java
private final Map<BuildMode, ModeState> modeStates = new EnumMap<>(BuildMode.class);

public ModeState getModeState(BuildMode mode) {
    return modeStates.computeIfAbsent(mode, k -> new ModeState());
}
```

### Task C4: Add Bounds Checking
**Issue #18**

In `drawForeground()` and other methods that access collections:
```java
int n = Math.min(uniqueBlocks.size(), blockGroups.size());
for (int i = 0; i < n; i++) {
    int g = blockGroups.get(i);
    if (g < 0 || g >= maxGroups) {
        LOGGER.warn("Invalid group index {} for block {}", g, uniqueBlocks.get(i));
        continue;
    }
    groupToBlocks.computeIfAbsent(g, k -> new ArrayList<>()).add(uniqueBlocks.get(i));
}
```

### Task C5: Standardize Sync Method Naming
**Issue #22**

Rename methods to consistent pattern:
- `setWallUniqueBlocks` -> `syncWallUniqueBlocks`
- `setTowerBlockCounts` -> `syncTowerBlockCounts`
- `setExcavationValues` -> `syncExcavationState`
- `setMiningValues` -> `syncMiningState`
- `setTerraformingValues` -> `syncTerraformingState`

---

## Stream D: Network Payload Validation

**Files:** All files in `src/main/java/ninja/trek/mc/goldgolem/net/`

**Depends on:** None

### Task D1: Create Base Payload Validator

Create new file: `src/main/java/ninja/trek/mc/goldgolem/net/PayloadValidator.java`

```java
public final class PayloadValidator {

    public static int clampInt(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            LOGGER.warn("Payload field {} out of range: {} (expected {}-{})",
                fieldName, value, min, max);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public static float clampFloat(float value, float min, float max, String fieldName) {
        if (value < min || value > max) {
            LOGGER.warn("Payload field {} out of range: {} (expected {}-{})",
                fieldName, value, min, max);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    public static <T> List<T> validateListSize(List<T> list, int expectedSize, String fieldName) {
        if (list == null) {
            LOGGER.warn("Payload field {} is null, expected list of size {}", fieldName, expectedSize);
            return Collections.emptyList();
        }
        if (list.size() != expectedSize) {
            LOGGER.warn("Payload field {} has wrong size: {} (expected {})",
                fieldName, list.size(), expectedSize);
        }
        return list;
    }

    public static boolean isValidBlockId(String blockId) {
        if (blockId == null || blockId.isEmpty()) return false;
        Identifier id = Identifier.tryParse(blockId);
        return id != null && Registries.BLOCK.containsId(id);
    }
}
```

### Task D2: Add Validation to C2S Payloads

Example for `SetGradientWindowC2SPayload`:
```java
public record SetGradientWindowC2SPayload(int entityId, int row, float window, int noiseScale)
    implements CustomPayload {

    public SetGradientWindowC2SPayload {
        row = PayloadValidator.clampInt(row, 0, 1, "row");
        window = PayloadValidator.clampFloat(window, 0.0f, 9.0f, "window");
        noiseScale = PayloadValidator.clampInt(noiseScale, 1, 16, "noiseScale");
    }
    // ...
}
```

Apply similar validation to:
- `SetTowerHeightC2SPayload`: height 1-256
- `SetExcavationHeightC2SPayload`: height 1-10
- `SetExcavationDepthC2SPayload`: depth 1-64
- `SetPathWidthC2SPayload`: width 1-9
- `SetOreMiningModeC2SPayload`: ordinal 0-2
- `SetTerraformingScanRadiusC2SPayload`: radius 1-32

### Task D3: Add Validation to S2C Payloads

Example for `SyncGradientS2CPayload`:
```java
public SyncGradientS2CPayload {
    blocksMain = PayloadValidator.validateListSize(blocksMain, 9, "blocksMain");
    blocksStep = PayloadValidator.validateListSize(blocksStep, 9, "blocksStep");
}
```

Apply to all sync payloads.

---

## Stream E: WFC/Tree System Fixes

**Files:**
- `src/main/java/ninja/trek/mc/goldgolem/tree/TreeWFCBuilder.java`
- `src/main/java/ninja/trek/mc/goldgolem/tree/TreeTileCache.java`
- `src/main/java/ninja/trek/mc/goldgolem/tree/TreeTileExtractor.java`
- `src/main/java/ninja/trek/mc/goldgolem/tree/TreeDefinition.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/TreeBuildStrategy.java`

**Depends on:** None

### Task E1: Fix Hash Collision Vulnerability
**Issue #3**

Replace hash-based keys with unique IDs in `TreeTileCache`:

```java
// Replace encodeAdjacencyKey with direct storage
private final Map<String, Map<Direction, Set<String>>> adjacencyRules = new HashMap<>();

public void addAdjacencyRule(String fromTileId, Direction direction, String toTileId) {
    adjacencyRules
        .computeIfAbsent(fromTileId, k -> new EnumMap<>(Direction.class))
        .computeIfAbsent(direction, k -> new HashSet<>())
        .add(toTileId);
}

public Set<String> getValidNeighbors(String tileId, Direction direction) {
    Map<Direction, Set<String>> dirMap = adjacencyRules.get(tileId);
    if (dirMap == null) return Collections.emptySet();
    Set<String> neighbors = dirMap.get(direction);
    return neighbors != null ? neighbors : Collections.emptySet();
}
```

Update `TreeTileExtractor` to use new API instead of encoded keys.

### Task E2: Implement Proper Constraint Propagation
**Issue #4**

Rewrite `TreeWFCBuilder.propagate()` with iterative arc consistency:

```java
private void propagate(BlockPos collapsedPos, String chosenTile) {
    Queue<BlockPos> worklist = new LinkedList<>();
    worklist.add(collapsedPos);

    while (!worklist.isEmpty()) {
        BlockPos current = worklist.poll();
        String currentTile = collapsed.get(current);
        if (currentTile == null) continue;

        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = current.offset(dir);
            if (collapsed.containsKey(neighborPos)) continue;

            Set<String> validNeighbors = tileCache.getValidNeighbors(currentTile, dir);
            Set<String> currentPossible = waveFunction.get(neighborPos);

            if (currentPossible == null) {
                waveFunction.put(neighborPos, new HashSet<>(validNeighbors));
            } else {
                int sizeBefore = currentPossible.size();
                currentPossible.retainAll(validNeighbors);

                if (currentPossible.isEmpty()) {
                    // Contradiction - could implement backtracking here
                    waveFunction.remove(neighborPos);
                    LOGGER.warn("WFC contradiction at {}", neighborPos);
                } else if (currentPossible.size() < sizeBefore) {
                    // Constraints changed, propagate further
                    worklist.add(neighborPos);
                }
            }
        }
    }
}
```

### Task E3: Fix ConcurrentModificationException
**Issue #15**

In `TreeWFCBuilder.step()`:
```java
public boolean step(Random random) {
    // Collect positions to remove first
    List<BlockPos> toRemove = new ArrayList<>();
    BlockPos minEntropyPos = null;
    int minEntropy = Integer.MAX_VALUE;

    for (BlockPos pos : frontier) {
        Set<String> possibleTiles = waveFunction.get(pos);
        if (possibleTiles == null || possibleTiles.isEmpty()) {
            toRemove.add(pos);
            continue;
        }

        int entropy = possibleTiles.size();
        if (entropy < minEntropy) {
            minEntropy = entropy;
            minEntropyPos = pos;
        }
    }

    // Remove after iteration
    frontier.removeAll(toRemove);

    if (minEntropyPos == null) {
        return !frontier.isEmpty();
    }
    // ... rest of method
}
```

### Task E4: Fix State Consistency in TreeBuildStrategy
**Issue #16**

Replace boolean flags with explicit state enum:
```java
private enum CacheState {
    NOT_STARTED,
    CACHING,
    CACHED,
    FAILED
}

private CacheState cacheState = CacheState.NOT_STARTED;

@Override
public void tick(GoldGolemEntity golem) {
    switch (cacheState) {
        case NOT_STARTED -> initializeCaching(golem);
        case CACHING -> continueCaching(golem);
        case CACHED -> continueBuilding(golem);
        case FAILED -> handleFailure(golem);
    }
}
```

### Task E5: Add Priority Queue for Entropy Search
**Issue #19**

Replace linear search with priority queue:
```java
private final PriorityQueue<BlockPos> frontier = new PriorityQueue<>(
    Comparator.comparingInt(pos -> {
        Set<String> tiles = waveFunction.get(pos);
        return tiles != null ? tiles.size() : Integer.MAX_VALUE;
    })
);

// Note: Need to re-add positions when their entropy changes
private void updateFrontierPriority(BlockPos pos) {
    frontier.remove(pos);
    frontier.add(pos);
}
```

### Task E6: Add Resource Recovery Check
**Issue #24**

In `TreeBuildStrategy.tick()`:
```java
private int resourceCheckCooldown = 0;

if (isWaitingForResources()) {
    resourceCheckCooldown--;
    if (resourceCheckCooldown <= 0) {
        resourceCheckCooldown = 100; // Check every 5 seconds
        if (hasRequiredResources()) {
            setWaitingForResources(false);
            LOGGER.info("Resources available, resuming tree building");
        }
    }
    // Spawn particles...
    return;
}
```

### Task E7: Add TreeDefinition Validation
**Issue #25**

```java
public TreeDefinition(BlockPos origin, List<TreeModule> modules, List<String> uniqueBlockIds) {
    if (origin == null) {
        throw new IllegalArgumentException("Tree origin cannot be null");
    }
    if (modules == null || modules.isEmpty()) {
        throw new IllegalArgumentException("Tree must have at least one module");
    }
    if (uniqueBlockIds == null || uniqueBlockIds.isEmpty()) {
        throw new IllegalArgumentException("Tree must have at least one unique block ID");
    }

    this.origin = origin.toImmutable();
    this.modules = Collections.unmodifiableList(new ArrayList<>(modules));
    this.uniqueBlockIds = Collections.unmodifiableList(new ArrayList<>(uniqueBlockIds));
}
```

---

## Stream F: Exception Handling

**Files:**
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/GoldGolemEntity.java`
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/TreeBuildStrategy.java`

**Depends on:** None

### Task F1: Add Logging to Snapshot Methods
**Issue #10**

In `findSnapshotPath()`:
```java
try {
    String json = Files.readString(p);
    JsonElement parsed = JsonParser.parseString(json);
    // ...
} catch (IOException e) {
    LOGGER.warn("Failed to read snapshot file {}: {}", p, e.getMessage());
} catch (JsonSyntaxException e) {
    LOGGER.warn("Invalid JSON in snapshot file {}: {}", p, e.getMessage());
}
```

In `writeSnapshotForName()`:
```java
} catch (IOException e) {
    LOGGER.error("Failed to write snapshot {}: {}", name, e.getMessage());
    return null;
} catch (Exception e) {
    LOGGER.error("Unexpected error writing snapshot {}", name, e);
    return null;
}
```

### Task F2: Improve Exception Handling in TreeBuildStrategy

```java
try {
    treeTileCache = TreeTileExtractor.extract(...);
} catch (OutOfMemoryError e) {
    LOGGER.error("Out of memory extracting tree tiles - tree may be too complex", e);
    cacheState = CacheState.FAILED;
    golem.setBuildingPaths(false);
    return;
} catch (IllegalArgumentException e) {
    LOGGER.error("Invalid tree definition: {}", e.getMessage());
    cacheState = CacheState.FAILED;
    golem.setBuildingPaths(false);
    return;
} catch (Exception e) {
    LOGGER.error("Failed to cache tree tiles", e);
    cacheState = CacheState.FAILED;
    golem.setBuildingPaths(false);
    return;
}
```

---

## Stream G: Ring Buffer Thread Safety

**Files:** `src/main/java/ninja/trek/mc/goldgolem/world/entity/GoldGolemEntity.java`

**Depends on:** None

### Task G1: Make Ring Buffer Thread-Safe
**Issue #5**

Option A - Use synchronized (simpler):
```java
private final Object ringBufferLock = new Object();

public boolean recordPlaced(long key) {
    synchronized (ringBufferLock) {
        if (recentPlaced.contains(key)) return false;

        if (placedSize == placedRing.length) {
            long old = placedRing[placedHead];
            recentPlaced.remove(old);
            placedRing[placedHead] = key;
            placedHead = (placedHead + 1) % placedRing.length;
        } else {
            placedRing[(placedHead + placedSize) % placedRing.length] = key;
            placedSize++;
        }

        recentPlaced.add(key);
        return true;
    }
}

public void unrecordPlaced(long key) {
    synchronized (ringBufferLock) {
        recentPlaced.remove(key);
    }
}

public boolean wasRecentlyPlaced(long key) {
    synchronized (ringBufferLock) {
        return recentPlaced.contains(key);
    }
}
```

Option B - Use ConcurrentHashMap (remove ring buffer):
```java
private final ConcurrentHashMap.KeySetView<Long, Boolean> recentPlaced =
    ConcurrentHashMap.newKeySet();
private final AtomicInteger placedCount = new AtomicInteger(0);
private static final int MAX_PLACED_ENTRIES = 8192;

public boolean recordPlaced(long key) {
    if (!recentPlaced.add(key)) return false;

    // Evict oldest entries if over limit
    if (placedCount.incrementAndGet() > MAX_PLACED_ENTRIES) {
        // Note: This is approximate eviction, not FIFO
        Iterator<Long> it = recentPlaced.iterator();
        while (placedCount.get() > MAX_PLACED_ENTRIES * 0.9 && it.hasNext()) {
            it.next();
            it.remove();
            placedCount.decrementAndGet();
        }
    }
    return true;
}
```

---

## Stream H: String Optimizations

**Files:** `src/main/java/ninja/trek/mc/goldgolem/world/entity/GoldGolemEntity.java`

**Depends on:** None

### Task H1: Optimize sanitizeJsonBaseName
**Issue #17**

```java
private static String sanitizeJsonBaseName(String name) {
    if (name == null || name.isEmpty()) return "golem";

    StringBuilder sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
        char c = name.charAt(i);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ' || c == '.') {
            sb.append(c);
        }
    }

    // Trim trailing dots and spaces
    int end = sb.length();
    while (end > 0 && (sb.charAt(end - 1) == '.' || sb.charAt(end - 1) == ' ')) {
        end--;
    }

    String result = sb.substring(0, end).trim();
    return result.isEmpty() ? "golem" : result;
}
```

### Task H2: Cache Gradient Copies
**Issue #17**

```java
private String[] cachedGradientCopy = null;
private boolean gradientCopyDirty = true;

public String[] getGradientCopy() {
    if (gradientCopyDirty || cachedGradientCopy == null) {
        cachedGradientCopy = new String[GRADIENT_SIZE];
        for (int i = 0; i < GRADIENT_SIZE; i++) {
            cachedGradientCopy[i] = (gradient[i] == null) ? "" : gradient[i];
        }
        gradientCopyDirty = false;
    }
    return cachedGradientCopy.clone(); // Still return copy for safety
}

public void setGradient(int idx, String value) {
    gradient[idx] = value;
    gradientCopyDirty = true;
}
```

---

## Stream I: Inventory Caching

**Files:**
- `src/main/java/ninja/trek/mc/goldgolem/world/entity/strategy/BaseMiningStrategy.java` (after Stream B)
- Or directly in Mining/Excavation strategies if Stream B not done

**Depends on:** Ideally Stream B, but can be done independently

### Task I1: Cache Tool Positions
**Issue #20**

```java
public class ToolCache {
    private int[] toolSlots = null;
    private int inventoryVersion = -1;

    public void invalidate() {
        toolSlots = null;
    }

    public int[] getToolSlots(Inventory inventory, int currentVersion) {
        if (toolSlots == null || currentVersion != inventoryVersion) {
            toolSlots = scanForTools(inventory);
            inventoryVersion = currentVersion;
        }
        return toolSlots;
    }

    private int[] scanForTools(Inventory inventory) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (isTool(stack)) {
                slots.add(i);
            }
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private boolean isTool(ItemStack stack) {
        return stack.getItem() instanceof ToolItem ||
               stack.getItem() instanceof ShearsItem;
    }
}
```

Add to strategy:
```java
private final ToolCache toolCache = new ToolCache();
private int lastInventoryHash = 0;

// Call when inventory changes
public void onInventoryChanged() {
    toolCache.invalidate();
}

// In findTwoTools:
int[] toolSlots = toolCache.getToolSlots(golem.getInventory(), computeInventoryHash());
```

---

## Execution Plan

### Phase 1: Foundation (All in Parallel)

Start these streams simultaneously:
- **Stream B**: BaseMiningStrategy extraction
- **Stream D**: Payload validation
- **Stream E**: WFC fixes
- **Stream F**: Exception handling
- **Stream G**: Ring buffer fix
- **Stream H**: String optimizations
- **Stream I**: Inventory caching

**Expected completion:** All streams independent, can run fully in parallel.

### Phase 2: Core Refactoring (After Phase 1)

Start these after Phase 1 completes:
- **Stream A**: GoldGolemEntity refactoring (needs Stream B's BaseMiningStrategy)
- **Stream C**: GUI thread safety & consolidation

**Note:** Stream A Task A6 depends on strategies having group managers, which requires Stream B to be done first.

### Phase 3: Integration

After Phase 2:
- Integration testing
- Fix any cross-stream conflicts
- Final cleanup and documentation

---

## Agent Assignment Recommendation

For maximum parallelism with 9 work streams:

| Agent | Stream | Estimated Complexity |
|-------|--------|---------------------|
| Agent 1 | B (Mining/Excavation) | High |
| Agent 2 | E (WFC/Tree) | High |
| Agent 3 | A (GoldGolemEntity) | High - Start Phase 2 |
| Agent 4 | C (GUI) | Medium - Start Phase 2 |
| Agent 5 | D (Payloads) | Medium |
| Agent 6 | F + G (Exceptions + Ring Buffer) | Low |
| Agent 7 | H + I (String + Inventory) | Low |

Total: 7 concurrent agents maximum, reducing to 2 in Phase 2.
