package ninja.trek.mc.goldgolem.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import ninja.trek.mc.goldgolem.client.state.ClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void goldgolem$renderLines(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return;

        boolean holding = mc.player.getMainHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET)
            || mc.player.getOffHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET);
        if (!holding) return;

        Map<Integer, List<BlockPos>> all = ClientState.getAllLines();
        if (all.isEmpty()) return;

        Camera cam = mc.gameRenderer.getCamera();
        if (cam == null) return;

        double cx = cam.getPos().x;
        double cy = cam.getPos().y;
        double cz = cam.getPos().z;

        MatrixStack matrices = new MatrixStack();
        VertexConsumerProvider.Immediate immediate = mc.getBufferBuilders().getEntityVertexConsumers();

        float r = 1.0f, g = 0.84f, b = 0.2f, a = 1.0f;

        try {
            for (var entry : all.entrySet()) {
                List<BlockPos> pts = entry.getValue();
                for (int i = 0; i + 1 < pts.size(); i += 2) {
                    BlockPos aPos = pts.get(i);
                    BlockPos bPos = pts.get(i + 1);
                    float ax = (float) (aPos.getX() + 0.5 - cx);
                    float ay = (float) (aPos.getY() + 0.05 - cy);
                    float az = (float) (aPos.getZ() + 0.5 - cz);
                    float bx = (float) (bPos.getX() + 0.5 - cx);
                    float by = (float) (bPos.getY() + 0.05 - cy);
                    float bz = (float) (bPos.getZ() + 0.5 - cz);

                    // Approximate the line with small debug boxes along the segment.
                    int steps = 16;
                    float dx = (bx - ax) / steps;
                    float dy = (by - ay) / steps;
                    float dz = (bz - az) / steps;
                    float half = 0.01f;
                    var buf = immediate.getBuffer(RenderLayer.getDebugFilledBox());
                    for (int s = 0; s <= steps; s++) {
                        float x = ax + dx * s;
                        float y = ay + dy * s;
                        float z = az + dz * s;
                        VertexRendering.drawFilledBox(matrices, buf,
                                x - half, y - half, z - half,
                                x + half, y + half, z + half,
                                r, g, b, a);
                    }
                }
            }
        } finally {
            immediate.draw();
        }
    }
}
