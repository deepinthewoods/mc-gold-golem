package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tree.TreeDefinition;
import ninja.trek.mc.goldgolem.tree.TreeModule;
import ninja.trek.mc.goldgolem.tree.TreeScanner;
import ninja.trek.mc.goldgolem.tree.TreeTile;
import ninja.trek.mc.goldgolem.tree.TreeTileCache;
import ninja.trek.mc.goldgolem.tree.TreeTileExtractor;
import ninja.trek.mc.goldgolem.tree.TreeWFCBuilder;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import ninja.trek.mc.goldgolem.util.GradientGroupManager;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Strategy for Tree building mode.
 * Uses PlacementPlanner for reach-aware block placement - the golem moves
 * within reach of each block before placing it.
 */
public class TreeBuildStrategy extends AbstractBuildStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(TreeBuildStrategy.class);

    // Gradient group manager for tree blocks
    private final GradientGroupManager groups = new GradientGroupManager();

    // E4: State machine for cache state consistency
    private enum CacheState {
        NOT_STARTED,
        CACHING,
        CACHED,
        FAILED
    }

    // Phase state machine for partial structure integration
    private enum TreePhase { NORMAL, MINING_GOLD, BUILDING }

    // E6: Resource recovery check cooldown (in ticks)
    private static final int RESOURCE_CHECK_COOLDOWN = 100; // 5 seconds

    // Tree building state
    private TreeTileCache treeTileCache = null;
    private TreeWFCBuilder treeWFCBuilder = null;
    // E4: Replace boolean flag with explicit state enum
    private CacheState cacheState = CacheState.NOT_STARTED;
    private boolean treeWaitingForInventory = false;

    // E6: Cooldown for resource recovery check
    private int resourceCheckCooldown = 0;

    // PlacementPlanner for reach-aware building
    private PlacementPlanner planner = null;
    private boolean tileBlocksLoaded = false;

    // Current tile being placed
    private BlockPos currentTileOrigin = null;
    private Map<BlockPos, BlockState> currentTileBlocks = new HashMap<>();
    // Positions where gradient sampled a mine action
    private Set<BlockPos> minePositions = new HashSet<>();

    // Gradient mining helper for mine-action slots
    private final GradientMiningHelper gradientMiner = new GradientMiningHelper();

    // Partial structure integration state
    private TreePhase treePhase = TreePhase.NORMAL;
    private Set<BlockPos> partialScanGoldPositions = new HashSet<>();
    private BlockPos partialScanOrigin = null;
    private Deque<BlockPos> goldMineQueue = new ArrayDeque<>();

    @Override
    public BuildMode getMode() {
        return BuildMode.TREE;
    }

    @Override
    public String getNbtPrefix() {
        return "Tree";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }
    }

    @Override
    public void tick(GoldGolemEntity golem, Player owner) {
        tickTreeMode(golem, owner);
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        // Tree mode completes when WFC is exhausted and no pending blocks
        return treeWFCBuilder != null && treeWFCBuilder.isFinished() && !treeWFCBuilder.hasPendingBlocks();
    }

    @Override
    public void writeNbt(CompoundTag nbt) {
        // E4: Save cache state as string instead of boolean
        nbt.putString("CacheState", cacheState.name());
        nbt.putBoolean("WaitingForInventory", treeWaitingForInventory);
        nbt.putBoolean("TileBlocksLoaded", tileBlocksLoaded);
        // E6: Save resource check cooldown
        nbt.putInt("ResourceCheckCooldown", resourceCheckCooldown);

        // Save current tile origin
        if (currentTileOrigin != null) {
            nbt.putInt("TileOriginX", currentTileOrigin.getX());
            nbt.putInt("TileOriginY", currentTileOrigin.getY());
            nbt.putInt("TileOriginZ", currentTileOrigin.getZ());
        }

        // Save planner state
        if (planner != null) {
            CompoundTag plannerNbt = new CompoundTag();
            planner.writeNbt(plannerNbt);
            nbt.put("Planner", plannerNbt);
        }

        // Save gradient groups
        groups.writeToNbt(nbt, "Groups");

        // Save partial scan state
        nbt.putString("TreePhase", treePhase.name());
        if (partialScanOrigin != null) {
            nbt.putInt("PartialOriginX", partialScanOrigin.getX());
            nbt.putInt("PartialOriginY", partialScanOrigin.getY());
            nbt.putInt("PartialOriginZ", partialScanOrigin.getZ());
        }
        if (!partialScanGoldPositions.isEmpty()) {
            int[] coords = new int[partialScanGoldPositions.size() * 3];
            int i = 0;
            for (BlockPos pos : partialScanGoldPositions) {
                coords[i++] = pos.getX();
                coords[i++] = pos.getY();
                coords[i++] = pos.getZ();
            }
            nbt.putIntArray("PartialGoldPositions", coords);
        }
    }

    @Override
    public void readNbt(CompoundTag nbt) {
        // E4: Load cache state from string, with backward compatibility for boolean
        if (nbt.contains("CacheState")) {
            String stateStr = nbt.getStringOr("CacheState", CacheState.NOT_STARTED.name());
            try {
                cacheState = CacheState.valueOf(stateStr);
            } catch (IllegalArgumentException e) {
                cacheState = CacheState.NOT_STARTED;
            }
        } else if (nbt.contains("TilesCached")) {
            // Backward compatibility: convert old boolean to new enum
            cacheState = nbt.getBooleanOr("TilesCached", false) ? CacheState.CACHED : CacheState.NOT_STARTED;
        }
        treeWaitingForInventory = nbt.getBooleanOr("WaitingForInventory", false);
        tileBlocksLoaded = nbt.getBooleanOr("TileBlocksLoaded", false);
        // E6: Load resource check cooldown
        resourceCheckCooldown = nbt.getIntOr("ResourceCheckCooldown", 0);

        // Load current tile origin
        if (nbt.contains("TileOriginX")) {
            currentTileOrigin = new BlockPos(
                nbt.getIntOr("TileOriginX", 0),
                nbt.getIntOr("TileOriginY", 0),
                nbt.getIntOr("TileOriginZ", 0)
            );
        }
        if (planner != null) {
            nbt.getCompound("Planner").ifPresent(planner::readNbt);
        }

        // Load gradient groups
        groups.readFromNbt(nbt, "Groups");

        // Load partial scan state
        if (nbt.contains("TreePhase")) {
            try {
                treePhase = TreePhase.valueOf(nbt.getStringOr("TreePhase", TreePhase.NORMAL.name()));
            } catch (IllegalArgumentException e) {
                treePhase = TreePhase.NORMAL;
            }
        }
        if (nbt.contains("PartialOriginX")) {
            partialScanOrigin = new BlockPos(
                nbt.getIntOr("PartialOriginX", 0),
                nbt.getIntOr("PartialOriginY", 0),
                nbt.getIntOr("PartialOriginZ", 0)
            );
        }
        partialScanGoldPositions.clear();
        if (nbt.contains("PartialGoldPositions")) {
            int[] coords = nbt.getIntArray("PartialGoldPositions").orElseGet(() -> new int[0]);
            for (int i = 0; i + 2 < coords.length; i += 3) {
                partialScanGoldPositions.add(new BlockPos(coords[i], coords[i + 1], coords[i + 2]));
            }
        }
        // Reconstruct gold mine queue for MINING_GOLD phase
        goldMineQueue.clear();
        if (treePhase == TreePhase.MINING_GOLD && entity != null) {
            for (BlockPos pos : partialScanGoldPositions) {
                if (entity.level().getBlockState(pos).is(Blocks.GOLD_BLOCK)) {
                    goldMineQueue.add(pos);
                }
            }
            if (goldMineQueue.isEmpty()) {
                treePhase = TreePhase.BUILDING;
            }
        }
    }

    @Override
    public boolean usesGroupUI() {
        return true;
    }

    @Override
    public boolean usesPlayerTracking() {
        return true;
    }

    /**
     * Get the gradient group manager for tree blocks.
     */
    public GradientGroupManager getGroups() {
        return groups;
    }

    /**
     * Clear building state.
     */
    public void clearState() {
        treeWFCBuilder = null;
        // E4: Reset cache state to NOT_STARTED
        cacheState = CacheState.NOT_STARTED;
        treeTileCache = null;
        treeWaitingForInventory = false;
        // E6: Reset resource check cooldown
        resourceCheckCooldown = 0;
        tileBlocksLoaded = false;
        currentTileOrigin = null;
        currentTileBlocks.clear();
        minePositions.clear();
        if (planner != null) {
            planner.clear();
        }
        // Clear partial scan state
        treePhase = TreePhase.NORMAL;
        partialScanGoldPositions.clear();
        partialScanOrigin = null;
        goldMineQueue.clear();
    }

    // ========== Getters ==========

    // E4: Updated to use CacheState enum
    public boolean isTilesCached() { return cacheState == CacheState.CACHED; }
    public boolean isWaitingForInventory() { return treeWaitingForInventory; }

    public void setTilesCached(boolean cached) {
        // E4: Convert boolean to appropriate state
        this.cacheState = cached ? CacheState.CACHED : CacheState.NOT_STARTED;
    }

    public void setTileCache(TreeTileCache cache) {
        this.treeTileCache = cache;
    }

    public void setWFCBuilder(TreeWFCBuilder builder) {
        this.treeWFCBuilder = builder;
    }

    // ========== Polymorphic Dispatch Methods ==========

    @Override
    public boolean isWaitingForResources() {
        return treeWaitingForInventory;
    }

    @Override
    public void setWaitingForResources(boolean waiting) {
        treeWaitingForInventory = waiting;
        // When resuming (waiting=false), clear state so tiles will be recached
        if (!waiting) {
            clearState();
        }
    }

    @Override
    public void onConfigurationChanged(String configKey) {
        if ("tilingPreset".equals(configKey)) {
            clearState();
        }
    }

    @Override
    public void writeLegacyNbt(ValueOutput view) {
        view.putBoolean("TreeWaitingForInventory", treeWaitingForInventory);
        if (planner != null) {
            planner.writeView(view.child("TreePlanner"));
        }
        // Partial scan persistence
        view.putString("TreePhase", treePhase.name());
        if (partialScanOrigin != null) {
            view.putInt("PartialOriginX", partialScanOrigin.getX());
            view.putInt("PartialOriginY", partialScanOrigin.getY());
            view.putInt("PartialOriginZ", partialScanOrigin.getZ());
        }
        if (!partialScanGoldPositions.isEmpty()) {
            int[] coords = new int[partialScanGoldPositions.size() * 3];
            int i = 0;
            for (BlockPos pos : partialScanGoldPositions) {
                coords[i++] = pos.getX();
                coords[i++] = pos.getY();
                coords[i++] = pos.getZ();
            }
            view.putIntArray("PartialGoldPositions", coords);
        }
    }

    @Override
    public void readLegacyNbt(ValueInput view) {
        treeWaitingForInventory = view.getBooleanOr("TreeWaitingForInventory", false);
        if (planner == null && entity != null) {
            planner = new PlacementPlanner(entity);
        }
        if (planner != null) {
            view.child("TreePlanner").ifPresent(planner::readView);
        }
        // Load partial scan state
        String phaseStr = view.getStringOr("TreePhase", TreePhase.NORMAL.name());
        try {
            treePhase = TreePhase.valueOf(phaseStr);
        } catch (IllegalArgumentException e) {
            treePhase = TreePhase.NORMAL;
        }
        int ox = view.getIntOr("PartialOriginX", Integer.MIN_VALUE);
        if (ox != Integer.MIN_VALUE) {
            partialScanOrigin = new BlockPos(ox,
                view.getIntOr("PartialOriginY", 0),
                view.getIntOr("PartialOriginZ", 0));
        }
        partialScanGoldPositions.clear();
        int[] coords = view.getIntArray("PartialGoldPositions").orElseGet(() -> new int[0]);
        for (int i = 0; i + 2 < coords.length; i += 3) {
            partialScanGoldPositions.add(new BlockPos(coords[i], coords[i + 1], coords[i + 2]));
        }
        // Reconstruct gold mine queue
        goldMineQueue.clear();
        if (treePhase == TreePhase.MINING_GOLD && entity != null) {
            for (BlockPos pos : partialScanGoldPositions) {
                if (entity.level().getBlockState(pos).is(Blocks.GOLD_BLOCK)) {
                    goldMineQueue.add(pos);
                }
            }
            if (goldMineQueue.isEmpty()) {
                treePhase = TreePhase.BUILDING;
            }
        }
    }

    @Override
    public FeedResult handleFeedInteraction(Player player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }

        // Check if golem is standing on gold block → start partial scan
        if (entity != null && !entity.level().isClientSide()) {
            BlockPos belowFeet = entity.blockPosition().below();
            if (entity.level().getBlockState(belowFeet).is(Blocks.GOLD_BLOCK)) {
                startPartialScan(player, belowFeet);
                return FeedResult.STARTED;
            }
        }

        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        treeWaitingForInventory = false;
    }

    // ========== Partial Scan ==========

    private void startPartialScan(Player player, BlockPos goldPos) {
        if (entity.getTreeModules().isEmpty()) return;

        TreeScanner.PartialScanResult result = TreeScanner.scanPartial(
            entity.level(), goldPos, player);
        if (!result.ok()) {
            LOGGER.warn("Partial scan failed: {}", result.error());
            return;
        }

        // Reset WFC state (keep tile cache and modules)
        treeWFCBuilder = null;
        cacheState = CacheState.NOT_STARTED;
        tileBlocksLoaded = false;
        currentTileOrigin = null;
        currentTileBlocks.clear();
        minePositions.clear();
        if (planner != null) planner.clear();

        // Store scan results
        partialScanGoldPositions = result.goldPositions();
        partialScanOrigin = goldPos;

        // Mine gold first if golem has a tool, otherwise go straight to building
        if (golemHasTool() && !partialScanGoldPositions.isEmpty()) {
            treePhase = TreePhase.MINING_GOLD;
            goldMineQueue = new ArrayDeque<>(partialScanGoldPositions);
        } else {
            treePhase = TreePhase.BUILDING;
        }

        // Ensure building is active
        entity.setBuildingPaths(true);
    }

    private boolean golemHasTool() {
        if (entity == null) return false;
        var inventory = entity.getInventory();
        BlockState goldState = Blocks.GOLD_BLOCK.defaultBlockState();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getDestroySpeed(goldState) > 1.0f) {
                return true;
            }
        }
        return false;
    }

    // ========== Gold Mining Phase ==========

    private void tickGoldMining(GoldGolemEntity golem) {
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }

        // If currently mining a block, tick the miner
        if (gradientMiner.isMining()) {
            boolean done = gradientMiner.tickMining(golem, isLeftHandActive());
            if (done) {
                gradientMiner.reset(golem);
                // Remove from queue — block is now mined
            }
            return;
        }

        // If queue is empty, transition to building
        if (goldMineQueue.isEmpty()) {
            treePhase = TreePhase.BUILDING;
            planner.clear();
            tileBlocksLoaded = false;
            return;
        }

        // Load gold positions into planner if needed
        if (planner.isComplete()) {
            List<BlockPos> remaining = new ArrayList<>();
            // Only queue positions that are still gold blocks
            for (BlockPos pos : goldMineQueue) {
                if (golem.level().getBlockState(pos).is(Blocks.GOLD_BLOCK)) {
                    remaining.add(pos);
                }
            }
            goldMineQueue = new ArrayDeque<>(remaining);
            if (remaining.isEmpty()) {
                treePhase = TreePhase.BUILDING;
                planner.clear();
                tileBlocksLoaded = false;
                return;
            }
            planner.setBlocks(remaining, pos ->
                !golem.level().getBlockState(pos).is(Blocks.GOLD_BLOCK));
        }

        // Tick planner — callback starts mining instead of placing
        PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
            goldMineQueue.remove(pos);
            gradientMiner.startMining(pos);
            return false; // mining happens over subsequent ticks
        });

        if (result == PlacementPlanner.TickResult.COMPLETED) {
            // All gold mined or skipped
            treePhase = TreePhase.BUILDING;
            planner.clear();
            tileBlocksLoaded = false;
        }
    }

    // ========== Main tick logic ==========

    private void tickTreeMode(GoldGolemEntity golem, Player owner) {
        // Tree mode: WFC-based building that follows player with nuggets
        if (!golem.isBuildingPaths()) return;

        // Ensure planner exists
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }

        List<TreeModule> treeModules = golem.getTreeModules();
        BlockPos treeOrigin = golem.getTreeOrigin();
        List<String> treeUniqueBlockIds = golem.getTreeUniqueBlockIds();
        TilingPreset treeTilingPreset = golem.getTreeTilingPreset();

        if (treeModules.isEmpty() || treeOrigin == null) return;

        // Handle gold mining phase before any WFC/building logic
        if (treePhase == TreePhase.MINING_GOLD) {
            tickGoldMining(golem);
            return;
        }

        // E6: If waiting for inventory, check periodically if resources are available
        if (isWaitingForResources()) {
            resourceCheckCooldown--;
            if (resourceCheckCooldown <= 0) {
                resourceCheckCooldown = RESOURCE_CHECK_COOLDOWN;
                // Check if the golem now has required resources
                if (hasRequiredResources(golem)) {
                    setWaitingForResources(false);
                    LOGGER.info("Resources available, resuming tree building");
                }
            }

            // Show thunder cloud particles periodically
            if (golem.tickCount % 20 == 0) {
                if (golem.level() instanceof ServerLevel sw) {
                    sw.sendParticles(ParticleTypes.CLOUD,
                        golem.getX(), golem.getY() + 2.0, golem.getZ(),
                        6, 0.4, 0.2, 0.4, 0.02);
                }
            }
            // Don't do any building work while waiting
            return;
        }

        // Cache tiles if not already done
        if (cacheState != CacheState.CACHED || treeTileCache == null) {
            try {
                // Build ground blocks set from persisted ground type
                Set<Block> groundBlocks = new HashSet<>();
                String groundId = golem.getTreeGroundBlockId();
                if (groundId != null) {
                    groundBlocks.add(Blocks.GRASS_BLOCK);
                    groundBlocks.add(Blocks.DIRT);
                    groundBlocks.add(Blocks.DIRT_PATH);
                }

                // Build stop blocks set (air, gold, ground types)
                Set<Block> stopBlocks = new HashSet<>();
                stopBlocks.add(Blocks.GOLD_BLOCK);
                stopBlocks.addAll(groundBlocks);

                // Extract tiles using current preset (groundBlockId flows through TreeDefinition)
                TreeDefinition def = new TreeDefinition(treeOrigin, treeModules, treeUniqueBlockIds, groundId);
                var stored = golem.getTreeModuleBlockStates();
                treeTileCache = TreeTileExtractor.extract(
                    golem.level(), def, treeTilingPreset, treeOrigin,
                    (stored != null && !stored.isEmpty()) ? stored : null);
                cacheState = CacheState.CACHED;

                if (treeTileCache.isEmpty()) {
                    // No tiles extracted, stop building permanently
                    golem.setBuildingPaths(false);
                    return;
                }

                // Initialize WFC builder (only if new)
                if (treeWFCBuilder == null) {
                    Random random = new Random(golem.getUUID().getMostSignificantBits());
                    BlockPos wfcStart = (treePhase == TreePhase.BUILDING && partialScanOrigin != null)
                        ? partialScanOrigin : golem.blockPosition();
                    Set<BlockPos> overrides = (treePhase == TreePhase.BUILDING)
                        ? partialScanGoldPositions : Collections.emptySet();
                    treeWFCBuilder = new TreeWFCBuilder(
                        treeTileCache, golem.level(), wfcStart, stopBlocks,
                        groundBlocks, random, overrides);
                }

            } catch (OutOfMemoryError e) {
                LOGGER.error("Out of memory extracting tree tiles - tree may be too complex", e);
                cacheState = CacheState.FAILED;
                treeTileCache = null;
                golem.setBuildingPaths(false);
                return;
            } catch (IllegalArgumentException e) {
                LOGGER.error("Invalid tree definition: {}", e.getMessage());
                cacheState = CacheState.FAILED;
                treeTileCache = null;
                golem.setBuildingPaths(false);
                return;
            } catch (Exception e) {
                LOGGER.error("Failed to cache tree tiles", e);
                cacheState = CacheState.FAILED;
                treeTileCache = null;
                golem.setBuildingPaths(false);
                return;
            }
        }

        // Run WFC algorithm steps (run multiple steps per tick for faster generation)
        if (treeWFCBuilder != null && !treeWFCBuilder.isFinished()) {
            // Run a few WFC steps per tick
            for (int i = 0; i < 5 && !treeWFCBuilder.isFinished(); i++) {
                treeWFCBuilder.step();
            }
        }

        // Process current tile with PlacementPlanner
        if (currentTileOrigin == null || (planner.isComplete() && currentTileBlocks.isEmpty())) {
            // Get next tile from WFC
            if (treeWFCBuilder != null && treeWFCBuilder.hasPendingBlocks()) {
                currentTileOrigin = treeWFCBuilder.getNextBuildPosition();
                if (currentTileOrigin != null) {
                    // Load tile blocks
                    loadTileBlocks(golem, currentTileOrigin);
                    tileBlocksLoaded = false;
                }
            }
        }

        // Load tile blocks into planner if needed
        if (currentTileOrigin != null && !tileBlocksLoaded && (!currentTileBlocks.isEmpty() || !minePositions.isEmpty())) {
            // Combine placement and mine positions
            List<BlockPos> allPositions = new ArrayList<>(currentTileBlocks.keySet());
            allPositions.addAll(minePositions);
            // Use block checker to skip already-correct blocks
            planner.setBlocks(allPositions, pos -> {
                if (minePositions.contains(pos)) {
                    return golem.level().getBlockState(pos).isAir(); // already mined
                }
                BlockState expected = currentTileBlocks.get(pos);
                if (expected == null) return true; // Skip if no expected state
                BlockState current = golem.level().getBlockState(pos);
                return current.getBlock() == expected.getBlock();
            });

            // Set up exclusion zone filter (inverted pyramid above golem)
            planner.setBlockFilter(pos -> {
                BlockPos golemFeet = golem.blockPosition();
                int dy = pos.getY() - golemFeet.getY();
                if (dy <= 0) return false;
                int dxAbs = Math.abs(pos.getX() - golemFeet.getX());
                int dzAbs = Math.abs(pos.getZ() - golemFeet.getZ());
                int chebyshev = Math.max(dxAbs, dzAbs);
                return chebyshev <= dy;
            });

            // Set up neighbor scorer
            planner.setBlockScorer(pos -> {
                var world = golem.level();
                int neighbors = 0;
                if (!world.getBlockState(pos.north()).isAir()) neighbors++;
                if (!world.getBlockState(pos.south()).isAir()) neighbors++;
                if (!world.getBlockState(pos.east()).isAir()) neighbors++;
                if (!world.getBlockState(pos.west()).isAir()) neighbors++;
                if (!world.getBlockState(pos.below()).isAir()) neighbors++;
                return neighbors;
            });

            tileBlocksLoaded = true;
        }

        // No current tile, check if done
        if (currentTileOrigin == null) {
            if (treeWFCBuilder != null && treeWFCBuilder.isFinished() && !treeWFCBuilder.hasPendingBlocks()) {
                // Reset partial scan state on completion
                treePhase = TreePhase.NORMAL;
                partialScanGoldPositions.clear();
                partialScanOrigin = null;
                golem.setBuildingPaths(false);
            }
            return;
        }

        // Tick with 2-tick pacing
        // Tick gradient mining if active
        if (gradientMiner.isMining()) {
            boolean done = gradientMiner.tickMining(golem, isLeftHandActive());
            if (done) {
                gradientMiner.reset(golem);
            }
            return;
        }

        if (!shouldPlaceThisTick()) {
            return;
        }

        // Use planner to handle movement and placement
        PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
            return placeTreeBlock(golem, pos, nextPos);
        });

        switch (result) {
            case PLACED_BLOCK:
                alternateHand();
                break;

            case COMPLETED:
                // Tile complete, move to next
                currentTileOrigin = null;
                currentTileBlocks.clear();
                tileBlocksLoaded = false;
                break;

            case DEFERRED:
                // Block was deferred - check if it was due to inventory
                // If so, mark waiting for resources
                break;

            case WORKING:
            case IDLE:
                // Still working or nothing to do
                break;
        }
    }

    /**
     * Load all blocks for a tile into the currentTileBlocks map.
     */
    private void loadTileBlocks(GoldGolemEntity golem, BlockPos tileOriginPos) {
        currentTileBlocks.clear();
        minePositions.clear();

        if (treeWFCBuilder == null) return;

        String tileId = treeWFCBuilder.getCollapsedTile(tileOriginPos);
        if (tileId == null) return;

        TreeTile tile = treeWFCBuilder.getTile(tileId);
        if (tile == null) return;

        // Collect all blocks in the tile
        int tileSize = tile.size;
        for (int dx = 0; dx < tileSize; dx++) {
            for (int dy = 0; dy < tileSize; dy++) {
                for (int dz = 0; dz < tileSize; dz++) {
                    BlockPos placePos = tileOriginPos.offset(dx, dy, dz);
                    BlockState targetState = tile.getBlock(dx, dy, dz);

                    // Skip air blocks
                    if (targetState.isAir()) continue;

                    // Skip ground marker positions (ground already exists)
                    if (targetState == TreeTileExtractor.GROUND_MARKER) continue;

                    // Don't overwrite existing non-air blocks (except gold from partial scan)
                    if (!golem.level().getBlockState(placePos).isAir()) {
                        if (partialScanGoldPositions.contains(placePos)
                                && golem.level().getBlockState(placePos).is(Blocks.GOLD_BLOCK)) {
                            // Allow overwriting unmined gold blocks from partial scan
                        } else {
                            continue;
                        }
                    }

                    // Check if gradient maps to a mine action
                    if (isTreeGradientMineAction(golem, targetState, placePos)) {
                        minePositions.add(placePos);
                        continue;
                    }

                    // Sample from gradient for this block type
                    BlockState finalState = sampleTreeGradient(golem, targetState, placePos);
                    // If null, gradient slot was empty - skip this block
                    if (finalState != null) {
                        currentTileBlocks.put(placePos, finalState);
                    }
                }
            }
        }
    }

    /**
     * Place a single tree block.
     * @return true if the block was placed successfully
     */
    private boolean placeTreeBlock(GoldGolemEntity golem, BlockPos pos, BlockPos nextPos) {
        // Check for mine action
        if (minePositions.contains(pos)) {
            minePositions.remove(pos);
            gradientMiner.startMining(pos);
            return false; // will mine over subsequent ticks
        }
        BlockState stateToPlace = currentTileBlocks.get(pos);
        if (stateToPlace == null) return false;

        // Consume from inventory
        String blockIdToConsume = BuiltInRegistries.BLOCK.getKey(stateToPlace.getBlock()).toString();
        if (!golem.consumeBlockFromInventory(blockIdToConsume)) {
            // No blocks in inventory - mark as depleted and waiting
            golem.handleMissingBuildingBlock();
            return false;
        }

        // Place the block
        golem.level().setBlock(pos, stateToPlace, 3);
        currentTileBlocks.remove(pos);

        // Spawn particles
        if (golem.level() instanceof ServerLevel sw) {
            sw.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                3, 0.3, 0.3, 0.3, 0.0);
        }

        // Update hand animations
        golem.beginHandAnimation(isLeftHandActive(), pos, nextPos);

        return true;
    }

    private BlockState sampleTreeGradient(GoldGolemEntity golem, BlockState originalState, BlockPos pos) {
        // Get block ID
        String blockId = BuiltInRegistries.BLOCK.getKey(originalState.getBlock()).toString();

        // Find the gradient group for this block
        Integer groupIdx = golem.getTreeBlockGroup().get(blockId);
        if (groupIdx == null || groupIdx >= golem.getTreeGroupSlots().size()) {
            // No gradient assigned, use original
            return originalState;
        }

        String[] gradientSlots = golem.getTreeGroupSlots().get(groupIdx);
        float window = golem.getTreeGroupWindows().get(groupIdx);
        int noiseScale = (groupIdx < golem.getTreeGroupNoiseScales().size()) ? golem.getTreeGroupNoiseScales().get(groupIdx) : 1;

        // Sample from gradient using position hash
        int lastNonEmpty = -1;
        for (int i = gradientSlots.length - 1; i >= 0; i--) {
            if (gradientSlots[i] != null && !gradientSlots[i].isEmpty()) {
                lastNonEmpty = i;
                break;
            }
        }

        if (lastNonEmpty < 0) {
            // All gradient slots empty - skip this block entirely
            return null;
        }

        int w = (int) Math.max(1, Math.round(window));
        double u01 = golem.sampleGradientNoise01(pos, noiseScale);
        int idx = (int) Math.floor(u01 * (double) w);
        if (idx >= w) idx = w - 1;
        if (idx > lastNonEmpty) idx = lastNonEmpty;

        String sampledBlockId = gradientSlots[idx];
        if (sampledBlockId == null || sampledBlockId.isEmpty()) {
            // Sampled slot is empty - skip this block entirely
            return null;
        }

        // Mine actions are handled by isTreeGradientMineAction, not here
        if (GradientSlotUtil.isMineAction(sampledBlockId)) return null;

        Identifier id = Identifier.tryParse(sampledBlockId);
        if (id == null) return null;
        net.minecraft.world.level.block.Block sampledBlock = BuiltInRegistries.BLOCK.getValue(id);
        return sampledBlock.defaultBlockState();
    }

    /**
     * Check if the gradient for a tree block maps to a mine action.
     * Uses the same sampling logic as sampleTreeGradient but only checks the result.
     */
    private boolean isTreeGradientMineAction(GoldGolemEntity golem, BlockState originalState, BlockPos pos) {
        String blockId = BuiltInRegistries.BLOCK.getKey(originalState.getBlock()).toString();
        Integer groupIdx = golem.getTreeBlockGroup().get(blockId);
        if (groupIdx == null || groupIdx >= golem.getTreeGroupSlots().size()) return false;

        String[] gradientSlots = golem.getTreeGroupSlots().get(groupIdx);
        float window = golem.getTreeGroupWindows().get(groupIdx);
        int noiseScale = (groupIdx < golem.getTreeGroupNoiseScales().size()) ? golem.getTreeGroupNoiseScales().get(groupIdx) : 1;

        int lastNonEmpty = -1;
        for (int i = gradientSlots.length - 1; i >= 0; i--) {
            if (gradientSlots[i] != null && !gradientSlots[i].isEmpty()) { lastNonEmpty = i; break; }
        }
        if (lastNonEmpty < 0) return false;

        int w = (int) Math.max(1, Math.round(window));
        double u01 = golem.sampleGradientNoise01(pos, noiseScale);
        int idx = (int) Math.floor(u01 * (double) w);
        if (idx >= w) idx = w - 1;
        if (idx > lastNonEmpty) idx = lastNonEmpty;

        String sampledBlockId = gradientSlots[idx];
        return sampledBlockId != null && GradientSlotUtil.isMineAction(sampledBlockId);
    }

    /**
     * E6: Check if the golem has required resources to continue building.
     * Returns true if there are blocks in inventory that match current tile needs.
     */
    private boolean hasRequiredResources(GoldGolemEntity golem) {
        // If no current tile blocks to place, we don't need resources yet
        if (currentTileBlocks.isEmpty()) {
            return true;
        }

        // Gather required block IDs
        Set<String> requiredBlockIds = new HashSet<>();
        for (BlockState state : currentTileBlocks.values()) {
            requiredBlockIds.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }

        // Check if we have at least one of the required blocks in inventory
        var inventory = golem.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                String stackId = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
                if (requiredBlockIds.contains(stackId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
