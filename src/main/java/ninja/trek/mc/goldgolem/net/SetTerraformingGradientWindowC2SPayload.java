package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload for setting a terraforming gradient window.
 * gradientType: 0 = vertical, 1 = horizontal, 2 = sloped
 */
public record SetTerraformingGradientWindowC2SPayload(int entityId, int gradientType, int window) implements CustomPayload {
    public static final Id<SetTerraformingGradientWindowC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_terraforming_gradient_window"));
    public static final PacketCodec<RegistryByteBuf, SetTerraformingGradientWindowC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::gradientType,
            PacketCodecs.VAR_INT, SetTerraformingGradientWindowC2SPayload::window,
            SetTerraformingGradientWindowC2SPayload::new
    );
    @Override
    public Id<SetTerraformingGradientWindowC2SPayload> getId() { return ID; }
}
