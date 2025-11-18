package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record SetTowerGroupSlotC2SPayload(int entityId, int group, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetTowerGroupSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tower_group_slot"));

    public static final PacketCodec<RegistryByteBuf, SetTowerGroupSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTowerGroupSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTowerGroupSlotC2SPayload::group,
            PacketCodecs.VAR_INT, SetTowerGroupSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetTowerGroupSlotC2SPayload::block,
            SetTowerGroupSlotC2SPayload::new
    );

    @Override
    public Id<SetTowerGroupSlotC2SPayload> getId() { return ID; }
}
