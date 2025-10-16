package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record SetWallGroupSlotC2SPayload(int entityId, int group, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetWallGroupSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_wall_group_slot"));

    public static final PacketCodec<RegistryByteBuf, SetWallGroupSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetWallGroupSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetWallGroupSlotC2SPayload::group,
            PacketCodecs.VAR_INT, SetWallGroupSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetWallGroupSlotC2SPayload::block,
            SetWallGroupSlotC2SPayload::new
    );

    @Override
    public Id<SetWallGroupSlotC2SPayload> getId() { return ID; }
}
