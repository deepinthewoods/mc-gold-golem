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

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Strategy for Tree building mode.
 * Builds tree structures using Wave Function Collapse tiling.
 */
public class TreeBuildStrategy extends AbstractBuildStrategy {

    // Tree building state
    private TreeTileCache treeTileCache = null;
    private TreeWFCBuilder treeWFCBuilder = null;
    private boolean treeTilesCached = false;
    private boolean treeWaitingForInventory = false;

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
        // Note: treeTileCache and treeWFCBuilder are transient (rebuilt on load)
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        treeTilesCached = nbt.getBoolean("TilesCached", false);
        treeWaitingForInventory = nbt.getBoolean("WaitingForInventory", false);
        // Note: treeTileCache and treeWFCBuilder will be rebuilt when tick resumes
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

        // Place blocks from WFC output at 2-tick intervals
        if (placementTickCounter == 0 && treeWFCBuilder != null && treeWFCBuilder.hasPendingBlocks()) {
            BlockPos buildPos = treeWFCBuilder.getNextBuildPosition();
            if (buildPos != null) {
                // Try to pathfind to the build position
                double ty = golem.computeGroundTargetY(new Vec3d(buildPos.getX() + 0.5, buildPos.getY(), buildPos.getZ() + 0.5));
                golem.getNavigation().startMovingTo(buildPos.getX() + 0.5, ty, buildPos.getZ() + 0.5, 1.1);

                // Check if stuck and teleport if necessary
                double dx = golem.getX() - (buildPos.getX() + 0.5);
                double dz = golem.getZ() - (buildPos.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (golem.getNavigation().isIdle() && distSq > 1.0) {
                    stuckTicks++;
                    if (stuckTicks >= 20) {
                        if (golem.getEntityWorld() instanceof ServerWorld sw) {
                            sw.spawnParticles(ParticleTypes.PORTAL, golem.getX(), golem.getY() + 0.5, golem.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                            sw.spawnParticles(ParticleTypes.PORTAL, buildPos.getX() + 0.5, ty + 0.5, buildPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                        }
                        golem.refreshPositionAndAngles(buildPos.getX() + 0.5, ty, buildPos.getZ() + 0.5, golem.getYaw(), golem.getPitch());
                        golem.getNavigation().stop();
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }

                // Place the blocks from the tile - returns false if inventory depleted
                boolean success = placeTreeTile(golem, buildPos);
                if (!success) {
                    // Out of inventory - stop building and wait for restock
                    golem.setBuildingPaths(false);
                    treeWaitingForInventory = true;

                    // Show angry villager particles
                    if (golem.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.ANGRY_VILLAGER,
                            golem.getX(), golem.getY() + 2.0, golem.getZ(),
                            5, 0.3, 0.3, 0.3, 0.0);
                    }

                    // Note: getNextBuildPosition already removed the position from queue
                    // The next tile will be attempted when resumed
                    return;
                }
            }
        }

        // Check if finished building (no more pending blocks and WFC is done)
        if (treeWFCBuilder != null && treeWFCBuilder.isFinished() && !treeWFCBuilder.hasPendingBlocks()) {
            golem.setBuildingPaths(false);
        }
    }

    private boolean placeTreeTile(GoldGolemEntity golem, BlockPos tileOriginPos) {
        if (golem.getEntityWorld().isClient()) return true;
        if (treeWFCBuilder == null) return true;

        String tileId = treeWFCBuilder.getCollapsedTile(tileOriginPos);
        if (tileId == null) return true;

        TreeTile tile = treeWFCBuilder.getTile(tileId);
        if (tile == null) return true;

        // Track if we successfully placed at least one block, and if we failed due to inventory
        boolean placedAny = false;
        boolean inventoryDepleted = false;

        // Place all blocks in the tile
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

                    // Consume from inventory
                    String blockIdToConsume = Registries.BLOCK.getId(finalState.getBlock()).toString();
                    if (!golem.consumeBlockFromInventory(blockIdToConsume)) {
                        // No blocks in inventory - mark as depleted
                        inventoryDepleted = true;
                        continue;
                    }

                    // Place the block
                    golem.getEntityWorld().setBlockState(placePos, finalState, 3);
                    placedAny = true;

                    // Spawn particles
                    if (golem.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                            placePos.getX() + 0.5, placePos.getY() + 0.5, placePos.getZ() + 0.5,
                            3, 0.3, 0.3, 0.3, 0.0);
                    }
                }
            }
        }

        // If we depleted inventory and couldn't place anything this tile, fail
        if (inventoryDepleted && !placedAny) {
            return false; // Signal inventory depletion
        }

        // Update hand animations (use first block of tile for visual)
        if (placedAny) {
            golem.setLeftHandTargetPos(Optional.of(tileOriginPos));
            golem.setLeftArmHasTarget(true);
            golem.setLeftHandAnimationTick(0);
        }

        return true; // Success (or partial success is okay)
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

        int idx = Math.abs(pos.getX() + pos.getZ() + pos.getY()) % (int) Math.max(1, Math.round(window));
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
