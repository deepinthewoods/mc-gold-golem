package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record SetTreeGroupSlotC2SPayload(int entityId, int group, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetTreeGroupSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tree_group_slot"));

    public static final PacketCodec<RegistryByteBuf, SetTreeGroupSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTreeGroupSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTreeGroupSlotC2SPayload::group,
            PacketCodecs.VAR_INT, SetTreeGroupSlotC2SPayload::slot,
            Identifier.PACKET_CODEC.collect(PacketCodecs.toOptional()), SetTreeGroupSlotC2SPayload::block,
            SetTreeGroupSlotC2SPayload::new
    );

    @Override
    public Id<SetTreeGroupSlotC2SPayload> getId() { return ID; }
}
