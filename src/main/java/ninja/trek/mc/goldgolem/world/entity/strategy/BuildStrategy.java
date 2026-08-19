package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

/**
 * Strategy interface for golem build modes.
 * Each build mode (Path, Wall, Tower, etc.) implements this interface
 * to encapsulate its specific behavior and state.
 */
public interface BuildStrategy {

    /**
     * Result of handling a feed interaction (gold nugget fed to golem).
     */
    enum FeedResult {
        NOT_HANDLED,     // Use default behavior
        STARTED,         // Building started successfully
        ALREADY_ACTIVE,  // Already active, show message
        RESUMED          // Resumed from waiting state
    }

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
    void tick(GoldGolemEntity golem, Player owner);

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
    void writeNbt(CompoundTag nbt);

    /**
     * Deserialize strategy-specific state from NBT.
     * @param nbt The compound to read from
     */
    void readNbt(CompoundTag nbt);

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

    // ==================== Polymorphic Dispatch Methods ====================

    /**
     * Get a configuration integer value by key.
     * Replaces instanceof checks for getMiningBranchDepth, getExcavationHeight, etc.
     * @param key Configuration key (e.g., "branchDepth", "height")
     * @param defaultValue Value to return if key is not found
     * @return The configuration value or defaultValue
     */
    default int getConfigInt(String key, int defaultValue) {
        return defaultValue;
    }

    /**
     * Set a configuration integer value by key.
     * @param key Configuration key
     * @param value Value to set
     */
    default void setConfigInt(String key, int value) {
    }

    /**
     * Called when a configuration value changes that may require state updates.
     * For example, when tree tiling preset changes.
     * @param configKey The key that changed
     */
    default void onConfigurationChanged(String configKey) {
    }

    /**
     * @return true if this strategy is waiting for resources (e.g., tree mode out of inventory)
     */
    default boolean isWaitingForResources() {
        return false;
    }

    /**
     * Set the waiting for resources state.
     * @param waiting Whether the strategy is waiting for resources
     */
    default void setWaitingForResources(boolean waiting) {
    }

    /**
     * Write legacy NBT data with prefixed keys for backward compatibility.
     * Called from GoldGolemEntity.writeCustomData() instead of instanceof checks.
     * @param view The WriteView to write to
     */
    default void writeLegacyNbt(ValueOutput view) {
    }

    /**
     * Read legacy NBT data with prefixed keys for backward compatibility.
     * Called from GoldGolemEntity.readCustomData() instead of instanceof checks.
     * @param view The ReadView to read from
     */
    default void readLegacyNbt(ValueInput view) {
    }

    /**
     * Handle a feed interaction (gold nugget fed to golem).
     * Replaces instanceof checks in interactMob().
     * @param player The player feeding the golem
     * @return FeedResult indicating what happened
     */
    default FeedResult handleFeedInteraction(Player player) {
        return FeedResult.NOT_HANDLED;
    }

    /**
     * Handle owner damage (owner hitting the golem to stop it).
     * Replaces instanceof checks in damage().
     */
    default void handleOwnerDamage() {
    }

    /**
     * Handle an owner player attack before the normal stop/damage behavior.
     * @return true when the attack was consumed by the strategy
     */
    default boolean handleOwnerAttack(Player player) {
        return false;
    }

    /**
     * @return true if this strategy can start from its idle state
     */
    default boolean canStartFromIdle() {
        return true;
    }

    /**
     * Start building from the idle state.
     */
    default void startFromIdle() {
    }
}
