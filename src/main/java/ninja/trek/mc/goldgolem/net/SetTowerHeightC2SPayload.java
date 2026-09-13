package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SetTowerHeightC2SPayload(int entityId, int height) implements CustomPacketPayload {

    public SetTowerHeightC2SPayload {
        height = PayloadValidator.clampInt(height, 1, 256, "height");
    }

    public static final Type<SetTowerHeightC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_tower_height"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTowerHeightC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetTowerHeightC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetTowerHeightC2SPayload::height,
            SetTowerHeightC2SPayload::new
    );
    @Override
    public Type<SetTowerHeightC2SPayload> type() { return ID; }
}
