package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetTreeTilingPresetC2SPayload(int entityId, int presetOrdinal) implements CustomPacketPayload {
    public static final Type<SetTreeTilingPresetC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_tree_tiling_preset"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetTreeTilingPresetC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTreeTilingPresetC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTreeTilingPresetC2SPayload::presetOrdinal,
            SetTreeTilingPresetC2SPayload::new
    );

    @Override
    public Type<SetTreeTilingPresetC2SPayload> type() { return ID; }
}
