package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client payload to sync Mining mode settings.
 */
public record SyncMiningS2CPayload(int entityId, int branchDepth, int branchSpacing, int tunnelHeight, int oreMiningMode) implements CustomPacketPayload {
    public static final Type<SyncMiningS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "sync_mining"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMiningS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncMiningS2CPayload::entityId,
            ByteBufCodecs.VAR_INT, SyncMiningS2CPayload::branchDepth,
            ByteBufCodecs.VAR_INT, SyncMiningS2CPayload::branchSpacing,
            ByteBufCodecs.VAR_INT, SyncMiningS2CPayload::tunnelHeight,
            ByteBufCodecs.VAR_INT, SyncMiningS2CPayload::oreMiningMode,
            SyncMiningS2CPayload::new
    );

    @Override
    public Type<SyncMiningS2CPayload> type() { return ID; }
}
