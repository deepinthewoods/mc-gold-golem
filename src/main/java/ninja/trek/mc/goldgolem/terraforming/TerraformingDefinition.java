package ninja.trek.mc.goldgolem.terraforming;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Set;

/**
 * Holds the terraforming skeleton definition captured during scanning.
 */
public record TerraformingDefinition(
        BlockPos origin,              // Center of the 3x3 gold platform
        List<BlockPos> skeletonBlocks, // All skeleton block positions (absolute)
        Set<Block> skeletonTypes,     // Block types that make up the skeleton
        BlockPos minBound,            // Minimum bounding box corner
        BlockPos maxBound             // Maximum bounding box corner
) {
}
