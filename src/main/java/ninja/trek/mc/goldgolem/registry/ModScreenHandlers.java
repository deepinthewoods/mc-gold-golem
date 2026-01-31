package ninja.trek.mc.goldgolem.registry;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;
import ninja.trek.mc.goldgolem.GoldGolem;
import ninja.trek.mc.goldgolem.screen.GolemInventoryScreenHandler;

public final class ModScreenHandlers {
    private ModScreenHandlers() {}

    public static MenuType<GolemInventoryScreenHandler> GOLEM_SCREEN_HANDLER;

    public static void init() {
        GOLEM_SCREEN_HANDLER = Registry.register(
                BuiltInRegistries.MENU,
                GoldGolem.id("golem_inventory"),
                new ExtendedScreenHandlerType<>((syncId, playerInv, data) ->
                        new GolemInventoryScreenHandler(syncId, playerInv, data),
                        ninja.trek.mc.goldgolem.screen.GolemOpenData.CODEC)
        );
    }
}
