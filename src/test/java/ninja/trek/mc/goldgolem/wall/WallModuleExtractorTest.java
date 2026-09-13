package ninja.trek.mc.goldgolem.wall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WallModuleExtractorTest {
    @Test
    void ordersMarkersByPathsThroughTheStructure() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(0, 0, 4);
        BlockPos c = new BlockPos(4, 0, 4);
        BlockPos d = new BlockPos(4, 0, 0);
        Set<BlockPos> uShapedWall = path(a, b, c, d);

        var result = WallModuleExtractor.buildMarkerChain(uShapedWall, List.of(a, b, c, d));

        assertEquals(List.of(0, 1, 2, 3), result.chain());
    }

    @Test
    void rejectsBranchedMarkerLayouts() {
        BlockPos center = BlockPos.ZERO;
        BlockPos west = new BlockPos(-3, 0, 0);
        BlockPos east = new BlockPos(3, 0, 0);
        BlockPos south = new BlockPos(0, 0, 3);
        Set<BlockPos> branched = new HashSet<>();
        branched.addAll(path(west, center));
        branched.addAll(path(center, east));
        branched.addAll(path(center, south));

        var result = WallModuleExtractor.buildMarkerChain(
                branched, List.of(west, center, east, south));

        assertFalse(result.ok());
    }

    @Test
    void rejectsMarkerLoopsInsteadOfChoosingAnArbitraryBreak() {
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(0, 0, 3);
        BlockPos c = new BlockPos(3, 0, 3);
        BlockPos d = new BlockPos(3, 0, 0);
        Set<BlockPos> loop = path(a, b, c, d, a);

        var result = WallModuleExtractor.buildMarkerChain(loop, List.of(a, b, c, d));

        assertFalse(result.ok());
    }

    private static Set<BlockPos> path(BlockPos... waypoints) {
        Set<BlockPos> voxels = new HashSet<>();
        voxels.add(waypoints[0]);
        for (int i = 1; i < waypoints.length; i++) {
            BlockPos current = waypoints[i - 1];
            BlockPos target = waypoints[i];
            List<BlockPos> segment = new ArrayList<>();
            while (!current.equals(target)) {
                current = new BlockPos(
                        current.getX() + Integer.signum(target.getX() - current.getX()),
                        current.getY() + Integer.signum(target.getY() - current.getY()),
                        current.getZ() + Integer.signum(target.getZ() - current.getZ()));
                segment.add(current);
            }
            voxels.addAll(segment);
        }
        return voxels;
    }
}
