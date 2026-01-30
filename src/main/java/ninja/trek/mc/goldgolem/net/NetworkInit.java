package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.OreMiningMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.MiningBuildStrategy;
import ninja.trek.mc.goldgolem.world.entity.strategy.ExcavationBuildStrategy;

public class NetworkInit {
    public static void register() {
        // === GENERIC GROUP MODE PAYLOADS ===
        // Generic payloads for group-based modes (Wall, Tower, Tree)
        PayloadTypeRegistry.playC2S().register(SetGroupModeWindowC2SPayload.ID, SetGroupModeWindowC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GroupModeStateS2CPayload.ID, GroupModeStateS2CPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetGroupModeSlotC2SPayload.ID, SetGroupModeSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetGroupModeBlockGroupC2SPayload.ID, SetGroupModeBlockGroupC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTowerHeightC2SPayload.ID, SetTowerHeightC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ResetTowerOriginC2SPayload.ID, ResetTowerOriginC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GroupModeBlockGroupsS2CPayload.ID, GroupModeBlockGroupsS2CPayload.CODEC);

        // === PATH/GRADIENT MODE PAYLOADS ===
        PayloadTypeRegistry.playC2S().register(SetGradientSlotC2SPayload.ID, SetGradientSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetPathWidthC2SPayload.ID, SetPathWidthC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetGradientWindowC2SPayload.ID, SetGradientWindowC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncGradientS2CPayload.ID, SyncGradientS2CPayload.CODEC);

        // === SHARED PAYLOADS ===
        PayloadTypeRegistry.playS2C().register(LinesS2CPayload.ID, LinesS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UniqueBlocksS2CPayload.ID, UniqueBlocksS2CPayload.CODEC);

        // === EXCAVATION MODE PAYLOADS ===
        PayloadTypeRegistry.playC2S().register(SetExcavationHeightC2SPayload.ID, SetExcavationHeightC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetExcavationDepthC2SPayload.ID, SetExcavationDepthC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncExcavationS2CPayload.ID, SyncExcavationS2CPayload.CODEC);

        // === MINING MODE PAYLOADS ===
        PayloadTypeRegistry.playS2C().register(SyncMiningS2CPayload.ID, SyncMiningS2CPayload.CODEC);

        // === TUNNEL MODE PAYLOADS ===
        PayloadTypeRegistry.playC2S().register(SetTunnelWidthC2SPayload.ID, SetTunnelWidthC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTunnelHeightC2SPayload.ID, SetTunnelHeightC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncTunnelS2CPayload.ID, SyncTunnelS2CPayload.CODEC);

        // === ORE MINING MODE PAYLOAD (shared by Mining, Excavation, and Tunnel) ===
        PayloadTypeRegistry.playC2S().register(SetOreMiningModeC2SPayload.ID, SetOreMiningModeC2SPayload.CODEC);

        // === TERRAFORMING MODE PAYLOADS ===
        PayloadTypeRegistry.playC2S().register(SetTerraformingGradientSlotC2SPayload.ID, SetTerraformingGradientSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTerraformingScanRadiusC2SPayload.ID, SetTerraformingScanRadiusC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTerraformingGradientWindowC2SPayload.ID, SetTerraformingGradientWindowC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncTerraformingS2CPayload.ID, SyncTerraformingS2CPayload.CODEC);

        // === GENERIC GROUP MODE HANDLERS ===
        // Single handler for all group-based modes (Wall, Tower, Tree)

        ServerPlayNetworking.registerGlobalReceiver(SetGroupModeWindowC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    switch (payload.mode()) {
                        case WALL -> {
                            golem.setWallGroupWindow(payload.group(), payload.window());
                            golem.setWallGroupNoiseScale(payload.group(), payload.scale());
                        }
                        case TOWER -> {
                            golem.setTowerGroupWindow(payload.group(), payload.window());
                            golem.setTowerGroupNoiseScale(payload.group(), payload.scale());
                        }
                        case TREE -> {
                            golem.setTreeGroupWindow(payload.group(), payload.window());
                            golem.setTreeGroupNoiseScale(payload.group(), payload.scale());
                        }
                        default -> { }
                    }
                    sendGroupModeState(player, golem, payload.mode());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetGroupModeSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    switch (payload.mode()) {
                        case WALL -> golem.setWallGroupSlot(payload.group(), payload.slot(), id);
                        case TOWER -> golem.setTowerGroupSlot(payload.group(), payload.slot(), id);
                        case TREE -> golem.setTreeGroupSlot(payload.group(), payload.slot(), id);
                        default -> { }
                    }
                    sendGroupModeState(player, golem, payload.mode());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetGroupModeBlockGroupC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    switch (payload.mode()) {
                        case WALL -> golem.setWallBlockGroup(payload.blockId(), payload.group());
                        case TOWER -> golem.setTowerBlockGroup(payload.blockId(), payload.group());
                        case TREE -> golem.setTreeBlockGroup(payload.blockId(), payload.group());
                        default -> { }
                    }
                    sendGroupModeState(player, golem, payload.mode());
                }
            });
        });

        // === TOWER MODE HANDLER ===
        ServerPlayNetworking.registerGlobalReceiver(SetTowerHeightC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTowerHeight(payload.height());
                    sendGroupModeState(player, golem, BuildMode.TOWER);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ResetTowerOriginC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTowerOrigin(golem.getBlockPos());
                }
            });
        });

        // === PATH/GRADIENT MODE HANDLERS ===

        ServerPlayNetworking.registerGlobalReceiver(SetGradientSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    if (payload.row() == 0) {
                        golem.setSurfaceGradientSlot(payload.slot(), id);
                    } else if (payload.row() == 1) {
                        golem.setGradientSlot(payload.slot(), id);
                    } else {
                        golem.setStepGradientSlot(payload.slot(), id);
                    }
                    sendSync(player, golem);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetPathWidthC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setPathWidth(payload.width());
                    sendSync(player, golem);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetGradientWindowC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    if (payload.row() == 0) {
                        golem.setSurfaceGradientWindow(payload.window());
                        golem.setGradientNoiseScaleSurface(payload.scale());
                    } else if (payload.row() == 1) {
                        golem.setGradientWindow(payload.window());
                        golem.setGradientNoiseScaleMain(payload.scale());
                    } else {
                        golem.setStepGradientWindow(payload.window());
                        golem.setGradientNoiseScaleStep(payload.scale());
                    }
                    sendSync(player, golem);
                }
            });
        });

        // === EXCAVATION MODE HANDLERS ===

        ServerPlayNetworking.registerGlobalReceiver(SetExcavationHeightC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setExcavationSliders(payload.height(), golem.getExcavationDepth());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetExcavationDepthC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setExcavationSliders(golem.getExcavationHeight(), payload.depth());
                }
            });
        });

        // === TUNNEL MODE HANDLERS ===

        ServerPlayNetworking.registerGlobalReceiver(SetTunnelWidthC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTunnelWidth(payload.width());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetTunnelHeightC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTunnelHeight(payload.height());
                }
            });
        });

        // === ORE MINING MODE HANDLER ===
        ServerPlayNetworking.registerGlobalReceiver(SetOreMiningModeC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    OreMiningMode mode = OreMiningMode.fromOrdinal(payload.oreMiningModeOrdinal());
                    if (payload.targetMode() == 0) {
                        // Mining mode
                        golem.setMiningOreMiningMode(mode);
                    } else if (payload.targetMode() == 1) {
                        // Excavation mode
                        golem.setExcavationOreMiningMode(mode);
                    } else {
                        // Tunnel mode
                        golem.setTunnelOreMiningMode(mode);
                    }
                }
            });
        });

        // === TERRAFORMING MODE HANDLERS ===

        ServerPlayNetworking.registerGlobalReceiver(SetTerraformingGradientSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    switch (payload.gradientType()) {
                        case 0 -> golem.setTerraformingGradientVerticalSlot(payload.slot(), id);
                        case 1 -> golem.setTerraformingGradientHorizontalSlot(payload.slot(), id);
                        case 2 -> golem.setTerraformingGradientSlopedSlot(payload.slot(), id);
                    }
                    sendTerraformingSync(player, golem);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetTerraformingScanRadiusC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTerraformingScanRadius(payload.radius());
                    sendTerraformingSync(player, golem);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(SetTerraformingGradientWindowC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    switch (payload.gradientType()) {
                        case 0 -> {
                            golem.setTerraformingGradientVerticalWindow(payload.window());
                            golem.setTerraformingGradientVerticalScale(payload.scale());
                        }
                        case 1 -> {
                            golem.setTerraformingGradientHorizontalWindow(payload.window());
                            golem.setTerraformingGradientHorizontalScale(payload.scale());
                        }
                        case 2 -> {
                            golem.setTerraformingGradientSlopedWindow(payload.window());
                            golem.setTerraformingGradientSlopedScale(payload.scale());
                        }
                    }
                    sendTerraformingSync(player, golem);
                }
            });
        });
    }

    /**
     * Send group mode state using the generic payload.
     */
    private static void sendGroupModeState(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem, BuildMode mode) {
        ServerPlayNetworking.send(player, new UniqueBlocksS2CPayload(golem.getId(), mode, getUniqueBlocks(golem, mode)));

        java.util.List<Integer> groups;
        java.util.List<Float> windows;
        java.util.List<Integer> scales;
        java.util.List<String> slots;
        java.util.Map<String, Object> extraData;

        switch (mode) {
            case WALL -> {
                var ids = golem.getWallUniqueBlockIds();
                groups = golem.getWallBlockGroupMap(ids);
                windows = golem.getWallGroupWindows();
                scales = golem.getWallGroupNoiseScales();
                slots = golem.getWallGroupFlatSlots();
                extraData = GroupModeStateS2CPayload.createWallExtraData();
            }
            case TOWER -> {
                var ids = golem.getTowerUniqueBlockIds();
                groups = golem.getTowerBlockGroupMap(ids);
                windows = golem.getTowerGroupWindows();
                scales = golem.getTowerGroupNoiseScales();
                slots = golem.getTowerGroupFlatSlots();
                extraData = GroupModeStateS2CPayload.createTowerExtraData(golem.getTowerBlockCounts(), golem.getTowerHeight());
            }
            case TREE -> {
                var ids = golem.getTreeUniqueBlockIds();
                groups = golem.getTreeBlockGroupMap(ids);
                windows = golem.getTreeGroupWindows();
                scales = golem.getTreeGroupNoiseScales();
                slots = golem.getTreeGroupFlatSlots();
                extraData = GroupModeStateS2CPayload.createTreeExtraData(golem.getTreeTilingPreset().ordinal());
            }
            default -> {
                return;
            }
        }

        // Send generic block groups payload
        ServerPlayNetworking.send(player, new GroupModeBlockGroupsS2CPayload(golem.getId(), mode, groups));
        // Send generic group mode state payload
        ServerPlayNetworking.send(player, new GroupModeStateS2CPayload(golem.getId(), mode, windows, scales, slots, extraData));
    }

    /**
     * Helper to get unique blocks for a mode.
     */
    private static java.util.List<String> getUniqueBlocks(GoldGolemEntity golem, BuildMode mode) {
        return switch (mode) {
            case WALL -> golem.getWallUniqueBlockIds();
            case TOWER -> golem.getTowerUniqueBlockIds();
            case TREE -> golem.getTreeUniqueBlockIds();
            default -> java.util.List.of();
        };
    }

    private static void sendSync(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem) {
        var payload = new SyncGradientS2CPayload(
                golem.getId(),
                golem.getPathWidth(),
                golem.getGradientNoiseScaleMain(),
                golem.getGradientNoiseScaleStep(),
                golem.getGradientNoiseScaleSurface(),
                golem.getGradientWindow(),
                golem.getStepGradientWindow(),
                golem.getSurfaceGradientWindow(),
                java.util.Arrays.asList(golem.getGradientCopy()),
                java.util.Arrays.asList(golem.getStepGradientCopy()),
                java.util.Arrays.asList(golem.getSurfaceGradientCopy())
        );
        ServerPlayNetworking.send(player, payload);
    }

    private static void sendTerraformingSync(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem) {
        var payload = new SyncTerraformingS2CPayload(
                golem.getId(),
                golem.getTerraformingScanRadius(),
                golem.getTerraformingGradientVerticalWindow(),
                golem.getTerraformingGradientHorizontalWindow(),
                golem.getTerraformingGradientSlopedWindow(),
                golem.getTerraformingGradientVerticalScale(),
                golem.getTerraformingGradientHorizontalScale(),
                golem.getTerraformingGradientSlopedScale(),
                java.util.Arrays.asList(golem.getTerraformingGradientVerticalCopy()),
                java.util.Arrays.asList(golem.getTerraformingGradientHorizontalCopy()),
                java.util.Arrays.asList(golem.getTerraformingGradientSlopedCopy())
        );
        ServerPlayNetworking.send(player, payload);
    }
}
