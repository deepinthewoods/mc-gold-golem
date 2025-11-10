package ninja.trek.mc.goldgolem.client.renderer;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public class GoldGolemEntityRenderer extends EntityRenderer<GoldGolemEntity, GoldGolemEntityRenderer.GoldGolemRenderState> {
    private static final Identifier TEXTURE = Identifier.of("gold-golem", "textures/entity/goldgolem.png");

    public GoldGolemEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    public static class GoldGolemRenderState extends EntityRenderState {
    }

    @Override
    public GoldGolemRenderState createRenderState() {
        return new GoldGolemRenderState();
    }

    public void render(GoldGolemRenderState state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        // no-op: custom mesh rendering has been removed
    }

    protected Identifier getTexture(GoldGolemRenderState state) {
        return TEXTURE;
    }
}
