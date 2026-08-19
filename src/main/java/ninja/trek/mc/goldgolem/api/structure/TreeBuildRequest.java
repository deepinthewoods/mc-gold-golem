package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;

import java.util.Objects;

/** Runs tree WFC from an origin until natural completion or the supplied safety cap. */
public record TreeBuildRequest(BlockPos origin, long seed, int maxTiles) implements StructureBuildRequest {
    public TreeBuildRequest {
        Objects.requireNonNull(origin, "origin");
        if (maxTiles < 1) {
            throw new IllegalArgumentException("maxTiles must be positive");
        }
    }

    @Override
    public TemplateMode mode() {
        return TemplateMode.TREE;
    }
}
