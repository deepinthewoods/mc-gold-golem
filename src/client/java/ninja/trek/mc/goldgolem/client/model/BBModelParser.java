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
    }

    public static class BBFace {
        public double[] uv = new double[4];
        public String texture;
    }

    public static BBModel parse(Identifier modelId) throws Exception {
        MinecraftClient client = MinecraftClient.getInstance();
        Resource resource = client.getResourceManager().getResource(modelId)
                .orElseThrow(() -> new RuntimeException("Model not found: " + modelId));

        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream())) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }

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

        if (json.has("faces")) {
            JsonObject faces = json.getAsJsonObject("faces");
            for (String direction : faces.keySet()) {
                BBFace face = parseFace(faces.getAsJsonObject(direction));
                element.faces.put(direction, face);
            }
        }

        return element;
    }

    private static BBFace parseFace(JsonObject json) {
        BBFace face = new BBFace();

        if (json.has("uv")) {
            JsonElement uvElement = json.get("uv");

            // Check if UV is an array (simple box format) or object (mesh format with per-vertex UVs)
            if (uvElement.isJsonArray()) {
                // Simple box UV format: [u1, v1, u2, v2]
                JsonArray uv = uvElement.getAsJsonArray();
                face.uv[0] = uv.get(0).getAsDouble();
                face.uv[1] = uv.get(1).getAsDouble();
                face.uv[2] = uv.get(2).getAsDouble();
                face.uv[3] = uv.get(3).getAsDouble();
            } else if (uvElement.isJsonObject()) {
                // Mesh format with per-vertex UVs: {"vertexId": [u, v], ...}
                // For simplicity, we'll extract the min/max UV coordinates from all vertices
                JsonObject uvObj = uvElement.getAsJsonObject();
                double minU = Double.MAX_VALUE, minV = Double.MAX_VALUE;
                double maxU = Double.MIN_VALUE, maxV = Double.MIN_VALUE;

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

        if (json.has("texture")) {
            JsonElement textureElement = json.get("texture");
            if (textureElement.isJsonPrimitive()) {
                face.texture = textureElement.getAsString();
            } else if (textureElement.isJsonPrimitive() && textureElement.getAsJsonPrimitive().isNumber()) {
                face.texture = String.valueOf(textureElement.getAsInt());
            }
        }

        return face;
    }
}
