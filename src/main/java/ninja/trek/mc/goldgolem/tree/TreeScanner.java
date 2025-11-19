package ninja.trek.mc.goldgolem.tree;

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
 * Summon-time Tree Mode scanner.
 * Performs flood fill from second gold block, identifies gold block separators,
 * and extracts individual modules as separate connected components.
 */
public final class TreeScanner {
    public static final int MAX_VOXELS = 4096;
    public static final int MAX_EXTENT = 512; // per axis bound size

    private static final Direction[] NEIGHBORS = new Direction[]{
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public record Result(TreeDefinition def, String error) {
        public boolean ok() { return def != null && (error == null || error.isEmpty()); }
    }

    public static Result scan(World world, BlockPos secondGoldPos, PlayerEntity summoner) {
        // Determine the block the player is standing on (one below feet)
        BlockPos playerGround = summoner == null ? null : summoner.getBlockPos().down();

        // Canonicalize ground-equivalence only if the player stands on a ground type
        boolean unifyGround = false;
        Block groundType = null;
        if (playerGround != null) {
            BlockState gs = world.getBlockState(playerGround);
            groundType = gs.getBlock();
            if (groundType == Blocks.GRASS_BLOCK || groundType == Blocks.DIRT || groundType == Blocks.DIRT_PATH) {
                unifyGround = true;
            }
        }

        // Constrained flood fill - include all blocks (including gold blocks initially)
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(secondGoldPos);
        visited.add(secondGoldPos);

        BlockPos min = new BlockPos(secondGoldPos);
        BlockPos max = new BlockPos(secondGoldPos);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.removeFirst();
            for (Direction d : NEIGHBORS) {
                BlockPos n = cur.offset(d);
                if (visited.contains(n)) continue;

                BlockState st = world.getBlockState(n);
                // Exclude snow layers
                if (st.isOf(Blocks.SNOW)) continue;
                // Exclude air
                if (st.isAir()) continue;
                // Ignore the type of block the player is standing on
                if (groundType != null) {
                    Block nb = st.getBlock();
                    if (nb == groundType) continue;
                    // If standing on a ground-equivalence type, ignore all three (dirt/grass/path)
                    if (unifyGround && (nb == Blocks.GRASS_BLOCK || nb == Blocks.DIRT || nb == Blocks.DIRT_PATH)) continue;
                }
                // Also ignore exactly the block position the player is standing on
                if (playerGround != null && n.equals(playerGround)) continue;

                visited.add(n);
                // bounds check
                min = new BlockPos(Math.min(min.getX(), n.getX()), Math.min(min.getY(), n.getY()), Math.min(min.getZ(), n.getZ()));
                max = new BlockPos(Math.max(max.getX(), n.getX()), Math.max(max.getY(), n.getY()), Math.max(max.getZ(), n.getZ()));

                if (visited.size() > MAX_VOXELS) {
                    return new Result(null, "Tree scan exceeded 4096 blocks");
                }
                if ((max.getX() - min.getX() + 1) > MAX_EXTENT ||
                        (max.getY() - min.getY() + 1) > MAX_EXTENT ||
                        (max.getZ() - min.getZ() + 1) > MAX_EXTENT) {
                    return new Result(null, "Tree scan exceeded 512x512x512 bounds");
                }
                queue.addLast(n);
            }
        }

        // Separate gold blocks from regular blocks
        Set<BlockPos> goldBlocks = new HashSet<>();
        Set<BlockPos> regularBlocks = new HashSet<>();
        for (BlockPos abs : visited) {
            BlockState st = world.getBlockState(abs);
            if (st.isOf(Blocks.GOLD_BLOCK)) {
                goldBlocks.add(abs);
            } else {
                regularBlocks.add(abs);
            }
        }

        // Find connected components in regularBlocks (modules separated by gold blocks)
        List<Set<BlockPos>> components = findConnectedComponents(regularBlocks);

        if (components.isEmpty()) {
            return new Result(null, "No input modules found (only gold blocks detected)");
        }

        // Build relative modules from origin (secondGoldPos)
        List<TreeModule> modules = new ArrayList<>();
        Set<String> uniqSet = new HashSet<>();
        List<String> uniques = new ArrayList<>();

        for (Set<BlockPos> comp : components) {
            Set<BlockPos> relVoxels = new HashSet<>();
            for (BlockPos abs : comp) {
                BlockPos rel = abs.subtract(secondGoldPos);
                relVoxels.add(rel);

                // Collect unique block IDs
                BlockState st = world.getBlockState(abs);
                Block b = st.getBlock();
                String id = Registries.BLOCK.getId(b).toString();
                // Unify ground ids if requested
                if (unifyGround && (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.DIRT_PATH)) {
                    id = Registries.BLOCK.getId(Blocks.DIRT).toString();
                }
                if (uniqSet.add(id)) {
                    uniques.add(id);
                }
            }
            modules.add(new TreeModule(relVoxels));
        }

        TreeDefinition def = new TreeDefinition(secondGoldPos.toImmutable(), modules, uniques);
        return new Result(def, null);
    }

    /**
     * Finds connected components in a set of block positions using 6-neighbor connectivity.
     */
    private static List<Set<BlockPos>> findConnectedComponents(Set<BlockPos> blocks) {
        List<Set<BlockPos>> components = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos start : blocks) {
            if (visited.contains(start)) continue;

            // BFS to find component
            Set<BlockPos> component = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            component.add(start);

            while (!queue.isEmpty()) {
                BlockPos cur = queue.removeFirst();
                for (Direction d : NEIGHBORS) {
                    BlockPos n = cur.offset(d);
                    if (blocks.contains(n) && visited.add(n)) {
                        queue.add(n);
                        component.add(n);
                    }
                }
            }

            components.add(component);
        }

        return components;
    }

    /**
     * Writes the scan result to a JSON file for debugging/export.
     */
    public static Path writeJson(Path baseDir, UUID owner, TreeDefinition def) throws IOException {
        Path folder = baseDir.resolve("GoldGolemModules");
        Files.createDirectories(folder);
        String fname = String.format(Locale.ROOT,
                "tree_%d_%d_%d_%d.json",
                System.currentTimeMillis(), def.origin.getX(), def.origin.getY(), def.origin.getZ());
        Path out = folder.resolve(fname);

        StringBuilder sb = new StringBuilder(1 << 14);
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"owner\": \"").append(owner == null ? "" : owner.toString()).append("\",\n");
        sb.append("  \"origin\": [").append(def.origin.getX()).append(',').append(def.origin.getY()).append(',').append(def.origin.getZ()).append("],\n");
        sb.append("  \"moduleCount\": ").append(def.modules.size()).append(",\n");

        // uniqueBlockIds
        sb.append("  \"uniqueBlockIds\": [");
        for (int i = 0; i < def.uniqueBlockIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(def.uniqueBlockIds.get(i)).append('\"');
        }
        sb.append("],\n");

        // modules
        sb.append("  \"modules\": [\n");
        for (int m = 0; m < def.modules.size(); m++) {
            if (m > 0) sb.append(",\n");
            TreeModule module = def.modules.get(m);
            sb.append("    {\n");
            sb.append("      \"voxelCount\": ").append(module.size()).append(",\n");
            sb.append("      \"centroid\": [").append(module.centroid.getX()).append(',')
                .append(module.centroid.getY()).append(',').append(module.centroid.getZ()).append("],\n");
            sb.append("      \"voxels\": [");
            int c = 0;
            for (BlockPos p : module.voxels) {
                if (c++ > 0) sb.append(',');
                sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
            }
            sb.append("]\n");
            sb.append("    }");
        }
        sb.append("\n  ]\n");
        sb.append("}\n");

        Files.writeString(out, sb.toString());
        return out;
    }
}
