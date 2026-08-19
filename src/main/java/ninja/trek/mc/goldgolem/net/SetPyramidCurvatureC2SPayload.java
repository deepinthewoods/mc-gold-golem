package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetPyramidCurvatureC2SPayload(int entityId, int curvature) implements CustomPacketPayload {
    public SetPyramidCurvatureC2SPayload {
        curvature = PayloadValidator.clampInt(curvature, -100, 100, "curvature");
    }

    public static final Type<SetPyramidCurvatureC2SPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_pyramid_curvature"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetPyramidCurvatureC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetPyramidCurvatureC2SPayload::entityId,
            ByteBufCodecs.INT, SetPyramidCurvatureC2SPayload::curvature,
            SetPyramidCurvatureC2SPayload::new
    );

    @Override
    public Type<SetPyramidCurvatureC2SPayload> type() { return ID; }
}
