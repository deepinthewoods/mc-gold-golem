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
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.*;

/**
 * Strategy for Tree building mode.
 * Uses PlacementPlanner for reach-aware block placement - the golem moves
 * within reach of each block before placing it.
 */
public class TreeBuildStrategy extends AbstractBuildStrategy {

    // Tree building state
    private TreeTileCache treeTileCache = null;
    private TreeWFCBuilder treeWFCBuilder = null;
    private boolean treeTilesCached = false;
    private boolean treeWaitingForInventory = false;

    // PlacementPlanner for reach-aware building
    private PlacementPlanner planner = null;
    private boolean tileBlocksLoaded = false;

    // Current tile being placed
    private BlockPos currentTileOrigin = null;
    private Map<BlockPos, BlockState> currentTileBlocks = new HashMap<>();

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
        nbt.putBoolean("TilesCached", treeTilesCached);
        nbt.putBoolean("WaitingForInventory", treeWaitingForInventory);
        nbt.putBoolean("TileBlocksLoaded", tileBlocksLoaded);

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
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        treeTilesCached = nbt.getBoolean("TilesCached", false);
        treeWaitingForInventory = nbt.getBoolean("WaitingForInventory", false);
        tileBlocksLoaded = nbt.getBoolean("TileBlocksLoaded", false);

        // Load current tile origin
        if (nbt.contains("TileOriginX")) {
            currentTileOrigin = new BlockPos(
                nbt.getInt("TileOriginX", 0),
                nbt.getInt("TileOriginY", 0),
                nbt.getInt("TileOriginZ", 0)
            );
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
     * Clear building state.
     */
    public void clearState() {
        treeWFCBuilder = null;
        treeTilesCached = false;
        treeTileCache = null;
        treeWaitingForInventory = false;
        tileBlocksLoaded = false;
        currentTileOrigin = null;
        currentTileBlocks.clear();
        if (planner != null) {
            planner.clear();
        }
    }

    // ========== Getters ==========

    public boolean isTilesCached() { return treeTilesCached; }
    public boolean isWaitingForInventory() { return treeWaitingForInventory; }

    public void setTilesCached(boolean cached) {
        this.treeTilesCached = cached;
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
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        treeWaitingForInventory = view.getBoolean("TreeWaitingForInventory", false);
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (treeWaitingForInventory) {
            treeWaitingForInventory = false;
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

        // If waiting for inventory, show angry particles and don't build
        if (treeWaitingForInventory) {
            // Show angry villager particles periodically
            if (golem.age % 20 == 0) {
                if (golem.getEntityWorld() instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                        golem.getX(), golem.getY() + 2.0, golem.getZ(),
                        1, 0.2, 0.2, 0.2, 0.0);
                }
            }
            // Don't do any building work while waiting
            return;
        }

        // Cache tiles if not already done
        if (!treeTilesCached || treeTileCache == null) {
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
                treeTileCache = TreeTileExtractor.extract(
                    golem.getEntityWorld(), def, treeTilingPreset, treeOrigin);
                treeTilesCached = true;

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

            } catch (Exception e) {
                // Failed to cache tiles, stop building
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
        if (currentTileOrigin != null && !tileBlocksLoaded && !currentTileBlocks.isEmpty()) {
            planner.setBlocks(new ArrayList<>(currentTileBlocks.keySet()));
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

                    // Sample from gradient for this block type
                    BlockState finalState = sampleTreeGradient(golem, targetState, placePos);
                    currentTileBlocks.put(placePos, finalState);
                }
            }
        }
    }

    /**
     * Place a single tree block.
     * @return true if the block was placed successfully
     */
    private boolean placeTreeBlock(GoldGolemEntity golem, BlockPos pos, BlockPos nextPos) {
        BlockState stateToPlace = currentTileBlocks.get(pos);
        if (stateToPlace == null) return false;

        // Consume from inventory
        String blockIdToConsume = Registries.BLOCK.getId(stateToPlace.getBlock()).toString();
        if (!golem.consumeBlockFromInventory(blockIdToConsume)) {
            // No blocks in inventory - mark as depleted and waiting
            golem.setBuildingPaths(false);
            treeWaitingForInventory = true;

            // Show angry villager particles
            if (golem.getEntityWorld() instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                    golem.getX(), golem.getY() + 2.0, golem.getZ(),
                    5, 0.3, 0.3, 0.3, 0.0);
            }
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
        golem.setLeftHandTargetPos(Optional.of(pos));
        golem.setLeftArmHasTarget(true);
        golem.setLeftHandAnimationTick(0);

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
            // Empty gradient, use original
            return originalState;
        }

        int w = (int) Math.max(1, Math.round(window));
        double u01 = golem.sampleGradientNoise01(pos, noiseScale);
        int idx = (int) Math.floor(u01 * (double) w);
        if (idx >= w) idx = w - 1;
        if (idx > lastNonEmpty) idx = lastNonEmpty;

        String sampledBlockId = gradientSlots[idx];
        if (sampledBlockId == null || sampledBlockId.isEmpty()) {
            return originalState;
        }

        Identifier id = Identifier.tryParse(sampledBlockId);
        if (id == null) return originalState;
        net.minecraft.block.Block sampledBlock = Registries.BLOCK.get(id);
        return sampledBlock.getDefaultState();
    }
}
