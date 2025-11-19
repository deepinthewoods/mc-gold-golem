package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record SetTreeTilingPresetC2SPayload(int entityId, int presetOrdinal) implements CustomPayload {
    public static final Id<SetTreeTilingPresetC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_tree_tiling_preset"));

    public static final PacketCodec<RegistryByteBuf, SetTreeTilingPresetC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetTreeTilingPresetC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetTreeTilingPresetC2SPayload::presetOrdinal,
            SetTreeTilingPresetC2SPayload::new
    );

    @Override
    public Id<SetTreeTilingPresetC2SPayload> getId() { return ID; }
}
