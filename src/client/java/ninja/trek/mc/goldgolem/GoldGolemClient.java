package ninja.trek.mc.goldgolem;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.entity.EntityRendererFactories;
import ninja.trek.mc.goldgolem.client.net.ClientNet;
import ninja.trek.mc.goldgolem.client.renderer.GoldGolemEntityRenderer;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;
import ninja.trek.mc.goldgolem.client.screen.GolemHandledScreen;

public class GoldGolemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(ModScreenHandlers.GOLEM_SCREEN_HANDLER, GolemHandledScreen::new);
        ClientNet.init();
        EntityRendererFactories.register(GoldGolemEntities.GOLD_GOLEM, GoldGolemEntityRenderer::new);
    }
}
