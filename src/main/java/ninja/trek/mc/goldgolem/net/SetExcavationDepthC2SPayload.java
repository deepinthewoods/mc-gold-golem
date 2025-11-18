package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetExcavationDepthC2SPayload(int entityId, int depth) implements CustomPayload {
    public static final Id<SetExcavationDepthC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_excavation_depth"));
    public static final PacketCodec<RegistryByteBuf, SetExcavationDepthC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetExcavationDepthC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetExcavationDepthC2SPayload::depth,
            SetExcavationDepthC2SPayload::new
    );
    @Override
    public Id<SetExcavationDepthC2SPayload> getId() { return ID; }
}
