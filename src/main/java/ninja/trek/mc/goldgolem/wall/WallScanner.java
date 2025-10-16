package ninja.trek.mc.goldgolem.wall;

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
 * Summon-time wall-mode scanner.
 * Implements a constrained 6-neighbor flood fill around the gold block,
 * capturing all blocks except snow layers, with limits: <=4096 voxels and within a 512^3 AABB.
 * Also ignores the single block the player is standing on from consideration.
 */
public final class WallScanner {
    public static final int MAX_VOXELS = 4096;
    public static final int MAX_EXTENT = 512; // per axis bound size

    private static final Direction[] NEIGHBORS = new Direction[]{
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    public record Result(WallDefinition def, String error) {
        public boolean ok() { return def != null && (error == null || error.isEmpty()); }
    }

    public static Result scan(World world, BlockPos goldPos, PlayerEntity summoner) {
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

        // Constrained flood fill
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(goldPos);
        visited.add(goldPos);

        BlockPos min = new BlockPos(goldPos);
        BlockPos max = new BlockPos(goldPos);

        while (!queue.isEmpty()) {
            BlockPos cur = queue.removeFirst();
            for (Direction d : NEIGHBORS) {
                BlockPos n = cur.offset(d);
                if (visited.contains(n)) continue;
                // exclude snow layers
                BlockState st = world.getBlockState(n);
                if (st.isOf(Blocks.SNOW)) continue;
                // Ignore the type of block the player is standing on (skip all of that type in the fill)
                if (groundType != null) {
                    Block nb = st.getBlock();
                    if (nb == groundType) continue;
                    // If standing on a ground-equivalence type, ignore all three (dirt/grass/path)
                    if (unifyGround && (nb == Blocks.GRASS_BLOCK || nb == Blocks.DIRT || nb == Blocks.DIRT_PATH)) continue;
                }
                // Also ignore exactly the block position the player is standing on
                if (playerGround != null && n.equals(playerGround)) continue;
                // otherwise include all blocks, including air? Specification says include all blocks; keep air excluded by heuristic: only include non-air.
                if (st.isAir()) continue;

                // if unifyGround, treat grass/dirt/path as equivalent – we still include them in the fill,
                // but for uniqueness list we can remap ids later. For fill bounds, no change.

                visited.add(n);
                // bounds check
                min = new BlockPos(Math.min(min.getX(), n.getX()), Math.min(min.getY(), n.getY()), Math.min(min.getZ(), n.getZ()));
                max = new BlockPos(Math.max(max.getX(), n.getX()), Math.max(max.getY(), n.getY()), Math.max(max.getZ(), n.getZ()));

                if (visited.size() > MAX_VOXELS) {
                    return new Result(null, "Wall scan exceeded 4096 blocks");
                }
                if ((max.getX() - min.getX() + 1) > MAX_EXTENT ||
                        (max.getY() - min.getY() + 1) > MAX_EXTENT ||
                        (max.getZ() - min.getZ() + 1) > MAX_EXTENT) {
                    return new Result(null, "Wall scan exceeded 512x512x512 bounds");
                }
                queue.addLast(n);
            }
        }

        // Build relative set from origin goldPos
        Set<BlockPos> rel = new HashSet<>();
        List<String> uniques = new ArrayList<>();
        Set<String> uniqSet = new HashSet<>();
        List<BlockPos> golds = new ArrayList<>();
        for (BlockPos abs : visited) {
            BlockState st = world.getBlockState(abs);
            Block b = st.getBlock();
            String id = Registries.BLOCK.getId(b).toString();
            // Unify ground ids if requested
            if (unifyGround && (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.DIRT_PATH)) {
                id = Registries.BLOCK.getId(Blocks.DIRT).toString();
            }
            if (uniqSet.add(id)) uniques.add(id);
            BlockPos r = abs.subtract(goldPos);
            rel.add(r);
            if (b == Blocks.GOLD_BLOCK) golds.add(r);
        }

        WallDefinition def = new WallDefinition(goldPos.toImmutable(), rel, golds, uniques);
        return new Result(def, null);
    }

    public static Path writeJson(Path baseDir, UUID owner, WallDefinition def) throws IOException {
        Path folder = baseDir.resolve("GoldGolemModules");
        Files.createDirectories(folder);
        String fname = String.format(Locale.ROOT,
                "wall_%d_%d_%d_%d.json",
                System.currentTimeMillis(), def.origin.getX(), def.origin.getY(), def.origin.getZ());
        Path out = folder.resolve(fname);

        // Simple JSON writer without external deps
        StringBuilder sb = new StringBuilder(1 << 14);
        sb.append("{\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"owner\": \"").append(owner == null ? "" : owner.toString()).append("\",\n");
        sb.append("  \"origin\": [").append(def.origin.getX()).append(',').append(def.origin.getY()).append(',').append(def.origin.getZ()).append("],\n");
        // uniques
        sb.append("  \"uniqueBlockIds\": [");
        for (int i = 0; i < def.uniqueBlockIds.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\"').append(def.uniqueBlockIds.get(i)).append('\"');
        }
        sb.append("],\n");
        // gold markers
        sb.append("  \"goldMarkers\": [");
        for (int i = 0; i < def.goldMarkers.size(); i++) {
            if (i > 0) sb.append(',');
            var p = def.goldMarkers.get(i);
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
        }
        sb.append("],\n");
        // voxels
        sb.append("  \"voxels\": [");
        int c = 0;
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
