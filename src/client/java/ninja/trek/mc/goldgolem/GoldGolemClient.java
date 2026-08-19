package ninja.trek.mc.goldgolem;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;
import ninja.trek.mc.goldgolem.client.model.GoldGolemModelLoader;
import ninja.trek.mc.goldgolem.client.net.ClientNet;
import ninja.trek.mc.goldgolem.client.renderer.GoldGolemEntityRenderer;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;

public class GoldGolemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModScreenHandlers.GOLEM_SCREEN_HANDLER, GolemHandledScreen::new);
        ClientNet.init();
        GoldGolemModelLoader.init();
        EntityRenderers.register(GoldGolemEntities.GOLD_GOLEM, GoldGolemEntityRenderer::new);
    }
}
