package ninja.trek.mc.goldgolem.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.client.state.ClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// removed unused imports from older pipeline

import java.util.List;
import java.util.Map;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    private static int goldgolem$dbgFrame = 0;

    @Inject(method = "pushEntityRenders", at = @At("TAIL"))
    private void goldgolem$renderLines(MatrixStack matrices,
                                       net.minecraft.client.render.state.WorldRenderState renderStates,
                                       net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                                       CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return;

        boolean holding = mc.player.getMainHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET)
                || mc.player.getOffHandStack().isOf(net.minecraft.item.Items.GOLD_NUGGET);
        if (!holding) return;

        Map<Integer, ClientState.LineData> all = ClientState.getAllLineData();
        if (all.isEmpty()) return;

        // Colors
        // queued (dark orange)
        final int qR = 179, qG = 98, qB = 0, qA = 255;
        // current (dark green)
        final int cR = 34, cG = 139, cB = 34, cA = 255;
        // preview (gray)
        final int pR = 140, pG = 140, pB = 140, pA = 255;

        // Submit per-segment custom draw commands using the world render command queue.
        if ((goldgolem$dbgFrame++ % 20) == 0) {
            int segCount = 0;
            for (var e : all.entrySet()) {
                List<Vec3d> pts = e.getValue().points;
                segCount += (pts == null) ? 0 : (pts.size() / 2);
            }
            System.out.println("[GoldGolem][Render] submitting segments=" + segCount + " holdingNugget=true");
        }
        // Camera-relative coordinates: subtract camera pos for world-space lines
        var camPos = renderStates.cameraRenderState.pos;
        final float cx = (float) camPos.x;
        final float cy = (float) camPos.y;
        final float cz = (float) camPos.z;

        for (var e : all.entrySet()) {
            final int entityId = e.getKey();
            ClientState.LineData data = e.getValue();
            List<Vec3d> pts = data.points;
            // Determine which segment is "current": choose the segment whose midpoint is closest to the entity
            int currentIdx = -1;
            double bestDistSq = Double.MAX_VALUE;
            var world = mc.world;
            if (world != null) {
                var ent = world.getEntityById(entityId);
                if (ent != null) {
                    double ex = ent.getX();
                    double ey = ent.getY();
                    double ez = ent.getZ();
                    for (int i = 0; i + 1 < pts.size(); i += 2) {
                        Vec3d av = pts.get(i);
                        Vec3d bv = pts.get(i + 1);
                        double mx = (av.x + bv.x) * 0.5;
                        double my = (av.y + bv.y) * 0.5;
                        double mz = (av.z + bv.z) * 0.5;
                        double dx = mx - ex;
                        double dy = my - ey;
                        double dz = mz - ez;
                        double d2 = dx*dx + dy*dy + dz*dz;
                        if (d2 < bestDistSq) { bestDistSq = d2; currentIdx = i; }
                    }
                }
            }
            // Use outline-like layer so lines draw on top of terrain
            final RenderLayer lineLayer = RenderLayer.getSecondaryBlockOutline();
            for (int i = 0; i + 1 < pts.size(); i += 2) {
                Vec3d av = pts.get(i);
                Vec3d bv = pts.get(i + 1);
                final float ax = (float) (av.x);
                final float ay = (float) (av.y + 0.01); // nudge above surface to avoid z-fight
                final float az = (float) (av.z);
                final float bx = (float) (bv.x);
                final float by = (float) (bv.y + 0.01);
                final float bz = (float) (bv.z);

                if ((goldgolem$dbgFrame % 60) == 0) {
                    System.out.println("[GoldGolem][Render] seg[" + i + "] A=("+ax+","+ay+","+az+") B=("+bx+","+by+","+bz+")");
                }
                var batching = queue.getBatchingQueue(1000);
                // Use standard line layer for 1.21.10
                final RenderLayer layer = lineLayer;
                final boolean isCurrent = (i == currentIdx);
                final int rr = isCurrent ? cR : qR;
                final int gg = isCurrent ? cG : qG;
                final int bb = isCurrent ? cB : qB;
                final int aa = isCurrent ? cA : qA;
                batching.submitCustom(matrices, layer, (entry, vc) -> {
                    int light = 0x00F000F0; // fullbright (sky/block mix)
                    vc.vertex(entry, ax - cx, ay - cy, az - cz)
                      .color(rr, gg, bb, aa)
                      .normal(entry, 0.0f, 1.0f, 0.0f)
                      .light(light);
                    vc.vertex(entry, bx - cx, by - cy, bz - cz)
                      .color(rr, gg, bb, aa)
                      .normal(entry, 0.0f, 1.0f, 0.0f)
                      .light(light);
                });
            }

            // Preview: from last endpoint (or anchor) to the player
            if (mc.player != null) {
                float pxw = (float) mc.player.getX();
                float pyw = (float) mc.player.getY();
                float pzw = (float) mc.player.getZ();
                float sxw, syw, szw;
                if (pts.size() >= 2) {
                    Vec3d last = pts.get(pts.size() - 1);
                    sxw = (float) (last.x);
                    syw = (float) (last.y);
                    szw = (float) (last.z);
                } else {
                    if (data.anchor.isPresent()) {
                        Vec3d a = data.anchor.get();
                        sxw = (float) a.x;
                        syw = (float) a.y;
                        szw = (float) a.z;
                    } else {
                        // As a last resort, use player feet
                        sxw = pxw;
                        syw = pyw + 0.05f;
                        szw = pzw;
                    }
                }
                var batchingPrev = queue.getBatchingQueue(1000);
                batchingPrev.submitCustom(matrices, lineLayer, (entry, vc) -> {
                    int light = 0x00F000F0;
                    vc.vertex(entry, sxw - cx, (syw + 0.01f) - cy, szw - cz)
                      .color(pR, pG, pB, pA)
                      .normal(entry, 0.0f, 1.0f, 0.0f)
                      .light(light);
                    vc.vertex(entry, pxw - cx, (pyw + 0.01f) - cy, pzw - cz)
                      .color(pR, pG, pB, pA)
                      .normal(entry, 0.0f, 1.0f, 0.0f)
                      .light(light);
                });
            }
        }
    }
}
