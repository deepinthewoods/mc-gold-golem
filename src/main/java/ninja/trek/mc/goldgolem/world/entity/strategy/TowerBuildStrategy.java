package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for Tower building mode.
 * Uses PlacementPlanner for reach-aware block placement - the golem moves
 * within reach of each block before placing it, similar to how a player would build.
 */
public class TowerBuildStrategy extends AbstractBuildStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger(TowerBuildStrategy.class);

    // Tower building state
    private int currentLayerY = 0;           // Current Y layer being processed
    private boolean layerInitialized = false; // Whether current layer blocks are loaded into planner
    private PlacementPlanner planner = null;
    private int totalHeight = 0;             // Cached for progress tracking

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
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
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
        return currentLayerY >= entity.getTowerHeight() && (planner == null || planner.isComplete());
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        nbt.putInt("CurrentLayerY", currentLayerY);
        nbt.putBoolean("LayerInitialized", layerInitialized);
        nbt.putInt("TotalHeight", totalHeight);

        // Save planner state
        if (planner != null) {
            NbtCompound plannerNbt = new NbtCompound();
            planner.writeNbt(plannerNbt);
            nbt.put("Planner", plannerNbt);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        currentLayerY = nbt.getInt("CurrentLayerY", 0);
        layerInitialized = nbt.getBoolean("LayerInitialized", false);
        totalHeight = nbt.getInt("TotalHeight", 0);

        // Load planner state (planner will be created on next initialize)
        // Note: planner needs golem reference, so we defer loading
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
        currentLayerY = 0;
        layerInitialized = false;
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
    public FeedResult handleFeedInteraction(PlayerEntity player) {
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
        if ("towerOrigin".equals(configKey)) {
            clearState();
        }
    }

    // ========== Main tick logic ==========

    private void tickTowerMode(GoldGolemEntity golem, PlayerEntity owner) {
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
        if (currentLayerY >= height && planner.isComplete()) {
            golem.setBuildingPaths(false);
            return;
        }

        // Initialize current layer if needed
        if (!layerInitialized && currentLayerY < height) {
            List<BlockPos> layerBlocks = getLayerVoxels(golem, template, origin, currentLayerY);
            if (layerBlocks.isEmpty()) {
                // Empty layer, move to next
                currentLayerY++;
                return;
            }
            planner.setBlocks(layerBlocks);
            layerInitialized = true;
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
                break;

            case COMPLETED:
                // Layer complete, move to next
                currentLayerY++;
                layerInitialized = false;
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
    private List<BlockPos> getLayerVoxels(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, int layerY) {
        if (template == null) return List.of();

        List<BlockPos> layerVoxels = new ArrayList<>();
        int moduleHeight = template.moduleHeight;
        if (moduleHeight == 0) return layerVoxels;

        // Determine which module repetition we're in and the Y offset within that module
        int moduleIndex = layerY / moduleHeight;
        int yWithinModule = layerY % moduleHeight;
        int relYTarget = template.minY + yWithinModule;

        // Collect all voxels at this Y level within the current module
        for (var voxel : template.voxels) {
            int relY = voxel.rel.getY();
            if (relY == relYTarget) {
                // Calculate absolute position: origin + module offset + voxel relative position
                int absoluteY = origin.getY() + (moduleIndex * moduleHeight) + relY;
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
    private boolean placeTowerBlock(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, BlockPos pos, BlockPos nextPos) {
        if (golem.getEntityWorld().isClient()) return false;

        // Get the original block state from the template
        BlockState targetState = getTowerBlockStateAt(template, origin, pos);
        if (targetState == null) {
            LOGGER.warn("Tower placement failed: missing target state at pos={} origin={}", pos, origin);
            return false;
        }

        // Use gradient sampling to potentially replace with a different block
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(targetState.getBlock()).toString();
        Integer groupIdx = golem.getTowerBlockGroup().get(blockId);
        if (groupIdx == null || groupIdx < 0 || groupIdx >= golem.getTowerGroupSlots().size()) {
            // No group mapping, place original block
            return golem.placeBlockFromInventory(pos, targetState, nextPos, isLeftHandActive());
        }

        // Sample gradient based on Y position in total tower (not module)
        String[] slots = golem.getTowerGroupSlots().get(groupIdx);
        float window = (groupIdx < golem.getTowerGroupWindows().size()) ? golem.getTowerGroupWindows().get(groupIdx) : 1.0f;
        int noiseScale = (groupIdx < golem.getTowerGroupNoiseScales().size()) ? golem.getTowerGroupNoiseScales().get(groupIdx) : 1;
        int sampledIndex = sampleTowerGradient(golem, slots, window, noiseScale, pos);

        if (sampledIndex >= 0 && sampledIndex < 9) {
            String sampledId = slots[sampledIndex];
            if (sampledId != null && !sampledId.isEmpty()) {
                BlockState sampledState = golem.getBlockStateFromId(sampledId);
                if (sampledState != null) {
                    return golem.placeBlockFromInventory(pos, sampledState, nextPos, isLeftHandActive());
                }
            }
        }

        // Fallback: place original block
        return golem.placeBlockFromInventory(pos, targetState, nextPos, isLeftHandActive());
    }

    private BlockState getTowerBlockStateAt(TowerModuleTemplate template, BlockPos origin, BlockPos pos) {
        if (template == null || origin == null) return null;

        int moduleHeight = template.moduleHeight;
        if (moduleHeight == 0) return null;

        // Calculate relative position from tower origin
        int relX = pos.getX() - origin.getX();
        int relY = pos.getY() - origin.getY();
        int relZ = pos.getZ() - origin.getZ();

        // Determine Y within module
        int yWithinModule = Math.floorMod(relY - template.minY, moduleHeight) + template.minY;

        // Find matching voxel in template
        for (var voxel : template.voxels) {
            if (voxel.rel.getX() == relX && voxel.rel.getY() == yWithinModule && voxel.rel.getZ() == relZ) {
                return voxel.state;
            }
        }

        return null;
    }

    private int sampleTowerGradient(GoldGolemEntity golem, String[] slots, float window, int noiseScale, BlockPos pos) {
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

        // Map Y position in tower to gradient space [0, G-1]
        double s = ((double) currentLayerY / (double) height) * (G - 1);

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
