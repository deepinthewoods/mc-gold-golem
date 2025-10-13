package ninja.trek.mc.goldgolem.registry;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import ninja.trek.mc.goldgolem.GoldGolem;
import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;

public final class ModScreenHandlers {
    private ModScreenHandlers() {}

    public static ScreenHandlerType<GolemInventoryScreenHandler> GOLEM_SCREEN_HANDLER;

    public static void init() {
        GOLEM_SCREEN_HANDLER = Registry.register(
                Registries.SCREEN_HANDLER,
                GoldGolem.id("golem_inventory"),
                new ExtendedScreenHandlerType<>((syncId, playerInv, data) ->
                        new GolemInventoryScreenHandler(syncId, playerInv, data),
                        ninja.trek.mc.goldgolem.screen.GolemOpenData.CODEC)
        );
    }
}
