package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import ninja.trek.mc.goldgolem.client.screen.GolemScreen;
import ninja.trek.mc.goldgolem.client.state.ClientState;

public final class ClientNet {
    private ClientNet() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SyncGradientS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                String[] arr = payload.blocks().toArray(new String[0]);
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.screen.GolemHandledScreen screen) {
                    screen.applyServerSync(payload.width(), payload.window(), arr);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(LinesS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                ClientState.setLines(payload.entityId(), payload.points());
            });
        });
    }
}
