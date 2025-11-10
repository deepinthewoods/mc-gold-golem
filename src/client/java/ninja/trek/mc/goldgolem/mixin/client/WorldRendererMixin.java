package ninja.trek.mc.goldgolem.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
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

    // Find the surface Y (top of a solid full-cube block) near the given y0 for column (bx, bz)
    private Double goldgolem$findSurfaceY(ClientWorld world, int bx, int bz, int y0) {
        Integer groundY = null;
        for (int yy = y0 + 3; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isFullCube(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return null;
        return groundY + 1.0; // surface is one above the solid block
    }

    // Build a stepped polyline that follows block edges in XZ and stays on top of solid blocks; insert verticals at height changes.
    private java.util.List<Vec3d> goldgolem$buildSteppedPath(ClientWorld world, Vec3d a, Vec3d b) {
        java.util.ArrayList<Vec3d> verts = new java.util.ArrayList<>();
        double vx = b.x - a.x;
        double vz = b.z - a.z;
        double lenXZ = Math.hypot(vx, vz);
        int x = net.minecraft.util.math.MathHelper.floor(a.x);
        int z = net.minecraft.util.math.MathHelper.floor(a.z);
        int tx = net.minecraft.util.math.MathHelper.floor(b.x);
        int tz = net.minecraft.util.math.MathHelper.floor(b.z);

        if (lenXZ < 1e-6) {
            // Degenerate in XZ: just add start and end projected to their columns if surfaces exist
            Double s0 = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.math.MathHelper.floor(a.y));
            if (s0 != null) verts.add(new Vec3d(a.x, s0 + 0.01, a.z));
            Double s1 = goldgolem$findSurfaceY(world, tx, tz, net.minecraft.util.math.MathHelper.floor(b.y));
            if (s1 != null) {
                double y1 = s1 + 0.01;
                if (!verts.isEmpty()) {
                    Vec3d last = verts.get(verts.size() - 1);
                    if (Math.abs(last.y - y1) > 1e-3) {
                        verts.add(new Vec3d(last.x, y1, last.z));
                    }
                }
                verts.add(new Vec3d(b.x, y1, b.z));
            }
            return verts;
        }

        int stepX = vx > 0 ? 1 : (vx < 0 ? -1 : 0);
        int stepZ = vz > 0 ? 1 : (vz < 0 ? -1 : 0);
        double invVx = vx != 0.0 ? 1.0 / vx : Double.POSITIVE_INFINITY;
        double invVz = vz != 0.0 ? 1.0 / vz : Double.POSITIVE_INFINITY;

        double nextGridX = stepX > 0 ? (x + 1) : x;
        double nextGridZ = stepZ > 0 ? (z + 1) : z;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (nextGridX - a.x) * invVx;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (nextGridZ - a.z) * invVz;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(invVx);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(invVz);

        // Seed with starting surface
        Double lastY = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.math.MathHelper.floor(a.y));
        if (lastY != null) {
            double y0 = lastY + 0.01;
            verts.add(new Vec3d(a.x, y0, a.z));
            lastY = y0;
        }

        double t = 0.0;
        // Traverse cells until reaching target cell
        while (x != tx || z != tz) {
            boolean stepInX = tMaxX < tMaxZ;
            if (stepInX) {
                t = tMaxX;
                tMaxX += tDeltaX;
                x += stepX;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
            if (t > 1.0) t = 1.0; // clamp
            double posX = a.x + vx * t;
            double posZ = a.z + vz * t;
            double yGuess = a.y + (b.y - a.y) * t;
            Double surf = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.math.MathHelper.floor(yGuess));
            if (surf == null) {
                lastY = null; // break the run over gaps
                continue;
            }
            double yHere = surf + 0.01;
            if (lastY == null) {
                verts.add(new Vec3d(posX, yHere, posZ));
                lastY = yHere;
            } else {
                if (Math.abs(yHere - lastY) > 1e-3) {
                    // vertical at the intersection point
                    verts.add(new Vec3d(posX, lastY, posZ));
                    verts.add(new Vec3d(posX, yHere, posZ));
                } else {
                    verts.add(new Vec3d(posX, yHere, posZ));
                }
                lastY = yHere;
            }
        }

        // Ensure endpoint at B
        Double endSurf = goldgolem$findSurfaceY(world, tx, tz, net.minecraft.util.math.MathHelper.floor(b.y));
        if (endSurf != null) {
            double yEnd = endSurf + 0.01;
            if (!verts.isEmpty()) {
                Vec3d last = verts.get(verts.size() - 1);
                if (Math.abs(last.y - yEnd) > 1e-3) {
                    verts.add(new Vec3d(b.x, last.y, b.z));
                }
            }
            verts.add(new Vec3d(b.x, yEnd, b.z));
        }
        return verts;
    }

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
        // look direction (blue)
        final int lR = 0, lG = 100, lB = 255, lA = 255;
        // left eye (cyan)
        final int leR = 0, leG = 255, leB = 255, leA = 255;
        // right eye (magenta)
        final int reR = 255, reG = 0, reB = 255, reA = 255;

        // Submit per-segment custom draw commands using the world render command queue.
        // removed periodic console logging
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
            var cworld = mc.world;
            if (cworld != null) {
                var ent = cworld.getEntityById(entityId);
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
                if (cworld == null) continue;
                java.util.List<Vec3d> poly = goldgolem$buildSteppedPath((ClientWorld) cworld, av, bv);
                if (poly.size() < 2) continue;
                final boolean isCurrent = (i == currentIdx);
                final int rr = isCurrent ? cR : qR;
                final int gg = isCurrent ? cG : qG;
                final int bb = isCurrent ? cB : qB;
                final int aa = isCurrent ? cA : qA;
                final RenderLayer layer = lineLayer;
                var batching = queue.getBatchingQueue(1000);
                // emit each segment in the polyline
                for (int k = 0; k + 1 < poly.size(); k++) {
                    Vec3d p0 = poly.get(k);
                    Vec3d p1 = poly.get(k + 1);
                    final float ax = (float) p0.x;
                    final float ay = (float) p0.y;
                    final float az = (float) p0.z;
                    final float bx = (float) p1.x;
                    final float by = (float) p1.y;
                    final float bz = (float) p1.z;
                    batching.submitCustom(matrices, layer, (entry, vc) -> {
                        int light = 0x00F000F0;
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

                // If this is the current segment, also outline the blocks across its full path width
                if (isCurrent) {
                    // Determine width from entity if available (falls back to 3)
                    int width = 3;
                    int half = 1;
                    if (cworld != null) {
                        var ent = cworld.getEntityById(entityId);
                        if (ent instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity ge) {
                            int w = Math.max(1, Math.min(9, ge.getPathWidth()));
                            // Snap odd like server logic
                            if ((w & 1) == 0) w = (w < 9) ? (w + 1) : (w - 1);
                            width = w;
                            half = (width - 1) / 2;
                        }
                    }
                    // Compute supercover cells between av and bv (XZ only)
                    int x0 = net.minecraft.util.math.MathHelper.floor(av.x);
                    int z0 = net.minecraft.util.math.MathHelper.floor(av.z);
                    int x1 = net.minecraft.util.math.MathHelper.floor(bv.x);
                    int z1 = net.minecraft.util.math.MathHelper.floor(bv.z);
                    int dx = Math.abs(x1 - x0);
                    int dz = Math.abs(z1 - z0);
                    int sx = (x0 < x1) ? 1 : -1;
                    int sz = (z0 < z1) ? 1 : -1;
                    int err = dx - dz;
                    int cx0 = x0;
                    int cz0 = z0;
                    // Helper to submit a unit-cube outline at (bx, bz) with given color
                    java.util.function.Consumer<int[]> emitAt = (pos) -> {
                        int bxw = pos[0];
                        int bzw = pos[1];
                        int cr = pos[2];
                        int cg = pos[3];
                        int cb = pos[4];
                        int ca = pos[5];
                        Double surf = goldgolem$findSurfaceY((ClientWorld) cworld, bxw, bzw,
                                net.minecraft.util.math.MathHelper.floor((av.y + bv.y) * 0.5));
                        if (surf == null) return;
                        float y0w = (float) (surf.doubleValue() - 1.0);
                        float y1w = (float) (surf.doubleValue());
                        float xw0 = bxw;
                        float zw0 = bzw;
                        float xw1 = bxw + 1.0f;
                        float zw1 = bzw + 1.0f;
                        var bq = queue.getBatchingQueue(1000);
                        bq.submitCustom(matrices, lineLayer, (entry, vc) -> {
                            int light = 0x00F000F0;
                            // bottom rectangle
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            // top rectangle
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            // verticals
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw0 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw1 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y0w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                            vc.vertex(entry, xw0 - cx, y1w - cy, zw1 - cz).color(cr, cg, cb, ca).normal(entry, 0, 1, 0).light(light);
                        });
                    };

                    // Walk supercover cells and emit outlines
                    boolean xMajor = Math.abs(bv.x - av.x) >= Math.abs(bv.z - av.z);
                    // Colors: center white, others dark grey
                    final int cwR = 255, cwG = 255, cwB = 255, cwA = 255;
                    final int gwR = 90, gwG = 90, gwB = 90, gwA = 255;

                    // helper to emit a full-width column centered at (cx0,cz0)
                    final int halfF = half;
                    final boolean xMajorF = xMajor;
                    java.util.function.BiConsumer<Integer, Integer> emitFullWidth = (gxw, gzw) -> {
                        for (int j = -halfF; j <= halfF; j++) {
                            int bxw = xMajorF ? gxw : (gxw + j);
                            int bzw = xMajorF ? (gzw + j) : gzw;
                            boolean center = (j == 0);
                            emitAt.accept(new int[]{bxw, bzw, center ? cwR : gwR, center ? cwG : gwG, center ? cwB : gwB, center ? cwA : gwA});
                        }
                    };

                    emitFullWidth.accept(cx0, cz0);
                    while (cx0 != x1 || cz0 != z1) {
                        int e2 = err << 1;
                        if (e2 > -dz) { err -= dz; cx0 += sx; emitFullWidth.accept(cx0, cz0); }
                        if (e2 <  dx) { err += dx; cz0 += sz; emitFullWidth.accept(cx0, cz0); }
                    }
                }
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
                if (cworld != null) {
                    java.util.List<Vec3d> poly = goldgolem$buildSteppedPath((ClientWorld) cworld,
                            new Vec3d(sxw, syw, szw), new Vec3d(pxw, pyw, pzw));
                    if (poly.size() >= 2) {
                        // Compute cumulative lengths for per-vertex fade in the last 1m
                        double totalLen = 0.0;
                        double[] cum = new double[poly.size()];
                        cum[0] = 0.0;
                        for (int j = 1; j < poly.size(); j++) {
                            double d = poly.get(j).distanceTo(poly.get(j - 1));
                            totalLen += d;
                            cum[j] = totalLen;
                        }
                        var batchingPrev = queue.getBatchingQueue(1000);
                        final int baseR = pR, baseG = pG, baseB = pB;
                        for (int j = 0; j + 1 < poly.size(); j++) {
                            Vec3d p0 = poly.get(j);
                            Vec3d p1 = poly.get(j + 1);
                            double f0 = Math.max(0.0, Math.min(1.0, (cum[j] - Math.max(0.0, totalLen - 1.0)) / 1.0));
                            double f1 = Math.max(0.0, Math.min(1.0, (cum[j + 1] - Math.max(0.0, totalLen - 1.0)) / 1.0));
                            int c0r = (int) Math.round(baseR + (255 - baseR) * f0);
                            int c0g = (int) Math.round(baseG + (255 - baseG) * f0);
                            int c0b = (int) Math.round(baseB + (255 - baseB) * f0);
                            int c1r = (int) Math.round(baseR + (255 - baseR) * f1);
                            int c1g = (int) Math.round(baseG + (255 - baseG) * f1);
                            int c1b = (int) Math.round(baseB + (255 - baseB) * f1);
                            final float ax = (float) p0.x;
                            final float ay = (float) p0.y;
                            final float az = (float) p0.z;
                            final float bx = (float) p1.x;
                            final float by = (float) p1.y;
                            final float bz = (float) p1.z;
                            final int cr0r = c0r, cr0g = c0g, cr0b = c0b;
                            final int cr1r = c1r, cr1g = c1g, cr1b = c1b;
                            batchingPrev.submitCustom(matrices, lineLayer, (entry, vc) -> {
                                int light = 0x00F000F0;
                                vc.vertex(entry, ax - cx, ay - cy, az - cz)
                                  .color(cr0r, cr0g, cr0b, pA)
                                  .normal(entry, 0.0f, 1.0f, 0.0f)
                                  .light(light);
                                vc.vertex(entry, bx - cx, by - cy, bz - cz)
                                  .color(cr1r, cr1g, cr1b, pA)
                                  .normal(entry, 0.0f, 1.0f, 0.0f)
                                  .light(light);
                            });
                        }
                    }
                }
            }

            // Render look direction lines for the golem
            if (cworld != null) {
                var ent = cworld.getEntityById(entityId);
                if (ent instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
                    float gx = (float) golem.getX();
                    float gy = (float) golem.getY() + 0.75f; // Roughly head height
                    float gz = (float) golem.getZ();

                    // Main look direction (blue)
                    float lookYaw = golem.getYaw();
                    float lookPitch = golem.getPitch();
                    Vec3d lookDir = goldgolem$getLookVector(lookYaw, lookPitch, 2.0);
                    float lookEndX = gx + (float) lookDir.x;
                    float lookEndY = gy + (float) lookDir.y;
                    float lookEndZ = gz + (float) lookDir.z;

                    var batchingLook = queue.getBatchingQueue(1000);
                    batchingLook.submitCustom(matrices, RenderLayer.getSecondaryBlockOutline(), (entry, vc) -> {
                        int light = 0x00F000F0;
                        vc.vertex(entry, gx - cx, gy - cy, gz - cz)
                          .color(lR, lG, lB, lA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                        vc.vertex(entry, lookEndX - cx, lookEndY - cy, lookEndZ - cz)
                          .color(lR, lG, lB, lA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                    });

                    // Left eye direction (cyan) - offset slightly to the left
                    float eyeOffsetX = 0.2f;
                    float leftEyeX = gx - eyeOffsetX;
                    float leftEyeY = gy;
                    float leftEyeZ = gz;
                    float leftEyeYaw = golem.getLeftEyeYaw();
                    float leftEyePitch = golem.getLeftEyePitch();
                    Vec3d leftEyeDir = goldgolem$getLookVector(leftEyeYaw, leftEyePitch, 1.5);
                    float leftEyeEndX = leftEyeX + (float) leftEyeDir.x;
                    float leftEyeEndY = leftEyeY + (float) leftEyeDir.y;
                    float leftEyeEndZ = leftEyeZ + (float) leftEyeDir.z;

                    var batchingLeftEye = queue.getBatchingQueue(1000);
                    batchingLeftEye.submitCustom(matrices, RenderLayer.getSecondaryBlockOutline(), (entry, vc) -> {
                        int light = 0x00F000F0;
                        vc.vertex(entry, leftEyeX - cx, leftEyeY - cy, leftEyeZ - cz)
                          .color(leR, leG, leB, leA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                        vc.vertex(entry, leftEyeEndX - cx, leftEyeEndY - cy, leftEyeEndZ - cz)
                          .color(leR, leG, leB, leA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                    });

                    // Right eye direction (magenta) - offset slightly to the right
                    float rightEyeX = gx + eyeOffsetX;
                    float rightEyeY = gy;
                    float rightEyeZ = gz;
                    float rightEyeYaw = golem.getRightEyeYaw();
                    float rightEyePitch = golem.getRightEyePitch();
                    Vec3d rightEyeDir = goldgolem$getLookVector(rightEyeYaw, rightEyePitch, 1.5);
                    float rightEyeEndX = rightEyeX + (float) rightEyeDir.x;
                    float rightEyeEndY = rightEyeY + (float) rightEyeDir.y;
                    float rightEyeEndZ = rightEyeZ + (float) rightEyeDir.z;

                    var batchingRightEye = queue.getBatchingQueue(1000);
                    batchingRightEye.submitCustom(matrices, RenderLayer.getSecondaryBlockOutline(), (entry, vc) -> {
                        int light = 0x00F000F0;
                        vc.vertex(entry, rightEyeX - cx, rightEyeY - cy, rightEyeZ - cz)
                          .color(reR, reG, reB, reA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                        vc.vertex(entry, rightEyeEndX - cx, rightEyeEndY - cy, rightEyeEndZ - cz)
                          .color(reR, reG, reB, reA)
                          .normal(entry, 0.0f, 1.0f, 0.0f)
                          .light(light);
                    });
                }
            }
        }
    }

    /**
     * Convert yaw and pitch to a look vector with the given length
     */
    private Vec3d goldgolem$getLookVector(float yaw, float pitch, double length) {
        float yawRad = (float) Math.toRadians(-yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRad);
        double x = -cosPitch * Math.sin(yawRad) * length;
        double y = -Math.sin(pitchRad) * length;
        double z = cosPitch * Math.cos(yawRad) * length;
        return new Vec3d(x, y, z);
    }
}
