package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record UniqueBlocksS2CPayload(int entityId, List<String> blockIds) implements CustomPayload {
    public static final Id<UniqueBlocksS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "unique_blocks"));

    public static final PacketCodec<RegistryByteBuf, UniqueBlocksS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, UniqueBlocksS2CPayload::entityId,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), UniqueBlocksS2CPayload::blockIds,
            UniqueBlocksS2CPayload::new
    );

    @Override
    public Id<UniqueBlocksS2CPayload> getId() { return ID; }
}

