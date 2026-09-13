package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementPlannerProgressTest {
    @Test
    void movementMustBeMeaningfulToResetStallTimer() {
        Vec3 start = new Vec3(0.0, 64.0, 0.0);

        assertFalse(PlacementPlanner.isMeaningfulPlacementMovement(
                start, new Vec3(0.24, 64.0, 0.0)));
        assertTrue(PlacementPlanner.isMeaningfulPlacementMovement(
                start, new Vec3(0.25, 64.0, 0.0)));
    }

    @Test
    void placementStallTriggersAfterFiveSeconds() {
        assertFalse(PlacementPlanner.hasPlacementStalled(99L, 0L));
        assertTrue(PlacementPlanner.hasPlacementStalled(100L, 0L));
        assertFalse(PlacementPlanner.hasPlacementStalled(10_000L, Long.MIN_VALUE));
    }
}
