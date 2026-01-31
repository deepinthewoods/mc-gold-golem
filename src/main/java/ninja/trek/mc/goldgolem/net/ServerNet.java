package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.List;

public final class ServerNet {
    private ServerNet() {}

    public static void sendLines(ServerPlayer player, int entityId, List<Vec3> points, java.util.Optional<Vec3> anchor) {
        ServerPlayNetworking.send(player, new LinesS2CPayload(entityId, points, anchor));
    }
}
