package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Server-to-client payload for syncing terraforming mode state.
 */
public record SyncTerraformingS2CPayload(
        int entityId,
        int scanRadius,
        int verticalWindow,
        int horizontalWindow,
        int slopedWindow,
        List<String> verticalGradient,
        List<String> horizontalGradient,
        List<String> slopedGradient
) implements CustomPayload {
    public static final Id<SyncTerraformingS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_terraforming"));

    public static final PacketCodec<RegistryByteBuf, SyncTerraformingS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncTerraformingS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncTerraformingS2CPayload::scanRadius,
            PacketCodecs.VAR_INT, SyncTerraformingS2CPayload::verticalWindow,
            PacketCodecs.VAR_INT, SyncTerraformingS2CPayload::horizontalWindow,
            PacketCodecs.VAR_INT, SyncTerraformingS2CPayload::slopedWindow,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncTerraformingS2CPayload::verticalGradient,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncTerraformingS2CPayload::horizontalGradient,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncTerraformingS2CPayload::slopedGradient,
            SyncTerraformingS2CPayload::new
    );

    @Override
    public Id<SyncTerraformingS2CPayload> getId() { return ID; }
}
