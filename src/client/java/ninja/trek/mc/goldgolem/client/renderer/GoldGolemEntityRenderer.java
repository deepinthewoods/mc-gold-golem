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
import ninja.trek.mc.goldgolem.BuildMode;
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
        public float leftEyeYaw;
        public float leftEyePitch;
        public float rightEyeYaw;
        public float rightEyePitch;
        public float leftArmRotation;
        public float rightArmRotation;
        public net.minecraft.item.ItemStack leftHandItem = net.minecraft.item.ItemStack.EMPTY;
        public net.minecraft.item.ItemStack rightHandItem = net.minecraft.item.ItemStack.EMPTY;
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
        state.activeWheelSet = (entity.getBuildMode() == BuildMode.WALL) ? 1 : 0;
        state.bodyYaw = entity.getBodyYaw();
        state.pitch = entity.getPitch();
        state.yaw = entity.getYaw();
        state.leftEyeYaw = entity.getLeftEyeYaw();
        state.leftEyePitch = entity.getLeftEyePitch();
        state.rightEyeYaw = entity.getRightEyeYaw();
        state.rightEyePitch = entity.getRightEyePitch();
        state.leftArmRotation = entity.getLeftArmRotation();
        state.rightArmRotation = entity.getRightArmRotation();
        state.leftHandItem = entity.getLeftHandItem();
        state.rightHandItem = entity.getRightHandItem();
    }

    /**
     * Result of eye rotation calculation including both Y-axis and Z-axis rotations.
     */
    private static class EyeRotation {
        float yRotation;  // Y-axis rotation: 0° (forward) or ±90° (sideways)
        float zRotation;  // Z-axis rotation: 0°, 90°, 180°, or 270° (quadrant selection)

        EyeRotation(float yRotation, float zRotation) {
            this.yRotation = yRotation;
            this.zRotation = zRotation;
        }
    }

    /**
     * Calculate eye rotation for chameleon-style eyes based on look direction.
     *
     * Eyes are positioned at outer edges of the head. Each eye has 8 possible directions:
     * - 4 directions when facing forward (Y=0°, Z selects quadrant)
     * - 4 directions when facing sideways (Y=±90°, Z selects quadrant)
     *
     * The eye texture is designed as a "+" pattern with 4 quadrants.
     * Z-rotation selects which quadrant to display:
     *   0°: down+right, 90°: down+left, 180°: up+left, 270°: up+right
     *
     * For sideways viewing, the entire eye rotates 90° outward on Y-axis first,
     * then Z-rotation selects the appropriate quadrant in that orientation.
     *
     * @param isLeftEye true if this is the left eye, false for right eye
     * @param eyeYaw the eye's independent yaw (relative to head/body)
     * @param eyePitch the eye's independent pitch
     */
    private static EyeRotation calculateEyeRotation(boolean isLeftEye, float eyeYaw, float eyePitch) {
        // Convert look direction to a normalized vector in the golem's local space
        // In Minecraft: yaw 0 = south (-Z), 90 = west (-X), 180 = north (+Z), 270 = east (+X)
        // Pitch: negative = up, positive = down
        float yawRad = (float) Math.toRadians(-eyeYaw);
        float pitchRad = (float) Math.toRadians(eyePitch);

        float cosPitch = (float) Math.cos(pitchRad);
        float lookX = -cosPitch * (float) Math.sin(yawRad);  // Right is +X
        float lookY = -(float) Math.sin(pitchRad);           // Up is +Y, down is -Y
        float lookZ = cosPitch * (float) Math.cos(yawRad);   // Forward is +Z

        // Determine if we should use forward or sideways orientation
        // For left eye: sideways means looking left (negative X)
        // For right eye: sideways means looking right (positive X)
        boolean useSidewaysOrientation;
        if (isLeftEye) {
            // Left eye: use sideways if looking more left than forward
            useSidewaysOrientation = (lookX < 0) && (Math.abs(lookX) > Math.abs(lookZ));
        } else {
            // Right eye: use sideways if looking more right than forward
            useSidewaysOrientation = (lookX > 0) && (Math.abs(lookX) > Math.abs(lookZ));
        }

        float yRotation;
        float adjustedLookX, adjustedLookY, adjustedLookZ;

        if (useSidewaysOrientation) {
            // Rotate the look vector by ±90° around Y to get it into forward-facing space
            // Left eye: rotate by -90° (looking left becomes looking forward)
            // Right eye: rotate by +90° (looking right becomes looking forward)
            if (isLeftEye) {
                yRotation = -90.0f;  // Eye points left
                // Rotate look vector by -90° around Y: (x, y, z) -> (z, y, -x)
                adjustedLookX = lookZ;
                adjustedLookY = lookY;
                adjustedLookZ = -lookX;
            } else {
                yRotation = 90.0f;  // Eye points right
                // Rotate look vector by +90° around Y: (x, y, z) -> (-z, y, x)
                adjustedLookX = -lookZ;
                adjustedLookY = lookY;
                adjustedLookZ = lookX;
            }
        } else {
            // Forward-facing orientation
            yRotation = 0.0f;
            adjustedLookX = lookX;
            adjustedLookY = lookY;
            adjustedLookZ = lookZ;
        }

        // Now calculate Z-rotation for quadrant selection using the adjusted look vector
        // Define the 4 quadrant directions (same as before)
        float sqrt3 = (float) (1.0 / Math.sqrt(3.0));

        // 0°: down+right (right, down, forward)
        float[] dir0 = {sqrt3, -sqrt3, sqrt3};
        // 90°: down+left (left, down, forward)
        float[] dir90 = {-sqrt3, -sqrt3, sqrt3};
        // 180°: up+left (left, up, forward)
        float[] dir180 = {-sqrt3, sqrt3, sqrt3};
        // 270°: up+right (right, up, forward)
        float[] dir270 = {sqrt3, sqrt3, sqrt3};

        // Calculate dot products with adjusted look vector
        float dot0 = adjustedLookX * dir0[0] + adjustedLookY * dir0[1] + adjustedLookZ * dir0[2];
        float dot90 = adjustedLookX * dir90[0] + adjustedLookY * dir90[1] + adjustedLookZ * dir90[2];
        float dot180 = adjustedLookX * dir180[0] + adjustedLookY * dir180[1] + adjustedLookZ * dir180[2];
        float dot270 = adjustedLookX * dir270[0] + adjustedLookY * dir270[1] + adjustedLookZ * dir270[2];

        // Find the Z-rotation with maximum dot product
        float maxDot = dot0;
        float zRotation = 0.0f;

        if (dot90 > maxDot) {
            maxDot = dot90;
            zRotation = 90.0f;
        }
        if (dot180 > maxDot) {
            maxDot = dot180;
            zRotation = 180.0f;
        }
        if (dot270 > maxDot) {
            zRotation = 270.0f;
        }

        return new EyeRotation(yRotation, zRotation);
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

        // Calculate head rotation relative to body (based on look direction)
        float headYawRotation = state.yaw - state.bodyYaw;
        float headPitchRotation = state.pitch;

        // Find head pivot for proper eye parenting
        float headPivotX = 0.0f, headPivotY = 12.0f, headPivotZ = 0.0f;
        for (GoldGolemModelLoader.MeshPart m : meshParts) {
            if (m.name() != null && m.name().toLowerCase().contains("head")) {
                headPivotX = m.pivotX();
                headPivotY = m.pivotY();
                headPivotZ = m.pivotZ();
                //System.out.println("Head pivot found: [" + headPivotX + ", " + headPivotY + ", " + headPivotZ + "]");
                break;
            }
        }
        //System.out.println("Using head pivot: [" + headPivotX + ", " + headPivotY + ", " + headPivotZ + "]");

        for (GoldGolemModelLoader.MeshPart mesh : meshParts) {
            String meshName = mesh.name();

            // Check if this is a head mesh
            boolean isHeadMesh = meshName != null && meshName.toLowerCase().contains("head");

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
                // Eye mesh: parent to head, then apply independent eye rotation
                matrices.push();

                // Determine if this is left or right eye based on pivot X position
                // Negative X = left side, Positive X = right side
                boolean isLeftEye = mesh.pivotX() < 0;

//                System.out.println((isLeftEye ? "Left" : "Right") + " eye pivot: ["
//                    + mesh.pivotX() + ", " + mesh.pivotY() + ", " + mesh.pivotZ() + "]");

                // STEP 1: Apply head rotation around head's pivot (this moves the eye with the head)
                matrices.translate(headPivotX, headPivotY, headPivotZ);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(headYawRotation));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(headPitchRotation));
                matrices.translate(-headPivotX, -headPivotY, -headPivotZ);

                // STEP 2: Apply eye's own rotation around its own pivot
                matrices.translate(mesh.pivotX(), mesh.pivotY(), mesh.pivotZ());

                // Get independent eye look direction
                float eyeYaw = isLeftEye ? state.leftEyeYaw : state.rightEyeYaw;
                float eyePitch = isLeftEye ? state.leftEyePitch : state.rightEyePitch;

                // Calculate eye rotation (both Y and Z axes) based on independent look direction
                EyeRotation eyeRotation = calculateEyeRotation(isLeftEye, eyeYaw, eyePitch);

                // Apply Y-rotation first (0° for forward, ±90° for sideways)
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(eyeRotation.yRotation));

                // Then apply Z-rotation for quadrant selection
                matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(eyeRotation.zRotation));

                matrices.translate(-mesh.pivotX(), -mesh.pivotY(), -mesh.pivotZ());

                renderMesh(matrices, queue, layer, mesh, overlay, light);
                matrices.pop();
            } else if (meshName != null && meshName.toLowerCase().contains("arm")) {
                // Arm mesh: apply swing rotation
                matrices.push();

                matrices.translate(mesh.pivotX(), mesh.pivotY(), mesh.pivotZ());

                // Determine if this is left or right arm based on mesh name
                boolean isLeftArm = meshName.toLowerCase().contains("arm_l");
                float armRotation = isLeftArm ? state.leftArmRotation : state.rightArmRotation;

                // Apply rotation around X-axis (forward/backward swing)
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(armRotation));

                matrices.translate(-mesh.pivotX(), -mesh.pivotY(), -mesh.pivotZ());

                renderMesh(matrices, queue, layer, mesh, overlay, light);

                // TODO: Item rendering - needs implementation for MC 1.21.10
                // The API has changed significantly:
                // - ModelTransformationMode is now ItemDisplayContext
                // - Item rendering now uses a different system with ItemModelManager
                // For now, just log when hands should have items
                net.minecraft.item.ItemStack handItem = isLeftArm ? state.leftHandItem : state.rightHandItem;
                if (!handItem.isEmpty()) {
                    System.out.println("Should render " + (isLeftArm ? "left" : "right") + " hand item: " + handItem.getItem().toString());
                    System.out.println("  Arm rotation: " + armRotation);
                    System.out.println("  Mesh pivot: [" + mesh.pivotX() + ", " + mesh.pivotY() + ", " + mesh.pivotZ() + "]");
                }

                matrices.pop();
            } else if (isHeadMesh) {
                // Head mesh: rotate based on look direction
                matrices.push();

                matrices.translate(mesh.pivotX(), mesh.pivotY(), mesh.pivotZ());

                // Apply head rotation based on look direction
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(headYawRotation));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(headPitchRotation));

                matrices.translate(-mesh.pivotX(), -mesh.pivotY(), -mesh.pivotZ());

                renderMesh(matrices, queue, layer, mesh, overlay, light);
                matrices.pop();
            } else {
                // Non-wheel, non-eye, non-head mesh: render normally
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
