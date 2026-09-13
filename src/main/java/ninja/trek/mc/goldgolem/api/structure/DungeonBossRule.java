package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.world.level.block.Block;

import java.util.Objects;

/** Hard landmark and route constraints for the single boss room. */
public record DungeonBossRule(
        Block marker,
        int minimumEntranceDistance,
        int minimumRouteSeparation
) {
    public DungeonBossRule {
        Objects.requireNonNull(marker, "marker");
        if (minimumEntranceDistance < 1 || minimumEntranceDistance > 149) {
            throw new IllegalArgumentException("minimumEntranceDistance must be between 1 and 149");
        }
        if (minimumRouteSeparation < 1 || minimumRouteSeparation > 149) {
            throw new IllegalArgumentException("minimumRouteSeparation must be between 1 and 149");
        }
    }
}
