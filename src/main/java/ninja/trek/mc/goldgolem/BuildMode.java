package ninja.trek.mc.goldgolem;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/**
 * Build modes for the Gold Golem.
 * This enum is used for network serialization and UI mode detection.
 */
public enum BuildMode {
    PATH,
    WALL,
    TOWER,
    MINING,        // Placeholder - not fully implemented
    EXCAVATION,
    TERRAFORMING,
    TREE,
    TUNNEL,
    GRADIENT       // Alias for PATH (gradient-based building)
    ;

    private static final BuildMode[] VALUES = values();

    /**
     * Packet codec for serializing BuildMode over the network.
     * Uses ordinal for efficiency (values must not be reordered).
     */
    public static final PacketCodec<RegistryByteBuf, BuildMode> PACKET_CODEC =
            new PacketCodec<RegistryByteBuf, BuildMode>() {
                @Override
                public BuildMode decode(RegistryByteBuf buf) {
                    int ordinal = buf.readVarInt();
                    if (ordinal >= 0 && ordinal < VALUES.length) {
                        return VALUES[ordinal];
                    }
                    return PATH;
                }

                @Override
                public void encode(RegistryByteBuf buf, BuildMode value) {
                    buf.writeVarInt(value.ordinal());
                }
            };

    /**
     * Check if this mode uses the group-based UI (Wall, Tower, Tree).
     */
    public boolean isGroupMode() {
        return this == WALL || this == TOWER || this == TREE;
    }

    /**
     * Check if this mode uses gradient-based building (Path/Gradient).
     */
    public boolean isGradientMode() {
        return this == PATH || this == GRADIENT;
    }
}
