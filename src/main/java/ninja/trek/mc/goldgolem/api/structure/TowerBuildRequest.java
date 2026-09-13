package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Repeats a captured tower module to the requested block height. */
public record TowerBuildRequest(BlockPos origin, int height) implements StructureBuildRequest {
    public TowerBuildRequest {
        Objects.requireNonNull(origin, "origin");
        if (height < 1 || height > 256) {
            throw new IllegalArgumentException("height must be between 1 and 256");
        }
    }

    @Override
    public TemplateMode mode() {
        return TemplateMode.TOWER;
    }
}
