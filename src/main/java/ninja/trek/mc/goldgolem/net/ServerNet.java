package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class ServerNet {
    private ServerNet() {}

    public static void sendLines(ServerPlayerEntity player, int entityId, List<Vec3d> points, java.util.Optional<Vec3d> anchor) {
        ServerPlayNetworking.send(player, new LinesS2CPayload(entityId, points, anchor));
    }
}
