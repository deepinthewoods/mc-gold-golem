package ninja.trek.mc.goldgolem.net;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SyncGradientS2CPayload(int entityId, int width, int noiseScaleMain, int noiseScaleStep, int noiseScaleSurface, float windowMain, float windowStep, float windowSurface, List<String> blocksMain, List<String> blocksStep, List<String> blocksSurface) implements CustomPacketPayload {

    private static final int GRADIENT_SIZE = 9;

    public SyncGradientS2CPayload {
        blocksMain = PayloadValidator.validateListSize(blocksMain, GRADIENT_SIZE, "blocksMain");
        blocksStep = PayloadValidator.validateListSize(blocksStep, GRADIENT_SIZE, "blocksStep");
        blocksSurface = PayloadValidator.validateListSize(blocksSurface, GRADIENT_SIZE, "blocksSurface");
    }

    public static final Type<SyncGradientS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "sync_gradient"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGradientS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SyncGradientS2CPayload::entityId,
            ByteBufCodecs.VAR_INT, SyncGradientS2CPayload::width,
            ByteBufCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleMain,
            ByteBufCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleStep,
            ByteBufCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleSurface,
            ByteBufCodecs.FLOAT, SyncGradientS2CPayload::windowMain,
            ByteBufCodecs.FLOAT, SyncGradientS2CPayload::windowStep,
            ByteBufCodecs.FLOAT, SyncGradientS2CPayload::windowSurface,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncGradientS2CPayload::blocksMain,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncGradientS2CPayload::blocksStep,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SyncGradientS2CPayload::blocksSurface,
            SyncGradientS2CPayload::new
    );

    @Override
    public Type<SyncGradientS2CPayload> type() { return ID; }
}
