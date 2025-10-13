package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public record LinesS2CPayload(int entityId, List<BlockPos> points) implements CustomPayload {
    public static final Id<LinesS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "lines"));

    public static final PacketCodec<RegistryByteBuf, LinesS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, LinesS2CPayload::entityId,
            BlockPos.PACKET_CODEC.collect(PacketCodecs.toList()), LinesS2CPayload::points,
            LinesS2CPayload::new
    );

    @Override
    public Id<LinesS2CPayload> getId() { return ID; }
}

