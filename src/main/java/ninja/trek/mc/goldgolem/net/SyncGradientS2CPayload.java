package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.List;

public record SyncGradientS2CPayload(int entityId, int width, List<String> blocks) implements CustomPayload {
    public static final Id<SyncGradientS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_gradient"));

    public static final PacketCodec<RegistryByteBuf, SyncGradientS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::width,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncGradientS2CPayload::blocks,
            SyncGradientS2CPayload::new
    );

    @Override
    public Id<SyncGradientS2CPayload> getId() { return ID; }
}
