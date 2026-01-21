package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SyncExcavationS2CPayload(int entityId, int height, int depth, int oreMiningMode) implements CustomPayload {
    public static final Id<SyncExcavationS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_excavation"));
    public static final PacketCodec<RegistryByteBuf, SyncExcavationS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncExcavationS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncExcavationS2CPayload::height,
            PacketCodecs.VAR_INT, SyncExcavationS2CPayload::depth,
            PacketCodecs.VAR_INT, SyncExcavationS2CPayload::oreMiningMode,
            SyncExcavationS2CPayload::new
    );
    @Override
    public Id<SyncExcavationS2CPayload> getId() { return ID; }
}
