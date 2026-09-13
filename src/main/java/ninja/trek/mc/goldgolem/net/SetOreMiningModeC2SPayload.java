package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-to-server payload to set ore mining mode for Mining or Excavation strategies.
 * targetMode: 0 = Mining, 1 = Excavation
 */
public record SetOreMiningModeC2SPayload(int entityId, int targetMode, int oreMiningModeOrdinal) implements CustomPacketPayload {

    public SetOreMiningModeC2SPayload {
        targetMode = PayloadValidator.clampInt(targetMode, 0, 2, "targetMode");
        oreMiningModeOrdinal = PayloadValidator.clampInt(oreMiningModeOrdinal, 0, 2, "oreMiningModeOrdinal");
    }

    public static final Type<SetOreMiningModeC2SPayload> ID = new Type<>(Identifier.fromNamespaceAndPath("gold-golem", "set_ore_mining_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetOreMiningModeC2SPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SetOreMiningModeC2SPayload::entityId,
            ByteBufCodecs.VAR_INT, SetOreMiningModeC2SPayload::targetMode,
            ByteBufCodecs.VAR_INT, SetOreMiningModeC2SPayload::oreMiningModeOrdinal,
            SetOreMiningModeC2SPayload::new
    );

    @Override
    public Type<SetOreMiningModeC2SPayload> type() { return ID; }
}
