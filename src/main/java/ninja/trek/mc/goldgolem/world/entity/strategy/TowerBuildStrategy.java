package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Strategy for Tower building mode.
 * Builds a tower at a fixed location based on a captured template.
 */
public class TowerBuildStrategy extends AbstractBuildStrategy {

    // Tower building state
    private int currentY = 0;
    private int placementCursor = 0;

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
        return currentY >= entity.getTowerHeight();
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        nbt.putInt("CurrentY", currentY);
        nbt.putInt("PlacementCursor", placementCursor);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        currentY = nbt.getInt("CurrentY", 0);
        placementCursor = nbt.getInt("PlacementCursor", 0);
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
        currentY = 0;
        placementCursor = 0;
    }

    // ========== Getters ==========

    public int getCurrentY() { return currentY; }
    public int getPlacementCursor() { return placementCursor; }

    // ========== Main tick logic ==========

    private void tickTowerMode(GoldGolemEntity golem, PlayerEntity owner) {
        // Tower mode: build at fixed location (towerOrigin), no player tracking
        if (!golem.isBuildingPaths()) return;

        TowerModuleTemplate template = golem.getTowerTemplate();
        BlockPos origin = golem.getTowerOrigin();
        int height = golem.getTowerHeight();

        if (template == null || origin == null) return;

        // Check if we've finished building the tower
        if (currentY >= height) {
            golem.setBuildingPaths(false);
            return;
        }

        // Get all voxels for the current Y layer
        List<BlockPos> currentLayerVoxels = getCurrentLayerVoxels(golem, template, origin);

        if (currentLayerVoxels.isEmpty()) {
            // No voxels in this layer, move to next Y level
            currentY++;
            placementCursor = 0;
            return;
        }

        // Place blocks at 2-tick intervals (same as other modes)
        if (placementTickCounter == 0 && placementCursor < currentLayerVoxels.size()) {
            BlockPos targetPos = currentLayerVoxels.get(placementCursor);

            // Try to pathfind to the target position
            double ty = golem.computeGroundTargetY(new Vec3d(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5));
            golem.getNavigation().startMovingTo(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, 1.1);

            // Check if stuck and teleport if necessary
            double dx = golem.getX() - (targetPos.getX() + 0.5);
            double dz = golem.getZ() - (targetPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (golem.getNavigation().isIdle() && distSq > 1.0) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    if (golem.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, golem.getX(), golem.getY() + 0.5, golem.getZ(), 40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, targetPos.getX() + 0.5, ty + 0.5, targetPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
                    }
                    golem.refreshPositionAndAngles(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, golem.getYaw(), golem.getPitch());
                    golem.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }

            // Determine next block for animation preview
            BlockPos nextPos = null;
            if (placementCursor + 1 < currentLayerVoxels.size()) {
                nextPos = currentLayerVoxels.get(placementCursor + 1);
            }

            // Place the block
            placeTowerBlock(golem, template, origin, targetPos, nextPos);
            placementCursor++;
        }

        // Check if we've finished this layer
        if (placementCursor >= currentLayerVoxels.size()) {
            currentY++;
            placementCursor = 0;
        }
    }

    private List<BlockPos> getCurrentLayerVoxels(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin) {
        if (template == null) return Collections.emptyList();

        List<BlockPos> layerVoxels = new ArrayList<>();
        int moduleHeight = template.moduleHeight;
        if (moduleHeight == 0) return layerVoxels;

        // Determine which module repetition we're in and the Y offset within that module
        int moduleIndex = currentY / moduleHeight;
        int yWithinModule = currentY % moduleHeight;

        // Collect all voxels at this Y level within the current module
        for (var voxel : template.voxels) {
            int relY = voxel.rel.getY();
            if (relY == yWithinModule) {
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

    private void placeTowerBlock(GoldGolemEntity golem, TowerModuleTemplate template, BlockPos origin, BlockPos pos, BlockPos nextPos) {
        if (golem.getEntityWorld().isClient()) return;

        // Get the original block state from the template
        BlockState targetState = getTowerBlockStateAt(template, origin, pos);
        if (targetState == null) return;

        // Use gradient sampling to potentially replace with a different block
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(targetState.getBlock()).toString();
        Integer groupIdx = golem.getTowerBlockGroup().get(blockId);
        if (groupIdx == null || groupIdx < 0 || groupIdx >= golem.getTowerGroupSlots().size()) {
            // No group mapping, place original block
            golem.placeBlockFromInventory(pos, targetState, nextPos);
            return;
        }

        // Sample gradient based on Y position in total tower (not module)
        String[] slots = golem.getTowerGroupSlots().get(groupIdx);
        float window = (groupIdx < golem.getTowerGroupWindows().size()) ? golem.getTowerGroupWindows().get(groupIdx) : 1.0f;
        int sampledIndex = sampleTowerGradient(golem, slots, window, pos);

        if (sampledIndex >= 0 && sampledIndex < 9) {
            String sampledId = slots[sampledIndex];
            if (sampledId != null && !sampledId.isEmpty()) {
                BlockState sampledState = golem.getBlockStateFromId(sampledId);
                if (sampledState != null) {
                    golem.placeBlockFromInventory(pos, sampledState, nextPos);
                    return;
                }
            }
        }

        // Fallback: place original block
        golem.placeBlockFromInventory(pos, targetState, nextPos);
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
        int yWithinModule = relY % moduleHeight;

        // Find matching voxel in template
        for (var voxel : template.voxels) {
            if (voxel.rel.getX() == relX && voxel.rel.getY() == yWithinModule && voxel.rel.getZ() == relZ) {
                return voxel.state;
            }
        }

        return null;
    }

    private int sampleTowerGradient(GoldGolemEntity golem, String[] slots, float window, BlockPos pos) {
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
        double s = ((double) currentY / (double) height) * (G - 1);

        // Apply windowing
        float W = Math.min(window, G);
        if (W > 0) {
            // Deterministic random offset based on position
            double u = deterministic01(golem.getId(), pos.getX(), pos.getZ(), currentY) * W - (W / 2.0);
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

    private double deterministic01(int entityId, int bx, int bz, int j) {
        long v = 0x9E3779B97F4A7C15L;
        v ^= ((long) entityId * 0x9E3779B97F4A7C15L);
        v ^= ((long) bx * 0xC2B2AE3D27D4EB4FL);
        v ^= ((long) bz * 0x165667B19E3779F9L);
        v ^= ((long) j * 0x85EBCA77C2B2AE63L);
        v ^= (v >>> 33);
        v *= 0xff51afd7ed558ccdL;
        v ^= (v >>> 33);
        v *= 0xc4ceb9fe1a85ec53L;
        v ^= (v >>> 33);
        return (Double.longBitsToDouble((v >>> 12) | 0x3FF0000000000000L) - 1.0);
    }
}
