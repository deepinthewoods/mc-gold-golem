package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.util.GradientGroupManager;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Strategy for Tower building mode.
 * Uses PlacementPlanner for reach-aware block placement - the golem moves
 * within reach of each block before placing it, similar to how a player would build.
 */
public class TowerBuildStrategy extends AbstractBuildStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger(TowerBuildStrategy.class);
    private static final int LAYER_WINDOW_SIZE = 3;  // Load this many layers ahead

    // Gradient group manager for tower blocks
    private final GradientGroupManager groups = new GradientGroupManager();

    // Gradient mining helper for mine-action slots
    protected final GradientMiningHelper gradientMiner = new GradientMiningHelper();

    // Tower building state
    private int currentLayerY = 0;           // Current Y layer being processed
    private boolean layerInitialized = false; // Whether current layer blocks are loaded into planner
    private PlacementPlanner planner = null;
    private int totalHeight = 0;             // Cached for progress tracking
    private int lowestLoadedY = 0;           // Lowest Y layer currently in planner
    private int highestLoadedY = -1;         // Highest Y layer currently in planner

    @Override
    public BuildMode getMode() {
        return BuildMode.TOWER;
    }

    @Override
    public String getNbtPrefix() {
        return "Tower";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }
        totalHeight = golem.getTowerHeight();
    }

    @Override
    public void tick(GoldGolemEntity golem, Player owner) {
        tickTowerMode(golem, owner);
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        if (entity == null) return false;
        return highestLoadedY >= entity.getTowerHeight() - 1 && (planner == null || planner.isComplete());
    }

    @Override
    public void writeNbt(CompoundTag nbt) {
        nbt.putInt("CurrentLayerY", currentLayerY);
        nbt.putBoolean("LayerInitialized", layerInitialized);
        nbt.putInt("TotalHeight", totalHeight);
        nbt.putInt("LowestLoadedY", lowestLoadedY);
        nbt.putInt("HighestLoadedY", highestLoadedY);

        // Save planner state
        if (planner != null) {
            CompoundTag plannerNbt = new CompoundTag();
            planner.writeNbt(plannerNbt);
            nbt.put("Planner", plannerNbt);
        }

        // Save gradient groups
        groups.writeToNbt(nbt, "Groups");
    }

    @Override
    public void readNbt(CompoundTag nbt) {
        currentLayerY = nbt.getIntOr("CurrentLayerY", 0);
        layerInitialized = nbt.getBooleanOr("LayerInitialized", false);
        totalHeight = nbt.getIntOr("TotalHeight", 0);
        lowestLoadedY = nbt.getIntOr("LowestLoadedY", 0);
        highestLoadedY = nbt.getIntOr("HighestLoadedY", -1);

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
     * Get the gradient group manager for tower blocks.
     */
    public GradientGroupManager getGroups() {
        return groups;
    }

    /**
     * Clear building state.
     */
    public void clearState() {
        currentLayerY = 0;
        layerInitialized = false;
        lowestLoadedY = 0;
        highestLoadedY = -1;
        if (planner != null) {
            planner.clear();
        }
    }

    // ========== Getters ==========

    public int getCurrentLayerY() { return currentLayerY; }

    /**
     * Get building progress as a percentage (0-100).
     */
    public int getProgressPercent() {
        if (totalHeight <= 0) return 0;
        return Math.min(100, (currentLayerY * 100) / totalHeight);
    }

    // ========== Polymorphic Dispatch Methods ==========

    @Override
    public FeedResult handleFeedInteraction(Player player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        // Tower mode: always starts when nugget is fed
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        // Clear tower mode state
        clearState();
    }

    @Override
    public void onConfigurationChanged(String configKey) {
        if ("towerOrigin".equals(configKey)
                || "towerHeight".equals(configKey)
                || "towerGradient".equals(configKey)) {
            clearState();
            if (entity != null) {
                totalHeight = entity.getTowerHeight();
            }
        }
    }

    @Override
    public void writeLegacyNbt(ValueOutput view) {
        view.putInt("TowerCurrentY", currentLayerY);
        view.putBoolean("TowerLayerInitialized", layerInitialized);
        if (planner != null) {
            planner.writeView(view.child("TowerPlanner"));
        }
    }

    @Override
    public void readLegacyNbt(ValueInput view) {
        currentLayerY = view.getIntOr("TowerCurrentY", 0);
        layerInitialized = view.getBooleanOr("TowerLayerInitialized", false);
        if (planner == null && entity != null) {
            planner = new PlacementPlanner(entity);
        }
        if (planner != null) {
            view.child("TowerPlanner").ifPresent(planner::readView);
        }
    }

    // ========== Main tick logic ==========

    private void tickTowerMode(GoldGolemEntity golem, Player owner) {
        if (!golem.isBuildingPaths()) return;

        TowerModuleTemplate template = golem.getTowerTemplate();
        BlockPos origin = golem.getTowerOrigin();
        int height = golem.getTowerHeight();

        if (template == null || origin == null) {
            LOGGER.warn("Tower build halted: missing template or origin (template={}, origin={})",
                    template != null, origin);
            return;
        }

        // Ensure planner exists
        if (planner == null) {
            planner = new PlacementPlanner(golem);
            // Try to restore saved state
            // (In a full implementation, we'd load from NBT here)
        }

        // Check if we've finished building the tower
        if (highestLoadedY >= height - 1 && planner.isComplete()) {
            golem.setBuildingPaths(false);
            return;
        }

        // Initialize layers if needed — load up to LAYER_WINDOW_SIZE layers at once
        if (!layerInitialized && currentLayerY < height) {
            TowerModuleTemplate templateFinal = template;
            BlockPos originFinal = origin;

            // Find the first non-empty layer starting from currentLayerY
            int startY = currentLayerY;
            while (startY < height) {
                List<BlockPos> firstLayer = getLayerVoxels(golem, template, origin, startY);
                if (!firstLayer.isEmpty()) {
                    break;
                }
                startY++;
            }
            if (startY >= height) {
                currentLayerY = height;
                return;
            }

            // Load first layer with setBlocks (resets planner)
            List<BlockPos> firstLayerBlocks = getLayerVoxels(golem, template, origin, startY);
            planner.setBlocks(firstLayerBlocks, pos -> isBlockAlreadyCorrect(golem, templateFinal, originFinal, pos));
            lowestLoadedY = startY;
            highestLoadedY = startY;

            // Load additional layers with addBlocks
            for (int i = 1; i < LAYER_WINDOW_SIZE; i++) {
                int nextY = startY + i;
                if (nextY >= height) break;
                List<BlockPos> nextLayerBlocks = getLayerVoxels(golem, template, origin, nextY);
                if (!nextLayerBlocks.isEmpty()) {
                    planner.addBlocks(nextLayerBlocks, pos -> isBlockAlreadyCorrect(golem, templateFinal, originFinal, pos));
                }
                highestLoadedY = nextY;
            }

            // Set up exclusion zone filter (inverted pyramid above golem)
            planner.setBlockFilter(pos -> {
                BlockPos golemFeet = golem.blockPosition();
                int dy = pos.getY() - golemFeet.getY();
                if (dy <= 0) return false; // Only exclude above
                int dxAbs = Math.abs(pos.getX() - golemFeet.getX());
                int dzAbs = Math.abs(pos.getZ() - golemFeet.getZ());
                int chebyshev = Math.max(dxAbs, dzAbs);
                return chebyshev <= dy; // Inverted pyramid: at height H, exclude within Chebyshev H
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

            currentLayerY = lowestLoadedY;
            layerInitialized = true;
        }

        // Tick gradient mining if active
        if (gradientMiner.isMining()) {
            boolean done = gradientMiner.tickMining(golem, isLeftHandActive());
            if (done) {
                gradientMiner.reset(golem);
            }
            return;
        }

        // Tick the planner with 2-tick pacing (same as before)
        if (!shouldPlaceThisTick()) {
            return;
        }

        // Use planner to handle movement and placement
        PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
            return placeTowerBlock(golem, template, origin, pos, nextPos);
        });

        switch (result) {
            case PLACED_BLOCK:
                alternateHand();
                // Check if lowest loaded layer is now complete — slide window up
                int lowestLoadedWorldY = getBuildBaseY(origin) + lowestLoadedY;
                if (!planner.hasBlocksAtY(lowestLoadedWorldY)) {
                    lowestLoadedY++;
                    currentLayerY = lowestLoadedY;
                    // Load next layer at highestLoadedY + 1 if available
                    if (highestLoadedY + 1 < height) {
                        highestLoadedY++;
                        List<BlockPos> nextLayer = getLayerVoxels(golem, template, origin, highestLoadedY);
                        if (!nextLayer.isEmpty()) {
                            TowerModuleTemplate templateFinal2 = template;
                            BlockPos originFinal2 = origin;
                            planner.addBlocks(nextLayer, pos -> isBlockAlreadyCorrect(golem, templateFinal2, originFinal2, pos));
                        }
                    }
                }
                break;

            case COMPLETED:
                // Planner queue empty — check if there are more layers to load
                if (highestLoadedY + 1 < height) {
                    // Slide window: advance lowestLoadedY and load next layers
                    lowestLoadedY = highestLoadedY + 1;
                    currentLayerY = lowestLoadedY;
                    for (int i = 0; i < LAYER_WINDOW_SIZE; i++) {
                        int nextY = lowestLoadedY + i;
                        if (nextY >= height) break;
                        List<BlockPos> nextLayer = getLayerVoxels(golem, template, origin, nextY);
                        if (!nextLayer.isEmpty()) {
                            TowerModuleTemplate templateFinal3 = template;
                            BlockPos originFinal3 = origin;
                            if (i == 0) {
                                planner.setBlocks(nextLayer, pos -> isBlockAlreadyCorrect(golem, templateFinal3, originFinal3, pos));
                            } else {
                                planner.addBlocks(nextLayer, pos -> isBlockAlreadyCorrect(golem, templateFinal3, originFinal3, pos));
                            }
                        }
                        highestLoadedY = nextY;
                    }
                } else {
                    // Tower done
                    golem.setBuildingPaths(false);
                }
                break;

            case DEFERRED:
                // Block was deferred, planner will retry later
                break;

            case WORKING:
            case IDLE:
                // Still working or nothing to do
                break;
        }
    }

    /**
     * Get all voxels for a specific Y layer.
     */
    protected List<BlockPos> getLayerVoxels(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, int layerY) {
        if (template == null) return List.of();

        List<BlockPos> layerVoxels = new ArrayList<>();
        int moduleHeight = template.moduleHeight;
        if (moduleHeight == 0) return layerVoxels;

        // Determine which module repetition we're in and the Y offset within that module
        int yWithinModule = layerY % moduleHeight;
        int relYTarget = template.minY + yWithinModule;

        // Collect all voxels at this Y level within the current module
        for (var voxel : template.voxels) {
            int relY = voxel.rel.getY();
            if (relY == relYTarget) {
                // Layer zero replaces the supporting base block. The requested height therefore
                // includes that base instead of starting in the air above it.
                int absoluteY = getBuildBaseY(origin) + layerY;
                BlockPos absPos = new BlockPos(
                        origin.getX() + voxel.rel.getX(),
                        absoluteY,
                        origin.getZ() + voxel.rel.getZ()
                );
                layerVoxels.add(absPos);
            }
        }

        return layerVoxels;
    }

    /**
     * Place a tower block with gradient sampling.
     * @return true if the block was placed
     */
    protected boolean placeTowerBlock(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, BlockPos pos, BlockPos nextPos) {
        if (golem.level().isClientSide()) return false;

        // Get the original block state from the template
        BlockState templateState = getTowerBlockStateAt(template, origin, pos);
        if (templateState == null) {
            LOGGER.warn("Tower placement failed: missing target state at pos={} origin={}", pos, origin);
            return false;
        }

        TowerTarget target = resolveTowerTarget(golem, template, origin, pos, templateState);
        return switch (target.action()) {
            case PLACE -> golem.placeBlockFromInventoryWithTemplate(
                    pos, templateState, target.state(), nextPos, isLeftHandActive());
            case REMOVE -> golem.removeBlockToInventory(pos, nextPos, isLeftHandActive());
            case MINE -> {
                if (golem.level().getBlockState(pos).isAir()) {
                    yield true;
                }
                gradientMiner.startMining(pos);
                yield false;
            }
            case SKIP -> true;
        };
    }

    protected int getBuildBaseY(BlockPos origin) {
        return origin.getY() - 1;
    }

    protected int getLayerY(BlockPos origin, BlockPos pos) {
        return pos.getY() - getBuildBaseY(origin);
    }

    protected BlockState getTowerBlockStateAt(TowerModuleTemplate template, BlockPos origin, BlockPos pos) {
        if (template == null || origin == null) return null;

        int moduleHeight = template.moduleHeight;
        if (moduleHeight == 0) return null;

        // Calculate relative position from the footprint and base-inclusive layer zero.
        int relX = pos.getX() - origin.getX();
        int layerY = getLayerY(origin, pos);
        int relZ = pos.getZ() - origin.getZ();

        // Determine Y within module
        int yWithinModule = Math.floorMod(layerY, moduleHeight) + template.minY;

        // Find matching voxel in template
        for (var voxel : template.voxels) {
            if (voxel.rel.getX() == relX && voxel.rel.getY() == yWithinModule && voxel.rel.getZ() == relZ) {
                return voxel.state;
            }
        }

        return null;
    }

    /**
     * Check if the correct block is already at the given position.
     * Used to skip blocks when resuming a build.
     */
    protected boolean isBlockAlreadyCorrect(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, BlockPos pos) {
        BlockState templateState = getTowerBlockStateAt(template, origin, pos);
        if (templateState == null) return true;
        TowerTarget target = resolveTowerTarget(golem, template, origin, pos, templateState);
        BlockState currentState = golem.level().getBlockState(pos);
        return switch (target.action()) {
            case PLACE -> currentState.getBlock() == target.state().getBlock();
            case REMOVE, MINE -> currentState.isAir();
            case SKIP -> true;
        };
    }

    /**
     * Get the expected block state at a position, applying gradient sampling.
     * Returns null for remove, mine, and no-op gradient targets.
     */
    protected BlockState getExpectedBlockState(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, BlockPos pos) {
        BlockState templateState = getTowerBlockStateAt(template, origin, pos);
        if (templateState == null) return null;
        TowerTarget target = resolveTowerTarget(golem, template, origin, pos, templateState);
        return target.action() == TowerTargetAction.PLACE ? target.state() : null;
    }

    private TowerTarget resolveTowerTarget(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin,
                                            BlockPos pos, BlockState templateState) {
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(templateState.getBlock()).toString();
        Integer groupIdx = golem.getTowerBlockGroup().get(blockId);
        if (groupIdx == null || groupIdx < 0 || groupIdx >= golem.getTowerGroupSlots().size()) {
            return new TowerTarget(TowerTargetAction.PLACE, templateState);
        }

        String[] slots = golem.getTowerGroupSlots().get(groupIdx);
        float window = (groupIdx < golem.getTowerGroupWindows().size()) ? golem.getTowerGroupWindows().get(groupIdx) : 1.0f;
        int noiseScale = (groupIdx < golem.getTowerGroupNoiseScales().size()) ? golem.getTowerGroupNoiseScales().get(groupIdx) : 1;
        int sampledIndex = sampleTowerGradient(golem, template, origin, slots, window, noiseScale, pos);
        if (sampledIndex < 0 || sampledIndex >= slots.length) {
            // A completely unconfigured group is a no-op, not an instruction to erase its blocks.
            return new TowerTarget(TowerTargetAction.SKIP, null);
        }

        String sampledId = slots[sampledIndex];
        if (sampledId == null || sampledId.isEmpty()) {
            return new TowerTarget(TowerTargetAction.REMOVE, null);
        }
        if (GradientSlotUtil.isMineAction(sampledId)) {
            return new TowerTarget(TowerTargetAction.MINE, null);
        }
        BlockState sampledState = golem.getBlockStateFromId(sampledId);
        return sampledState == null
                ? new TowerTarget(TowerTargetAction.SKIP, null)
                : new TowerTarget(TowerTargetAction.PLACE, sampledState);
    }

    private enum TowerTargetAction {
        PLACE,
        REMOVE,
        MINE,
        SKIP
    }

    private record TowerTarget(TowerTargetAction action, BlockState state) {
    }

    protected int sampleTowerGradient(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin,
                                    String[] slots, float window, int noiseScale, BlockPos pos) {
        int height = golem.getTowerHeight();
        if (height == 0) return -1;

        // Count non-empty gradient slots
        int G = 0;
        for (int i = 8; i >= 0; i--) {
            if (slots[i] != null && !slots[i].isEmpty()) {
                G = i + 1;
                break;
            }
        }
        if (G == 0) return -1;

        // Derive the layer from the position itself. The planner holds several layers at once,
        // so using the moving currentLayerY cursor made a queued block's expected material change.
        int layerY = getLayerY(origin, pos);
        layerY = Math.max(0, Math.min(height - 1, layerY));

        // Map the full tower height to gradient space [0, G-1].
        double heightFraction = height == 1 ? 0.0 : (double) layerY / (double) (height - 1);
        double s = heightFraction * (G - 1);

        // Apply windowing
        float W = Math.min(window, G);
        if (W > 0) {
            // Deterministic random offset based on position
            double u = golem.sampleGradientNoise01(pos, noiseScale) * W - (W / 2.0);
            s += u;
        }

        // Edge reflection (triangle wave)
        double a = -0.5;
        double b = G - 0.5;
        double L = b - a;
        double y = (s - a) % (2 * L);
        if (y < 0) y += 2 * L;
        double r = (y <= L) ? y : (2 * L - y);
        double s_ref = a + r;

        // Clamp and round
        int index = (int) Math.round(s_ref);
        return Math.max(0, Math.min(G - 1, index));
    }

}
