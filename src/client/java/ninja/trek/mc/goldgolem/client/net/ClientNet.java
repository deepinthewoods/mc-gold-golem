package ninja.trek.mc.goldgolem.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.client.state.ClientState;
import ninja.trek.mc.goldgolem.net.*;

public final class ClientNet {
    private ClientNet() {}

    public static void init() {
        // === PATH/GRADIENT MODE ===
        ClientPlayNetworking.registerGlobalReceiver(SyncGradientS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                String[] mainArr = payload.blocksMain().toArray(new String[0]);
                String[] stepArr = payload.blocksStep().toArray(new String[0]);
                String[] surfaceArr = payload.blocksSurface().toArray(new String[0]);
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.applyServerSync(payload.width(), payload.noiseScaleMain(), payload.noiseScaleStep(), payload.noiseScaleSurface(), payload.windowMain(), payload.windowStep(), payload.windowSurface(), mainArr, stepArr, surfaceArr);
                }
            });
        });

        // === SHARED ===
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
                    // Only set the unique blocks for the specific mode
                    switch (payload.mode()) {
                        case WALL -> screen.syncWallUniqueBlocks(payload.blockIds());
                        case TOWER -> screen.syncTowerUniqueBlocks(payload.blockIds());
                        case TREE -> screen.syncTreeUniqueBlocks(payload.blockIds());
                        default -> { }
                    }
                }
            });
        });

        // === GROUP MODES (Wall, Tower, Tree) ===
        // Generic group mode state handler
        ClientPlayNetworking.registerGlobalReceiver(GroupModeStateS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    switch (payload.mode()) {
                        case WALL -> screen.syncWallGroupsState(payload.windows(), payload.noiseScales(), payload.flatSlots());
                        case TOWER -> {
                            screen.syncTowerBlockCounts(
                                    payload.getBlockCounts().keySet().stream().toList(),
                                    payload.getBlockCounts().values().stream().toList(),
                                    payload.getTowerHeight()
                            );
                            screen.syncTowerGroupsState(payload.windows(), payload.noiseScales(), payload.flatSlots());
                        }
                        case TREE -> screen.syncTreeGroupsState(payload.getTilingPresetOrdinal(), payload.windows(), payload.noiseScales(), payload.flatSlots());
                        default -> { }
                    }
                }
            });
        });

        // Generic block groups handler
        ClientPlayNetworking.registerGlobalReceiver(GroupModeBlockGroupsS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    switch (payload.mode()) {
                        case WALL -> screen.syncWallBlockGroups(payload.groups());
                        case TOWER -> screen.syncTowerBlockGroups(payload.groups());
                        case TREE -> screen.syncTreeBlockGroups(payload.groups());
                        default -> { }
                    }
                }
            });
        });

        // === EXCAVATION MODE ===
        ClientPlayNetworking.registerGlobalReceiver(SyncExcavationS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.syncExcavationState(payload.height(), payload.depth(), payload.oreMiningMode());
                }
            });
        });

        // === MINING MODE ===
        ClientPlayNetworking.registerGlobalReceiver(SyncMiningS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.syncMiningState(payload.branchDepth(), payload.branchSpacing(), payload.tunnelHeight(), payload.oreMiningMode());
                }
            });
        });

        // === TUNNEL MODE ===
        ClientPlayNetworking.registerGlobalReceiver(SyncTunnelS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.syncTunnelState(payload.width(), payload.height(), payload.oreMiningMode());
                }
            });
        });

        // === TERRAFORMING MODE ===
        ClientPlayNetworking.registerGlobalReceiver(SyncTerraformingS2CPayload.ID, (payload, context) -> {
            var mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.syncTerraformingState(
                            payload.scanRadius(),
                            payload.verticalWindow(),
                            payload.horizontalWindow(),
                            payload.slopedWindow(),
                            payload.verticalScale(),
                            payload.horizontalScale(),
                            payload.slopedScale(),
                            payload.verticalGradient(),
                            payload.horizontalGradient(),
                            payload.slopedGradient()
                    );
                }
            });
        });
    }
}
