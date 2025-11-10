package ninja.trek.mc.goldgolem.client.model;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import ninja.trek.mc.goldgolem.GoldGolem;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the Blockbench-authored Assimp mesh from resources and keeps it cached for the renderer.
 */
public final class GoldGolemModelLoader implements SimpleSynchronousResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier FABRIC_ID = GoldGolem.id("goldgolem_model_reload");
    private static final Identifier MODEL_ID = GoldGolem.id("models/entity/goldgolem.gltf");
    private static final int ASSIMP_FLAGS = Assimp.aiProcess_Triangulate
            | Assimp.aiProcess_JoinIdenticalVertices
            | Assimp.aiProcess_GenSmoothNormals
            | Assimp.aiProcess_OptimizeMeshes
            | Assimp.aiProcess_FlipUVs;

    private static volatile List<MeshPart> meshes = Collections.emptyList();

    private GoldGolemModelLoader() {}

    public static void init() {
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new GoldGolemModelLoader());
    }

    public static List<MeshPart> getMeshes() {
        return meshes;
    }

    @Override
    public Identifier getFabricId() {
        return FABRIC_ID;
    }

    @Override
    public void reload(ResourceManager manager) {
        meshes = loadMeshes(manager);
    }

    private static List<MeshPart> loadMeshes(ResourceManager manager) {
        try {
            Resource resource = manager.getResource(MODEL_ID)
                    .orElseThrow(() -> new FileNotFoundException("Missing model: " + MODEL_ID));
            try (InputStream stream = resource.getInputStream()) {
                byte[] bytes = stream.readAllBytes();
                ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
                try {
                    buffer.put(bytes).flip();
                    AIScene scene = Assimp.aiImportFileFromMemory(buffer, ASSIMP_FLAGS, "gltf");
                    if (scene == null) {
                        LOGGER.error("Assimp failed to read {}: {}", MODEL_ID, Assimp.aiGetErrorString());
                        return Collections.emptyList();
                    }

                    try {
                        List<MeshPart> baked = new ArrayList<>();
                        traverse(scene.mRootNode(), new Matrix4f().identity(), scene, baked);
                        return Collections.unmodifiableList(baked);
                    } finally {
                        Assimp.aiReleaseImport(scene);
                    }
                } finally {
                    MemoryUtil.memFree(buffer);
                }
            }
        } catch (IOException ex) {
            LOGGER.error("Failed to load {} from resources", MODEL_ID, ex);
            return Collections.emptyList();
        }
    }

    private static void traverse(AINode node, Matrix4f parentTransform, AIScene scene, List<MeshPart> out) {
        Matrix4f nodeTransform = new Matrix4f(parentTransform).mul(toMatrix(node.mTransformation()));

        IntBuffer meshIndices = node.mMeshes();
        if (meshIndices != null) {
            for (int i = 0; i < node.mNumMeshes(); i++) {
                int meshIndex = meshIndices.get(i);
                AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                out.add(bakeMesh(mesh, nodeTransform));
            }
        }

        var children = node.mChildren();
        if (children == null) {
            return;
        }
        for (int i = 0; i < node.mNumChildren(); i++) {
            traverse(AINode.create(children.get(i)), nodeTransform, scene, out);
        }
    }

    private static MeshPart bakeMesh(AIMesh mesh, Matrix4f transform) {
        int vertexCount = mesh.mNumVertices();
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        Matrix3f normalMatrix = transform.normal(new Matrix3f());
        AIVector3D.Buffer vertexBuffer = mesh.mVertices();
        AIVector3D.Buffer normalBuffer = mesh.mNormals();
        AIVector3D.Buffer texBuffer = mesh.mTextureCoords(0);

        for (int i = 0; i < vertexCount; i++) {
            AIVector3D vertex = vertexBuffer.get(i);
            Vector4f pos = new Vector4f(vertex.x(), vertex.y(), vertex.z(), 1.0f).mul(transform);
            positions[i * 3] = pos.x();
            positions[i * 3 + 1] = pos.y();
            positions[i * 3 + 2] = pos.z();

            Vector3f normal = new Vector3f();
            if (normalBuffer != null) {
                AIVector3D n = normalBuffer.get(i);
                normal.set(n.x(), n.y(), n.z());
            } else {
                normal.set(0.0f, 1.0f, 0.0f);
            }
            normal.mul(normalMatrix).normalize();
            normals[i * 3] = normal.x();
            normals[i * 3 + 1] = normal.y();
            normals[i * 3 + 2] = normal.z();

            if (texBuffer != null) {
                AIVector3D uv = texBuffer.get(i);
                uvs[i * 2] = uv.x();
                uvs[i * 2 + 1] = uv.y();
            } else {
                uvs[i * 2] = 0.0f;
                uvs[i * 2 + 1] = 0.0f;
            }
        }

        int faceCount = mesh.mNumFaces();
        AIFace.Buffer faces = mesh.mFaces();
        int triangleIndexCount = 0;
        for (int i = 0; i < faceCount; i++) {
            int verticesInFace = faces.get(i).mNumIndices();
            if (verticesInFace >= 3) {
                triangleIndexCount += (verticesInFace - 2) * 3;
            }
        }

        int[] indices = new int[triangleIndexCount];
        int cursor = 0;
        for (int i = 0; i < faceCount; i++) {
            AIFace face = faces.get(i);
            IntBuffer faceIndices = face.mIndices();
            int verticesInFace = face.mNumIndices();
            if (verticesInFace < 3) {
                continue;
            }

            int root = faceIndices.get(0);
            for (int j = 1; j < verticesInFace - 1; j++) {
                indices[cursor++] = root;
                indices[cursor++] = faceIndices.get(j);
                indices[cursor++] = faceIndices.get(j + 1);
            }
        }

        return new MeshPart(positions, normals, uvs, indices);
    }

    private static Matrix4f toMatrix(AIMatrix4x4 source) {
        // Assimp stores matrices in row-major order, so transpose to match JOML/OpenGL column-major layout.
        return new Matrix4f(
                source.a1(), source.a2(), source.a3(), source.a4(),
                source.b1(), source.b2(), source.b3(), source.b4(),
                source.c1(), source.c2(), source.c3(), source.c4(),
                source.d1(), source.d2(), source.d3(), source.d4()
        ).transpose();
    }

    public record MeshPart(float[] positions, float[] normals, float[] uvs, int[] indices) {}
}
