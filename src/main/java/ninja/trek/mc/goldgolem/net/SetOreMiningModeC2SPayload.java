package ninja.trek.mc.goldgolem.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Client-to-server payload to set ore mining mode for Mining or Excavation strategies.
 * targetMode: 0 = Mining, 1 = Excavation
 */
public record SetOreMiningModeC2SPayload(int entityId, int targetMode, int oreMiningModeOrdinal) implements CustomPayload {

    public SetOreMiningModeC2SPayload {
        targetMode = PayloadValidator.clampInt(targetMode, 0, 1, "targetMode");
        oreMiningModeOrdinal = PayloadValidator.clampInt(oreMiningModeOrdinal, 0, 2, "oreMiningModeOrdinal");
    }

    public static final Id<SetOreMiningModeC2SPayload> ID = new Id<>(Identifier.of("gold-golem", "set_ore_mining_mode"));
    public static final PacketCodec<RegistryByteBuf, SetOreMiningModeC2SPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, SetOreMiningModeC2SPayload::entityId,
            PacketCodecs.VAR_INT, SetOreMiningModeC2SPayload::targetMode,
            PacketCodecs.VAR_INT, SetOreMiningModeC2SPayload::oreMiningModeOrdinal,
            SetOreMiningModeC2SPayload::new
    );

    @Override
    public Id<SetOreMiningModeC2SPayload> getId() { return ID; }
}
