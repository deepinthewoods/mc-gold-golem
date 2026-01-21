package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

/**
 * Strategy interface for golem build modes.
 * Each build mode (Path, Wall, Tower, etc.) implements this interface
 * to encapsulate its specific behavior and state.
 */
public interface BuildStrategy {

    /**
     * Set the entity reference for this strategy.
     * Called when the strategy is associated with a golem.
     * @param entity The golem entity
     */
    void setEntity(GoldGolemEntity entity);

    /**
     * @return The golem entity this strategy is associated with
     */
    GoldGolemEntity getEntity();

    /**
     * @return The build mode this strategy handles
     */
    BuildMode getMode();

    /**
     * Called when this strategy becomes active.
     * Initialize any state needed for building.
     */
    void initialize(GoldGolemEntity golem);

    /**
     * Called every tick while the golem is building with this strategy.
     * @param golem The golem entity
     * @param owner The owner player (may be null for non-tracking modes)
     */
    void tick(GoldGolemEntity golem, PlayerEntity owner);

    /**
     * Called when the strategy is deactivated or the golem stops building.
     * Clean up any temporary state.
     */
    void cleanup(GoldGolemEntity golem);

    /**
     * @return true if this strategy's work is complete
     */
    boolean isComplete();

    /**
     * Stop any in-progress building activity.
     */
    void stop(GoldGolemEntity golem);

    /**
     * Serialize strategy-specific state to NBT.
     * @param nbt The compound to write to
     */
    void writeNbt(NbtCompound nbt);

    /**
     * Deserialize strategy-specific state from NBT.
     * @param nbt The compound to read from
     */
    void readNbt(NbtCompound nbt);

    /**
     * @return true if this strategy tracks the player's position (Path, Wall, Tower, Tree)
     */
    default boolean usesPlayerTracking() {
        return true;
    }

    /**
     * @return true if this strategy uses the gradient UI (Path mode)
     */
    default boolean usesGradientUI() {
        return false;
    }

    /**
     * @return true if this strategy uses the group UI (Wall, Tower, Tree modes)
     */
    default boolean usesGroupUI() {
        return false;
    }

    /**
     * @return The NBT prefix for this strategy's serialized data (for backward compatibility)
     */
    default String getNbtPrefix() {
        return getMode().name();
    }
}
