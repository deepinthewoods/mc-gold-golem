package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MovePyramidPriorityC2SPayload(int entityId, String blockId, int delta) implements CustomPacketPayload {
    public MovePyramidPriorityC2SPayload {
        blockId = blockId == null ? "" : blockId;
        delta = PayloadValidator.clampInt(delta, -1, 1, "delta");
    }

    public static final Type<MovePyramidPriorityC2SPayload> ID =
            new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "move_pyramid_priority"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MovePyramidPriorityC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MovePyramidPriorityC2SPayload::entityId,
            ByteBufCodecs.stringUtf8(128), MovePyramidPriorityC2SPayload::blockId,
            ByteBufCodecs.INT, MovePyramidPriorityC2SPayload::delta,
            MovePyramidPriorityC2SPayload::new
    );

    @Override
    public Type<MovePyramidPriorityC2SPayload> type() { return ID; }
}
