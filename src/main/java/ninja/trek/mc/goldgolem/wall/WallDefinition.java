package ninja.trek.mc.goldgolem.wall;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Captured wall-mode data from summon-time scan.
 * Contains the combined module voxel set, gold marker positions,
 * and the list of unique block ids for gradient grouping.
 * This is a scaffold to enable mode selection, persistence, and export.
 */
public final class WallDefinition {
    public final BlockPos origin; // gold block pos used as scan origin
    public final Set<BlockPos> voxels; // relative positions from origin
    public final List<BlockPos> goldMarkers; // relative positions from origin
    public final List<String> uniqueBlockIds; // block registry ids (e.g. minecraft:stone)

    public WallDefinition(BlockPos origin, Set<BlockPos> voxels, List<BlockPos> goldMarkers, List<String> uniqueBlockIds) {
        this.origin = origin;
        this.voxels = Collections.unmodifiableSet(new HashSet<>(voxels));
        this.goldMarkers = Collections.unmodifiableList(new ArrayList<>(goldMarkers));
        this.uniqueBlockIds = Collections.unmodifiableList(new ArrayList<>(uniqueBlockIds));
    }
}

