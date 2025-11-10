package ninja.trek.mc.goldgolem.client.renderer;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import ninja.trek.mc.goldgolem.client.model.GoldGolemModelLoader;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public class GoldGolemEntityRenderer extends EntityRenderer<GoldGolemEntity, GoldGolemEntityRenderer.GoldGolemRenderState> {
    private static final Identifier TEXTURE = Identifier.of("gold-golem", "textures/entity/goldgolem.png");
    private static final RenderLayer GOLD_GOLEM_TRIANGLES_LAYER = createLayer();

    public GoldGolemEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    public static class GoldGolemRenderState extends EntityRenderState {
    }

    @Override
    public GoldGolemRenderState createRenderState() {
        return new GoldGolemRenderState();
    }

    public void render(
            GoldGolemRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraState
    ) {
        var meshParts = GoldGolemModelLoader.getMeshes();
        if (meshParts.isEmpty()) {
            super.render(state, matrices, queue, cameraState);
            return;
        }

        matrices.push();
        matrices.translate(0.0f, 0.5f, 0.0f);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));

        var layer = GOLD_GOLEM_TRIANGLES_LAYER;
        int overlay = OverlayTexture.DEFAULT_UV;
        int light = state.light;
        queue.submitCustom(matrices, layer, (entry, consumer) -> {
            for (GoldGolemModelLoader.MeshPart mesh : meshParts) {
                int[] indices = mesh.indices();
                if (indices.length < 3) continue;
                for (int i = 0; i <= indices.length - 3; i += 3) {
                    emitVertex(entry, consumer, mesh, indices[i], overlay, light);
                    emitVertex(entry, consumer, mesh, indices[i + 1], overlay, light);
                    emitVertex(entry, consumer, mesh, indices[i + 2], overlay, light);
                }
            }
        });

        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }

    private static RenderLayer createLayer() {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
                .withLocation(Identifier.of("gold-golem", "pipeline/gold_golem_triangles"))
                .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES)
                .build();
        RenderLayer.MultiPhaseParameters params = RenderLayer.MultiPhaseParameters.builder()
                .texture(new RenderPhase.Texture(TEXTURE, false))
                .lightmap(RenderLayer.ENABLE_LIGHTMAP)
                .overlay(RenderLayer.ENABLE_OVERLAY_COLOR)
                .build(true);
        return RenderLayer.of("gold_golem_triangles", 1536, true, true, pipeline, params);
    }

    private static void emitVertex(MatrixStack.Entry entry, VertexConsumer consumer,
                                   GoldGolemModelLoader.MeshPart mesh, int vertexIndex, int overlay, int light) {
        float[] positions = mesh.positions();
        float[] normals = mesh.normals();
        float[] uvs = mesh.uvs();
        int posBase = vertexIndex * 3;
        int uvBase = vertexIndex * 2;
        float px = positions[posBase];
        float py = positions[posBase + 1];
        float pz = positions[posBase + 2];
        float nx = normals[posBase];
        float ny = normals[posBase + 1];
        float nz = normals[posBase + 2];
        float u = uvs[uvBase];
        float v = uvs[uvBase + 1];

        consumer.vertex(entry, px, py, pz)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(overlay)
                .light(light)
                .normal(entry, nx, ny, nz);
    }
}
