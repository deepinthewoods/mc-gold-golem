package ninja.trek.mc.goldgolem.tree;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;

/**
 * Extracts NxNxN tiles from input modules using a sliding window approach.
 * Generates rotated variants and builds adjacency constraints for WFC.
 */
public final class TreeTileExtractor {

    /**
     * Extracts tiles from the given definition using the specified tiling preset.
     * Returns a TreeTileCache containing all tiles and adjacency rules.
     */
    public static TreeTileCache extract(World world, TreeDefinition def, TilingPreset preset, BlockPos origin) {
        int tileSize = preset.getSize();
        List<TreeTile> allTiles = new ArrayList<>();
        Map<Long, Set<String>> adjacencyRules = new HashMap<>();
        Map<Integer, String> patternToTileId = new HashMap<>(); // de-duplicate identical patterns
        int tileCounter = 0;

        // Process each module separately (no cross-module adjacency)
        for (int moduleIdx = 0; moduleIdx < def.modules.size(); moduleIdx++) {
            TreeModule module = def.modules.get(moduleIdx);

            // Build a map of relative positions to block states for quick lookup
            Map<BlockPos, BlockState> moduleBlocks = new HashMap<>();
            for (BlockPos relPos : module.voxels) {
                BlockPos absPos = origin.add(relPos);
                BlockState state = world.getBlockState(absPos);
                moduleBlocks.put(relPos, state);
            }

            // Find bounds of this module
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : module.voxels) {
                minX = Math.min(minX, p.getX());
                maxX = Math.max(maxX, p.getX());
                minY = Math.min(minY, p.getY());
                maxY = Math.max(maxY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxZ = Math.max(maxZ, p.getZ());
            }

            // Extract tiles using sliding window
            Map<BlockPos, String> positionToTileId = new HashMap<>(); // track which tile is at each position

            for (int x = minX; x <= maxX - tileSize + 1; x++) {
                for (int y = minY; y <= maxY - tileSize + 1; y++) {
                    for (int z = minZ; z <= maxZ - tileSize + 1; z++) {
                        BlockPos tileOrigin = new BlockPos(x, y, z);

                        // Extract NxNxN cube
                        BlockState[][][] blocks = new BlockState[tileSize][tileSize][tileSize];
                        boolean hasNonAir = false;
                        for (int dx = 0; dx < tileSize; dx++) {
                            for (int dy = 0; dy < tileSize; dy++) {
                                for (int dz = 0; dz < tileSize; dz++) {
                                    BlockPos pos = tileOrigin.add(dx, dy, dz);
                                    BlockState state = moduleBlocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
                                    blocks[dx][dy][dz] = state;
                                    if (!state.isAir()) hasNonAir = true;
                                }
                            }
                        }

                        // Skip empty tiles
                        if (!hasNonAir) continue;

                        // Create base tile (rotation 0)
                        String baseTileId = "tile_m" + moduleIdx + "_" + tileCounter + "_r0";
                        TreeTile baseTile = new TreeTile(baseTileId, tileSize, blocks, 0);

                        // Check if we've seen this pattern before
                        int patternHash = baseTile.patternHash();
                        String existingTileId = patternToTileId.get(patternHash);
                        if (existingTileId != null) {
                            // Reuse existing tile
                            positionToTileId.put(tileOrigin, existingTileId);
                            continue;
                        }

                        // New unique tile - add it and its rotations
                        allTiles.add(baseTile);
                        positionToTileId.put(tileOrigin, baseTileId);
                        patternToTileId.put(patternHash, baseTileId);

                        // Generate rotated variants (90, 180, 270 degrees)
                        TreeTile rot90 = baseTile.rotateY(90);
                        TreeTile rot180 = baseTile.rotateY(180);
                        TreeTile rot270 = baseTile.rotateY(270);

                        // Only add rotations if they're not identical to base or each other
                        Set<Integer> seenHashes = new HashSet<>();
                        seenHashes.add(patternHash);

                        for (TreeTile rotated : Arrays.asList(rot90, rot180, rot270)) {
                            int rotHash = rotated.patternHash();
                            if (!seenHashes.contains(rotHash)) {
                                allTiles.add(rotated);
                                patternToTileId.put(rotHash, rotated.id);
                                seenHashes.add(rotHash);
                            }
                        }

                        tileCounter++;
                    }
                }
            }

            // Build adjacency rules for this module
            buildAdjacencyRules(positionToTileId, tileSize, adjacencyRules, allTiles);
        }

        return new TreeTileCache(tileSize, allTiles, adjacencyRules);
    }

    /**
     * Builds adjacency rules by checking which tiles were neighbors in the input.
     */
    private static void buildAdjacencyRules(Map<BlockPos, String> positionToTileId, int tileSize,
                                           Map<Long, Set<String>> adjacencyRules, List<TreeTile> allTiles) {
        // For each tile position, check neighbors in each direction
        for (Map.Entry<BlockPos, String> entry : positionToTileId.entrySet()) {
            BlockPos pos = entry.getKey();
            String tileId = entry.getValue();

            for (Direction dir : Direction.values()) {
                // Calculate neighbor position (tiles overlap, so offset is 1, not tileSize)
                BlockPos neighborPos = pos.offset(dir, 1);
                String neighborTileId = positionToTileId.get(neighborPos);

                if (neighborTileId != null) {
                    // Record that tileId can have neighborTileId in direction dir
                    long key = TreeTileCache.encodeAdjacencyKey(tileId, dir);
                    adjacencyRules.computeIfAbsent(key, k -> new HashSet<>()).add(neighborTileId);
                }
            }
        }

        // Add adjacency rules for rotated variants based on base tiles
        // This is important: if tile A can be adjacent to tile B, then rotated A should be able to be adjacent to rotated B
        Map<String, TreeTile> tileMap = new HashMap<>();
        for (TreeTile tile : allTiles) {
            tileMap.put(tile.id, tile);
        }

        // Build rotation mappings
        Map<String, Map<Integer, String>> rotationMappings = new HashMap<>();
        for (TreeTile tile : allTiles) {
            String baseId = tile.id.replaceFirst("_r\\d+$", "");
            rotationMappings.computeIfAbsent(baseId, k -> new HashMap<>()).put(tile.rotation, tile.id);
        }

        // Propagate adjacency rules through rotations
        Map<Long, Set<String>> newRules = new HashMap<>();
        for (Map.Entry<Long, Set<String>> entry : adjacencyRules.entrySet()) {
            long key = entry.getKey();
            String fromTileId = null;
            Direction dir = null;

            // Decode key to get tile ID and direction
            for (TreeTile tile : allTiles) {
                for (Direction d : Direction.values()) {
                    if (TreeTileCache.encodeAdjacencyKey(tile.id, d) == key) {
                        fromTileId = tile.id;
                        dir = d;
                        break;
                    }
                }
                if (fromTileId != null) break;
            }

            if (fromTileId == null || dir == null) continue;

            // Get base tile ID
            String baseFromId = fromTileId.replaceFirst("_r\\d+$", "");
            TreeTile fromTile = tileMap.get(fromTileId);
            int rotation = fromTile.rotation;

            // For each valid neighbor in the original rule
            for (String toTileId : entry.getValue()) {
                String baseToId = toTileId.replaceFirst("_r\\d+$", "");

                // Apply the same rotation to both tiles and direction
                Map<Integer, String> fromRotations = rotationMappings.get(baseFromId);
                Map<Integer, String> toRotations = rotationMappings.get(baseToId);

                if (fromRotations != null && toRotations != null) {
                    for (int rot : Arrays.asList(0, 90, 180, 270)) {
                        String rotatedFromId = fromRotations.get(rot);
                        String rotatedToId = toRotations.get(rot);
                        if (rotatedFromId != null && rotatedToId != null) {
                            // Rotate the direction as well
                            Direction rotatedDir = rotateDirection(dir, rot);
                            long newKey = TreeTileCache.encodeAdjacencyKey(rotatedFromId, rotatedDir);
                            newRules.computeIfAbsent(newKey, k -> new HashSet<>()).add(rotatedToId);
                        }
                    }
                }
            }
        }

        // Merge new rules back
        for (Map.Entry<Long, Set<String>> entry : newRules.entrySet()) {
            adjacencyRules.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
        }
    }

    /**
     * Rotates a direction around the Y-axis.
     */
    private static Direction rotateDirection(Direction dir, int degrees) {
        int times = (degrees / 90) % 4;
        Direction result = dir;
        for (int i = 0; i < times; i++) {
            result = switch (result) {
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                case SOUTH -> Direction.WEST;
                case WEST -> Direction.NORTH;
                default -> result; // UP and DOWN don't rotate
            };
        }
        return result;
    }
}
