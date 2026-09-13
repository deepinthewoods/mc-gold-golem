package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Builds a wall by treating the guide points as an ordered player-walking path. */
public record WallBuildRequest(List<BlockPos> guidePoints, int maxModules) implements StructureBuildRequest {
    public WallBuildRequest {
        guidePoints = guidePoints == null ? List.of() : List.copyOf(guidePoints);
        if (guidePoints.size() < 2) {
            throw new IllegalArgumentException("A wall guide requires at least two points");
        }
        if (maxModules < 1) {
            throw new IllegalArgumentException("maxModules must be positive");
        }
    }

    @Override
    public TemplateMode mode() {
        return TemplateMode.WALL;
    }
}
