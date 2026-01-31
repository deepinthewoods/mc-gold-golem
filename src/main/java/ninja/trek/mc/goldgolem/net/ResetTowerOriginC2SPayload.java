package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ResetTowerOriginC2SPayload(int entityId) implements CustomPacketPayload {
    public static final Type<ResetTowerOriginC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "reset_tower_origin"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ResetTowerOriginC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ResetTowerOriginC2SPayload::entityId,
            ResetTowerOriginC2SPayload::new
    );
    @Override
    public Type<ResetTowerOriginC2SPayload> type() { return ID; }
}
