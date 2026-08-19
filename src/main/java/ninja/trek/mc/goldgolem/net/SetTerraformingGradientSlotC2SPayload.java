package ninja.trek.mc.goldgolem.net;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server payload for setting a terraforming gradient slot.
 * gradientType: 0 = vertical, 1 = horizontal, 2 = sloped
 */
public record SetTerraformingGradientSlotC2SPayload(int entityId, int gradientType, int slot, Optional<Identifier> block) implements CustomPacketPayload {
    public static final Type<SetTerraformingGradientSlotC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_terraforming_gradient_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetTerraformingGradientSlotC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::gradientType,
            ByteBufCodecs.VAR_INT, SetTerraformingGradientSlotC2SPayload::slot,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), SetTerraformingGradientSlotC2SPayload::block,
            SetTerraformingGradientSlotC2SPayload::new
    );

    @Override
    public Type<SetTerraformingGradientSlotC2SPayload> type() { return ID; }
}
