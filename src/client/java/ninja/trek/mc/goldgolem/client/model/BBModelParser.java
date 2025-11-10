package ninja.trek.mc.goldgolem.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for BlockBench .bbmodel files
 */
public class BBModelParser {
    public static class BBModel {
        public List<BBElement> elements = new ArrayList<>();
        public Map<String, BBElement> elementsByName = new HashMap<>();
        public int textureWidth;
        public int textureHeight;
    }

    public static class BBElement {
        public String name;
        public String uuid;
        public double[] origin = new double[3]; // pivot point
        public double[] rotation = new double[3];
        public double[] from = new double[3];
        public double[] to = new double[3];
        public Map<String, BBFace> faces = new HashMap<>();
        public Map<String, double[]> vertices = new HashMap<>();
        public List<BBFace> orderedFaces = new ArrayList<>();
        public boolean hasMeshFaces;
    }

    public static class BBFace {
        public String name;
        public boolean meshFace;
        public List<FaceVertex> meshVertices = new ArrayList<>();
        public double[] uv = new double[4];
        public String texture;
    }

    public static class FaceVertex {
        public String vertexKey;
        public double[] uv = new double[2];
        public boolean hasUv;
    }

    public static BBModel parse(Identifier modelId) throws Exception {
        MinecraftClient client = MinecraftClient.getInstance();
        Resource resource = client.getResourceManager().getResource(modelId)
                .orElseThrow(() -> new RuntimeException("Model not found: " + modelId));

        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream())) {
            return parse(reader);
        }
    }

    public static BBModel parse(InputStreamReader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

        BBModel model = new BBModel();

        // Parse metadata
        JsonObject meta = root.getAsJsonObject("meta");
        if (meta != null) {
            // Meta information if needed
        }

        // Parse resolution
        JsonObject resolution = root.getAsJsonObject("resolution");
        if (resolution != null) {
            model.textureWidth = resolution.get("width").getAsInt();
            model.textureHeight = resolution.get("height").getAsInt();
        } else {
            model.textureWidth = 64;
            model.textureHeight = 64;
        }

        // Parse elements
        JsonArray elements = root.getAsJsonArray("elements");
        if (elements != null) {
            for (JsonElement elem : elements) {
                BBElement element = parseElement(elem.getAsJsonObject());
                model.elements.add(element);
                if (element.name != null) {
                    model.elementsByName.put(element.name, element);
                }
            }
        }

        return model;
    }

    private static BBElement parseElement(JsonObject json) {
        BBElement element = new BBElement();

        element.name = json.has("name") ? json.get("name").getAsString() : "";
        element.uuid = json.has("uuid") ? json.get("uuid").getAsString() : "";

        if (json.has("origin")) {
            JsonArray origin = json.getAsJsonArray("origin");
            element.origin[0] = origin.get(0).getAsDouble();
            element.origin[1] = origin.get(1).getAsDouble();
            element.origin[2] = origin.get(2).getAsDouble();
        }

        if (json.has("rotation")) {
            JsonArray rotation = json.getAsJsonArray("rotation");
            element.rotation[0] = rotation.get(0).getAsDouble();
            element.rotation[1] = rotation.get(1).getAsDouble();
            element.rotation[2] = rotation.get(2).getAsDouble();
        }

        if (json.has("from")) {
            JsonArray from = json.getAsJsonArray("from");
            element.from[0] = from.get(0).getAsDouble();
            element.from[1] = from.get(1).getAsDouble();
            element.from[2] = from.get(2).getAsDouble();
        }

        if (json.has("to")) {
            JsonArray to = json.getAsJsonArray("to");
            element.to[0] = to.get(0).getAsDouble();
            element.to[1] = to.get(1).getAsDouble();
            element.to[2] = to.get(2).getAsDouble();
        }

        if (json.has("vertices")) {
            JsonObject vertices = json.getAsJsonObject("vertices");
            for (Map.Entry<String, JsonElement> entry : vertices.entrySet()) {
                JsonArray vertex = entry.getValue().getAsJsonArray();
                element.vertices.put(entry.getKey(), new double[]{
                        vertex.get(0).getAsDouble(),
                        vertex.get(1).getAsDouble(),
                        vertex.get(2).getAsDouble()
                });
            }
        }

        if (json.has("faces")) {
            JsonObject faces = json.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
                String faceId = entry.getKey();
                BBFace face = parseFace(faceId, entry.getValue().getAsJsonObject());
                element.faces.put(faceId, face);
                element.orderedFaces.add(face);
                if (face.meshFace) {
                    element.hasMeshFaces = true;
                }
            }
        }

        return element;
    }

    private static BBFace parseFace(String faceId, JsonObject json) {
        BBFace face = new BBFace();
        face.name = faceId;

        if (json.has("texture")) {
            JsonElement textureElement = json.get("texture");
            if (textureElement.isJsonPrimitive()) {
                if (textureElement.getAsJsonPrimitive().isNumber()) {
                    face.texture = String.valueOf(textureElement.getAsInt());
                } else {
                    face.texture = textureElement.getAsString();
                }
            }
        }

        JsonElement verticesElement = json.get("vertices");
        if (verticesElement != null && verticesElement.isJsonArray()) {
            face.meshFace = true;
            JsonArray vertexOrder = verticesElement.getAsJsonArray();
            JsonObject uvObject = json.has("uv") && json.get("uv").isJsonObject()
                    ? json.getAsJsonObject("uv")
                    : null;

            for (JsonElement vertexKeyElement : vertexOrder) {
                FaceVertex faceVertex = new FaceVertex();
                String vertexId = vertexKeyElement.getAsString();
                faceVertex.vertexKey = vertexId;

                if (uvObject != null && uvObject.has(vertexId)) {
                    JsonArray vertexUv = uvObject.getAsJsonArray(vertexId);
                    faceVertex.uv[0] = vertexUv.get(0).getAsDouble();
                    faceVertex.uv[1] = vertexUv.get(1).getAsDouble();
                    faceVertex.hasUv = true;
                }

                face.meshVertices.add(faceVertex);
            }

            if (uvObject != null && !uvObject.entrySet().isEmpty()) {
                double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE;
                double maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;

                for (Map.Entry<String, JsonElement> entry : uvObject.entrySet()) {
                    JsonArray vertexUv = entry.getValue().getAsJsonArray();
                    double u = vertexUv.get(0).getAsDouble();
                    double v = vertexUv.get(1).getAsDouble();

                    minU = Math.min(minU, u);
                    minV = Math.min(minV, v);
                    maxU = Math.max(maxU, u);
                    maxV = Math.max(maxV, v);
                }

                face.uv[0] = minU;
                face.uv[1] = minV;
                face.uv[2] = maxU;
                face.uv[3] = maxV;
            }
        } else if (json.has("uv")) {
            JsonElement uvElement = json.get("uv");

            if (uvElement.isJsonArray()) {
                JsonArray uv = uvElement.getAsJsonArray();
                face.uv[0] = uv.get(0).getAsDouble();
                face.uv[1] = uv.get(1).getAsDouble();
                face.uv[2] = uv.get(2).getAsDouble();
                face.uv[3] = uv.get(3).getAsDouble();
            } else if (uvElement.isJsonObject()) {
                JsonObject uvObj = uvElement.getAsJsonObject();
                double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE;
                double maxU = -Double.MAX_VALUE, maxV = -Double.MAX_VALUE;

                for (String key : uvObj.keySet()) {
                    JsonArray vertexUv = uvObj.getAsJsonArray(key);
                    double u = vertexUv.get(0).getAsDouble();
                    double v = vertexUv.get(1).getAsDouble();

                    minU = Math.min(minU, u);
                    minV = Math.min(minV, v);
                    maxU = Math.max(maxU, u);
                    maxV = Math.max(maxV, v);
                }

                face.uv[0] = minU;
                face.uv[1] = minV;
                face.uv[2] = maxU;
                face.uv[3] = maxV;
            }
        }

        return face;
    }
}
