package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server-to-client payload to sync Mining mode settings.
 */
public record SyncMiningS2CPayload(int entityId, int branchDepth, int branchSpacing, int tunnelHeight, int oreMiningMode) implements CustomPayload {
    public static final Id<SyncMiningS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_mining"));
    public static final PacketCodec<RegistryByteBuf, SyncMiningS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncMiningS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncMiningS2CPayload::branchDepth,
            PacketCodecs.VAR_INT, SyncMiningS2CPayload::branchSpacing,
            PacketCodecs.VAR_INT, SyncMiningS2CPayload::tunnelHeight,
            PacketCodecs.VAR_INT, SyncMiningS2CPayload::oreMiningMode,
            SyncMiningS2CPayload::new
    );

    @Override
    public Id<SyncMiningS2CPayload> getId() { return ID; }
}
