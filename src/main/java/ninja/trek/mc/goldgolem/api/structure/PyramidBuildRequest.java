package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Resamples a captured tower module into a pyramid. */
public record PyramidBuildRequest(BlockPos origin, int height, int curvature) implements StructureBuildRequest {
    public PyramidBuildRequest {
        Objects.requireNonNull(origin, "origin");
        if (height < 1 || height > 256) {
            throw new IllegalArgumentException("height must be between 1 and 256");
        }
        if (curvature < -100 || curvature > 100) {
            throw new IllegalArgumentException("curvature must be between -100 and 100");
        }
    }

    @Override
    public TemplateMode mode() {
        return TemplateMode.PYRAMID;
    }
}
