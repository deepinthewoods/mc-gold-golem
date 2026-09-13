package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;

import java.util.List;

/** Applies the same world-seeded material sampling used by the gradual builders. */
final class GradientSampler {
    private final GradientConfig config;
    private final SimplexNoise noise;

    GradientSampler(GradientConfig config, long worldSeed) {
        this.config = config;
        this.noise = new SimplexNoise(RandomSource.create(worldSeed));
    }

    BlockState sampleWall(BlockState original, BlockPos position, int moduleHeight, int relativeY) {
        Group group = groupFor(original);
        if (group == null) return original;
        int populatedSlots = populatedSlotCount(group.slots());
        if (populatedSlots == 0) return null;
        double coordinate = moduleHeight > 0
                ? (double) relativeY / (double) moduleHeight * (populatedSlots - 1) : 0.0;
        int index = reflectedIndex(coordinate, populatedSlots, group.window(), position, group.noiseScale());
        return stateFor(group.slots().get(index));
    }

    BlockState sampleTower(BlockState original, BlockPos position, int layer, int height) {
        Group group = groupFor(original);
        if (group == null) return original;
        int populatedSlots = populatedSlotCount(group.slots());
        if (populatedSlots == 0) return null;
        double fraction = height == 1 ? 0.0 : (double) layer / (double) (height - 1);
        int index = reflectedIndex(
                fraction * (populatedSlots - 1), populatedSlots,
                group.window(), position, group.noiseScale());
        return stateFor(group.slots().get(index));
    }

    BlockState sampleTree(BlockState original, BlockPos position) {
        Group group = groupFor(original);
        if (group == null) return original;
        int lastNonEmpty = populatedSlotCount(group.slots()) - 1;
        if (lastNonEmpty < 0) return null;
        int window = Math.max(1, Math.round(group.window()));
        int index = (int) Math.floor(sampleNoise01(position, group.noiseScale()) * window);
        index = Math.min(Math.min(index, window - 1), lastNonEmpty);
        return stateFor(group.slots().get(index));
    }

    private Group groupFor(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Integer groupIndex = config.blockGroups().get(blockId);
        if (groupIndex == null || groupIndex < 0 || groupIndex >= config.groupSlots().size()) return null;
        return new Group(
                config.groupSlots().get(groupIndex),
                config.groupWindows().get(groupIndex),
                config.groupNoiseScales().get(groupIndex)
        );
    }

    private int reflectedIndex(
            double coordinate,
            int populatedSlots,
            float configuredWindow,
            BlockPos position,
            int noiseScale
    ) {
        double window = Math.min(configuredWindow, populatedSlots);
        if (window > 0.0) {
            coordinate += sampleNoise01(position, noiseScale) * window - window / 2.0;
        }
        double lower = -0.5;
        double length = populatedSlots;
        double offset = (coordinate - lower) % (2.0 * length);
        if (offset < 0.0) offset += 2.0 * length;
        double reflected = offset <= length ? offset : 2.0 * length - offset;
        int index = (int) Math.round(lower + reflected);
        return Math.max(0, Math.min(populatedSlots - 1, index));
    }

    private double sampleNoise01(BlockPos position, int scale) {
        double divisor = Math.max(1, scale);
        double sampled = noise.getValue(
                position.getX() / divisor,
                position.getY() / divisor,
                position.getZ() / divisor
        );
        return Math.max(0.0, Math.min(1.0, (sampled + 1.0) * 0.5));
    }

    private static int populatedSlotCount(List<String> slots) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (!slots.get(i).isEmpty()) return i + 1;
        }
        return 0;
    }

    private static BlockState stateFor(String blockId) {
        if (blockId.isEmpty() || GradientSlotUtil.isMineAction(blockId)) return null;
        Identifier id = Identifier.tryParse(blockId);
        return id == null ? null : BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
    }

    private record Group(List<String> slots, float window, int noiseScale) {}
}
