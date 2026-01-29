package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.List;

public record SyncGradientS2CPayload(int entityId, int width, int noiseScaleMain, int noiseScaleStep, int noiseScaleSurface, float windowMain, float windowStep, float windowSurface, List<String> blocksMain, List<String> blocksStep, List<String> blocksSurface) implements CustomPayload {

    private static final int GRADIENT_SIZE = 9;

    public SyncGradientS2CPayload {
        blocksMain = PayloadValidator.validateListSize(blocksMain, GRADIENT_SIZE, "blocksMain");
        blocksStep = PayloadValidator.validateListSize(blocksStep, GRADIENT_SIZE, "blocksStep");
        blocksSurface = PayloadValidator.validateListSize(blocksSurface, GRADIENT_SIZE, "blocksSurface");
    }

    public static final Id<SyncGradientS2CPayload> ID = new Id<>(Identifier.of("gold-golem", "sync_gradient"));

    public static final PacketCodec<RegistryByteBuf, SyncGradientS2CPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::entityId,
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::width,
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleMain,
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleStep,
            PacketCodecs.VAR_INT, SyncGradientS2CPayload::noiseScaleSurface,
            PacketCodecs.FLOAT, SyncGradientS2CPayload::windowMain,
            PacketCodecs.FLOAT, SyncGradientS2CPayload::windowStep,
            PacketCodecs.FLOAT, SyncGradientS2CPayload::windowSurface,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncGradientS2CPayload::blocksMain,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncGradientS2CPayload::blocksStep,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SyncGradientS2CPayload::blocksSurface,
            SyncGradientS2CPayload::new
    );

    @Override
    public Id<SyncGradientS2CPayload> getId() { return ID; }
}
