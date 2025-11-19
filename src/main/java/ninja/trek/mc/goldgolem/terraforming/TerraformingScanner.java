package ninja.trek.mc.goldgolem.terraforming;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Terraforming mode scanner.
 * Detects 3x3 gold platform and scans skeleton structure attached to it.
 */
public final class TerraformingScanner {
    public static final int MAX_VOXELS = 4096;
    public static final int MAX_EXTENT = 512; // per axis bound size

    private static final Direction[] NEIGHBORS = new Direction[]{
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public record Result(TerraformingDefinition def, String error) {
        public boolean ok() { return def != null && (error == null || error.isEmpty()); }
    }

    /**
     * Scans for terraforming structure starting from the 3x3 gold platform.
     *
     * @param world The world
     * @param centerGoldPos The center position of the 3x3 gold platform
     * @param summoner The player who summoned the golem
     * @return Result containing skeleton data or error
     */
    public static Result scan(World world, BlockPos centerGoldPos, PlayerEntity summoner) {
        // First, verify the 3x3 gold platform exists
        List<BlockPos> platformPositions = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos pos = centerGoldPos.add(dx, 0, dz);
                if (!world.getBlockState(pos).isOf(Blocks.GOLD_BLOCK)) {
                    return new Result(null, "Invalid 3x3 gold platform - missing gold at offset " + dx + "," + dz);
                }
                platformPositions.add(pos);
            }
        }

        // Determine the block the player is standing on (to exclude it)
        BlockPos playerGround = summoner == null ? null : summoner.getBlockPos().down();
        Block groundType = null;
        if (playerGround != null) {
            BlockState gs = world.getBlockState(playerGround);
            groundType = gs.getBlock();
        }

        // Find all blocks directly adjacent to the 3x3 platform (not diagonal)
        Set<BlockPos> adjacentToPlatform = new HashSet<>();
        Set<Block> skeletonTypes = new HashSet<>();

        for (BlockPos platPos : platformPositions) {
            for (Direction d : NEIGHBORS) {
                BlockPos adj = platPos.offset(d);

                // Skip if already part of platform
                if (platformPositions.contains(adj)) continue;

                BlockState st = world.getBlockState(adj);

                // Skip air and snow layers
                if (st.isAir() || st.isOf(Blocks.SNOW)) continue;

                Block b = st.getBlock();

                // Skip the block type the player is standing on
                if (groundType != null && b == groundType) continue;

                adjacentToPlatform.add(adj);
                skeletonTypes.add(b);
            }
        }

        if (adjacentToPlatform.isEmpty()) {
            return new Result(null, "No skeleton blocks touching the 3x3 gold platform");
        }

        // Flood fill from adjacent blocks, only including blocks matching skeleton types
        Set<BlockPos> visited = new HashSet<>(platformPositions); // Start with platform as visited (don't include in skeleton)
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(adjacentToPlatform);
        visited.addAll(adjacentToPlatform);

        List<BlockPos> skeletonBlocks = new ArrayList<>(adjacentToPlatform);

        BlockPos min = new BlockPos(centerGoldPos);
        BlockPos max = new BlockPos(centerGoldPos);

        // Update bounds with initial skeleton blocks
        for (BlockPos pos : adjacentToPlatform) {
            min = new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
            max = new BlockPos(Math.max(max.getX(), pos.getX()), Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
        }

        // Flood fill
        while (!queue.isEmpty()) {
            BlockPos cur = queue.removeFirst();

            for (Direction d : NEIGHBORS) {
                BlockPos n = cur.offset(d);

                if (visited.contains(n)) continue;

                BlockState st = world.getBlockState(n);

                // Skip air and snow
                if (st.isAir() || st.isOf(Blocks.SNOW)) continue;

                Block b = st.getBlock();

                // Only include blocks that match one of the skeleton types
                if (!skeletonTypes.contains(b)) continue;

                // Skip player ground block
                if (playerGround != null && n.equals(playerGround)) continue;
                if (groundType != null && b == groundType) continue;

                visited.add(n);
                skeletonBlocks.add(n);

                // Update bounds
                min = new BlockPos(Math.min(min.getX(), n.getX()), Math.min(min.getY(), n.getY()), Math.min(min.getZ(), n.getZ()));
                max = new BlockPos(Math.max(max.getX(), n.getX()), Math.max(max.getY(), n.getY()), Math.max(max.getZ(), n.getZ()));

                // Check limits
                if (skeletonBlocks.size() > MAX_VOXELS) {
                    return new Result(null, "Skeleton scan exceeded 4096 blocks");
                }
                if ((max.getX() - min.getX() + 1) > MAX_EXTENT ||
                        (max.getY() - min.getY() + 1) > MAX_EXTENT ||
                        (max.getZ() - min.getZ() + 1) > MAX_EXTENT) {
                    return new Result(null, "Skeleton scan exceeded 512x512x512 bounds");
                }

                queue.addLast(n);
            }
        }

        if (skeletonBlocks.isEmpty()) {
            return new Result(null, "No skeleton blocks found");
        }

        TerraformingDefinition def = new TerraformingDefinition(
                centerGoldPos.toImmutable(),
                skeletonBlocks,
                skeletonTypes,
                min.toImmutable(),
                max.toImmutable()
        );

        return new Result(def, null);
    }

    public static Path writeJson(Path baseDir, UUID owner, TerraformingDefinition def) throws IOException {
        Path folder = baseDir.resolve("GoldGolemModules");
        Files.createDirectories(folder);
        String fname = String.format(Locale.ROOT,
                "terraforming_%d_%d_%d_%d.json",
                System.currentTimeMillis(), def.origin.getX(), def.origin.getY(), def.origin.getZ());
        Path out = folder.resolve(fname);

        // Simple JSON writer
        StringBuilder sb = new StringBuilder(1 << 14);
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"mode\": \"terraforming\",\n");
        sb.append("  \"owner\": \"").append(owner == null ? "" : owner.toString()).append("\",\n");
        sb.append("  \"origin\": [").append(def.origin.getX()).append(',').append(def.origin.getY()).append(',').append(def.origin.getZ()).append("],\n");

        // Skeleton types
        sb.append("  \"skeletonTypes\": [");
        int idx = 0;
        for (Block b : def.skeletonTypes) {
            if (idx++ > 0) sb.append(',');
            sb.append('\"').append(Registries.BLOCK.getId(b).toString()).append('\"');
        }
        sb.append("],\n");

        // Skeleton blocks
        sb.append("  \"skeletonBlocks\": [");
        for (int i = 0; i < def.skeletonBlocks.size(); i++) {
            if (i > 0) sb.append(',');
            BlockPos p = def.skeletonBlocks.get(i);
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
        }
        sb.append("],\n");

        // Bounds
        sb.append("  \"bounds\": {");
        sb.append("\"min\": [").append(def.minBound.getX()).append(',').append(def.minBound.getY()).append(',').append(def.minBound.getZ()).append("],");
        sb.append("\"max\": [").append(def.maxBound.getX()).append(',').append(def.maxBound.getY()).append(',').append(def.maxBound.getZ()).append("]");
        sb.append("}\n");

        sb.append("}\n");

        Files.writeString(out, sb.toString());
        return out;
    }
}
