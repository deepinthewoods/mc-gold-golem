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
import org.joml.Vector3f;

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

        renderModelWithWheels(matrices, vertexConsumers, light, state.activeWheelSet, state.wheelRotation);

        matrices.pop();
    }

    private void renderModelWithWheels(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                       int light, int activeWheelSet, float wheelRotation) {
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));

        for (BBModelParser.BBElement element : model.elements) {
            String name = element.name;
            boolean isWheel = name.matches("^w[0-9].*");

            if (isWheel && !isWheelInActiveSet(name, activeWheelSet)) {
                continue;
            }

            matrices.push();
            renderElement(element, name, matrices, vertexConsumer, light, wheelRotation, isWheel);
            matrices.pop();
        }
    }

    private void renderElement(BBModelParser.BBElement element, String name, MatrixStack matrices,
                               VertexConsumer vertexConsumer, int light, float wheelRotation, boolean isWheel) {
        float px = (float) (element.origin[0] / 16.0);
        float py = (float) (element.origin[1] / 16.0);
        float pz = (float) (element.origin[2] / 16.0);

        matrices.translate(px, py, pz);

        float rx = (float) element.rotation[0];
        float ry = (float) element.rotation[1];
        float rz = (float) element.rotation[2];

        if (isWheel) {
            float additionalRotation = wheelRotation;
            if (!isWheelLayerA(name)) {
                additionalRotation += (float) Math.toRadians(45.0);
            }
            rx += Math.toDegrees(additionalRotation);
        }

        if (rx != 0) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(rx));
        if (ry != 0) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(ry));
        if (rz != 0) matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rz));

        matrices.translate(-px, -py, -pz);

        if (element.hasMeshFaces) {
            renderMeshElement(matrices, vertexConsumer, element, light);
        } else {
            float x1 = (float) (element.from[0] / 16.0);
            float y1 = (float) (element.from[1] / 16.0);
            float z1 = (float) (element.from[2] / 16.0);
            float x2 = (float) (element.to[0] / 16.0);
            float y2 = (float) (element.to[1] / 16.0);
            float z2 = (float) (element.to[2] / 16.0);

            renderCube(matrices, vertexConsumer, x1, y1, z1, x2, y2, z2, element, light);
        }
    }

    private void renderMeshElement(MatrixStack matrices, VertexConsumer vertexConsumer,
                                   BBModelParser.BBElement element, int light) {
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();

        float tw = model.textureWidth > 0 ? model.textureWidth : 64.0f;
        float th = model.textureHeight > 0 ? model.textureHeight : 64.0f;

        for (BBModelParser.BBFace face : element.orderedFaces) {
            if (!face.meshFace || face.meshVertices.size() < 3) continue;
            renderMeshFace(face, element, vertexConsumer, positionMatrix, normalMatrix, light, tw, th);
        }
    }

    private void renderMeshFace(BBModelParser.BBFace face, BBModelParser.BBElement element,
                                VertexConsumer vertexConsumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                                int light, float tw, float th) {
        int vertexCount = face.meshVertices.size();
        if (vertexCount < 3) return;

        float[][] positions = new float[vertexCount][3];
        float[][] uvs = new float[vertexCount][2];

        for (int i = 0; i < vertexCount; i++) {
            BBModelParser.FaceVertex faceVertex = face.meshVertices.get(i);
            double[] vertexPosition = element.vertices.get(faceVertex.vertexKey);
            if (vertexPosition == null) {
                return;
            }

            positions[i][0] = (float) (vertexPosition[0] / 16.0);
            positions[i][1] = (float) (vertexPosition[1] / 16.0);
            positions[i][2] = (float) (vertexPosition[2] / 16.0);

            if (faceVertex.hasUv) {
                uvs[i][0] = (float) (faceVertex.uv[0] / tw);
                uvs[i][1] = (float) (faceVertex.uv[1] / th);
            } else {
                uvs[i][0] = (float) (face.uv[0] / tw);
                uvs[i][1] = (float) (face.uv[1] / th);
            }
        }

        float[] normal = calculateNormal(positions[0], positions[1], positions[2]);

        for (int i = 1; i < vertexCount - 1; i++) {
            emitTriangle(vertexConsumer, positionMatrix, normalMatrix,
                    positions[0], uvs[0],
                    positions[i], uvs[i],
                    positions[i + 1], uvs[i + 1],
                    normal[0], normal[1], normal[2], light);
        }
    }

    private float[] calculateNormal(float[] v0, float[] v1, float[] v2) {
        float ux = v1[0] - v0[0];
        float uy = v1[1] - v0[1];
        float uz = v1[2] - v0[2];

        float vx = v2[0] - v0[0];
        float vy = v2[1] - v0[1];
        float vz = v2[2] - v0[2];

        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;

        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length == 0) {
            return new float[]{0, 1, 0};
        }
        return new float[]{nx / length, ny / length, nz / length};
    }

    private void emitTriangle(VertexConsumer vertexConsumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                              float[] v1, float[] uv1,
                              float[] v2, float[] uv2,
                              float[] v3, float[] uv3,
                              float nx, float ny, float nz, int light) {
        addVertex(vertexConsumer, positionMatrix, normalMatrix, v1[0], v1[1], v1[2], uv1[0], uv1[1], nx, ny, nz, light);
        addVertex(vertexConsumer, positionMatrix, normalMatrix, v2[0], v2[1], v2[2], uv2[0], uv2[1], nx, ny, nz, light);
        addVertex(vertexConsumer, positionMatrix, normalMatrix, v3[0], v3[1], v3[2], uv3[0], uv3[1], nx, ny, nz, light);
        addVertex(vertexConsumer, positionMatrix, normalMatrix, v2[0], v2[1], v2[2], uv2[0], uv2[1], nx, ny, nz, light);
    }

    private void renderCube(MatrixStack matrices, VertexConsumer vertexConsumer,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            BBModelParser.BBElement element, int light) {
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();

        float tw = 64.0f;
        float th = 64.0f;

        renderFaceIfExists(element, "north", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z1, x2, y2, z1, 0f, 0f, -1f, light, tw, th);
        renderFaceIfExists(element, "south", vertexConsumer, positionMatrix, normalMatrix,
                x2, y1, z2, x1, y2, z2, 0f, 0f, 1f, light, tw, th);
        renderFaceIfExists(element, "west", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z2, x1, y2, z1, -1f, 0f, 0f, light, tw, th);
        renderFaceIfExists(element, "east", vertexConsumer, positionMatrix, normalMatrix,
                x2, y1, z1, x2, y2, z2, 1f, 0f, 0f, light, tw, th);
        renderFaceIfExists(element, "down", vertexConsumer, positionMatrix, normalMatrix,
                x1, y1, z1, x2, y1, z2, 0f, -1f, 0f, light, tw, th);
        renderFaceIfExists(element, "up", vertexConsumer, positionMatrix, normalMatrix,
                x1, y2, z2, x2, y2, z1, 0f, 1f, 0f, light, tw, th);
    }

    private void renderFaceIfExists(BBModelParser.BBElement element, String direction,
                                    VertexConsumer vertexConsumer, Matrix4f positionMatrix, Matrix3f normalMatrix,
                                    float x1, float y1, float z1, float x2, float y2, float z2,
                                    float nx, float ny, float nz, int light, float tw, float th) {
        BBModelParser.BBFace face = element.faces.get(direction);
        if (face == null) return;

        if (face.uv == null || face.uv.length < 4) return;

        float u1 = (float) (face.uv[0] / tw);
        float v1 = (float) (face.uv[1] / th);
        float u2 = (float) (face.uv[2] / tw);
        float v2 = (float) (face.uv[3] / th);

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
                           float x, float y, float z, float u, float v, float nx, float ny, float nz, int light) {
        Vector3f normal = new Vector3f(nx, ny, nz);
        normal.mul(normalMatrix);
        vertexConsumer.vertex(positionMatrix, x, y, z)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(0)
                .light(light)
                .normal(normal.x(), normal.y(), normal.z());
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
        return true;
    }

    private boolean isWheelLayerA(String boneName) {
        Matcher matcher = WHEEL_PATTERN.matcher(boneName);
        if (matcher.matches()) {
            return "a".equals(matcher.group(4));
        }
        return true;
    }
}
