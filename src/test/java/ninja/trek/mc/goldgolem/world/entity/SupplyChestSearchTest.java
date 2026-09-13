package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChestSearchTest {
    @Test
    void includesChestEightHorizontalBlocksFromSummonPosition() {
        BlockPos summonPosition = new BlockPos(10, 64, 10);

        assertTrue(GoldGolemEntity.isWithinSupplyChestSearchRadius(
                summonPosition, new BlockPos(18, 65, 10)));
    }

    @Test
    void excludesChestBeyondEightHorizontalBlocks() {
        BlockPos summonPosition = new BlockPos(10, 64, 10);

        assertFalse(GoldGolemEntity.isWithinSupplyChestSearchRadius(
                summonPosition, new BlockPos(19, 64, 10)));
    }

    @Test
    void doesNotTreatGolemHighAboveStartAsReturned() {
        BlockPos summonPosition = new BlockPos(10, 64, 10);

        assertFalse(GoldGolemEntity.isAtResourceWaitDestination(
                summonPosition, 10.5, 76.0, 10.5));
    }

    @Test
    void treatsNearbyThreeDimensionalPositionAsReturned() {
        BlockPos summonPosition = new BlockPos(10, 64, 10);

        assertTrue(GoldGolemEntity.isAtResourceWaitDestination(
                summonPosition, 11.5, 66.0, 11.5));
    }

    @Test
    void persistedSummonPositionTakesPrecedenceOverLegacyBuildStart() {
        BlockPos summonPosition = new BlockPos(10, 64, 10);
        BlockPos legacyBuildStart = new BlockPos(100, 70, 100);

        assertTrue(summonPosition.equals(GoldGolemEntity.selectPersistedSummonPosition(
                summonPosition, legacyBuildStart)));
    }

    @Test
    void legacyBuildStartMigratesWhenSummonPositionIsMissing() {
        BlockPos legacyBuildStart = new BlockPos(100, 70, 100);

        assertTrue(legacyBuildStart.equals(GoldGolemEntity.selectPersistedSummonPosition(
                null, legacyBuildStart)));
    }

    @Test
    void sidewaysJitterDoesNotResetReturnStallDetection() {
        assertFalse(GoldGolemEntity.hasMeaningfulResourceReturnProgress(25.0, 25.0));
        assertFalse(GoldGolemEntity.hasMeaningfulResourceReturnProgress(25.0, 24.95));
        assertTrue(GoldGolemEntity.hasMeaningfulResourceReturnProgress(25.0, 24.9));
    }
}
