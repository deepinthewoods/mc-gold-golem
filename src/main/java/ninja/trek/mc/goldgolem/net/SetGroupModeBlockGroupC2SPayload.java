package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;

/**
 * Generic payload for setting block group in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallBlockGroupC2SPayload, SetTowerBlockGroupC2SPayload, SetTreeBlockGroupC2SPayload.
 */
public record SetGroupModeBlockGroupC2SPayload(int entityId, BuildMode mode, String blockId, int group) implements CustomPacketPayload {
    public static final Type<SetGroupModeBlockGroupC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_group_mode_block_group"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGroupModeBlockGroupC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetGroupModeBlockGroupC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeBlockGroupC2SPayload::mode,
            ByteBufCodecs.stringUtf8(128), SetGroupModeBlockGroupC2SPayload::blockId,
            ByteBufCodecs.VAR_INT, SetGroupModeBlockGroupC2SPayload::group,
            SetGroupModeBlockGroupC2SPayload::new
    );

    @Override
    public Type<SetGroupModeBlockGroupC2SPayload> type() { return ID; }
}
