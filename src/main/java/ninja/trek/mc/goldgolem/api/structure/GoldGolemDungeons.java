package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.room.RoomPlacement;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.room.RoomTransform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Public entry point for side-effect-free dungeon planning and synchronous placement. */
public final class GoldGolemDungeons {
    private GoldGolemDungeons() {}

    public static DungeonPlanResult plan(GoldGolemTemplate template, DungeonGenerationRequest request) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(request, "request");
        return DungeonPlanner.plan(template, request);
    }

    public static BuildResult build(ServerLevel level, DungeonPlan plan, DungeonPlacement placement) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(placement, "placement");
        GeneratedDungeon generated;
        try {
            generated = materialize(plan, placement);
        } catch (IllegalArgumentException e) {
            return new BuildResult(BuildResult.Status.GENERATION_FAILED,
                    0, 0, 0, 0, List.of(e.getMessage()));
        } catch (Exception e) {
            return new BuildResult(BuildResult.Status.GENERATION_FAILED,
                    0, 0, 0, 0, List.of("Unexpected dungeon materialization failure: " + e.getMessage()));
        }
        return GoldGolemStructures.placeGenerated(level, generated.blocks, generated.requiredAir,
                placement.terrainPolicy(), false, List.of());
    }

    static GeneratedDungeon materialize(DungeonPlan plan, DungeonPlacement placement) {
        GoldGolemTemplate source = plan.template();
        RoomTransform global = new RoomTransform(rotationFor(placement.orientation()), false);
        BlockPos localAnchor = plan.entranceMarker();
        BlockPos worldAnchor = placement.entranceMarkerPosition();
        Set<Block> protectedMarkers = new HashSet<>();
        protectedMarkers.add(plan.request().entranceMarker());
        protectedMarkers.add(plan.request().bossRule().marker());
        for (SpecialRoomQuota quota : plan.request().specialRooms()) protectedMarkers.add(quota.marker());

        GradientSampler gradient = new GradientSampler(source.gradient(), plan.request().seed());
        Map<BlockPos, BlockState> roomBlocks = new LinkedHashMap<>();
        Set<BlockPos> requiredAir = new LinkedHashSet<>();
        List<Box> boxes = new ArrayList<>();
        for (PlannedDungeonRoom planned : plan.rooms()) {
            RoomPlacement roomPlacement = planned.placement();
            RoomTemplate room = source.roomTemplates().get(roomPlacement.templateIndex());
            for (Map.Entry<BlockPos, BlockState> entry : roomPlacement.blocks(room).entrySet()) {
                BlockState sampled = protectedMarkers.contains(entry.getValue().getBlock())
                        ? entry.getValue() : gradient.sampleTree(entry.getValue(), entry.getKey());
                if (sampled == null) continue;
                BlockPos world = transform(entry.getKey(), localAnchor, worldAnchor, global);
                BlockState transformed = global.apply(sampled);
                BlockState existing = roomBlocks.get(world);
                if (existing == null || !existing.is(Blocks.GOLD_BLOCK) || transformed.is(Blocks.GOLD_BLOCK)) {
                    roomBlocks.put(world, transformed);
                }
            }
            for (BlockPos air : roomPlacement.requiredAir(room)) {
                requiredAir.add(transform(air, localAnchor, worldAnchor, global));
            }
            boxes.add(transformedBox(room, roomPlacement, localAnchor, worldAnchor, global));
        }
        requiredAir.removeAll(roomBlocks.keySet());

        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        if (placement.envelope().enabled()) {
            appendEnvelope(blocks, boxes, placement.envelope(), placement.maxGeneratedBlocks());
        }
        blocks.putAll(roomBlocks);
        if (blocks.size() > placement.maxGeneratedBlocks()) {
            throw new IllegalArgumentException("Dungeon exceeds maxGeneratedBlocks="
                    + placement.maxGeneratedBlocks());
        }
        return new GeneratedDungeon(Map.copyOf(blocks), Set.copyOf(requiredAir));
    }

    private static void appendEnvelope(Map<BlockPos, BlockState> blocks, List<Box> boxes,
                                       DungeonEnvelope envelope, int blockLimit) {
        int totalThickness = envelope.layers().stream()
                .mapToInt(DungeonEnvelopeLayer::thickness).sum();
        Map<BlockPos, Integer> distances = new HashMap<>();
        for (Box box : boxes) {
            for (int x = box.minX - totalThickness; x <= box.maxX + totalThickness; x++) {
                for (int y = box.minY - totalThickness; y <= box.maxY + totalThickness; y++) {
                    for (int z = box.minZ - totalThickness; z <= box.maxZ + totalThickness; z++) {
                        int dx = axisDistance(x, box.minX, box.maxX);
                        int dy = axisDistance(y, box.minY, box.maxY);
                        int dz = axisDistance(z, box.minZ, box.maxZ);
                        int squared = dx * dx + dy * dy + dz * dz;
                        if (squared > totalThickness * totalThickness) continue;
                        distances.merge(new BlockPos(x, y, z), squared, Math::min);
                    }
                }
            }
            if (distances.size() > blockLimit) {
                throw new IllegalArgumentException("Dungeon envelope exceeds maxGeneratedBlocks=" + blockLimit);
            }
        }
        List<Map.Entry<BlockPos, Integer>> ordered = new ArrayList<>(distances.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator
                .comparingInt((BlockPos position) -> position.getY())
                .thenComparingInt(position -> position.getX())
                .thenComparingInt(position -> position.getZ())));
        for (Map.Entry<BlockPos, Integer> entry : ordered) {
            if (entry.getValue() == 0) continue;
            int distance = (int) Math.ceil(Math.sqrt(entry.getValue()));
            int edge = 0;
            for (DungeonEnvelopeLayer layer : envelope.layers()) {
                edge += layer.thickness();
                if (distance <= edge) {
                    blocks.put(entry.getKey(), layer.state());
                    break;
                }
            }
        }
    }

    private static int axisDistance(int value, int minimum, int maximum) {
        if (value < minimum) return minimum - value;
        if (value > maximum) return value - maximum;
        return 0;
    }

    private static Box transformedBox(RoomTemplate room, RoomPlacement placement,
                                      BlockPos localAnchor, BlockPos worldAnchor, RoomTransform global) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos corner : DungeonPlanner.corners(room.minBounds(), room.maxBounds())) {
            BlockPos local = placement.transform().apply(corner).offset(placement.offset());
            BlockPos world = transform(local, localAnchor, worldAnchor, global);
            minX = Math.min(minX, world.getX());
            minY = Math.min(minY, world.getY());
            minZ = Math.min(minZ, world.getZ());
            maxX = Math.max(maxX, world.getX());
            maxY = Math.max(maxY, world.getY());
            maxZ = Math.max(maxZ, world.getZ());
        }
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BlockPos transform(BlockPos local, BlockPos localAnchor,
                                      BlockPos worldAnchor, RoomTransform global) {
        return global.apply(local.subtract(localAnchor)).offset(worldAnchor);
    }

    private static int rotationFor(Direction orientation) {
        return switch (orientation) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Dungeon orientation must be horizontal");
        };
    }

    static record GeneratedDungeon(Map<BlockPos, BlockState> blocks, Set<BlockPos> requiredAir) {}
    private record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
}
