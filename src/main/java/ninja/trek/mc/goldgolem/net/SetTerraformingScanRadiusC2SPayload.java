package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTerraformingScanRadiusC2SPayload(int entityId, int radius) implements CustomPayload {

    public SetTerraformingScanRadiusC2SPayload {
        radius = PayloadValidator.clampInt(radius, 1, 32, "radius");
    }

    public static final Id<SetTerraformingScanRadiusC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_terraforming_scan_radius"));
    public static final PacketCodec<RegistryByteBuf, SetTerraformingScanRadiusC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTerraformingScanRadiusC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTerraformingScanRadiusC2SPayload::radius,
            SetTerraformingScanRadiusC2SPayload::new
    );
    @Override
    public Id<SetTerraformingScanRadiusC2SPayload> getId() { return ID; }
}
