package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Generic payload for setting group slot in group-based modes (Wall, Tower, Tree).
 * Replaces SetWallGroupSlotC2SPayload, SetTowerGroupSlotC2SPayload, SetTreeGroupSlotC2SPayload.
 */
public record SetGroupModeSlotC2SPayload(int entityId, BuildMode mode, int group, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetGroupModeSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_group_mode_slot"));

    public static final PacketCodec<RegistryByteBuf, SetGroupModeSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGroupModeSlotC2SPayload::entityId,
            BuildMode.PACKET_CODEC, SetGroupModeSlotC2SPayload::mode,
            PacketCodecs.VAR_INT, SetGroupModeSlotC2SPayload::group,
            PacketCodecs.VAR_INT, SetGroupModeSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetGroupModeSlotC2SPayload::block,
            SetGroupModeSlotC2SPayload::new
    );

    @Override
    public Id<SetGroupModeSlotC2SPayload> getId() { return ID; }
}
