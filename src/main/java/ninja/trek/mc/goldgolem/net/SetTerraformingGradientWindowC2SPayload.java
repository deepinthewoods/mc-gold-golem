package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server payload for setting a terraforming gradient window.
 * gradientType: 0 = vertical, 1 = horizontal, 2 = sloped
 */
public record SetTerraformingGradientWindowC2SPayload(int entityId, int gradientType, int window, int scale) implements CustomPacketPayload {
    public static final Type<SetTerraformingGradientWindowC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_terraforming_gradient_window"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTerraformingGradientWindowC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::gradientType,
            ByteBufCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::window,
            ByteBufCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::scale,
            SetTerraformingGradientWindowC2SPayload::new
    );
    @Override
    public Type<SetTerraformingGradientWindowC2SPayload> type() { return ID; }
}
