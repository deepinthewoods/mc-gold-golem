package ninja.trek.mc.goldgolem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Controls how the golem handles ore blocks during mining/excavation.
 */
public enum OreMiningMode {
    ALWAYS("Always"),           // Always mine ores (current behavior)
    NEVER("Never"),             // Skip ore blocks entirely
    SILK_TOUCH_FORTUNE("Silk/Fortune"); // Only mine if golem has Silk Touch or Fortune 3+ tool

    private static final OreMiningMode[] VALUES = values();

    private final String displayName;

    OreMiningMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Cycle to the next mode.
     */
    public OreMiningMode next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    /**
     * Get mode from ordinal, with bounds checking.
     */
    public static OreMiningMode fromOrdinal(int ordinal) {
        if (ordinal >= 0 && ordinal < VALUES.length) {
            return VALUES[ordinal];
        }
        return ALWAYS;
    }

    /**
     * Packet codec for serializing over the network.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, OreMiningMode> PACKET_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, OreMiningMode>() {
                @Override
                public OreMiningMode decode(RegistryFriendlyByteBuf buf) {
                    return fromOrdinal(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OreMiningMode value) {
                    buf.writeVarInt(value.ordinal());
                }
            };
}
