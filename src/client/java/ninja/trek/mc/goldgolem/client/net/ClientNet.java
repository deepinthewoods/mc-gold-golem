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
                if (mc.currentScreen instanceof ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen screen) {
                    screen.applyServerSync(payload.width(), payload.windowMain(), payload.windowStep(), mainArr, stepArr);
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
                        case WALL -> screen.setWallUniqueBlocks(payload.blockIds());
                        case TOWER -> screen.setTowerUniqueBlocks(payload.blockIds());
                        case TREE -> screen.setTreeUniqueBlocks(payload.blockIds());
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
                        case WALL -> screen.setWallGroupsState(payload.windows(), payload.flatSlots());
                        case TOWER -> {
                            screen.setTowerBlockCounts(
                                    payload.getBlockCounts().keySet().stream().toList(),
                                    payload.getBlockCounts().values().stream().toList(),
                                    payload.getTowerHeight()
                            );
                            screen.setTowerGroupsState(payload.windows(), payload.flatSlots());
                        }
                        case TREE -> screen.setTreeGroupsState(payload.getTilingPresetOrdinal(), payload.windows(), payload.flatSlots());
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
                        case WALL -> screen.setWallBlockGroups(payload.groups());
                        case TOWER -> screen.setTowerBlockGroups(payload.groups());
                        case TREE -> screen.setTreeBlockGroups(payload.groups());
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
                    screen.setExcavationValues(payload.height(), payload.depth());
                }
            });
        });

        // === TERRAFORMING MODE ===
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
