package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import ninja.trek.mc.goldgolem.client.screen.GolemScreen;

public final class ClientNet {
    private ClientNet() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SyncGradientS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof GolemScreen screen && screen.getEntityId() == payload.entityId()) {
                    String[] arr = payload.blocks().toArray(new String[0]);
                    screen.applyServerSync(payload.width(), arr);
                }
            });
        });
    }
}
