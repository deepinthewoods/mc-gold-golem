package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Generic payload for setting group window size in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallGroupWindowC2SPayload, SetTowerGroupWindowC2SPayload, SetTreeGroupWindowC2SPayload.
 */
public record SetGroupModeWindowC2SPayload(int entityId, BuildMode mode, int group, float window, int scale) implements CustomPayload {
    public static final Id<SetGroupModeWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_group_mode_window"));

    public static final PacketCodec<RegistryByteBuf, SetGroupModeWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGroupModeWindowC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeWindowC2SPayload::mode,
            PacketCodecs.VAR_INT, SetGroupModeWindowC2SPayload::group,
            PacketCodecs.FLOAT, SetGroupModeWindowC2SPayload::window,
            PacketCodecs.VAR_INT, SetGroupModeWindowC2SPayload::scale,
            SetGroupModeWindowC2SPayload::new
    );

    @Override
    public Id<SetGroupModeWindowC2SPayload> getId() { return ID; }
}
