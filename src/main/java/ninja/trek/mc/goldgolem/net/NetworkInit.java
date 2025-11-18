package ninja.trek.mc.goldgolem.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public class NetworkInit {
    public static void register() {
        PayloadTypeRegistry.playC2S().register(SetGradientSlotC2SPayload.ID, SetGradientSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetPathWidthC2SPayload.ID, SetPathWidthC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetGradientWindowC2SPayload.ID, SetGradientWindowC2SPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncGradientS2CPayload.ID, SyncGradientS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LinesS2CPayload.ID, LinesS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(UniqueBlocksS2CPayload.ID, UniqueBlocksS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WallBlockGroupsS2CPayload.ID, WallBlockGroupsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WallGroupsStateS2CPayload.ID, WallGroupsStateS2CPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(SetWallBlockGroupC2SPayload.ID, SetWallBlockGroupC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetWallGroupSlotC2SPayload.ID, SetWallGroupSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetWallGroupWindowC2SPayload.ID, SetWallGroupWindowC2SPayload.CODEC);

        // Tower Mode payloads
        PayloadTypeRegistry.playS2C().register(TowerBlockCountsS2CPayload.ID, TowerBlockCountsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TowerBlockGroupsS2CPayload.ID, TowerBlockGroupsS2CPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TowerGroupsStateS2CPayload.ID, TowerGroupsStateS2CPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTowerBlockGroupC2SPayload.ID, SetTowerBlockGroupC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTowerGroupSlotC2SPayload.ID, SetTowerGroupSlotC2SPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetTowerGroupWindowC2SPayload.ID, SetTowerGroupWindowC2SPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SetGradientSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    if (payload.row() == 0) {
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
                    if (payload.row() == 0) golem.setGradientWindow(payload.window());
                    else golem.setStepGradientWindow(payload.window());
                    sendSync(player, golem);
                }
            });
        });

        // Wall Mode UI receivers
        ServerPlayNetworking.registerGlobalReceiver(SetWallBlockGroupC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setWallBlockGroup(payload.blockId(), payload.group());
                    sendWallUiState(player, golem);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SetWallGroupSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    golem.setWallGroupSlot(payload.group(), payload.slot(), id);
                    sendWallUiState(player, golem);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SetWallGroupWindowC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setWallGroupWindow(payload.group(), payload.window());
                    sendWallUiState(player, golem);
                }
            });
        });

        // Tower Mode UI receivers
        ServerPlayNetworking.registerGlobalReceiver(SetTowerBlockGroupC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTowerBlockGroup(payload.blockId(), payload.group());
                    sendTowerUiState(player, golem);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SetTowerGroupSlotC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    String id = payload.block().map(Identifier::toString).orElse("");
                    golem.setTowerGroupSlot(payload.group(), payload.slot(), id);
                    sendTowerUiState(player, golem);
                }
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(SetTowerGroupWindowC2SPayload.ID, (payload, context) -> {
            var player = context.player();
            context.server().execute(() -> {
                var world = player.getEntityWorld();
                var e = world.getEntityById(payload.entityId());
                if (e instanceof GoldGolemEntity golem && golem.isOwner(player)) {
                    golem.setTowerGroupWindow(payload.group(), payload.window());
                    sendTowerUiState(player, golem);
                }
            });
        });
    }

    private static void sendWallUiState(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem) {
        var ids = golem.getWallUniqueBlockIds();
        ServerPlayNetworking.send(player, new UniqueBlocksS2CPayload(golem.getId(), ids));
        var groups = golem.getWallBlockGroupMap(ids);
        ServerPlayNetworking.send(player, new WallBlockGroupsS2CPayload(golem.getId(), groups));
        var wins = golem.getWallGroupWindows();
        var slots = golem.getWallGroupFlatSlots();
        ServerPlayNetworking.send(player, new WallGroupsStateS2CPayload(golem.getId(), wins, slots));
    }

    private static void sendTowerUiState(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem) {
        var ids = golem.getTowerUniqueBlockIds();
        var counts = golem.getTowerBlockCounts();
        // Convert counts map to parallel lists
        java.util.List<String> countIds = new java.util.ArrayList<>();
        java.util.List<Integer> countVals = new java.util.ArrayList<>();
        for (String id : ids) {
            countIds.add(id);
            countVals.add(counts.getOrDefault(id, 0));
        }
        ServerPlayNetworking.send(player, new UniqueBlocksS2CPayload(golem.getId(), ids));
        ServerPlayNetworking.send(player, new TowerBlockCountsS2CPayload(golem.getId(), countIds, countVals));
        var groups = golem.getTowerBlockGroupMap(ids);
        ServerPlayNetworking.send(player, new TowerBlockGroupsS2CPayload(golem.getId(), groups));
        var wins = golem.getTowerGroupWindows();
        var slots = golem.getTowerGroupFlatSlots();
        ServerPlayNetworking.send(player, new TowerGroupsStateS2CPayload(golem.getId(), wins, slots));
    }

    private static GoldGolemEntity findNearestOwnedGolem(net.minecraft.server.network.ServerPlayerEntity player, double radius) {
        var world = player.getEntityWorld();
        var box = player.getBoundingBox().expand(radius);
        GoldGolemEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (var e : world.getEntitiesByClass(GoldGolemEntity.class, box, g -> g.isOwner(player))) {
            double d = e.squaredDistanceTo(player);
            if (d < best) { best = d; nearest = e; }
        }
        return nearest;
    }

    private static void sendSync(net.minecraft.server.network.ServerPlayerEntity player, GoldGolemEntity golem) {
        var payload = new SyncGradientS2CPayload(
                golem.getId(),
                golem.getPathWidth(),
                golem.getGradientWindow(),
                golem.getStepGradientWindow(),
                java.util.Arrays.asList(golem.getGradientCopy()),
                java.util.Arrays.asList(golem.getStepGradientCopy())
        );
        ServerPlayNetworking.send(player, payload);
    }
}
