package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ninja.trek.mc.goldgolem.tower.PyramidResampler;
import ninja.trek.mc.goldgolem.room.RoomLayoutGenerator;
import ninja.trek.mc.goldgolem.room.RoomPlacement;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.tree.TreeDefinition;
import ninja.trek.mc.goldgolem.tree.TreeModule;
import ninja.trek.mc.goldgolem.tree.TreeTile;
import ninja.trek.mc.goldgolem.tree.TreeTileCache;
import ninja.trek.mc.goldgolem.tree.TreeTileExtractor;
import ninja.trek.mc.goldgolem.tree.TreeWFCBuilder;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.wall.WallStateTransform;
import ninja.trek.mc.goldgolem.world.entity.strategy.wall.ModulePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class StructureGenerators {
    private StructureGenerators() {}

    static GeneratedPlan generate(
            net.minecraft.server.level.ServerLevel level,
            GoldGolemTemplate template,
            StructureBuildRequest request
    ) {
        GradientSampler gradient = new GradientSampler(template.gradient(), level == null ? 0L : level.getSeed());
        return switch (request) {
            case WallBuildRequest wall -> generateWall(template.wallModules(), gradient, wall);
            case TowerBuildRequest tower -> generateTower(template.towerModule(), gradient, tower);
            case PyramidBuildRequest pyramid -> generatePyramid(
                    template.towerModule(), template.blockPriority(), gradient, pyramid);
            case TreeBuildRequest tree -> generateTree(level, template, gradient, tree);
            case RoomBuildRequest room -> generateRoom(template, room);
        };
    }

    private static GeneratedPlan generateRoom(GoldGolemTemplate captured, RoomBuildRequest request) {
        List<RoomTemplate> templates = captured.roomTemplates();
        RoomLayoutGenerator.Layout layout = RoomLayoutGenerator.generate(
                templates, request.origin(), request.initialDirection(), request.seed(), request.maxRooms());
        GradientSampler gradient = new GradientSampler(captured.gradient(), request.seed());
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (RoomPlacement placement : layout.rooms()) {
            RoomTemplate room = templates.get(placement.templateIndex());
            for (Map.Entry<BlockPos, BlockState> entry : placement.blocks(room).entrySet()) {
                BlockState sampled = gradient.sampleTree(entry.getValue(), entry.getKey());
                if (sampled != null) {
                    BlockState existing = blocks.get(entry.getKey());
                    if (existing == null || !existing.is(Blocks.GOLD_BLOCK) || sampled.is(Blocks.GOLD_BLOCK)) {
                        blocks.put(entry.getKey(), sampled);
                    }
                }
            }
        }
        return new GeneratedPlan(blocks, layout.requiredAir(templates), false);
    }

    private static GeneratedPlan generateTower(
            TowerModuleTemplate template,
            GradientSampler gradient,
            TowerBuildRequest request
    ) {
        requireTowerTemplate(template);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int layer = 0; layer < request.height(); layer++) {
            int relativeY = Math.floorMod(layer, template.moduleHeight) + template.minY;
            int worldY = request.origin().getY() - 1 + layer;
            for (TowerModuleTemplate.Voxel voxel : template.voxels) {
                if (voxel.rel.getY() != relativeY) continue;
                BlockPos position = new BlockPos(
                        request.origin().getX() + voxel.rel.getX(),
                        worldY,
                        request.origin().getZ() + voxel.rel.getZ()
                );
                BlockState state = gradient.sampleTower(voxel.state, position, layer, request.height());
                if (state != null) blocks.put(position, state);
            }
        }
        return new GeneratedPlan(blocks, false);
    }

    private static GeneratedPlan generatePyramid(
            TowerModuleTemplate template,
            List<String> priority,
            GradientSampler gradient,
            PyramidBuildRequest request
    ) {
        requireTowerTemplate(template);
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int layer = 0; layer < request.height(); layer++) {
            Map<BlockPos, BlockState> layerBlocks = PyramidResampler.buildLayer(
                    template,
                    request.origin(),
                    layer,
                    request.height(),
                    request.curvature(),
                    priority
            );
            for (Map.Entry<BlockPos, BlockState> entry : layerBlocks.entrySet()) {
                BlockState state = gradient.sampleTower(
                        entry.getValue(), entry.getKey(), layer, request.height());
                if (state != null) blocks.put(entry.getKey(), state);
            }
        }
        return new GeneratedPlan(blocks, false);
    }

    private static void requireTowerTemplate(TowerModuleTemplate template) {
        if (template == null || template.moduleHeight < 1 || template.voxels.isEmpty()) {
            throw new IllegalArgumentException("Template has no usable tower module");
        }
    }

    private static GeneratedPlan generateTree(
            net.minecraft.server.level.ServerLevel level,
            GoldGolemTemplate template,
            GradientSampler gradient,
            TreeBuildRequest request
    ) {
        List<Map<BlockPos, BlockState>> storedModules = template.treeModules();
        List<TreeModule> modules = new ArrayList<>(storedModules.size());
        Set<String> uniqueBlockIds = new HashSet<>();
        for (Map<BlockPos, BlockState> stored : storedModules) {
            modules.add(new TreeModule(stored.keySet()));
            for (BlockState state : stored.values()) {
                uniqueBlockIds.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            }
        }
        TreeDefinition definition = new TreeDefinition(
                request.origin(),
                modules,
                List.copyOf(uniqueBlockIds),
                template.treeGroundBlockId()
        );
        TreeTileCache tileCache = TreeTileExtractor.extract(
                level,
                definition,
                template.treeTilingPreset(),
                request.origin(),
                storedModules
        );
        if (tileCache.isEmpty()) {
            throw new IllegalArgumentException("Tree template produced no WFC tiles");
        }

        Set<Block> groundBlocks = new HashSet<>();
        if (template.treeGroundBlockId() != null) {
            groundBlocks.add(Blocks.GRASS_BLOCK);
            groundBlocks.add(Blocks.DIRT);
            groundBlocks.add(Blocks.DIRT_PATH);
        }
        Set<Block> stopBlocks = new HashSet<>(groundBlocks);
        stopBlocks.add(Blocks.GOLD_BLOCK);

        TreeWFCBuilder builder = new TreeWFCBuilder(
                tileCache,
                level,
                request.origin(),
                stopBlocks,
                groundBlocks,
                new Random(request.seed()),
                Set.of()
        );
        int steps = 0;
        while (!builder.isFinished() && steps < request.maxTiles()) {
            builder.step();
            steps++;
        }
        boolean capped = !builder.isFinished();

        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        while (builder.hasPendingBlocks()) {
            BlockPos tileOrigin = builder.getNextBuildPosition();
            if (tileOrigin == null) break;
            String tileId = builder.getCollapsedTile(tileOrigin);
            TreeTile tile = tileId == null ? null : builder.getTile(tileId);
            if (tile == null) continue;
            for (int dx = 0; dx < tile.size; dx++) {
                for (int dy = 0; dy < tile.size; dy++) {
                    for (int dz = 0; dz < tile.size; dz++) {
                        BlockState state = tile.getBlock(dx, dy, dz);
                        if (state.isAir() || state == TreeTileExtractor.GROUND_MARKER) continue;
                        BlockPos position = tileOrigin.offset(dx, dy, dz);
                        BlockState sampled = gradient.sampleTree(state, position);
                        if (sampled != null) blocks.put(position, sampled);
                    }
                }
            }
        }
        return new GeneratedPlan(blocks, capped);
    }

    private static GeneratedPlan generateWall(
            List<WallModuleTemplate> templates,
            GradientSampler gradient,
            WallBuildRequest request
    ) {
        if (templates.isEmpty()) throw new IllegalArgumentException("Template has no wall modules");
        double threshold = templates.stream().mapToDouble(WallModuleTemplate::horizLen).max().orElse(1.0) + 1.0;
        Vec3 anchor = Vec3.atLowerCornerOf(request.guidePoints().getFirst());
        WallJoinSlice outputSlice = null;
        int directionX = 1;
        int directionZ = 0;
        int placedModules = 0;
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

        for (int guideIndex = 1; guideIndex < request.guidePoints().size(); guideIndex++) {
            Vec3 guide = Vec3.atLowerCornerOf(request.guidePoints().get(guideIndex));
            while (horizontalDistance(anchor, guide) >= threshold) {
                if (placedModules >= request.maxModules()) {
                    throw new IllegalArgumentException("Wall generation reached maxModules before the guide ended");
                }
                Candidate candidate = chooseWallCandidate(
                        templates,
                        anchor,
                        guide,
                        directionX,
                        directionZ,
                        outputSlice,
                        placedModules == 0
                );
                if (candidate == null) {
                    throw new IllegalArgumentException("No compatible wall module can follow guide point " + guideIndex);
                }
                appendWallModule(blocks, templates.get(candidate.templateIndex()), gradient, candidate);
                anchor = candidate.end();
                outputSlice = candidate.outputSlice();
                directionX = candidate.outputDirectionX();
                directionZ = candidate.outputDirectionZ();
                placedModules++;
            }
        }
        if (placedModules == 0) {
            throw new IllegalArgumentException("Wall guide is shorter than the module placement threshold");
        }
        return new GeneratedPlan(blocks, false);
    }

    private static Candidate chooseWallCandidate(
            List<WallModuleTemplate> templates,
            Vec3 anchor,
            Vec3 guide,
            int effectiveDirectionX,
            int effectiveDirectionZ,
            WallJoinSlice effectiveSlice,
            boolean firstModule
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
            WallModuleTemplate template = templates.get(templateIndex);
            int moduleX = template.bMarker.getX() - template.aMarker.getX();
            int moduleY = template.bMarker.getY() - template.aMarker.getY();
            int moduleZ = template.bMarker.getZ() - template.aMarker.getZ();
            for (boolean reversed : new boolean[]{false, true}) {
                int dx = reversed ? -moduleX : moduleX;
                int dy = reversed ? -moduleY : moduleY;
                int dz = reversed ? -moduleZ : moduleZ;
                for (int rotation = 0; rotation < 4; rotation++) {
                    for (boolean mirror : new boolean[]{false, true}) {
                        int[] delta = ModulePlacement.rotateAndMirror(dx, dy, dz, rotation, mirror);
                        double guideX = guide.x - anchor.x;
                        double guideZ = guide.z - anchor.z;
                        if (delta[0] * guideX + delta[2] * guideZ <= 1.0e-6) continue;
                        Vec3 end = new Vec3(anchor.x + delta[0], anchor.y + delta[1], anchor.z + delta[2]);

                        if (!firstModule) {
                            WallJoinSlice input = reversed ? template.getBSlice() : template.getASlice();
                            if (effectiveSlice == null || input == null
                                    || !effectiveSlice.profileEquals(input.transformed(rotation, mirror))) {
                                continue;
                            }
                        }
                        WallJoinSlice output = reversed ? template.getASlice() : template.getBSlice();
                        if (output == null) continue;
                        output = output.transformed(rotation, mirror);

                        int[] outputDirection = outputDirection(template, reversed, rotation, delta);
                        double yNeed = guide.y - anchor.y;
                        double yError = Math.abs(guide.y - end.y);
                        boolean exactY = yError < 0.5;
                        boolean towardY = Math.abs(yNeed) < 1.0e-6
                                ? Math.abs(delta[1]) < 1.0e-6
                                : Math.signum(delta[1]) == Math.signum(yNeed);
                        boolean noOvershoot = Math.abs(delta[1]) <= Math.abs(yNeed) + 1.0e-6;
                        int verticalTier = exactY ? 0 : towardY && noOvershoot ? 1
                                : Math.abs(delta[1]) < 1.0e-6 ? 2 : 3;
                        double segmentDistance = distanceToSegmentXZ(end, anchor, guide);
                        double guideDistance = end.distanceTo(guide);
                        int continuity = delta[0] * effectiveDirectionX + delta[2] * effectiveDirectionZ;
                        candidates.add(new Candidate(
                                templateIndex,
                                rotation,
                                mirror,
                                reversed,
                                anchor,
                                end,
                                output,
                                outputDirection[0],
                                outputDirection[1],
                                verticalTier,
                                segmentDistance,
                                yError,
                                guideDistance,
                                continuity
                        ));
                    }
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt(Candidate::verticalTier)
                .thenComparingDouble(Candidate::segmentDistance)
                .thenComparingDouble(Candidate::yError)
                .thenComparingDouble(Candidate::guideDistance)
                .thenComparing(Comparator.comparingInt(Candidate::continuity).reversed())
                .thenComparingInt(Candidate::templateIndex)
                .thenComparing(Candidate::reversed)
                .thenComparingInt(Candidate::rotation)
                .thenComparing(Candidate::mirror));
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private static int[] outputDirection(
            WallModuleTemplate template,
            boolean reversed,
            int rotation,
            int[] delta
    ) {
        WallJoinSlice.Axis axis = reversed ? template.aSliceAxis : template.bSliceAxis;
        if (axis != null && (rotation == 1 || rotation == 3)) {
            axis = axis == WallJoinSlice.Axis.X_THICK
                    ? WallJoinSlice.Axis.Z_THICK : WallJoinSlice.Axis.X_THICK;
        }
        if (axis == WallJoinSlice.Axis.X_THICK && delta[0] != 0) {
            return new int[]{Integer.signum(delta[0]), 0};
        }
        if (axis == WallJoinSlice.Axis.Z_THICK && delta[2] != 0) {
            return new int[]{0, Integer.signum(delta[2])};
        }
        return Math.abs(delta[0]) >= Math.abs(delta[2])
                ? new int[]{Integer.signum(delta[0]), 0}
                : new int[]{0, Integer.signum(delta[2])};
    }

    private static void appendWallModule(
            Map<BlockPos, BlockState> blocks,
            WallModuleTemplate template,
            GradientSampler gradient,
            Candidate candidate
    ) {
        int reverseX = candidate.reversed()
                ? -(template.bMarker.getX() - template.aMarker.getX()) : 0;
        int reverseY = candidate.reversed()
                ? -(template.bMarker.getY() - template.aMarker.getY()) : 0;
        int reverseZ = candidate.reversed()
                ? -(template.bMarker.getZ() - template.aMarker.getZ()) : 0;
        int moduleMaxY = template.voxels.stream()
                .mapToInt(voxel -> voxel.rel.getY()).max().orElse(template.minY);
        int moduleHeight = Math.max(1, moduleMaxY - template.minY + 1);
        for (WallModuleTemplate.Voxel voxel : template.voxels) {
            int[] delta = ModulePlacement.rotateAndMirror(
                    voxel.rel.getX() + reverseX,
                    voxel.rel.getY() + reverseY,
                    voxel.rel.getZ() + reverseZ,
                    candidate.rotation(),
                    candidate.mirror()
            );
            BlockPos position = new BlockPos(
                    Mth.floor(candidate.anchor().x) + delta[0],
                    Mth.floor(candidate.anchor().y) + delta[1],
                    Mth.floor(candidate.anchor().z) + delta[2]
            );
            BlockState sampled = gradient.sampleWall(
                    voxel.state, position, moduleHeight, voxel.rel.getY() - template.minY);
            if (sampled != null) {
                blocks.put(position, WallStateTransform.forPlacement(
                        sampled, candidate.rotation(), candidate.mirror()));
            }
        }
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static double distanceToSegmentXZ(Vec3 point, Vec3 start, Vec3 end) {
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double lengthSquared = dx * dx + dz * dz;
        if (lengthSquared <= 1.0e-12) return Math.hypot(point.x - start.x, point.z - start.z);
        double t = ((point.x - start.x) * dx + (point.z - start.z) * dz) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double closestX = start.x + t * dx;
        double closestZ = start.z + t * dz;
        return Math.hypot(point.x - closestX, point.z - closestZ);
    }

    record GeneratedPlan(Map<BlockPos, BlockState> blocks, Set<BlockPos> requiredAir, boolean capped) {
        GeneratedPlan(Map<BlockPos, BlockState> blocks, boolean capped) {
            this(blocks, Set.of(), capped);
        }

        GeneratedPlan {
            blocks = Map.copyOf(blocks);
            requiredAir = requiredAir == null ? Set.of() : Set.copyOf(requiredAir);
        }
    }

    private record Candidate(
            int templateIndex,
            int rotation,
            boolean mirror,
            boolean reversed,
            Vec3 anchor,
            Vec3 end,
            WallJoinSlice outputSlice,
            int outputDirectionX,
            int outputDirectionZ,
            int verticalTier,
            double segmentDistance,
            double yError,
            double guideDistance,
            int continuity
    ) {}
}
