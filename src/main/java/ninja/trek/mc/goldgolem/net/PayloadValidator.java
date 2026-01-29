package ninja.trek.mc.goldgolem.net;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Utility class for validating network payload fields.
 * Provides clamping and validation methods that log warnings when values are out of range.
 */
public final class PayloadValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PayloadValidator.class);

    private PayloadValidator() {
        // Utility class - no instantiation
    }

    /**
     * Clamps an integer value to the specified range.
     * Logs a warning if the value was out of range.
     *
     * @param value     The value to clamp
     * @param min       Minimum allowed value (inclusive)
     * @param max       Maximum allowed value (inclusive)
     * @param fieldName Name of the field for logging
     * @return The clamped value
     */
    public static int clampInt(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            LOGGER.warn("Payload field {} out of range: {} (expected {}-{})",
                    fieldName, value, min, max);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    /**
     * Clamps a float value to the specified range.
     * Logs a warning if the value was out of range.
     *
     * @param value     The value to clamp
     * @param min       Minimum allowed value (inclusive)
     * @param max       Maximum allowed value (inclusive)
     * @param fieldName Name of the field for logging
     * @return The clamped value
     */
    public static float clampFloat(float value, float min, float max, String fieldName) {
        if (value < min || value > max) {
            LOGGER.warn("Payload field {} out of range: {} (expected {}-{})",
                    fieldName, value, min, max);
            return Math.max(min, Math.min(max, value));
        }
        return value;
    }

    /**
     * Validates that a list has the expected size.
     * Logs a warning if the list is null or has the wrong size.
     *
     * @param list         The list to validate
     * @param expectedSize The expected size
     * @param fieldName    Name of the field for logging
     * @param <T>          Type of list elements
     * @return The original list, or an empty list if null
     */
    public static <T> List<T> validateListSize(List<T> list, int expectedSize, String fieldName) {
        if (list == null) {
            LOGGER.warn("Payload field {} is null, expected list of size {}", fieldName, expectedSize);
            return Collections.emptyList();
        }
        if (list.size() != expectedSize) {
            LOGGER.warn("Payload field {} has wrong size: {} (expected {})",
                    fieldName, list.size(), expectedSize);
        }
        return list;
    }

    /**
     * Validates that a list is not null and optionally within a maximum size.
     * Logs a warning if the list is null or exceeds the max size.
     *
     * @param list      The list to validate
     * @param maxSize   Maximum allowed size (0 for no limit)
     * @param fieldName Name of the field for logging
     * @param <T>       Type of list elements
     * @return The original list, or an empty list if null
     */
    public static <T> List<T> validateList(List<T> list, int maxSize, String fieldName) {
        if (list == null) {
            LOGGER.warn("Payload field {} is null", fieldName);
            return Collections.emptyList();
        }
        if (maxSize > 0 && list.size() > maxSize) {
            LOGGER.warn("Payload field {} exceeds maximum size: {} (max {})",
                    fieldName, list.size(), maxSize);
        }
        return list;
    }

    /**
     * Checks if a block ID is valid (exists in the block registry).
     *
     * @param blockId The block ID to check (e.g., "minecraft:stone")
     * @return true if the block ID is valid, false otherwise
     */
    public static boolean isValidBlockId(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        Identifier id = Identifier.tryParse(blockId);
        return id != null && Registries.BLOCK.containsId(id);
    }
}
