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
