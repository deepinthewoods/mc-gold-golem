package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetTerraformingScanRadiusC2SPayload(int entityId, int radius) implements CustomPacketPayload {

    public SetTerraformingScanRadiusC2SPayload {
        radius = PayloadValidator.clampInt(radius, 1, 32, "radius");
    }

    public static final Type<SetTerraformingScanRadiusC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_terraforming_scan_radius"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTerraformingScanRadiusC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTerraformingScanRadiusC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTerraformingScanRadiusC2SPayload::radius,
            SetTerraformingScanRadiusC2SPayload::new
    );
    @Override
    public Type<SetTerraformingScanRadiusC2SPayload> type() { return ID; }
}
