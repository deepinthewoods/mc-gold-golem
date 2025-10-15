package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record SetGradientSlotC2SPayload(int entityId, int row, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetGradientSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_gradient_slot"));

    public static final PacketCodec<RegistryByteBuf, SetGradientSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetGradientSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetGradientSlotC2SPayload::row,
            PacketCodecs.VAR_INT, SetGradientSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetGradientSlotC2SPayload::block,
            SetGradientSlotC2SPayload::new
    );

    @Override
    public Id<SetGradientSlotC2SPayload> getId() { return ID; }
}
