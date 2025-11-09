package ninja.trek.mc.goldgolem.client.renderer;

import ninja.trek.mc.goldgolem.client.model.BBModelParser;
import ninja.trek.mc.goldgolem.client.model.GoldGolemModel;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GoldGolemEntityRenderer extends EntityRenderer<GoldGolemEntity, GoldGolemEntityRenderer.GoldGolemRenderState> {
    private static final Identifier TEXTURE = Identifier.of("gold-golem", "textures/entity/goldgolem.png");
    private static final Pattern WHEEL_PATTERN = Pattern.compile("^w([0-9])([lr])([0-9]+)([ab])$");

    private BBModelParser.BBModel model;

    public GoldGolemEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.model = GoldGolemModel.getModel();
    }

    public static class GoldGolemRenderState extends EntityRenderState {
        public int activeWheelSet;
        public float wheelRotation;
    }

    @Override
    public GoldGolemRenderState createRenderState() {
        return new GoldGolemRenderState();
    }

    @Override
    public void updateRenderState(GoldGolemEntity entity, GoldGolemRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.activeWheelSet = entity.getBuildMode() == GoldGolemEntity.BuildMode.PATH ? 0 : 1;
        state.wheelRotation = (float) entity.getWheelRotation();
    }

    public void render(GoldGolemRenderState state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();

        // Render the model
        renderModelWithWheels(matrices, vertexConsumers, light, state.activeWheelSet, state.wheelRotation);

        matrices.pop();
    }

    private void renderModelWithWheels(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                       int light, int activeWheelSet, float wheelRotation) {
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));

        for (BBModelParser.BBElement element : model.elements) {
            // Check if this is a wheel element
            boolean isWheel = element.name.matches("^w[0-9].*");

            if (isWheel) {
                // Check if this wheel should be visible
                if (!isWheelInActiveSet(element.name, activeWheelSet)) {
                    continue; // Skip wheels not in active set
                }
            }

            matrices.push();

            // Apply transformations
            renderElement(element, matrices, vertexConsumer, light, wheelRotation, isWheel);

            matrices.pop();
        }
    }

    private void renderElement(BBModelParser.BBElement element, MatrixStack matrices,
                                VertexConsumer vertexConsumer, int light, float wheelRotation, boolean isWheel) {
        // Convert BlockBench coordinates to Minecraft coordinates
        // BlockBench uses: X+ = East, Y+ = Up, Z+ = South
        // Origin is the pivot point
        float px = (float) element.origin[0] / 16.0f;
        float py = (float) element.origin[1] / 16.0f;
        float pz = (float) element.origin[2] / 16.0f;

        // Translate to pivot point
        matrices.translate(px, py, pz);

        // Apply rotations (in degrees from BlockBench)
        float rx = (float) element.rotation[0];
        float ry = (float) element.rotation[1];
        float rz = (float) element.rotation[2];

        // Apply wheel rotation if this is a wheel
        if (isWheel) {
            float additionalRotation = wheelRotation;

            // Apply 45-degree offset for 'b' layer wheels
            if (!isWheelLayerA(element.name)) {
                additionalRotation += (float) Math.toRadians(45.0);
            }

            // Add the rotation around the X-axis (assuming wheels rotate around X)
            rx += Math.toDegrees(additionalRotation);
        }

        if (rx != 0) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rx));
        if (ry != 0) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ry));
        if (rz != 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rz));

        // Translate back from pivot
        matrices.translate(-px, -py, -pz);

        // Render the cube
        float x1 = (float) element.from[0] / 16.0f;
        float y1 = (float) element.from[1] / 16.0f;
        float z1 = (float) element.from[2] / 16.0f;
        float x2 = (float) element.to[0] / 16.0f;
        float y2 = (float) element.to[1] / 16.0f;
        float z2 = (float) element.to[2] / 16.0f;

        renderCube(matrices, vertexConsumer, x1, y1, z1, x2, y2, z2, element, light);
    }

    private void renderCube(MatrixStack matrices, VertexConsumer vertexConsumer,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            BBModelParser.BBElement element, int light) {
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();

        float tw = model.textureWidth;
        float th = model.textureHeight;

        // Render each face
        renderFaceIfExists(element, "north", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z1, x2, y2, z1, 0, 0, -1, light, tw, th);
        renderFaceIfExists(element, "south", vertexConsumer, positionMatrix, normalMatrix,
                x2, y1, z2, x1, y2, z2, 0, 0, 1, light, tw, th);
        renderFaceIfExists(element, "west", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z2, x1, y2, z1, -1, 0, 0, light, tw, th);
        renderFaceIfExists(element, "east", vertexConsumer, positionMatrix, normalMatrix,
                x2, y1, z1, x2, y2, z2, 1, 0, 0, light, tw, th);
        renderFaceIfExists(element, "down", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z1, x2, y1, z2, 0, -1, 0, light, tw, th);
        renderFaceIfExists(element, "up", vertexConsumer, positionMatrix, normalMatrix,
                x1, y2, z2, x2, y2, z1, 0, 1, 0, light, tw, th);
    }

    private void renderFaceIfExists(BBModelParser.BBElement element, String direction,
                                     VertexConsumer vertexConsumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                                     float x1, float y1, float z1, float x2, float y2, float z2,
                                     int nx, int ny, int nz, int light, float tw, float th) {
        BBModelParser.BBFace face = element.faces.get(direction);
        if (face == null) return;

        float u1 = (float) face.uv[0] / tw;
        float v1 = (float) face.uv[1] / th;
        float u2 = (float) face.uv[2] / tw;
        float v2 = (float) face.uv[3] / th;

        // Add vertices based on direction
        switch (direction) {
            case "north":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z1, u1, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z1, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z1, u2, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z1, u1, v1, nx, ny, nz, light);
                break;
            case "south":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z2, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z2, u2, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z2, u1, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z2, u1, v2, nx, ny, nz, light);
                break;
            case "west":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z1, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z1, u2, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z2, u1, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z2, u1, v2, nx, ny, nz, light);
                break;
            case "east":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z2, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z2, u2, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z1, u1, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z1, u1, v2, nx, ny, nz, light);
                break;
            case "down":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z1, u1, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y1, z2, u1, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z2, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y1, z1, u2, v1, nx, ny, nz, light);
                break;
            case "up":
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z2, u1, v1, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x1, y2, z1, u1, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z1, u2, v2, nx, ny, nz, light);
                addVertex(vertexConsumer, positionMatrix, normalMatrix, x2, y2, z2, u2, v1, nx, ny, nz, light);
                break;
        }
    }

    private void addVertex(VertexConsumer vertexConsumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                           float x, float y, float z, float u, float v, int nx, int ny, int nz, int light) {
        vertexConsumer.vertex(positionMatrix, x, y, z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(0)
                .light(light)
                .normal((float) nx, (float) ny, (float) nz);
    }

    protected Identifier getTexture(GoldGolemRenderState state) {
        return TEXTURE;
    }

    private boolean isWheelInActiveSet(String boneName, int activeWheelSet) {
        Matcher matcher = WHEEL_PATTERN.matcher(boneName);
        if (matcher.matches()) {
            int wheelSet = Integer.parseInt(matcher.group(1));
            return wheelSet == activeWheelSet;
        }
        return true; // Show non-wheel elements
    }

    private boolean isWheelLayerA(String boneName) {
        Matcher matcher = WHEEL_PATTERN.matcher(boneName);
        if (matcher.matches()) {
            return "a".equals(matcher.group(4));
        }
        return true;
    }
}
