package ninja.trek.mc.goldgolem;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import ninja.trek.mc.goldgolem.net.ClientNet;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.registry.ModScreenHandlers;
import ninja.trek.mc.goldgolem.client.render.LineOverlayRenderer;
import ninja.trek.mc.goldgolem.screen.GolemHandledScreen;

public class GoldGolemClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HandledScreens.register(ModScreenHandlers.GOLEM_SCREEN_HANDLER, GolemHandledScreen::new);
        ClientNet.init();
        EntityRendererRegistry.register(GoldGolemEntities.GOLD_GOLEM, net.minecraft.client.render.entity.EmptyEntityRenderer::new);
        LineOverlayRenderer.init();
    }
}
