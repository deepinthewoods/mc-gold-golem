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
        // Build dynamic UI spec
        int gradientRows = 1;
        int golemSlots = golemInventory.size();
        int slider = 1;
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
        // Send initial sync of width/gradient to the opener
        var world = player.getEntityWorld();
        var e = world.getEntityById(entityId);
        if (e instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                    new ninja.trek.mc.goldgolem.net.SyncGradientS2CPayload(entityId, golem.getPathWidth(), java.util.Arrays.asList(golem.getGradientCopy())));
        }
    }
}
