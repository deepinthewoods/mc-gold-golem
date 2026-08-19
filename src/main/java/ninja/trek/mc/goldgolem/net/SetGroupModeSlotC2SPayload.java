package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Generic payload for setting group slot in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallGroupSlotC2SPayload, SetTowerGroupSlotC2SPayload, SetTreeGroupSlotC2SPayload.
 */
public record SetGroupModeSlotC2SPayload(int entityId, BuildMode mode, int group, int slot, Optional<Identifier> block) implements CustomPacketPayload {
    public static final Type<SetGroupModeSlotC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_group_mode_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGroupModeSlotC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetGroupModeSlotC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeSlotC2SPayload::mode,
            ByteBufCodecs.VAR_INT, SetGroupModeSlotC2SPayload::group,
            ByteBufCodecs.VAR_INT, SetGroupModeSlotC2SPayload::slot,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), SetGroupModeSlotC2SPayload::block,
            SetGroupModeSlotC2SPayload::new
    );

    @Override
    public Type<SetGroupModeSlotC2SPayload> type() { return ID; }
}
