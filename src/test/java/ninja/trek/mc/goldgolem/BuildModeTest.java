package ninja.trek.mc.goldgolem;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildModeTest {
    @Test
    void onlyBuildingModesReturnToSummonPositionForSupplies() {
        EnumSet<BuildMode> expected = EnumSet.of(
                BuildMode.PATH,
                BuildMode.WALL,
                BuildMode.TOWER,
                BuildMode.TERRAFORMING,
                BuildMode.TREE,
                BuildMode.GRADIENT,
                BuildMode.PYRAMID,
                BuildMode.ROOM);
        EnumSet<BuildMode> actual = EnumSet.noneOf(BuildMode.class);

        for (BuildMode mode : BuildMode.values()) {
            if (mode.returnsToSummonPositionWhenOutOfBlocks()) actual.add(mode);
        }

        assertEquals(expected, actual);
    }
}
