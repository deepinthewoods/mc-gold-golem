package ninja.trek.mc.goldgolem.screen;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Utilities to open Gold Golem screens from the server side. */
public final class GolemScreens {
    private GolemScreens() {}

    public static void open(ServerPlayerEntity player, int entityId, Inventory golemInventory) {
        // Inspect entity to decide UI flags (e.g., hide width slider in Wall Mode)
        var world0 = player.getEntityWorld();
        var ent0 = world0.getEntityById(entityId);
        boolean sliderEnabled = true;
        if (ent0 instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity g0) {
            if (g0.getBuildMode() == ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity.BuildMode.WALL) {
                sliderEnabled = false;
            }
        }

        // Build dynamic UI spec
        int gradientRows = 2;
        int golemSlots = golemInventory.size();
        int slider = sliderEnabled ? 1 : 0;
        var openData = new GolemOpenData(entityId, gradientRows, golemSlots, slider);

        player.openHandledScreen(new ExtendedScreenHandlerFactory<GolemOpenData>() {
            @Override
            public GolemOpenData getScreenOpeningData(ServerPlayerEntity player) {
                return openData;
            }

            @Override
            public Text getDisplayName() {
                return Text.translatable("screen.gold_golem.inventory");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity ignored) {
                return new GolemInventoryScreenHandler(syncId, playerInventory, golemInventory, openData);
            }
        });
        // Send initial sync of width/gradient/window to the opener
        var world = player.getEntityWorld();
        var e = world.getEntityById(entityId);
        if (e instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                    new ninja.trek.mc.goldgolem.net.SyncGradientS2CPayload(
                            entityId,
                            golem.getPathWidth(),
                            golem.getGradientWindow(),
                            golem.getStepGradientWindow(),
                            java.util.Arrays.asList(golem.getGradientCopy()),
                            java.util.Arrays.asList(golem.getStepGradientCopy())
                    ));
            if (golem.getBuildMode() == ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity.BuildMode.WALL) {
                // Initialize groups on first open if empty
                if (golem.getWallUniqueBlockIds() != null && !golem.getWallUniqueBlockIds().isEmpty()) {
                    if (golem.getWallGroupWindows().isEmpty()) {
                        golem.initWallGroups(golem.getWallUniqueBlockIds());
                    }
                    var ids = golem.getWallUniqueBlockIds();
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new ninja.trek.mc.goldgolem.net.UniqueBlocksS2CPayload(entityId, ids));
                    var groups = golem.getWallBlockGroupMap(ids);
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new ninja.trek.mc.goldgolem.net.WallBlockGroupsS2CPayload(entityId, groups));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new ninja.trek.mc.goldgolem.net.WallGroupsStateS2CPayload(entityId, golem.getWallGroupWindows(), golem.getWallGroupFlatSlots()));
                }
            }
        }
    }
}
