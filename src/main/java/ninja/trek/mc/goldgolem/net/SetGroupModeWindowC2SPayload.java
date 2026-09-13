package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;

/**
 * Generic payload for setting group window size in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallGroupWindowC2SPayload, SetTowerGroupWindowC2SPayload, SetTreeGroupWindowC2SPayload.
 */
public record SetGroupModeWindowC2SPayload(int entityId, BuildMode mode, int group, float window, int scale) implements CustomPacketPayload {
    public static final Type<SetGroupModeWindowC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_group_mode_window"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGroupModeWindowC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetGroupModeWindowC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeWindowC2SPayload::mode,
            ByteBufCodecs.VAR_INT, SetGroupModeWindowC2SPayload::group,
            ByteBufCodecs.FLOAT, SetGroupModeWindowC2SPayload::window,
            ByteBufCodecs.VAR_INT, SetGroupModeWindowC2SPayload::scale,
            SetGroupModeWindowC2SPayload::new
    );

    @Override
    public Type<SetGroupModeWindowC2SPayload> type() { return ID; }
}
