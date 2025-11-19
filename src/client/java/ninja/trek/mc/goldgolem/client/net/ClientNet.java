package ninja.trek.mc.goldgolem.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import ninja.trek.mc.goldgolem.client.state.ClientState;
import ninja.trek.mc.goldgolem.net.LinesS2CPayload;
import ninja.trek.mc.goldgolem.net.SyncGradientS2CPayload;
import ninja.trek.mc.goldgolem.net.UniqueBlocksS2CPayload;
import ninja.trek.mc.goldgolem.net.WallBlockGroupsS2CPayload;
import ninja.trek.mc.goldgolem.net.WallGroupsStateS2CPayload;
import ninja.trek.mc.goldgolem.net.TowerBlockCountsS2CPayload;
import ninja.trek.mc.goldgolem.net.TowerBlockGroupsS2CPayload;
import ninja.trek.mc.goldgolem.net.TowerGroupsStateS2CPayload;
import ninja.trek.mc.goldgolem.net.SyncExcavationS2CPayload;
import ninja.trek.mc.goldgolem.net.SyncTerraformingS2CPayload;

public final class ClientNet {
    private ClientNet() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(SyncGradientS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                String[] mainArr = payload.blocksMain().toArray(new String[0]);
                String[] stepArr = payload.blocksStep().toArray(new String[0]);
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.applyServerSync(payload.width(), payload.windowMain(), payload.windowStep(), mainArr, stepArr);
                }
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(LinesS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                ClientState.setLines(payload.entityId(), payload.points(), payload.anchor());
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(UniqueBlocksS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setWallUniqueBlocks(payload.blockIds());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(WallBlockGroupsS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setWallBlockGroups(payload.groups());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(WallGroupsStateS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setWallGroupsState(payload.windows(), payload.flatSlots());
                }
            });
        });

        // Tower mode receivers
        ClientPlayNetworking.registerGlobalReceiver(TowerBlockCountsS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setTowerBlockCounts(payload.blockIds(), payload.counts());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(TowerBlockGroupsS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setTowerBlockGroups(payload.groups());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(TowerGroupsStateS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setTowerGroupsState(payload.windows(), payload.flatSlots());
                }
            });
        });

        // Excavation mode receiver
        ClientPlayNetworking.registerGlobalReceiver(SyncExcavationS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setExcavationValues(payload.height(), payload.depth());
                }
            });
        });

        // Terraforming mode receiver
        ClientPlayNetworking.registerGlobalReceiver(SyncTerraformingS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.setTerraformingValues(
                            payload.scanRadius(),
                            payload.verticalWindow(),
                            payload.horizontalWindow(),
                            payload.slopedWindow(),
                            payload.verticalGradient(),
                            payload.horizontalGradient(),
                            payload.slopedGradient()
                    );
                }
            });
        });
    }
}
