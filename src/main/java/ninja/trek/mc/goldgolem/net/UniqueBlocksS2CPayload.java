package ninja.trek.mc.goldgolem.net;

import ninja.trek.mc.goldgolem.BuildMode;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UniqueBlocksS2CPayload(int entityId, BuildMode mode, List<String> blockIds) implements CustomPacketPayload {

    public UniqueBlocksS2CPayload {
        blockIds = PayloadValidator.validateList(blockIds, 0, "blockIds");
    }

    public static final Type<UniqueBlocksS2CPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "unique_blocks"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UniqueBlocksS2CPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UniqueBlocksS2CPayload::entityId,
            BuildMode.PACKET_CODEC, UniqueBlocksS2CPayload::mode,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), UniqueBlocksS2CPayload::blockIds,
            UniqueBlocksS2CPayload::new
    );

    @Override
    public Type<UniqueBlocksS2CPayload> type() { return ID; }
}

