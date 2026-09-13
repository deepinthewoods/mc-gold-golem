package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.room.RoomSocket;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.room.RoomTransform;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonPlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void plansDeterministicClosedBossLoopWithLandmarkQuota() {
        GoldGolemTemplate template = template(List.of(
                elbow(Blocks.BEACON),
                elbow(Blocks.LODESTONE),
                elbow(Blocks.ANVIL),
                elbow(Blocks.STONE)
        ));
        DungeonGenerationRequest request = request();

        DungeonPlanResult first = GoldGolemDungeons.plan(template, request);
        DungeonPlanResult second = GoldGolemDungeons.plan(template, request);

        assertTrue(first.succeeded(), () -> String.join("; ", first.diagnostics()));
        assertTrue(second.succeeded(), () -> String.join("; ", second.diagnostics()));
        assertEquals(first.plan().rooms(), second.plan().rooms());
        assertEquals(first.plan().connections(), second.plan().connections());
        assertEquals(4, first.plan().stats().roomCount());
        assertEquals(4, first.plan().stats().connectionCount());
        assertEquals(1, first.plan().stats().loopCount());
        assertEquals(0, first.plan().stats().deadEndCount());
        assertEquals(1, first.plan().stats().specialRoomCounts().get(Blocks.ANVIL));
        assertEquals(PlannedDungeonRoom.Role.BOSS, first.plan().rooms().get(first.plan().bossRoom()).role());
        assertEquals(PlannedDungeonRoom.Role.ENTRANCE,
                first.plan().rooms().get(first.plan().entranceRoom()).role());
    }

    @Test
    void anchorsEntranceMarkerAndBuildsOrderedContourLayers() {
        DungeonPlan plan = GoldGolemDungeons.plan(template(List.of(
                elbow(Blocks.BEACON), elbow(Blocks.LODESTONE),
                elbow(Blocks.ANVIL), elbow(Blocks.STONE))), request()).plan();
        BlockPos anchor = new BlockPos(100, 64, -30);
        DungeonPlacement placement = new DungeonPlacement(
                anchor,
                Direction.EAST,
                RoomTerrainPolicy.REQUIRE_CLEAR,
                DungeonEnvelope.of(
                        new DungeonEnvelopeLayer(Blocks.DEEPSLATE.defaultBlockState(), 2),
                        new DungeonEnvelopeLayer(Blocks.BEDROCK.defaultBlockState(), 1)
                )
        );

        GoldGolemDungeons.GeneratedDungeon generated = GoldGolemDungeons.materialize(plan, placement);
        GoldGolemDungeons.GeneratedDungeon open = GoldGolemDungeons.materialize(plan,
                new DungeonPlacement(anchor, Direction.EAST,
                        RoomTerrainPolicy.REQUIRE_CLEAR, DungeonEnvelope.NONE));

        assertEquals(Blocks.LODESTONE, generated.blocks().get(anchor).getBlock());
        assertTrue(generated.blocks().values().stream().anyMatch(state -> state.is(Blocks.DEEPSLATE)));
        assertTrue(generated.blocks().values().stream().anyMatch(state -> state.is(Blocks.BEDROCK)));
        assertTrue(generated.blocks().size() > open.blocks().size());
        assertFalse(generated.requiredAir().stream().anyMatch(generated.blocks()::containsKey));
        assertNotEquals(plan.entranceMarker(), anchor, "the local plan remains position-independent");
    }

    @Test
    void appliesEveryGlobalYawAroundTheEntranceMarker() {
        DungeonPlan plan = GoldGolemDungeons.plan(template(List.of(
                elbow(Blocks.BEACON), elbow(Blocks.LODESTONE),
                elbow(Blocks.ANVIL), elbow(Blocks.STONE))), request()).plan();
        BlockPos anchor = new BlockPos(20, 80, 30);
        GoldGolemDungeons.GeneratedDungeon north = GoldGolemDungeons.materialize(plan,
                new DungeonPlacement(anchor, Direction.NORTH,
                        RoomTerrainPolicy.REQUIRE_CLEAR, DungeonEnvelope.NONE));

        Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int rotation = 0; rotation < directions.length; rotation++) {
            GoldGolemDungeons.GeneratedDungeon rotated = GoldGolemDungeons.materialize(plan,
                    new DungeonPlacement(anchor, directions[rotation],
                            RoomTerrainPolicy.REQUIRE_CLEAR, DungeonEnvelope.NONE));
            RoomTransform transform = new RoomTransform(rotation, false);
            assertEquals(Blocks.LODESTONE, rotated.blocks().get(anchor).getBlock());
            for (Map.Entry<BlockPos, BlockState> entry : north.blocks().entrySet()) {
                BlockPos expectedPosition = transform.apply(entry.getKey().subtract(anchor)).offset(anchor);
                assertEquals(transform.apply(entry.getValue()), rotated.blocks().get(expectedPosition));
            }
        }
    }

    @Test
    void reportsMissingBossMarkerWithoutTouchingTheWorld() {
        GoldGolemTemplate template = template(List.of(
                elbow(Blocks.LODESTONE), elbow(Blocks.ANVIL), elbow(Blocks.STONE)));

        DungeonPlanResult result = GoldGolemDungeons.plan(template, request());

        assertEquals(DungeonPlanResult.Status.INVALID_TEMPLATE, result.status());
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().getFirst().contains("boss marker"));
    }

    @Test
    void largeGoalStopsAtTheConfiguredSearchBudget() {
        GoldGolemTemplate template = template(List.of(
                elbow(Blocks.BEACON), elbow(Blocks.LODESTONE),
                elbow(Blocks.ANVIL), elbow(Blocks.STONE)));
        DungeonGenerationRequest request = DungeonGenerationRequest.builder()
                .seed(9L)
                .roomCount(new RoomCountGoal(150, 150, 150))
                .entranceMarker(Blocks.LODESTONE)
                .bossRule(new DungeonBossRule(Blocks.BEACON, 20, 4))
                .addSpecialRoom(new SpecialRoomQuota(Blocks.ANVIL, 1, 3))
                .layout(DungeonLayoutProfile.defaults(DungeonLayoutStyle.BRAIDED))
                .searchLimits(1, 100)
                .build();

        DungeonPlanResult result = GoldGolemDungeons.plan(template, request);

        assertEquals(DungeonPlanResult.Status.SEARCH_LIMIT_REACHED, result.status());
    }

    @Test
    void closesAMultiRoomRingWithStraightAndCornerModules() {
        GoldGolemTemplate template = template(List.of(
                straight(Blocks.BEACON), straight(Blocks.LODESTONE),
                straight(Blocks.ANVIL), straight(Blocks.STONE),
                elbow(Blocks.COBBLESTONE)));
        DungeonGenerationRequest request = DungeonGenerationRequest.builder()
                .seed(811L)
                .roomCount(new RoomCountGoal(10, 10, 10))
                .entranceMarker(Blocks.LODESTONE)
                .bossRule(new DungeonBossRule(Blocks.BEACON, 3, 3))
                .addSpecialRoom(new SpecialRoomQuota(Blocks.ANVIL, 1, 1))
                .layout(DungeonLayoutProfile.defaults(DungeonLayoutStyle.LOOP_WITH_SPURS))
                .searchLimits(2, 250_000)
                .build();

        DungeonPlanResult result = GoldGolemDungeons.plan(template, request);

        assertTrue(result.succeeded(), () -> String.join("; ", result.diagnostics()));
        assertEquals(10, result.plan().stats().roomCount());
        assertEquals(1, result.plan().stats().loopCount());
        assertTrue(result.plan().stats().criticalPathLength() >= 3);
    }

    private static DungeonGenerationRequest request() {
        return DungeonGenerationRequest.builder()
                .seed(73L)
                .roomCount(new RoomCountGoal(4, 4, 4))
                .entranceMarker(Blocks.LODESTONE)
                .bossRule(new DungeonBossRule(Blocks.BEACON, 1, 2))
                .addSpecialRoom(new SpecialRoomQuota(Blocks.ANVIL, 1, 1))
                .layout(DungeonLayoutProfile.builder(DungeonLayoutStyle.LOOP_WITH_SPURS)
                        .compactness(0.8)
                        .build())
                .searchLimits(12, 100_000)
                .build();
    }

    private static RoomTemplate elbow(Block marker) {
        List<RoomSocket> sockets = List.of(
                new RoomSocket(new BlockPos(0, 1, -2), Direction.NORTH, 1, 2, false),
                new RoomSocket(new BlockPos(2, 1, 0), Direction.EAST, 1, 2, false)
        );
        Map<BlockPos, BlockState> voxels = new LinkedHashMap<>();
        voxels.put(BlockPos.ZERO, marker.defaultBlockState());
        voxels.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        Set<BlockPos> air = new LinkedHashSet<>();
        air.add(new BlockPos(0, 1, 0));
        air.add(new BlockPos(1, 1, 0));
        for (RoomSocket socket : sockets) air.addAll(socket.aperturePositions());
        return new RoomTemplate(voxels, air,
                new BlockPos(-2, 0, -2), new BlockPos(2, 3, 2), sockets);
    }

    private static RoomTemplate straight(Block marker) {
        List<RoomSocket> sockets = List.of(
                new RoomSocket(new BlockPos(0, 1, -2), Direction.NORTH, 1, 2, false),
                new RoomSocket(new BlockPos(0, 1, 2), Direction.SOUTH, 1, 2, false)
        );
        Map<BlockPos, BlockState> voxels = new LinkedHashMap<>();
        voxels.put(BlockPos.ZERO, marker.defaultBlockState());
        voxels.put(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState());
        Set<BlockPos> air = new LinkedHashSet<>();
        air.add(new BlockPos(0, 1, 0));
        air.add(new BlockPos(0, 2, 0));
        for (RoomSocket socket : sockets) air.addAll(socket.aperturePositions());
        return new RoomTemplate(voxels, air,
                new BlockPos(-2, 0, -2), new BlockPos(2, 3, 2), sockets);
    }

    private static GoldGolemTemplate template(List<RoomTemplate> rooms) {
        return new GoldGolemTemplate(
                2, TemplateMode.ROOM, List.of(), null, List.of(), rooms,
                TilingPreset.SMALL_3x3, null, List.of(), GradientConfig.EMPTY, 1, 0);
    }
}
