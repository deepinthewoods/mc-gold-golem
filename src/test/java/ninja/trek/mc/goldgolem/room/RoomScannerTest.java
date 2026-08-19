package ninja.trek.mc.goldgolem.room;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomScannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void capturesSharedMixedFramesAndExcludesGoldBridge() {
        Map<BlockPos, BlockState> blocks = twoRoomExample(false);
        BlockPos summonTop = new BlockPos(2, 3, 0);

        RoomScanner.ScanResult result = RoomScanner.scan(
                position -> blocks.getOrDefault(position, Blocks.AIR.defaultBlockState()), summonTop);

        assertTrue(result.ok(), result.error());
        assertEquals(2, result.definition().rooms().size());
        assertTrue(result.definition().rooms().stream().anyMatch(room -> room.doorCount() == 1));
        assertTrue(result.definition().rooms().stream().anyMatch(room -> room.doorCount() == 2));
        assertTrue(result.definition().rooms().stream().flatMap(room -> room.sockets().stream())
                .anyMatch(RoomSocket::floorEdge));
        assertTrue(result.definition().rooms().stream().flatMap(room -> room.sockets().stream())
                .anyMatch(socket -> !socket.floorEdge()));
        assertTrue(result.definition().rooms().stream().allMatch(room -> !room.interiorAir().isEmpty()));

        long capturedGold = result.definition().rooms().stream()
                .flatMap(room -> room.voxels().values().stream())
                .filter(state -> state.is(Blocks.GOLD_BLOCK))
                .count();
        assertEquals(27, capturedGold, "shared frame is copied into both rooms; bridge is excluded");
    }

    @Test
    void rejectsMismatchedApertureSizes() {
        Map<BlockPos, BlockState> blocks = twoRoomExample(true);
        RoomScanner.ScanResult result = RoomScanner.scan(
                position -> blocks.getOrDefault(position, Blocks.AIR.defaultBlockState()),
                new BlockPos(2, 3, 0));

        assertFalse(result.ok());
        assertTrue(result.error().contains("aperture size"), result.error());
    }

    @Test
    void summonBlockMustBelongToADoorwayTopRatherThanItsSide() {
        Map<BlockPos, BlockState> blocks = twoRoomExample(false);
        RoomScanner.ScanResult result = RoomScanner.scan(
                position -> blocks.getOrDefault(position, Blocks.AIR.defaultBlockState()),
                new BlockPos(1, 2, 0));

        assertFalse(result.ok());
        assertTrue(result.error().contains("doorway top"), result.error());
    }

    private static Map<BlockPos, BlockState> twoRoomExample(boolean wideSharedDoor) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();

        // Two 3x2x3 interiors with a shared wall at z=4.
        fillShell(blocks, 0, 4, 0, 4, stone);
        fillShell(blocks, 0, 4, 4, 8, stone);

        // External U frame at z=0, one-wide and two-high.
        carve(blocks, 2, 1, 0);
        carve(blocks, 2, 2, 0);
        putGold(blocks, gold, new BlockPos(1, 1, 0), new BlockPos(3, 1, 0),
                new BlockPos(1, 2, 0), new BlockPos(3, 2, 0),
                new BlockPos(1, 3, 0), new BlockPos(2, 3, 0), new BlockPos(3, 3, 0));

        int firstX = wideSharedDoor ? 1 : 2;
        int lastX = wideSharedDoor ? 2 : 2;
        for (int x = firstX; x <= lastX; x++) {
            carve(blocks, x, 1, 4);
            carve(blocks, x, 2, 4);
        }
        int left = firstX - 1;
        int right = lastX + 1;
        for (int y = 1; y <= 2; y++) {
            blocks.put(new BlockPos(left, y, 4), gold);
            blocks.put(new BlockPos(right, y, 4), gold);
        }
        for (int x = left; x <= right; x++) {
            blocks.put(new BlockPos(x, 3, 4), gold);
            blocks.put(new BlockPos(x, 0, 4), gold);
        }

        // An elevated bridge joins the frames without becoming another doorway top.
        blocks.put(new BlockPos(1, 4, 0), gold);
        blocks.put(new BlockPos(1, 5, 0), gold);
        for (int z = 0; z <= 4; z++) blocks.put(new BlockPos(1, 5, z), gold);
        blocks.put(new BlockPos(1, 4, 4), gold);
        return blocks;
    }

    private static void fillShell(
            Map<BlockPos, BlockState> blocks, int minX, int maxX, int minZ, int maxZ, BlockState state
    ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == minX || x == maxX || y == 0 || y == 3 || z == minZ || z == maxZ) {
                        blocks.put(new BlockPos(x, y, z), state);
                    }
                }
            }
        }
    }

    private static void carve(Map<BlockPos, BlockState> blocks, int x, int y, int z) {
        blocks.remove(new BlockPos(x, y, z));
    }

    private static void putGold(Map<BlockPos, BlockState> blocks, BlockState gold, BlockPos... positions) {
        for (BlockPos position : positions) blocks.put(position, gold);
    }
}
