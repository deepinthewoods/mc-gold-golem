package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import ninja.trek.mc.goldgolem.room.RoomLayoutGenerator;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomStructureTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void versionTwoRoundTripPreservesRoomDataAndVersionOneStillLoads() throws Exception {
        GoldGolemTemplate roomTemplate = template(List.of(corridor(), endRoom()));
        GoldGolemTemplate decoded = GoldGolemTemplateCodec.fromJson(GoldGolemTemplateCodec.toJson(roomTemplate));

        assertEquals(2, decoded.formatVersion());
        assertEquals(TemplateMode.ROOM, decoded.mode());
        assertEquals(2, decoded.roomTemplates().size());
        assertEquals(Direction.SOUTH, decoded.roomTemplates().getFirst().sockets().get(1).facing());

        com.google.gson.JsonObject legacy = GoldGolemTemplateCodec.toJson(towerTemplate());
        legacy.addProperty("version", 1);
        assertEquals(TemplateMode.TOWER, GoldGolemTemplateCodec.fromJson(legacy).mode());
    }

    @Test
    void seededLayoutIsClosedDeterministicAndReservesAnEnding() {
        List<RoomTemplate> rooms = List.of(corridor(), endRoom());
        RoomLayoutGenerator.Layout first = RoomLayoutGenerator.generate(
                rooms, BlockPos.ZERO, Direction.SOUTH, 41L, 2);
        RoomLayoutGenerator.Layout second = RoomLayoutGenerator.generate(
                rooms, BlockPos.ZERO, Direction.SOUTH, 41L, 2);

        assertEquals(first.rooms(), second.rooms());
        assertEquals(2, first.rooms().size());
        assertEquals(1, rooms.get(first.rooms().getLast().templateIndex()).doorCount());
        assertThrows(IllegalArgumentException.class, () -> RoomLayoutGenerator.generate(
                rooms, BlockPos.ZERO, Direction.SOUTH, 41L, 1));
    }

    @Test
    void fullAndUFramesCanConnectInEitherOrder() {
        List<RoomTemplate> fullToU = List.of(corridor(false, true), endRoom(false));
        List<RoomTemplate> uToFull = List.of(corridor(false, false), endRoom(true));

        assertEquals(2, RoomLayoutGenerator.generate(
                fullToU, BlockPos.ZERO, Direction.SOUTH, 7L, 2).rooms().size());
        assertEquals(2, RoomLayoutGenerator.generate(
                uToFull, BlockPos.ZERO, Direction.SOUTH, 7L, 2).rooms().size());
    }

    @Test
    void branchingLayoutReservesOneEndingForEveryFrontier() {
        List<RoomTemplate> rooms = List.of(branchRoom(), endRoom(false));
        RoomLayoutGenerator.Layout layout = RoomLayoutGenerator.generate(
                rooms, BlockPos.ZERO, Direction.SOUTH, 99L, 3);

        assertEquals(3, layout.rooms().size());
        assertEquals(2, layout.rooms().stream()
                .filter(placement -> rooms.get(placement.templateIndex()).doorCount() == 1)
                .count());
        assertEquals(5, layout.rooms().stream()
                .mapToInt(placement -> rooms.get(placement.templateIndex()).doorCount()).sum(),
                "all sockets except the initial entrance are paired");
    }

    @Test
    void requestValidatesDirectionLimitAndPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new RoomBuildRequest(
                BlockPos.ZERO, Direction.UP, 0L, 5, RoomTerrainPolicy.REQUIRE_CLEAR));
        assertThrows(IllegalArgumentException.class, () -> new RoomBuildRequest(
                BlockPos.ZERO, Direction.NORTH, 0L, 1001, RoomTerrainPolicy.REQUIRE_CLEAR));
        assertEquals(TemplateMode.ROOM, new RoomBuildRequest(
                BlockPos.ZERO, Direction.WEST, 3L, 5, RoomTerrainPolicy.IGNORE_TERRAIN).mode());
    }

    @Test
    void everyRotationAndMirrorKeepsSocketGeometryAndDirectionalStatesAligned() {
        RoomSocket socket = new RoomSocket(new BlockPos(2, 4, 5), Direction.SOUTH, 3, 2, true);
        var stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);

        for (int rotation = 0; rotation < 4; rotation++) {
            for (boolean mirror : new boolean[]{false, true}) {
                RoomTransform transform = new RoomTransform(rotation, mirror);
                RoomSocket transformed = socket.transformed(transform);

                assertEquals(socket.aperturePositions().stream().map(transform::apply).collect(
                                java.util.stream.Collectors.toSet()),
                        transformed.aperturePositions());
                assertEquals(transform.apply(Direction.SOUTH),
                        transform.apply(stairs).getValue(BlockStateProperties.HORIZONTAL_FACING));
            }
        }
    }

    private static RoomTemplate corridor() {
        return corridor(false, true);
    }

    private static RoomTemplate endRoom() {
        return endRoom(false);
    }

    private static RoomTemplate corridor(boolean entranceFloor, boolean exitFloor) {
        return room(List.of(
                new RoomSocket(new BlockPos(0, 1, 0), Direction.NORTH, 1, 2, entranceFloor),
                new RoomSocket(new BlockPos(0, 1, 4), Direction.SOUTH, 1, 2, exitFloor)
        ), 4);
    }

    private static RoomTemplate endRoom(boolean floor) {
        return room(List.of(new RoomSocket(
                new BlockPos(0, 1, 0), Direction.NORTH, 1, 2, floor)), 3);
    }

    private static RoomTemplate branchRoom() {
        List<RoomSocket> sockets = List.of(
                new RoomSocket(new BlockPos(2, 1, 0), Direction.NORTH, 1, 2, false),
                new RoomSocket(new BlockPos(2, 1, 4), Direction.SOUTH, 1, 2, false),
                new RoomSocket(new BlockPos(4, 1, 2), Direction.EAST, 1, 2, false));
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> voxels = new LinkedHashMap<>();
        Set<BlockPos> air = new LinkedHashSet<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) voxels.put(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
        }
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 2; y++) {
                for (int z = 1; z <= 3; z++) air.add(new BlockPos(x, y, z));
            }
        }
        for (RoomSocket socket : sockets) {
            for (BlockPos frame : socket.framePositions()) voxels.put(frame, Blocks.GOLD_BLOCK.defaultBlockState());
            air.addAll(socket.aperturePositions());
        }
        return new RoomTemplate(voxels, air, BlockPos.ZERO, new BlockPos(4, 3, 4), sockets);
    }

    private static RoomTemplate room(List<RoomSocket> sockets, int maxZ) {
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> voxels = new LinkedHashMap<>();
        Set<BlockPos> air = new LinkedHashSet<>();
        for (int z = 0; z <= maxZ; z++) {
            voxels.put(new BlockPos(0, 0, z), Blocks.STONE.defaultBlockState());
            air.add(new BlockPos(0, 1, z));
            air.add(new BlockPos(0, 2, z));
        }
        for (RoomSocket socket : sockets) {
            for (BlockPos frame : socket.framePositions()) voxels.put(frame, Blocks.GOLD_BLOCK.defaultBlockState());
            air.addAll(socket.aperturePositions());
        }
        return new RoomTemplate(voxels, air, new BlockPos(-1, 0, 0), new BlockPos(1, 3, maxZ), sockets);
    }

    private static GoldGolemTemplate template(List<RoomTemplate> rooms) {
        return new GoldGolemTemplate(
                2, TemplateMode.ROOM, List.of(), null, List.of(), rooms,
                TilingPreset.SMALL_3x3, null, List.of(), GradientConfig.EMPTY, 1, 0);
    }

    private static GoldGolemTemplate towerTemplate() {
        var tower = new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate(
                List.of(new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate.Voxel(
                        BlockPos.ZERO, Blocks.STONE.defaultBlockState())), 0, 0);
        return new GoldGolemTemplate(
                1, TemplateMode.TOWER, List.of(), tower, List.of(),
                TilingPreset.SMALL_3x3, null, List.of(), 1, 0);
    }
}
