package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

public record LinesS2CPayload(int entityId, List<Vec3d> points, Optional<Vec3d> anchor) implements CustomPayload {
    public static final Id<LinesS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "lines"));

    public static final PacketCodec<RegistryByteBuf, LinesS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, LinesS2CPayload::entityId,
            Vec3d.PACKET_CODEC.collect(PacketCodecs.toList()), LinesS2CPayload::points,
            PacketCodecs.optional(Vec3d.PACKET_CODEC), LinesS2CPayload::anchor,
            LinesS2CPayload::new
    );

    @Override
    public Id<LinesS2CPayload> getId() { return ID; }
}
