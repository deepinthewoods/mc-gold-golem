package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.Registries;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tree.TreeDefinition;
import ninja.trek.mc.goldgolem.tree.TreeModule;
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
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
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
    public void writeNbt(NbtCompound nbt) {
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
            NbtCompound plannerNbt = new NbtCompound();
            planner.writeNbt(plannerNbt);
            nbt.put("Planner", plannerNbt);
        }

        // Save gradient groups
        groups.writeToNbt(nbt, "Groups");
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        // E4: Load cache state from string, with backward compatibility for boolean
        if (nbt.contains("CacheState")) {
            String stateStr = nbt.getString("CacheState", CacheState.NOT_STARTED.name());
            try {
                cacheState = CacheState.valueOf(stateStr);
            } catch (IllegalArgumentException e) {
                cacheState = CacheState.NOT_STARTED;
            }
        } else if (nbt.contains("TilesCached")) {
            // Backward compatibility: convert old boolean to new enum
            cacheState = nbt.getBoolean("TilesCached", false) ? CacheState.CACHED : CacheState.NOT_STARTED;
        }
        treeWaitingForInventory = nbt.getBoolean("WaitingForInventory", false);
        tileBlocksLoaded = nbt.getBoolean("TileBlocksLoaded", false);
        // E6: Load resource check cooldown
        resourceCheckCooldown = nbt.getInt("ResourceCheckCooldown", 0);

        // Load current tile origin
        if (nbt.contains("TileOriginX")) {
            currentTileOrigin = new BlockPos(
                nbt.getInt("TileOriginX", 0),
                nbt.getInt("TileOriginY", 0),
                nbt.getInt("TileOriginZ", 0)
            );
        }
        if (planner != null) {
            nbt.getCompound("Planner").ifPresent(planner::readNbt);
        }

        // Load gradient groups
        groups.readFromNbt(nbt, "Groups");
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
        if (planner != null) {
            planner.clear();
        }
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
    public void writeLegacyNbt(WriteView view) {
        view.putBoolean("TreeWaitingForInventory", treeWaitingForInventory);
        if (planner != null) {
            planner.writeView(view.get("TreePlanner"));
        }
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        treeWaitingForInventory = view.getBoolean("TreeWaitingForInventory", false);
        if (planner == null && entity != null) {
            planner = new PlacementPlanner(entity);
        }
        if (planner != null) {
            view.getOptionalReadView("TreePlanner").ifPresent(planner::readView);
        }
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        treeWaitingForInventory = false;
    }

    // ========== Main tick logic ==========

    private void tickTreeMode(GoldGolemEntity golem, PlayerEntity owner) {
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
            if (golem.age % 20 == 0) {
                if (golem.getEntityWorld() instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.CLOUD,
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
                // Build stop blocks set (air, gold, ground types)
                Set<net.minecraft.block.Block> stopBlocks = new HashSet<>();
                stopBlocks.add(Blocks.GOLD_BLOCK);
                if (owner != null) {
                    BlockPos playerGround = owner.getBlockPos().down();
                    BlockState gs = golem.getEntityWorld().getBlockState(playerGround);
                    net.minecraft.block.Block groundType = gs.getBlock();
                    if (groundType == Blocks.GRASS_BLOCK ||
                        groundType == Blocks.DIRT ||
                        groundType == Blocks.DIRT_PATH) {
                        stopBlocks.add(Blocks.GRASS_BLOCK);
                        stopBlocks.add(Blocks.DIRT);
                        stopBlocks.add(Blocks.DIRT_PATH);
                    }
                }

                // Extract tiles using current preset
                TreeDefinition def = new TreeDefinition(treeOrigin, treeModules, treeUniqueBlockIds);
                var stored = golem.getTreeModuleBlockStates();
                treeTileCache = TreeTileExtractor.extract(
                    golem.getEntityWorld(), def, treeTilingPreset, treeOrigin,
                    (stored != null && !stored.isEmpty()) ? stored : null);
                cacheState = CacheState.CACHED;

                if (treeTileCache.isEmpty()) {
                    // No tiles extracted, stop building permanently
                    golem.setBuildingPaths(false);
                    return;
                }

                // Initialize WFC builder at golem's current position (only if new)
                if (treeWFCBuilder == null) {
                    Random random = new Random(golem.getUuid().getMostSignificantBits());
                    treeWFCBuilder = new TreeWFCBuilder(
                        treeTileCache, golem.getEntityWorld(), golem.getBlockPos(), stopBlocks, random);
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
                    return golem.getEntityWorld().getBlockState(pos).isAir(); // already mined
                }
                BlockState expected = currentTileBlocks.get(pos);
                if (expected == null) return true; // Skip if no expected state
                BlockState current = golem.getEntityWorld().getBlockState(pos);
                return current.getBlock() == expected.getBlock();
            });
            tileBlocksLoaded = true;
        }

        // No current tile, check if done
        if (currentTileOrigin == null) {
            if (treeWFCBuilder != null && treeWFCBuilder.isFinished() && !treeWFCBuilder.hasPendingBlocks()) {
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
                    BlockPos placePos = tileOriginPos.add(dx, dy, dz);
                    BlockState targetState = tile.getBlock(dx, dy, dz);

                    // Skip air blocks
                    if (targetState.isAir()) continue;

                    // Don't overwrite existing non-air blocks
                    if (!golem.getEntityWorld().getBlockState(placePos).isAir()) continue;

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
        String blockIdToConsume = Registries.BLOCK.getId(stateToPlace.getBlock()).toString();
        if (!golem.consumeBlockFromInventory(blockIdToConsume)) {
            // No blocks in inventory - mark as depleted and waiting
            golem.handleMissingBuildingBlock();
            return false;
        }

        // Place the block
        golem.getEntityWorld().setBlockState(pos, stateToPlace, 3);
        currentTileBlocks.remove(pos);

        // Spawn particles
        if (golem.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                3, 0.3, 0.3, 0.3, 0.0);
        }

        // Update hand animations
        golem.beginHandAnimation(isLeftHandActive(), pos, nextPos);

        return true;
    }

    private BlockState sampleTreeGradient(GoldGolemEntity golem, BlockState originalState, BlockPos pos) {
        // Get block ID
        String blockId = Registries.BLOCK.getId(originalState.getBlock()).toString();

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
        net.minecraft.block.Block sampledBlock = Registries.BLOCK.get(id);
        return sampledBlock.getDefaultState();
    }

    /**
     * Check if the gradient for a tree block maps to a mine action.
     * Uses the same sampling logic as sampleTreeGradient but only checks the result.
     */
    private boolean isTreeGradientMineAction(GoldGolemEntity golem, BlockState originalState, BlockPos pos) {
        String blockId = Registries.BLOCK.getId(originalState.getBlock()).toString();
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
            requiredBlockIds.add(Registries.BLOCK.getId(state.getBlock()).toString());
        }

        // Check if we have at least one of the required blocks in inventory
        var inventory = golem.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            var stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof net.minecraft.item.BlockItem bi) {
                String stackId = Registries.BLOCK.getId(bi.getBlock()).toString();
                if (requiredBlockIds.contains(stackId)) {
                    return true;
                }
            }
        }
        return false;
    }
}
