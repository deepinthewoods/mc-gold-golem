package ninja.trek.mc.goldgolem.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client payload for syncing terraforming mode state.
 */
public record SyncTerraformingS2CPayload(
        int entityId,
        int scanRadius,
        int verticalWindow,
        int horizontalWindow,
        int slopedWindow,
        int verticalScale,
        int horizontalScale,
        int slopedScale,
        List<String> verticalGradient,
        List<String> horizontalGradient,
        List<String> slopedGradient
) implements CustomPacketPayload {

    private static final int GRADIENT_SIZE = 9;

    public SyncTerraformingS2CPayload {
        verticalGradient = PayloadValidator.validateListSize(verticalGradient, GRADIENT_SIZE, "verticalGradient");
        horizontalGradient = PayloadValidator.validateListSize(horizontalGradient, GRADIENT_SIZE, "horizontalGradient");
        slopedGradient = PayloadValidator.validateListSize(slopedGradient, GRADIENT_SIZE, "slopedGradient");
    }

    public static final Type<SyncTerraformingS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "sync_terraforming"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncTerraformingS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::entityId,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::scanRadius,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::verticalWindow,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::horizontalWindow,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::slopedWindow,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::verticalScale,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::horizontalScale,
            ByteBufCodecs.VAR_INT, SyncTerraformingS2CPayload::slopedScale,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncTerraformingS2CPayload::verticalGradient,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncTerraformingS2CPayload::horizontalGradient,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncTerraformingS2CPayload::slopedGradient,
            SyncTerraformingS2CPayload::new
    );

    @Override
    public Type<SyncTerraformingS2CPayload> type() { return ID; }
}
