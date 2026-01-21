package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.*;

/**
 * Strategy for Terraforming mode.
 * Fills in a shell around skeleton blocks using alpha shape detection.
 */
public class TerraformingBuildStrategy extends AbstractBuildStrategy {

    // Terraforming state fields
    private BlockPos origin = null;
    private List<BlockPos> skeletonBlocks = null;
    private Set<Block> skeletonTypes = null;
    private Map<Integer, List<BlockPos>> shellByLayer = null;
    private int currentY = 0;
    private int layerProgress = 0;
    private BlockPos startPos = null;
    private int minY = 0;
    private int maxY = 0;

    @Override
    public BuildMode getMode() {
        return BuildMode.TERRAFORMING;
    }

    @Override
    public String getNbtPrefix() {
        return "TForm";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
    }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        tickTerraformingMode(golem);
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        if (shellByLayer == null) return false;
        return currentY > maxY;
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        // Save origin
        if (origin != null) {
            nbt.putInt("OriginX", origin.getX());
            nbt.putInt("OriginY", origin.getY());
            nbt.putInt("OriginZ", origin.getZ());
        }

        // Save start position
        if (startPos != null) {
            nbt.putInt("StartX", startPos.getX());
            nbt.putInt("StartY", startPos.getY());
            nbt.putInt("StartZ", startPos.getZ());
        }

        // Save bounds and progress
        nbt.putInt("MinY", minY);
        nbt.putInt("MaxY", maxY);
        nbt.putInt("CurrentY", currentY);
        nbt.putInt("LayerProgress", layerProgress);

        // Save skeleton blocks
        if (skeletonBlocks != null && !skeletonBlocks.isEmpty()) {
            nbt.putInt("SkeletonCount", skeletonBlocks.size());
            for (int i = 0; i < skeletonBlocks.size(); i++) {
                BlockPos pos = skeletonBlocks.get(i);
                nbt.putInt("Skel" + i + "X", pos.getX());
                nbt.putInt("Skel" + i + "Y", pos.getY());
                nbt.putInt("Skel" + i + "Z", pos.getZ());
            }
        } else {
            nbt.putInt("SkeletonCount", 0);
        }

        // Save skeleton types
        if (skeletonTypes != null && !skeletonTypes.isEmpty()) {
            nbt.putInt("SkelTypesCount", skeletonTypes.size());
            int idx = 0;
            for (Block block : skeletonTypes) {
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
                nbt.putString("SkelType" + idx, blockId);
                idx++;
            }
        } else {
            nbt.putInt("SkelTypesCount", 0);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        // Load origin
        if (nbt.contains("OriginX")) {
            origin = new BlockPos(
                nbt.getInt("OriginX", 0),
                nbt.getInt("OriginY", 0),
                nbt.getInt("OriginZ", 0)
            );
        } else {
            origin = null;
        }

        // Load start position
        if (nbt.contains("StartX")) {
            startPos = new BlockPos(
                nbt.getInt("StartX", 0),
                nbt.getInt("StartY", 0),
                nbt.getInt("StartZ", 0)
            );
        } else {
            startPos = null;
        }

        // Load bounds and progress
        minY = nbt.getInt("MinY", 0);
        maxY = nbt.getInt("MaxY", 0);
        currentY = nbt.getInt("CurrentY", 0);
        layerProgress = nbt.getInt("LayerProgress", 0);

        // Load skeleton blocks
        int skelCount = nbt.getInt("SkeletonCount", 0);
        if (skelCount > 0) {
            skeletonBlocks = new ArrayList<>();
            for (int i = 0; i < skelCount; i++) {
                int x = nbt.getInt("Skel" + i + "X", 0);
                int y = nbt.getInt("Skel" + i + "Y", 0);
                int z = nbt.getInt("Skel" + i + "Z", 0);
                skeletonBlocks.add(new BlockPos(x, y, z));
            }
        } else {
            skeletonBlocks = null;
        }

        // Load skeleton types
        int skelTypesCount = nbt.getInt("SkelTypesCount", 0);
        if (skelTypesCount > 0) {
            skeletonTypes = new HashSet<>();
            for (int i = 0; i < skelTypesCount; i++) {
                String blockId = nbt.getString("SkelType" + i, "");
                if (!blockId.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(blockId);
                    if (ident != null) {
                        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
                        if (block != null) {
                            skeletonTypes.add(block);
                        }
                    }
                }
            }
        } else {
            skeletonTypes = null;
        }

        // Rebuild shell from skeleton if we have skeleton blocks
        if (skeletonBlocks != null && !skeletonBlocks.isEmpty() && entity != null) {
            rebuildShell();
        }
    }

    @Override
    public boolean usesPlayerTracking() {
        return false; // Terraforming operates autonomously
    }

    // ========== Configuration ==========

    /**
     * Configure terraforming from a definition.
     */
    public void setConfig(ninja.trek.mc.goldgolem.terraforming.TerraformingDefinition def, BlockPos startPos) {
        this.origin = def.origin();
        this.skeletonBlocks = new ArrayList<>(def.skeletonBlocks());
        this.skeletonTypes = new HashSet<>(def.skeletonTypes());
        this.startPos = startPos;
        this.minY = def.minBound().getY();
        this.maxY = def.maxBound().getY();

        rebuildShell();

        this.currentY = minY;
        this.layerProgress = 0;
    }

    /**
     * Rebuild the shell layers after alpha parameter change.
     */
    public void rebuildShell() {
        if (skeletonBlocks == null || skeletonBlocks.isEmpty() || entity == null) return;

        int alpha = entity.getTerraformingAlpha();
        shellByLayer = new HashMap<>();

        for (int y = minY; y <= maxY; y++) {
            List<BlockPos> skeletonAtY = new ArrayList<>();
            for (BlockPos pos : skeletonBlocks) {
                if (pos.getY() == y) {
                    skeletonAtY.add(pos);
                }
            }

            if (!skeletonAtY.isEmpty()) {
                Set<BlockPos> shellSet = ninja.trek.mc.goldgolem.terraforming.AlphaShape.generateShell(
                        skeletonAtY, alpha, y);
                shellByLayer.put(y, new ArrayList<>(shellSet));
            }
        }
    }

    /**
     * Clear all state.
     */
    public void clearState() {
        currentY = 0;
        layerProgress = 0;
    }

    // ========== Getters ==========

    public BlockPos getOrigin() { return origin; }
    public BlockPos getStartPos() { return startPos; }
    public List<BlockPos> getSkeletonBlocks() { return skeletonBlocks; }
    public Set<Block> getSkeletonTypes() { return skeletonTypes; }
    public Map<Integer, List<BlockPos>> getShellByLayer() { return shellByLayer; }
    public int getCurrentY() { return currentY; }
    public int getLayerProgress() { return layerProgress; }
    public int getMinY() { return minY; }
    public int getMaxY() { return maxY; }

    // ========== Polymorphic Dispatch Methods ==========

    @Override
    public void writeLegacyNbt(WriteView view) {
        if (origin != null) {
            view.putInt("TFormOriginX", origin.getX());
            view.putInt("TFormOriginY", origin.getY());
            view.putInt("TFormOriginZ", origin.getZ());
        }
        if (startPos != null) {
            view.putInt("TFormStartX", startPos.getX());
            view.putInt("TFormStartY", startPos.getY());
            view.putInt("TFormStartZ", startPos.getZ());
        }
        view.putInt("TFormMinY", minY);
        view.putInt("TFormMaxY", maxY);
        view.putInt("TFormCurrentY", currentY);
        view.putInt("TFormLayerProgress", layerProgress);

        // Skeleton blocks
        if (skeletonBlocks != null && !skeletonBlocks.isEmpty()) {
            view.putInt("TFormSkeletonCount", skeletonBlocks.size());
            for (int i = 0; i < skeletonBlocks.size(); i++) {
                BlockPos pos = skeletonBlocks.get(i);
                view.putInt("TFormSkel" + i + "X", pos.getX());
                view.putInt("TFormSkel" + i + "Y", pos.getY());
                view.putInt("TFormSkel" + i + "Z", pos.getZ());
            }
        } else {
            view.putInt("TFormSkeletonCount", 0);
        }

        // Skeleton types
        if (skeletonTypes != null && !skeletonTypes.isEmpty()) {
            view.putInt("TFormSkelTypesCount", skeletonTypes.size());
            int idx = 0;
            for (Block block : skeletonTypes) {
                String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
                view.putString("TFormSkelType" + idx, blockId);
                idx++;
            }
        } else {
            view.putInt("TFormSkelTypesCount", 0);
        }
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        // Load origin
        if (view.contains("TFormOriginX")) {
            origin = new BlockPos(
                view.getInt("TFormOriginX", 0),
                view.getInt("TFormOriginY", 0),
                view.getInt("TFormOriginZ", 0)
            );
        } else {
            origin = null;
        }

        // Load start position
        if (view.contains("TFormStartX")) {
            startPos = new BlockPos(
                view.getInt("TFormStartX", 0),
                view.getInt("TFormStartY", 0),
                view.getInt("TFormStartZ", 0)
            );
        } else {
            startPos = null;
        }

        // Load bounds and progress
        minY = view.getInt("TFormMinY", 0);
        maxY = view.getInt("TFormMaxY", 0);
        currentY = view.getInt("TFormCurrentY", 0);
        layerProgress = view.getInt("TFormLayerProgress", 0);

        // Load skeleton blocks
        int skelCount = view.getInt("TFormSkeletonCount", 0);
        if (skelCount > 0) {
            skeletonBlocks = new ArrayList<>();
            for (int i = 0; i < skelCount; i++) {
                int x = view.getInt("TFormSkel" + i + "X", 0);
                int y = view.getInt("TFormSkel" + i + "Y", 0);
                int z = view.getInt("TFormSkel" + i + "Z", 0);
                skeletonBlocks.add(new BlockPos(x, y, z));
            }
        } else {
            skeletonBlocks = null;
        }

        // Load skeleton types
        int skelTypesCount = view.getInt("TFormSkelTypesCount", 0);
        if (skelTypesCount > 0) {
            skeletonTypes = new HashSet<>();
            for (int i = 0; i < skelTypesCount; i++) {
                String blockId = view.getString("TFormSkelType" + i, "");
                if (!blockId.isEmpty()) {
                    var ident = net.minecraft.util.Identifier.tryParse(blockId);
                    if (ident != null) {
                        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
                        if (block != null) {
                            skeletonTypes.add(block);
                        }
                    }
                }
            }
        } else {
            skeletonTypes = null;
        }

        // Rebuild shell from skeleton if we have skeleton blocks
        if (skeletonBlocks != null && !skeletonBlocks.isEmpty() && entity != null) {
            rebuildShell();
        }
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        // Terraforming mode: always starts when nugget is fed
        return FeedResult.STARTED;
    }

    // ========== Main tick logic ==========

    private void tickTerraformingMode(GoldGolemEntity golem) {
        // Invalid state check
        if (shellByLayer == null || origin == null) {
            golem.setBuildingPaths(false);
            return;
        }

        // STATE: WAITING - idle at start position until activated with gold nugget
        if (!golem.isBuildingPaths()) {
            if (startPos != null) {
                double dx = golem.getX() - (startPos.getX() + 0.5);
                double dz = golem.getZ() - (startPos.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (distSq > 4.0) {
                    golem.getNavigation().startMovingTo(startPos.getX() + 0.5,
                        startPos.getY(), startPos.getZ() + 0.5, 1.0);
                } else {
                    golem.getNavigation().stop();
                }
            }
            return;
        }

        // STATE: BUILDING - place shell blocks layer by layer
        List<BlockPos> currentLayer = shellByLayer.get(currentY);

        // If no shell at this Y level, move to next level
        if (currentLayer == null || currentLayer.isEmpty()) {
            currentY++;
            layerProgress = 0;

            // Check if all layers are complete
            if (currentY > maxY) {
                golem.setBuildingPaths(false);
            }
            return;
        }

        // If layer is complete, move to next layer
        if (layerProgress >= currentLayer.size()) {
            layerProgress = 0;
            currentY++;

            if (currentY > maxY) {
                golem.setBuildingPaths(false);
            }
            return;
        }

        // Get current target position
        BlockPos targetPos = currentLayer.get(layerProgress);

        // Navigate to position
        double distSq = golem.getBlockPos().getSquaredDistance(targetPos);
        if (distSq > 16.0) {
            golem.getNavigation().startMovingTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0);

            // Stuck detection
            if (golem.getNavigation().isIdle()) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    // Teleport
                    golem.refreshPositionAndAngles(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                            golem.getYaw(), golem.getPitch());
                    if (golem.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, golem.getX(), golem.getY() + 1.0, golem.getZ(),
                                20, 0.5, 0.5, 0.5, 0.1);
                    }
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            return;
        }

        stuckTicks = 0;

        // At position - place block every 2 ticks
        if (placementTickCounter % 2 == 0) {
            // Remove skeleton block if present
            BlockState currentState = golem.getEntityWorld().getBlockState(targetPos);
            if (skeletonTypes != null && skeletonTypes.contains(currentState.getBlock())) {
                golem.getEntityWorld().breakBlock(targetPos, false);
            }

            // Place shell block using slope-based gradient sampling
            BlockState toPlace = sampleTerraformingGradient(golem, targetPos);
            if (toPlace != null) {
                golem.placeBlockFromInventory(targetPos, toPlace, targetPos.up());
            }

            layerProgress++;
        }
    }

    /**
     * Samples the appropriate terraforming gradient based on local surface slope.
     */
    private BlockState sampleTerraformingGradient(GoldGolemEntity golem, BlockPos pos) {
        int scanRadius = golem.getTerraformingScanRadius();

        // Count surrounding shell blocks in scan radius to determine slope
        int vertical = 0;
        int horizontal = 0;

        for (BlockPos nearPos : BlockPos.iterate(
                pos.add(-scanRadius, -scanRadius, -scanRadius),
                pos.add(scanRadius, scanRadius, scanRadius))) {

            if (nearPos.equals(pos)) continue;

            // Check if this position is part of the shell or skeleton
            boolean isShellOrSkeleton = false;
            if (skeletonBlocks != null && skeletonBlocks.contains(nearPos)) {
                isShellOrSkeleton = true;
            } else {
                // Check if it's in any shell layer
                int nearY = nearPos.getY();
                if (shellByLayer.containsKey(nearY)) {
                    List<BlockPos> layer = shellByLayer.get(nearY);
                    if (layer != null && layer.contains(nearPos)) {
                        isShellOrSkeleton = true;
                    }
                }
            }

            if (!isShellOrSkeleton) continue;

            int dx = Math.abs(nearPos.getX() - pos.getX());
            int dy = Math.abs(nearPos.getY() - pos.getY());
            int dz = Math.abs(nearPos.getZ() - pos.getZ());

            // Classify as vertical or horizontal based on dominant axis
            if (dy > dx && dy > dz) {
                vertical++;
            } else {
                horizontal++;
            }
        }

        // Calculate slope ratio
        float total = vertical + horizontal;
        if (total == 0) {
            // No nearby blocks, default to horizontal
            return sampleGradientArray(golem.getTerraformingGradientHorizontalCopy(),
                    golem.getTerraformingGradientHorizontalWindow(), pos);
        }

        float ratio = vertical / total;

        // Classify and sample appropriate gradient
        if (ratio > 0.7f) {
            // Steep/vertical surface (cliffs, walls)
            return sampleGradientArray(golem.getTerraformingGradientVerticalCopy(),
                    golem.getTerraformingGradientVerticalWindow(), pos);
        } else if (ratio < 0.3f) {
            // Flat/horizontal surface (floors, tops)
            return sampleGradientArray(golem.getTerraformingGradientHorizontalCopy(),
                    golem.getTerraformingGradientHorizontalWindow(), pos);
        } else {
            // Sloped/diagonal surface
            return sampleGradientArray(golem.getTerraformingGradientSlopedCopy(),
                    golem.getTerraformingGradientSlopedWindow(), pos);
        }
    }

    /**
     * Samples from a gradient array using positional hashing.
     */
    private BlockState sampleGradientArray(String[] gradientArray, int window, BlockPos pos) {
        if (gradientArray == null || window <= 0) return null;

        // Count non-empty entries
        int g = 0;
        for (int i = gradientArray.length - 1; i >= 0; i--) {
            if (gradientArray[i] != null && !gradientArray[i].isEmpty()) {
                g = i + 1;
                break;
            }
        }

        if (g == 0) return null;

        // Clamp window
        int w = Math.max(0, Math.min(g, window));
        if (w == 0) return null;

        // Use positional hash to sample from gradient window
        long hash = (long) pos.getX() * 374761393L + (long) pos.getY() * 668265263L + (long) pos.getZ() * 2147483647L;
        int idx = (int) ((hash & 0x7FFFFFFFL) % w);

        String blockId = gradientArray[idx];
        if (blockId == null || blockId.isEmpty()) return null;

        var ident = net.minecraft.util.Identifier.tryParse(blockId);
        if (ident == null) return null;

        var block = net.minecraft.registry.Registries.BLOCK.get(ident);
        if (block == null) return null;

        return block.getDefaultState();
    }
}
