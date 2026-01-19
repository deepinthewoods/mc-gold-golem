package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Generic payload for setting block group in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallBlockGroupC2SPayload, SetTowerBlockGroupC2SPayload, SetTreeBlockGroupC2SPayload.
 */
public record SetGroupModeBlockGroupC2SPayload(int entityId, BuildMode mode, String blockId, int group) implements CustomPayload {
    public static final Id<SetGroupModeBlockGroupC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_group_mode_block_group"));

    public static final PacketCodec<RegistryByteBuf, SetGroupModeBlockGroupC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGroupModeBlockGroupC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeBlockGroupC2SPayload::mode,
            PacketCodecs.STRING, SetGroupModeBlockGroupC2SPayload::blockId,
            PacketCodecs.VAR_INT, SetGroupModeBlockGroupC2SPayload::group,
            SetGroupModeBlockGroupC2SPayload::new
    );

    @Override
    public Id<SetGroupModeBlockGroupC2SPayload> getId() { return ID; }
}
