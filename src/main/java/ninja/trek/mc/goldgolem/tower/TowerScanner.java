package ninja.trek.mc.goldgolem.tower;

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
 * Summon-time tower-mode scanner.
 * Implements a constrained 6-neighbor flood fill from the bottom gold block,
 * capturing all blocks except snow layers and gold blocks, with limits: <=4096 voxels and within a 512^3 AABB.
 * Also ignores the block type the player is standing on.
 */
public final class TowerScanner {
    public static final int MAX_VOXELS = 4096;
    public static final int MAX_EXTENT = 512; // per axis bound size

    private static final Direction[] NEIGHBORS = new Direction[]{
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public record Result(TowerDefinition def, String error) {
        public boolean ok() { return def != null && (error == null || error.isEmpty()); }
    }

    /**
     * Scans the structure from the gold blocks for tower mode.
     * @param world The world
     * @param goldBlockPositions List of gold block positions to start flood fill from
     * @param origin The origin position for relative coordinates (typically bottom gold block)
     * @param summoner The player who summoned the golem
     * @return Result containing the tower definition or error message
     */
    public static Result scan(World world, List<BlockPos> goldBlockPositions, BlockPos origin, PlayerEntity summoner) {
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

        // Constrained flood fill - start from all gold block positions
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos goldPos : goldBlockPositions) {
            queue.add(goldPos);
            visited.add(goldPos);
        }

        BlockPos min = new BlockPos(origin);
        BlockPos max = new BlockPos(origin);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.removeFirst();
            for (Direction d : NEIGHBORS) {
                BlockPos n = cur.offset(d);
                if (visited.contains(n)) continue;

                BlockState st = world.getBlockState(n);

                // Exclude snow layers
                if (st.isOf(Blocks.SNOW)) continue;

                // Exclude gold blocks (they're used for tower height marking)
                if (st.isOf(Blocks.GOLD_BLOCK)) continue;

                // Ignore the type of block the player is standing on
                if (groundType != null) {
                    Block nb = st.getBlock();
                    if (nb == groundType) continue;
                    // If standing on a ground-equivalence type, ignore all three (dirt/grass/path)
                    if (unifyGround && (nb == Blocks.GRASS_BLOCK || nb == Blocks.DIRT || nb == Blocks.DIRT_PATH)) continue;
                }

                // Also ignore exactly the block position the player is standing on
                if (playerGround != null && n.equals(playerGround)) continue;

                // Only include non-air blocks
                if (st.isAir()) continue;

                visited.add(n);
                // bounds check
                min = new BlockPos(Math.min(min.getX(), n.getX()), Math.min(min.getY(), n.getY()), Math.min(min.getZ(), n.getZ()));
                max = new BlockPos(Math.max(max.getX(), n.getX()), Math.max(max.getY(), n.getY()), Math.max(max.getZ(), n.getZ()));

                if (visited.size() > MAX_VOXELS) {
                    return new Result(null, "Tower scan exceeded 4096 blocks");
                }
                if ((max.getX() - min.getX() + 1) > MAX_EXTENT ||
                        (max.getY() - min.getY() + 1) > MAX_EXTENT ||
                        (max.getZ() - min.getZ() + 1) > MAX_EXTENT) {
                    return new Result(null, "Tower scan exceeded 512x512x512 bounds");
                }
                queue.addLast(n);
            }
        }

        // Build relative set from origin (bottomGoldPos)
        Set<BlockPos> rel = new HashSet<>();
        List<String> uniques = new ArrayList<>();
        Set<String> uniqSet = new HashSet<>();
        Map<String, Integer> blockCounts = new HashMap<>();

        for (BlockPos abs : visited) {
            BlockState st = world.getBlockState(abs);
            Block b = st.getBlock();
            String id = Registries.BLOCK.getId(b).toString();

            // Unify ground ids if requested
            if (unifyGround && (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.DIRT_PATH)) {
                id = Registries.BLOCK.getId(Blocks.DIRT).toString();
            }

            if (uniqSet.add(id)) uniques.add(id);
            blockCounts.put(id, blockCounts.getOrDefault(id, 0) + 1);

            BlockPos r = abs.subtract(origin);
            rel.add(r);
        }

        // Calculate module height (Y extent)
        int moduleHeight = max.getY() - min.getY() + 1;

        TowerDefinition def = new TowerDefinition(origin.toImmutable(), rel, uniques, blockCounts, moduleHeight);
        return new Result(def, null);
    }

    public static Path writeJson(Path baseDir, UUID owner, TowerDefinition def) throws IOException {
        Path folder = baseDir.resolve("GoldGolemModules");
        Files.createDirectories(folder);
        String fname = String.format(Locale.ROOT,
                "tower_%d_%d_%d_%d.json",
                System.currentTimeMillis(), def.origin.getX(), def.origin.getY(), def.origin.getZ());
        Path out = folder.resolve(fname);

        // Simple JSON writer without external deps
        StringBuilder sb = new StringBuilder(1 << 14);
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"owner\": \"").append(owner == null ? "" : owner.toString()).append("\",\n");
        sb.append("  \"origin\": [").append(def.origin.getX()).append(',').append(def.origin.getY()).append(',').append(def.origin.getZ()).append("],\n");
        sb.append("  \"moduleHeight\": ").append(def.moduleHeight).append(",\n");

        // uniques
        sb.append("  \"uniqueBlockIds\": [");
        for (int i = 0; i < def.uniqueBlockIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(def.uniqueBlockIds.get(i)).append('\"');
        }
        sb.append("],\n");

        // block counts
        sb.append("  \"blockCounts\": {");
        int c = 0;
        for (var entry : def.blockCounts.entrySet()) {
            if (c++ > 0) sb.append(',');
            sb.append('\"').append(entry.getKey()).append("\": ").append(entry.getValue());
        }
        sb.append("},\n");

        // voxels
        sb.append("  \"voxels\": [");
        c = 0;
        for (BlockPos p : def.voxels) {
            if (c++ > 0) sb.append(',');
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
        }
        sb.append("]\n");
        sb.append("}\n");

        Files.writeString(out, sb.toString());
        return out;
    }
}
