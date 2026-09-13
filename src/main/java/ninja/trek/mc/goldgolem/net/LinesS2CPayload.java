package ninja.trek.mc.goldgolem.net;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public record LinesS2CPayload(int entityId, List<Vec3> points, Optional<Vec3> anchor, boolean noValid) implements CustomPacketPayload {
    public static final Type<LinesS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "lines"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LinesS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, LinesS2CPayload::entityId,
            Vec3.STREAM_CODEC.apply(ByteBufCodecs.list()), LinesS2CPayload::points,
            ByteBufCodecs.optional(Vec3.STREAM_CODEC), LinesS2CPayload::anchor,
            ByteBufCodecs.BOOL, LinesS2CPayload::noValid,
            LinesS2CPayload::new
    );

    @Override
    public Type<LinesS2CPayload> type() { return ID; }
}
