package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registry for build strategies. Maps BuildMode to strategy factory functions.
 */
public class BuildStrategyRegistry {
    private static final Map<BuildMode, Supplier<BuildStrategy>> STRATEGIES = new EnumMap<>(BuildMode.class);

    static {
        register(BuildMode.PATH, PathBuildStrategy::new);
        register(BuildMode.WALL, WallBuildStrategy::new);
        register(BuildMode.TOWER, TowerBuildStrategy::new);
        register(BuildMode.MINING, MiningBuildStrategy::new);
        register(BuildMode.EXCAVATION, ExcavationBuildStrategy::new);
        register(BuildMode.TERRAFORMING, TerraformingBuildStrategy::new);
        register(BuildMode.TREE, TreeBuildStrategy::new);
        register(BuildMode.TUNNEL, TunnelBuildStrategy::new);
        register(BuildMode.GRADIENT, PathBuildStrategy::new); // GRADIENT is an alias for PATH
    }

    /**
     * Create a new strategy instance for the given build mode.
     * @param mode The build mode
     * @return A new strategy instance, or null if no strategy is registered for the mode
     */
    public static BuildStrategy create(BuildMode mode) {
        var supplier = STRATEGIES.get(mode);
        return supplier != null ? supplier.get() : null;
    }

    /**
     * Register a strategy factory for a build mode.
     * @param mode The build mode
     * @param supplier Factory function that creates new strategy instances
     */
    public static void register(BuildMode mode, Supplier<BuildStrategy> supplier) {
        STRATEGIES.put(mode, supplier);
    }

    /**
     * Check if a strategy is registered for the given mode.
     */
    public static boolean hasStrategy(BuildMode mode) {
        return STRATEGIES.containsKey(mode);
    }
}
