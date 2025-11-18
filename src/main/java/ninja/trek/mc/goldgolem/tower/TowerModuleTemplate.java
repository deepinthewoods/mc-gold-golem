package ninja.trek.mc.goldgolem.tower;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Template for a tower module.
 * Contains all voxels (blocks) that make up one instance of the module,
 * stored relative to the origin position.
 */
public final class TowerModuleTemplate {
    public static final class Voxel {
        public final BlockPos rel; // relative to module origin (bottom gold block position)
        public final BlockState state; // captured block state
        public Voxel(BlockPos rel, BlockState state) { this.rel = rel; this.state = state; }
    }

    public final List<Voxel> voxels; // all blocks in the module
    public final int minY; // min relative Y within module (for bottom reference)
    public final int maxY; // max relative Y within module (for top reference)
    public final int moduleHeight; // Y-height of the module

    public TowerModuleTemplate(List<Voxel> voxels, int minY, int maxY) {
        this.voxels = Collections.unmodifiableList(new ArrayList<>(voxels));
        this.minY = minY;
        this.maxY = maxY;
        this.moduleHeight = maxY - minY + 1;
    }
}
