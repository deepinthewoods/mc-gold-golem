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

    // Relative rotation speeds for each wheel set (inversely proportional to wheel diameter)
    // Calculated on first render based on actual mesh extents
    private static float[] wheelSpeedMultipliers = null;

    public GoldGolemEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
    }

    public static class GoldGolemRenderState extends EntityRenderState {
        public double wheelRotation;
        public int activeWheelSet;
        public float bodyYaw;
        public float pitch;
        public float yaw;
    }

    @Override
    public GoldGolemRenderState createRenderState() {
        return new GoldGolemRenderState();
    }

    @Override
    public void updateRenderState(GoldGolemEntity entity, GoldGolemRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.wheelRotation = entity.getWheelRotation();
        // Map BuildMode to wheel set: PATH -> 0, WALL -> 1
        state.activeWheelSet = (entity.getBuildMode() == GoldGolemEntity.BuildMode.WALL) ? 1 : 0;
        state.bodyYaw = entity.getBodyYaw();
        state.pitch = entity.getPitch();
        state.yaw = entity.getYaw();
    }

    /**
     * Calculate eye rotation based on look direction relative to body.
     * Returns rotation in degrees around the Z-axis in 90-degree increments.
     * Rotation values produce:
     *   0°: down+right
     *   90°: up+right
     *   180°: up+left
     *   270°: down+left
     *
     * Uses quadrant-based selection: finds which of the 4 eye orientations
     * has a direction closest to the actual look direction.
     */
    private static float calculateEyeRotation(float headYaw, float bodyYaw, float pitch) {
        // Calculate head rotation relative to body
        float relativeYaw = headYaw - bodyYaw;

        // Convert look direction to a normalized vector in the golem's local space
        // In Minecraft: yaw 0 = south (-Z), 90 = west (-X), 180 = north (+Z), 270 = east (+X)
        // Pitch: negative = up, positive = down
        float yawRad = (float) Math.toRadians(-relativeYaw); // Negate for correct rotation direction
        float pitchRad = (float) Math.toRadians(pitch);

        float cosPitch = (float) Math.cos(pitchRad);
        float lookX = -cosPitch * (float) Math.sin(yawRad);  // Right is +X
        float lookY = -(float) Math.sin(pitchRad);           // Up is +Y, down is -Y
        float lookZ = cosPitch * (float) Math.cos(yawRad);   // Forward is +Z (in local space)

        // Define the 4 eye base directions (normalized diagonal directions)
        // Each represents the center of a quadrant
        float sqrt3 = (float) (1.0 / Math.sqrt(3.0));

        // 0°: down+right (right, down, forward)
        float[] dir0 = {sqrt3, -sqrt3, sqrt3};

        // 90°: up+right (right, up, forward)
        float[] dir90 = {sqrt3, sqrt3, sqrt3};

        // 180°: up+left (left, up, forward)
        float[] dir180 = {-sqrt3, sqrt3, sqrt3};

        // 270°: down+left (left, down, forward)
        float[] dir270 = {-sqrt3, -sqrt3, sqrt3};

        // Calculate dot products to find closest match
        float dot0 = lookX * dir0[0] + lookY * dir0[1] + lookZ * dir0[2];
        float dot90 = lookX * dir90[0] + lookY * dir90[1] + lookZ * dir90[2];
        float dot180 = lookX * dir180[0] + lookY * dir180[1] + lookZ * dir180[2];
        float dot270 = lookX * dir270[0] + lookY * dir270[1] + lookZ * dir270[2];

        // Find the rotation with maximum dot product (closest direction)
        float maxDot = dot0;
        float rotation = 0.0f;

        if (dot90 > maxDot) {
            maxDot = dot90;
            rotation = 90.0f;
        }
        if (dot180 > maxDot) {
            maxDot = dot180;
            rotation = 180.0f;
        }
        if (dot270 > maxDot) {
            rotation = 270.0f;
        }

        return rotation;
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

        // Calculate wheel speed multipliers on first render
        if (wheelSpeedMultipliers == null) {
            wheelSpeedMultipliers = calculateWheelSpeedMultipliers(meshParts);
        }

        matrices.push();
        matrices.translate(0.0f, 0.0f, 0.0f);
        // Rotate the entire mesh based on body yaw (movement direction)
        // Additional 180° rotation to face the correct direction
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(360.0f - state.bodyYaw));

        var layer = GOLD_GOLEM_TRIANGLES_LAYER;
        int overlay = OverlayTexture.DEFAULT_UV;
        int light = state.light;

        for (GoldGolemModelLoader.MeshPart mesh : meshParts) {
            String meshName = mesh.name();

            // Check if this is an eye mesh
            boolean isEyeMesh = meshName != null && meshName.toLowerCase().contains("eye");

            // Check if this is a wheel mesh
            WheelInfo wheelInfo = parseWheelName(meshName);
            if (wheelInfo != null) {
                // Skip wheels not in the active set
                if (wheelInfo.setIndex != state.activeWheelSet) {
                    continue;
                }

                // Apply wheel rotation around pivot point
                // Matrix operations are post-multiplied, so they apply right-to-left:
                // This creates: T(+pivot) * R * T(-pivot), which gives R*(v - pivot) + pivot
                matrices.push();

                matrices.translate(mesh.pivotX(), mesh.pivotY(), mesh.pivotZ());

                // Apply the speed multiplier for this wheel set
                float speedMultiplier = (wheelSpeedMultipliers != null && wheelInfo.setIndex < wheelSpeedMultipliers.length)
                        ? wheelSpeedMultipliers[wheelInfo.setIndex]
                        : 1.0f;
                float rotationDegrees = (float) Math.toDegrees(state.wheelRotation * speedMultiplier);
                if (wheelInfo.part == 'b') {
                    rotationDegrees += 45.0f; // 45° offset for part 'b'
                }
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rotationDegrees));

                matrices.translate(-mesh.pivotX(), -mesh.pivotY(), -mesh.pivotZ());

                renderMesh(matrices, queue, layer, mesh, overlay, light);
                matrices.pop();
            } else if (isEyeMesh) {
                // Eye mesh: apply z-axis rotation based on look direction
                matrices.push();

                matrices.translate(mesh.pivotX(), mesh.pivotY(), mesh.pivotZ());

                // Calculate and apply eye rotation (90-degree increments)
                float eyeRotation = calculateEyeRotation(state.yaw, state.bodyYaw, state.pitch);
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(eyeRotation));

                matrices.translate(-mesh.pivotX(), -mesh.pivotY(), -mesh.pivotZ());

                renderMesh(matrices, queue, layer, mesh, overlay, light);
                matrices.pop();
            } else {
                // Non-wheel, non-eye mesh: render normally
                renderMesh(matrices, queue, layer, mesh, overlay, light);
            }
        }

        matrices.pop();
        super.render(state, matrices, queue, cameraState);
    }

    private void renderMesh(MatrixStack matrices, OrderedRenderCommandQueue queue, RenderLayer layer,
                           GoldGolemModelLoader.MeshPart mesh, int overlay, int light) {
        queue.submitCustom(matrices, layer, (entry, consumer) -> {
            int[] indices = mesh.indices();
            if (indices.length < 3) return;
            for (int i = 0; i <= indices.length - 3; i += 3) {
                emitVertex(entry, consumer, mesh, indices[i], overlay, light);
                emitVertex(entry, consumer, mesh, indices[i + 1], overlay, light);
                emitVertex(entry, consumer, mesh, indices[i + 2], overlay, light);
            }
        });
    }

    /**
     * Calculate relative rotation speed multipliers for each wheel set.
     * Smaller wheels rotate faster to cover the same ground distance.
     * Formula: angular_velocity = linear_velocity / radius
     */
    private static float[] calculateWheelSpeedMultipliers(java.util.List<GoldGolemModelLoader.MeshPart> meshParts) {
        // Map to store the average diameter for each wheel set
        java.util.Map<Integer, java.util.List<Float>> wheelSetDiameters = new java.util.HashMap<>();

        // Scan all meshes to find wheels and collect their diameters
        for (GoldGolemModelLoader.MeshPart mesh : meshParts) {
            WheelInfo wheelInfo = parseWheelName(mesh.name());
            if (wheelInfo != null) {
                wheelSetDiameters.computeIfAbsent(wheelInfo.setIndex, k -> new java.util.ArrayList<>())
                        .add(mesh.getDiameter());
            }
        }

        if (wheelSetDiameters.isEmpty()) {
            return new float[] { 1.0f };
        }

        // Calculate average diameter for each set
        int maxSetIndex = wheelSetDiameters.keySet().stream().max(Integer::compareTo).orElse(0);
        float[] avgDiameters = new float[maxSetIndex + 1];

        for (int i = 0; i <= maxSetIndex; i++) {
            java.util.List<Float> diameters = wheelSetDiameters.get(i);
            if (diameters != null && !diameters.isEmpty()) {
                avgDiameters[i] = (float) diameters.stream().mapToDouble(Float::doubleValue).average().orElse(1.0);
            } else {
                avgDiameters[i] = 1.0f;
            }
        }

        // Use the first wheel set as reference (speed multiplier = 1.0)
        float referenceDiameter = avgDiameters[0];
        float[] multipliers = new float[maxSetIndex + 1];

        for (int i = 0; i <= maxSetIndex; i++) {
            // Smaller wheels need higher multipliers to rotate faster
            multipliers[i] = referenceDiameter / avgDiameters[i];
        }

        return multipliers;
    }

    private static class WheelInfo {
        int setIndex;
        char side;      // 'r' or 'l'
        int wheelIndex; // 0, 1, etc.
        char part;      // 'a' or 'b'

        WheelInfo(int setIndex, char side, int wheelIndex, char part) {
            this.setIndex = setIndex;
            this.side = side;
            this.wheelIndex = wheelIndex;
            this.part = part;
        }
    }

    private static WheelInfo parseWheelName(String name) {
        // Format: w{setIndex}{side}{wheelIndex}{part}
        // Example: w0r0a, w1l0b, w2r1a
        if (name == null || !name.startsWith("w") || name.length() < 5) {
            return null;
        }

        try {
            int setIndex = Character.getNumericValue(name.charAt(1));
            char side = name.charAt(2);
            int wheelIndex = Character.getNumericValue(name.charAt(3));
            char part = name.charAt(4);

            if (setIndex < 0 || (side != 'r' && side != 'l') || wheelIndex < 0 ||
                (part != 'a' && part != 'b')) {
                return null;
            }

            return new WheelInfo(setIndex, side, wheelIndex, part);
        } catch (Exception e) {
            return null;
        }
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
