package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public entry point for loading and synchronously building Gold Golem templates. */
public final class GoldGolemStructures {
    private static final String RESOURCE_PREFIX = "gold-golem/templates/";

    private GoldGolemStructures() {}

    public static GoldGolemTemplate load(ServerLevel level, Identifier id) throws TemplateLoadException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
        Identifier resourceId = Identifier.fromNamespaceAndPath(
                id.getNamespace(), RESOURCE_PREFIX + id.getPath() + ".json");
        Resource resource = level.getServer().getResourceManager().getResource(resourceId)
                .orElseThrow(() -> new TemplateLoadException("Missing structure template: " + resourceId));
        try (var input = resource.open();
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return GoldGolemTemplateCodec.read(reader);
        } catch (IOException e) {
            throw new TemplateLoadException("Failed to read structure template " + resourceId, e);
        }
    }

    public static GoldGolemTemplate load(ServerLevel level, Path path) throws TemplateLoadException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(path, "path");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GoldGolemTemplateCodec.read(reader);
        } catch (IOException e) {
            throw new TemplateLoadException("Failed to read structure template " + path, e);
        }
    }

    public static BuildResult loadAndBuild(
            ServerLevel level,
            Identifier id,
            StructureBuildRequest request
    ) throws TemplateLoadException {
        return build(level, load(level, id), request);
    }

    public static BuildResult loadAndBuild(
            ServerLevel level,
            Path path,
            StructureBuildRequest request
    ) throws TemplateLoadException {
        return build(level, load(level, path), request);
    }

    public static BuildResult build(
            ServerLevel level,
            GoldGolemTemplate template,
            StructureBuildRequest request
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(request, "request");
        if (!level.getServer().isSameThread()) {
            return failure(BuildResult.Status.WRONG_THREAD, "Builds must run on the logical server thread");
        }
        if (!modesCompatible(template.mode(), request.mode())) {
            return failure(BuildResult.Status.INVALID_REQUEST,
                    "Template mode " + template.mode() + " cannot satisfy " + request.mode());
        }

        StructureGenerators.GeneratedPlan plan;
        try {
            plan = StructureGenerators.generate(level, template, request);
        } catch (IllegalArgumentException e) {
            return failure(BuildResult.Status.GENERATION_FAILED, e.getMessage());
        } catch (Exception e) {
            return failure(BuildResult.Status.GENERATION_FAILED,
                    "Unexpected generation failure: " + e.getMessage());
        }
        if (plan.blocks().isEmpty()) {
            return failure(BuildResult.Status.GENERATION_FAILED, "Generator produced no blocks");
        }

        RoomTerrainPolicy terrainPolicy = request instanceof RoomBuildRequest room
                ? room.terrainPolicy() : null;
        return placeGenerated(level, plan.blocks(), plan.requiredAir(), terrainPolicy,
                plan.capped(), plan.capped() ? List.of("Tree generation reached maxTiles") : List.of());
    }

    static BuildResult placeGenerated(
            ServerLevel level,
            Map<BlockPos, BlockState> blocks,
            Set<BlockPos> requiredAir,
            RoomTerrainPolicy terrainPolicy,
            boolean capped,
            List<String> successDiagnostics
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(blocks, "blocks");
        requiredAir = requiredAir == null ? Set.of() : Set.copyOf(requiredAir);
        if (!level.getServer().isSameThread()) {
            return failure(BuildResult.Status.WRONG_THREAD, "Builds must run on the logical server thread");
        }
        if (blocks.isEmpty()) return failure(BuildResult.Status.GENERATION_FAILED, "Generator produced no blocks");

        List<Map.Entry<BlockPos, BlockState>> ordered = new ArrayList<>(blocks.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator
                .comparingInt((BlockPos position) -> position.getY())
                .thenComparingInt(position -> position.getX())
                .thenComparingInt(position -> position.getZ())));
        for (Map.Entry<BlockPos, BlockState> entry : ordered) {
            BlockPos position = entry.getKey();
            if (position.getY() < level.getMinY() || position.getY() >= level.getMaxY()) {
                return failure(BuildResult.Status.INVALID_REQUEST,
                        "Generated position is outside build height: " + position);
            }
        }
        for (BlockPos position : requiredAir) {
            if (position.getY() < level.getMinY() || position.getY() >= level.getMaxY()) {
                return failure(BuildResult.Status.INVALID_REQUEST,
                        "Generated required-air position is outside build height: " + position);
            }
        }

        Set<Long> chunks = new HashSet<>();
        try {
            for (Map.Entry<BlockPos, BlockState> entry : ordered) {
                BlockPos position = entry.getKey();
                int chunkX = position.getX() >> 4;
                int chunkZ = position.getZ() >> 4;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                if (chunks.add(key)) level.getChunk(chunkX, chunkZ);
            }
            for (BlockPos position : requiredAir) {
                int chunkX = position.getX() >> 4;
                int chunkZ = position.getZ() >> 4;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                if (chunks.add(key)) level.getChunk(chunkX, chunkZ);
            }
        } catch (Exception e) {
            return new BuildResult(
                    BuildResult.Status.PLACEMENT_FAILED,
                    ordered.size(),
                    0,
                    0,
                    chunks.size(),
                    List.of("Failed to load target chunks: " + e.getMessage())
            );
        }

        if (terrainPolicy == RoomTerrainPolicy.REQUIRE_CLEAR) {
            for (Map.Entry<BlockPos, BlockState> entry : ordered) {
                BlockState current = level.getBlockState(entry.getKey());
                if (!current.equals(entry.getValue()) && !current.isAir() && !current.canBeReplaced()) {
                    return failure(BuildResult.Status.PLACEMENT_FAILED,
                            "Generated solid volume is obstructed at " + entry.getKey());
                }
            }
            for (BlockPos position : requiredAir) {
                if (!level.getBlockState(position).isAir()) {
                    return failure(BuildResult.Status.PLACEMENT_FAILED,
                            "Generated required-air volume is obstructed at " + position);
                }
            }
        }

        int placed = 0;
        int occupied = 0;
        for (Map.Entry<BlockPos, BlockState> entry : ordered) {
            BlockPos position = entry.getKey();
            BlockState current = level.getBlockState(position);
            if (!current.isAir() && !current.canBeReplaced()) {
                occupied++;
                continue;
            }
            if (level.setBlock(position, entry.getValue(), 3)) {
                placed++;
            } else {
                return new BuildResult(
                        BuildResult.Status.PLACEMENT_FAILED,
                        ordered.size(),
                        placed,
                        occupied,
                        chunks.size(),
                        List.of("World rejected block placement at " + position)
                );
            }
        }
        return new BuildResult(
                capped ? BuildResult.Status.CAPPED : BuildResult.Status.SUCCESS,
                ordered.size(),
                placed,
                occupied,
                chunks.size(),
                successDiagnostics == null ? List.of() : successDiagnostics
        );
    }

    private static boolean modesCompatible(TemplateMode template, TemplateMode request) {
        if (template == request) return true;
        return (template == TemplateMode.TOWER || template == TemplateMode.PYRAMID)
                && (request == TemplateMode.TOWER || request == TemplateMode.PYRAMID);
    }

    private static BuildResult failure(BuildResult.Status status, String diagnostic) {
        return new BuildResult(status, 0, 0, 0, 0, List.of(diagnostic));
    }
}
