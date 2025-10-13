package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class ServerNet {
    private ServerNet() {}

    public static void sendLines(ServerPlayerEntity player, int entityId, List<BlockPos> points) {
        ServerPlayNetworking.send(player, new LinesS2CPayload(entityId, points));
    }
}

