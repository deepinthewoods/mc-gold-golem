package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * Client-to-server payload for setting a terraforming gradient slot.
 * gradientType: 0 = vertical, 1 = horizontal, 2 = sloped
 */
public record SetTerraformingGradientSlotC2SPayload(int entityId, int gradientType, int slot, Optional<Identifier> block) implements CustomPayload {
    public static final Id<SetTerraformingGradientSlotC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_terraforming_gradient_slot"));

    public static final PacketCodec<RegistryByteBuf, SetTerraformingGradientSlotC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::gradientType,
            PacketCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::slot,
            PacketCodecs.optional(Identifier.PACKET_CODEC), SetTerraformingGradientSlotC2SPayload::block,
            SetTerraformingGradientSlotC2SPayload::new
    );

    @Override
    public Id<SetTerraformingGradientSlotC2SPayload> getId() { return ID; }
}
