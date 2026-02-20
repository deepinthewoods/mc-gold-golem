package ninja.trek.mc.goldgolem.mixin.client;

import ninja.trek.mc.goldgolem.client.state.ClientState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;

// removed unused imports from older pipeline

import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin {
    private static int goldgolem$dbgFrame = 0;

    // Find the surface Y (top of a solid full-cube block) near the given y0 for column (bx, bz)
    private Double goldgolem$findSurfaceY(ClientLevel world, int bx, int bz, int y0) {
        Integer groundY = null;
        for (int yy = y0 + 3; yy >= y0 - 8; yy--) {
            BlockPos test = new BlockPos(bx, yy, bz);
            var st = world.getBlockState(test);
            if (!st.isAir() && st.isCollisionShapeFullBlock(world, test)) { groundY = yy; break; }
        }
        if (groundY == null) return null;
        return groundY + 1.0; // surface is one above the solid block
    }

    // Build a stepped polyline that follows block edges in XZ and stays on top of solid blocks; insert verticals at height changes.
    private java.util.List<Vec3> goldgolem$buildSteppedPath(ClientLevel world, Vec3 a, Vec3 b) {
        java.util.ArrayList<Vec3> verts = new java.util.ArrayList<>();
        double vx = b.x - a.x;
        double vz = b.z - a.z;
        double lenXZ = Math.hypot(vx, vz);
        int x = net.minecraft.util.Mth.floor(a.x);
        int z = net.minecraft.util.Mth.floor(a.z);
        int tx = net.minecraft.util.Mth.floor(b.x);
        int tz = net.minecraft.util.Mth.floor(b.z);

        if (lenXZ < 1e-6) {
            // Degenerate in XZ: just add start and end projected to their columns if surfaces exist
            Double s0 = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.Mth.floor(a.y));
            if (s0 != null) verts.add(new Vec3(a.x, s0 + 0.01, a.z));
            Double s1 = goldgolem$findSurfaceY(world, tx, tz, net.minecraft.util.Mth.floor(b.y));
            if (s1 != null) {
                double y1 = s1 + 0.01;
                if (!verts.isEmpty()) {
                    Vec3 last = verts.get(verts.size() - 1);
                    if (Math.abs(last.y - y1) > 1e-3) {
                        verts.add(new Vec3(last.x, y1, last.z));
                    }
                }
                verts.add(new Vec3(b.x, y1, b.z));
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
        Double lastY = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.Mth.floor(a.y));
        if (lastY != null) {
            double y0 = lastY + 0.01;
            verts.add(new Vec3(a.x, y0, a.z));
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
            Double surf = goldgolem$findSurfaceY(world, x, z, net.minecraft.util.Mth.floor(yGuess));
            if (surf == null) {
                lastY = null; // break the run over gaps
                continue;
            }
            double yHere = surf + 0.01;
            if (lastY == null) {
                verts.add(new Vec3(posX, yHere, posZ));
                lastY = yHere;
            } else {
                if (Math.abs(yHere - lastY) > 1e-3) {
                    // vertical at the intersection point
                    verts.add(new Vec3(posX, lastY, posZ));
                    verts.add(new Vec3(posX, yHere, posZ));
                } else {
                    verts.add(new Vec3(posX, yHere, posZ));
                }
                lastY = yHere;
            }
        }

        // Ensure endpoint at B
        Double endSurf = goldgolem$findSurfaceY(world, tx, tz, net.minecraft.util.Mth.floor(b.y));
        if (endSurf != null) {
            double yEnd = endSurf + 0.01;
            if (!verts.isEmpty()) {
                Vec3 last = verts.get(verts.size() - 1);
                if (Math.abs(last.y - yEnd) > 1e-3) {
                    verts.add(new Vec3(b.x, last.y, b.z));
                }
            }
            verts.add(new Vec3(b.x, yEnd, b.z));
        }
        return verts;
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void goldgolem$renderLines(PoseStack matrices,
                                       net.minecraft.client.renderer.state.LevelRenderState renderStates,
                                       net.minecraft.client.renderer.SubmitNodeCollector queue,
                                       CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) return;

        boolean holding = mc.player.getMainHandItem().is(net.minecraft.world.item.Items.GOLD_NUGGET)
                || mc.player.getOffhandItem().is(net.minecraft.world.item.Items.GOLD_NUGGET);
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
            List<Vec3> pts = data.points;
            // Determine which segment is "current": choose the segment whose midpoint is closest to the entity
            int currentIdx = -1;
            double bestDistSq = Double.MAX_VALUE;
            var cworld = mc.level;
            if (cworld != null) {
                var ent = cworld.getEntity(entityId);
                if (ent != null) {
                    double ex = ent.getX();
                    double ey = ent.getY();
                    double ez = ent.getZ();
                    for (int i = 0; i + 1 < pts.size(); i += 2) {
                        Vec3 av = pts.get(i);
                        Vec3 bv = pts.get(i + 1);
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
            final RenderType lineLayer = RenderTypes.secondaryBlockOutline();
            for (int i = 0; i + 1 < pts.size(); i += 2) {
                Vec3 av = pts.get(i);
                Vec3 bv = pts.get(i + 1);
                if (cworld == null) continue;
                final boolean isCurrent = (i == currentIdx);
                final int rr = isCurrent ? cR : qR;
                final int gg = isCurrent ? cG : qG;
                final int bb = isCurrent ? cB : qB;
                final int aa = isCurrent ? cA : qA;
                final RenderType layer = lineLayer;
                // Straight line between module endpoints (gold block positions)
                var batching = queue.order(1000);
                final float ax = (float) av.x;
                final float ay = (float) av.y;
                final float az = (float) av.z;
                final float bx = (float) bv.x;
                final float by = (float) bv.y;
                final float bz = (float) bv.z;
                batching.submitCustomGeometry(matrices, layer, (entry, vc) -> {
                    vc.addVertex(entry, ax - cx, ay - cy, az - cz)
                      .setColor(rr, gg, bb, aa)
                      .setNormal(entry, 0.0f, 1.0f, 0.0f)
                      .setLineWidth(1.0f);
                    vc.addVertex(entry, bx - cx, by - cy, bz - cz)
                      .setColor(rr, gg, bb, aa)
                      .setNormal(entry, 0.0f, 1.0f, 0.0f)
                      .setLineWidth(1.0f);
                });

                // If this is the current segment, also outline the blocks across its full path width
                if (isCurrent) {
                    // Determine width from entity if available (falls back to 3)
                    int width = 3;
                    int half = 1;
                    if (cworld != null) {
                        var ent = cworld.getEntity(entityId);
                        if (ent instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity ge) {
                            int w = Math.max(1, Math.min(9, ge.getPathWidth()));
                            // Snap odd like server logic
                            if ((w & 1) == 0) w = (w < 9) ? (w + 1) : (w - 1);
                            width = w;
                            half = (width - 1) / 2;
                        }
                    }
                    // Compute supercover cells between av and bv (XZ only)
                    int x0 = net.minecraft.util.Mth.floor(av.x);
                    int z0 = net.minecraft.util.Mth.floor(av.z);
                    int x1 = net.minecraft.util.Mth.floor(bv.x);
                    int z1 = net.minecraft.util.Mth.floor(bv.z);
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
                        Double surf = goldgolem$findSurfaceY((ClientLevel) cworld, bxw, bzw,
                                net.minecraft.util.Mth.floor((av.y + bv.y) * 0.5));
                        if (surf == null) return;
                        float y0w = (float) (surf.doubleValue() - 1.0);
                        float y1w = (float) (surf.doubleValue());
                        float xw0 = bxw;
                        float zw0 = bzw;
                        float xw1 = bxw + 1.0f;
                        float zw1 = bzw + 1.0f;
                        var bq = queue.order(1000);
                        bq.submitCustomGeometry(matrices, lineLayer, (entry, vc) -> {
                            // bottom rectangle
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            // top rectangle
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            // verticals
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw0 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw1 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y0w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
                            vc.addVertex(entry, xw0 - cx, y1w - cy, zw1 - cz).setColor(cr, cg, cb, ca).setNormal(entry, 0, 1, 0).setLineWidth(1.0f);
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
                    Vec3 last = pts.get(pts.size() - 1);
                    sxw = (float) (last.x);
                    syw = (float) (last.y);
                    szw = (float) (last.z);
                } else {
                    if (data.anchor.isPresent()) {
                        Vec3 a = data.anchor.get();
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
                // Straight preview line from last module end to player
                {
                    final int baseR = data.noValid ? 220 : pR;
                    final int baseG = data.noValid ? 40 : pG;
                    final int baseB = data.noValid ? 40 : pB;
                    var batchingPrev = queue.order(1000);
                    final float fsx = sxw, fsy = syw, fsz = szw;
                    final float fpx = pxw, fpy = pyw, fpz = pzw;
                    batchingPrev.submitCustomGeometry(matrices, lineLayer, (entry, vc) -> {
                        vc.addVertex(entry, fsx - cx, fsy - cy, fsz - cz)
                          .setColor(baseR, baseG, baseB, pA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                        vc.addVertex(entry, fpx - cx, fpy - cy, fpz - cz)
                          .setColor(255, 255, 255, pA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                    });
                }
            }

            // Render look direction lines for the golem
            if (cworld != null) {
                var ent = cworld.getEntity(entityId);
                if (ent instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
                    float gx = (float) golem.getX();
                    float gy = (float) golem.getY() + 0.75f; // Roughly head height
                    float gz = (float) golem.getZ();

                    // Main look direction (blue)
                    float lookYaw = golem.getYRot();
                    float lookPitch = golem.getXRot();
                    Vec3 lookDir = goldgolem$getLookVector(lookYaw, lookPitch, 2.0);
                    float lookEndX = gx + (float) lookDir.x;
                    float lookEndY = gy + (float) lookDir.y;
                    float lookEndZ = gz + (float) lookDir.z;

                    var batchingLook = queue.order(1000);
                    batchingLook.submitCustomGeometry(matrices, RenderTypes.secondaryBlockOutline(), (entry, vc) -> {
                        vc.addVertex(entry, gx - cx, gy - cy, gz - cz)
                          .setColor(lR, lG, lB, lA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                        vc.addVertex(entry, lookEndX - cx, lookEndY - cy, lookEndZ - cz)
                          .setColor(lR, lG, lB, lA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                    });

                    // Left eye direction (cyan) - offset slightly to the left
                    float eyeOffsetX = 0.2f;
                    float leftEyeX = gx - eyeOffsetX;
                    float leftEyeY = gy;
                    float leftEyeZ = gz;
                    float leftEyeYaw = golem.getLeftEyeYaw();
                    float leftEyePitch = golem.getLeftEyePitch();
                    Vec3 leftEyeDir = goldgolem$getLookVector(leftEyeYaw, leftEyePitch, 1.5);
                    float leftEyeEndX = leftEyeX + (float) leftEyeDir.x;
                    float leftEyeEndY = leftEyeY + (float) leftEyeDir.y;
                    float leftEyeEndZ = leftEyeZ + (float) leftEyeDir.z;

                    var batchingLeftEye = queue.order(1000);
                    batchingLeftEye.submitCustomGeometry(matrices, RenderTypes.secondaryBlockOutline(), (entry, vc) -> {
                        vc.addVertex(entry, leftEyeX - cx, leftEyeY - cy, leftEyeZ - cz)
                          .setColor(leR, leG, leB, leA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                        vc.addVertex(entry, leftEyeEndX - cx, leftEyeEndY - cy, leftEyeEndZ - cz)
                          .setColor(leR, leG, leB, leA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                    });

                    // Right eye direction (magenta) - offset slightly to the right
                    float rightEyeX = gx + eyeOffsetX;
                    float rightEyeY = gy;
                    float rightEyeZ = gz;
                    float rightEyeYaw = golem.getRightEyeYaw();
                    float rightEyePitch = golem.getRightEyePitch();
                    Vec3 rightEyeDir = goldgolem$getLookVector(rightEyeYaw, rightEyePitch, 1.5);
                    float rightEyeEndX = rightEyeX + (float) rightEyeDir.x;
                    float rightEyeEndY = rightEyeY + (float) rightEyeDir.y;
                    float rightEyeEndZ = rightEyeZ + (float) rightEyeDir.z;

                    var batchingRightEye = queue.order(1000);
                    batchingRightEye.submitCustomGeometry(matrices, RenderTypes.secondaryBlockOutline(), (entry, vc) -> {
                        vc.addVertex(entry, rightEyeX - cx, rightEyeY - cy, rightEyeZ - cz)
                          .setColor(reR, reG, reB, reA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                        vc.addVertex(entry, rightEyeEndX - cx, rightEyeEndY - cy, rightEyeEndZ - cz)
                          .setColor(reR, reG, reB, reA)
                          .setNormal(entry, 0.0f, 1.0f, 0.0f)
                          .setLineWidth(1.0f);
                    });
                }
            }
        }
    }

    /**
     * Convert yaw and pitch to a look vector with the given length
     */
    private Vec3 goldgolem$getLookVector(float yaw, float pitch, double length) {
        float yawRad = (float) Math.toRadians(-yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRad);
        double x = -cosPitch * Math.sin(yawRad) * length;
        double y = -Math.sin(pitchRad) * length;
        double z = cosPitch * Math.cos(yawRad) * length;
        return new Vec3(x, y, z);
    }
}
