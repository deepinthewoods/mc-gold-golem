package ninja.trek.mc.goldgolem.api.structure;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.room.RoomSocket;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** JSON codec for the stable public procedural-template payload. */
public final class GoldGolemTemplateCodec {
    public static final String SNAPSHOT_TEMPLATE_KEY = "publicTemplate";
    private static final int MAX_VOXELS_PER_MODULE = 16_000;
    private static final int MAX_EXTENT = 512;
    private static final int MAX_GRADIENT_GROUPS = 64;
    private static final int MAX_GRADIENT_SLOTS = 64;
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Comparator<BlockPos> POSITION_ORDER = Comparator
            .comparingInt((BlockPos position) -> position.getY())
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    private GoldGolemTemplateCodec() {}

    public static GoldGolemTemplate read(Reader reader) throws TemplateLoadException {
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new TemplateLoadException("Template root must be a JSON object");
            }
            return fromJson(parsed.getAsJsonObject());
        } catch (TemplateLoadException e) {
            throw e;
        } catch (Exception e) {
            throw new TemplateLoadException("Invalid template JSON", e);
        }
    }

    public static void write(GoldGolemTemplate template, Writer writer) throws IOException {
        PRETTY_GSON.toJson(toJson(template), writer);
    }

    public static GoldGolemTemplate fromJson(JsonObject root) throws TemplateLoadException {
        JsonObject template = root;
        if (root.has(SNAPSHOT_TEMPLATE_KEY) && root.get(SNAPSHOT_TEMPLATE_KEY).isJsonObject()) {
            template = root.getAsJsonObject(SNAPSHOT_TEMPLATE_KEY);
        }

        requireString(template, "format", GoldGolemTemplate.FORMAT_ID);
        int version = requireInt(template, "version");
        if (version < 1 || version > GoldGolemTemplate.CURRENT_FORMAT_VERSION) {
            throw new TemplateLoadException("Unsupported template version: " + version);
        }

        TemplateMode mode;
        try {
            mode = TemplateMode.valueOf(requireString(template, "mode").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TemplateLoadException("Unsupported template mode", e);
        }

        int defaultHeight = optionalInt(template, "defaultHeight", 1);
        int defaultCurvature = optionalInt(template, "defaultCurvature", 0);
        List<String> priority = readStrings(template.get("blockPriority"));
        GradientConfig gradient = template.has("gradient")
                ? readGradient(requireObject(template, "gradient")) : GradientConfig.EMPTY;
        List<WallModuleTemplate> walls = List.of();
        TowerModuleTemplate tower = null;
        List<Map<BlockPos, BlockState>> trees = List.of();
        List<RoomTemplate> rooms = List.of();
        TilingPreset preset = TilingPreset.SMALL_3x3;
        String groundBlockId = null;

        switch (mode) {
            case WALL -> walls = readWallModules(requireArray(template, "wallModules"));
            case TOWER, PYRAMID -> tower = readTowerModule(requireObject(template, "towerModule"));
            case TREE -> {
                JsonObject tree = requireObject(template, "tree");
                String presetName = requireString(tree, "tilingPreset");
                try {
                    preset = TilingPreset.valueOf(presetName);
                } catch (IllegalArgumentException e) {
                    throw new TemplateLoadException("Unknown tree tiling preset: " + presetName, e);
                }
                groundBlockId = optionalString(tree, "groundBlockId", null);
                trees = readTreeModules(requireArray(tree, "modules"));
            }
            case ROOM -> {
                if (version < 2) throw new TemplateLoadException("Room templates require format version 2");
                rooms = readRoomTemplates(requireArray(template, "rooms"));
            }
        }

        GoldGolemTemplate result = new GoldGolemTemplate(
                version,
                mode,
                walls,
                tower,
                trees,
                rooms,
                preset,
                groundBlockId,
                priority,
                gradient,
                defaultHeight,
                defaultCurvature
        );
        validate(result);
        return result;
    }

    public static JsonObject toJson(GoldGolemTemplate template) {
        JsonObject root = new JsonObject();
        root.addProperty("format", GoldGolemTemplate.FORMAT_ID);
        root.addProperty("version", GoldGolemTemplate.CURRENT_FORMAT_VERSION);
        root.addProperty("mode", template.mode().name().toLowerCase(Locale.ROOT));
        root.addProperty("defaultHeight", template.defaultHeight());
        root.addProperty("defaultCurvature", template.defaultCurvature());
        root.add("blockPriority", writeStrings(template.blockPriority()));
        if (!template.gradient().isEmpty()) {
            root.add("gradient", writeGradient(template.gradient()));
        }

        switch (template.mode()) {
            case WALL -> root.add("wallModules", writeWallModules(template.wallModules()));
            case TOWER, PYRAMID -> root.add("towerModule", writeTowerModule(template.towerModule()));
            case TREE -> {
                JsonObject tree = new JsonObject();
                tree.addProperty("tilingPreset", template.treeTilingPreset().name());
                if (template.treeGroundBlockId() != null) {
                    tree.addProperty("groundBlockId", template.treeGroundBlockId());
                }
                tree.add("modules", writeTreeModules(template.treeModules()));
                root.add("tree", tree);
            }
            case ROOM -> root.add("rooms", writeRoomTemplates(template.roomTemplates()));
        }
        return root;
    }

    private static void validate(GoldGolemTemplate template) throws TemplateLoadException {
        if (template.defaultHeight() < 1 || template.defaultHeight() > 256) {
            throw new TemplateLoadException("defaultHeight must be between 1 and 256");
        }
        if (template.defaultCurvature() < -100 || template.defaultCurvature() > 100) {
            throw new TemplateLoadException("defaultCurvature must be between -100 and 100");
        }
        for (String blockId : template.blockPriority()) {
            validateBlockId(blockId, "block priority");
        }
        switch (template.mode()) {
            case WALL -> {
                if (template.wallModules().isEmpty()) {
                    throw new TemplateLoadException("Wall template has no modules");
                }
            }
            case TOWER, PYRAMID -> {
                TowerModuleTemplate tower = template.towerModule();
                if (tower == null || tower.voxels.isEmpty() || tower.moduleHeight < 1) {
                    throw new TemplateLoadException("Tower template has no usable module");
                }
            }
            case TREE -> {
                if (template.treeModules().isEmpty()) {
                    throw new TemplateLoadException("Tree template has no modules");
                }
                if (template.treeGroundBlockId() != null) {
                    validateBlockId(template.treeGroundBlockId(), "tree ground block");
                }
            }
            case ROOM -> validateRoomTemplates(template.roomTemplates());
        }
        validateGradient(template.gradient());
    }

    private static JsonObject writeGradient(GradientConfig gradient) {
        JsonObject result = new JsonObject();
        JsonObject blockGroups = new JsonObject();
        gradient.blockGroups().forEach(blockGroups::addProperty);
        result.add("blockGroups", blockGroups);

        JsonArray groups = new JsonArray();
        for (List<String> slots : gradient.groupSlots()) groups.add(writeStrings(slots));
        result.add("groups", groups);

        JsonArray windows = new JsonArray();
        gradient.groupWindows().forEach(windows::add);
        result.add("windows", windows);

        JsonArray noiseScales = new JsonArray();
        gradient.groupNoiseScales().forEach(noiseScales::add);
        result.add("noiseScales", noiseScales);
        return result;
    }

    private static GradientConfig readGradient(JsonObject json) throws TemplateLoadException {
        JsonObject blockGroupJson = requireObject(json, "blockGroups");
        JsonArray groupsJson = requireArray(json, "groups");
        JsonArray windowsJson = requireArray(json, "windows");
        JsonArray noiseScalesJson = requireArray(json, "noiseScales");
        if (groupsJson.size() > MAX_GRADIENT_GROUPS) {
            throw new TemplateLoadException("Too many gradient groups: " + groupsJson.size());
        }
        if (windowsJson.size() != groupsJson.size() || noiseScalesJson.size() != groupsJson.size()) {
            throw new TemplateLoadException("Gradient groups, windows, and noiseScales must have equal lengths");
        }

        Map<String, Integer> blockGroups = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : blockGroupJson.entrySet()) {
            blockGroups.put(entry.getKey(), entry.getValue().getAsInt());
        }
        List<List<String>> groups = new ArrayList<>(groupsJson.size());
        List<Float> windows = new ArrayList<>(groupsJson.size());
        List<Integer> noiseScales = new ArrayList<>(groupsJson.size());
        for (int i = 0; i < groupsJson.size(); i++) {
            JsonElement groupElement = groupsJson.get(i);
            if (!groupElement.isJsonArray()) {
                throw new TemplateLoadException("Gradient group " + i + " must be an array");
            }
            JsonArray slotsJson = groupElement.getAsJsonArray();
            if (slotsJson.size() > MAX_GRADIENT_SLOTS) {
                throw new TemplateLoadException("Too many slots in gradient group " + i);
            }
            List<String> slots = new ArrayList<>(slotsJson.size());
            for (JsonElement slot : slotsJson) slots.add(slot.getAsString());
            groups.add(slots);
            windows.add(windowsJson.get(i).getAsFloat());
            noiseScales.add(noiseScalesJson.get(i).getAsInt());
        }
        GradientConfig result = new GradientConfig(blockGroups, groups, windows, noiseScales);
        validateGradient(result);
        return result;
    }

    private static void validateGradient(GradientConfig gradient) throws TemplateLoadException {
        int groupCount = gradient.groupSlots().size();
        if (groupCount > MAX_GRADIENT_GROUPS) {
            throw new TemplateLoadException("Too many gradient groups: " + groupCount);
        }
        if (gradient.groupWindows().size() != groupCount
                || gradient.groupNoiseScales().size() != groupCount) {
            throw new TemplateLoadException("Gradient configuration arrays have inconsistent lengths");
        }
        for (Map.Entry<String, Integer> entry : gradient.blockGroups().entrySet()) {
            validateBlockId(entry.getKey(), "gradient source block");
            if (entry.getValue() < 0 || entry.getValue() >= groupCount) {
                throw new TemplateLoadException("Invalid gradient group " + entry.getValue()
                        + " for " + entry.getKey());
            }
        }
        for (int group = 0; group < groupCount; group++) {
            List<String> slots = gradient.groupSlots().get(group);
            if (slots.size() > MAX_GRADIENT_SLOTS) {
                throw new TemplateLoadException("Too many slots in gradient group " + group);
            }
            float window = gradient.groupWindows().get(group);
            if (!Float.isFinite(window) || window < 0.0f || window > MAX_GRADIENT_SLOTS) {
                throw new TemplateLoadException("Invalid gradient window for group " + group + ": " + window);
            }
            int noiseScale = gradient.groupNoiseScales().get(group);
            if (noiseScale < 1 || noiseScale > 1_024) {
                throw new TemplateLoadException("Invalid gradient noise scale for group " + group + ": " + noiseScale);
            }
            for (String slot : slots) {
                if (!slot.isEmpty() && !GradientSlotUtil.isMineAction(slot)) {
                    validateBlockId(slot, "gradient slot");
                }
            }
        }
    }

    private static void validateBlockId(String rawId, String context) throws TemplateLoadException {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new TemplateLoadException("Unknown " + context + " id: " + rawId);
        }
    }

    private static JsonArray writeWallModules(List<WallModuleTemplate> modules) {
        JsonArray result = new JsonArray();
        for (WallModuleTemplate module : modules) {
            JsonObject json = new JsonObject();
            json.add("a", writePos(module.aMarker));
            json.add("b", writePos(module.bMarker));
            json.addProperty("minY", module.minY);
            if (module.aSliceAxis != null) json.addProperty("aSliceAxis", module.aSliceAxis.name());
            if (module.bSliceAxis != null) json.addProperty("bSliceAxis", module.bSliceAxis.name());
            JsonArray voxels = new JsonArray();
            for (WallModuleTemplate.Voxel voxel : module.voxels) {
                voxels.add(writeVoxel(voxel.rel, voxel.state));
            }
            json.add("voxels", voxels);
            result.add(json);
        }
        return result;
    }

    private static List<WallModuleTemplate> readWallModules(JsonArray array) throws TemplateLoadException {
        List<WallModuleTemplate> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new TemplateLoadException("Wall module must be an object");
            JsonObject json = element.getAsJsonObject();
            BlockPos a = readPos(requireArray(json, "a"));
            BlockPos b = readPos(requireArray(json, "b"));
            int minY = requireInt(json, "minY");
            WallJoinSlice.Axis aAxis = readAxis(json, "aSliceAxis");
            WallJoinSlice.Axis bAxis = readAxis(json, "bSliceAxis");
            JsonArray voxelJson = requireArray(json, "voxels");
            checkVoxelCount(voxelJson.size());
            List<WallModuleTemplate.Voxel> voxels = new ArrayList<>(voxelJson.size());
            for (JsonElement voxel : voxelJson) {
                VoxelData data = readVoxel(voxel);
                voxels.add(new WallModuleTemplate.Voxel(data.pos(), data.state()));
            }
            result.add(new WallModuleTemplate(a, b, voxels, minY, aAxis, bAxis));
        }
        return List.copyOf(result);
    }

    private static JsonObject writeTowerModule(TowerModuleTemplate module) {
        JsonObject result = new JsonObject();
        if (module == null) return result;
        result.addProperty("minY", module.minY);
        result.addProperty("maxY", module.maxY);
        JsonArray voxels = new JsonArray();
        for (TowerModuleTemplate.Voxel voxel : module.voxels) {
            voxels.add(writeVoxel(voxel.rel, voxel.state));
        }
        result.add("voxels", voxels);
        return result;
    }

    private static TowerModuleTemplate readTowerModule(JsonObject json) throws TemplateLoadException {
        int minY = requireInt(json, "minY");
        int maxY = requireInt(json, "maxY");
        if (minY < -MAX_EXTENT || maxY > MAX_EXTENT || maxY < minY) {
            throw new TemplateLoadException("Invalid tower Y bounds: " + minY + ".." + maxY);
        }
        JsonArray voxelJson = requireArray(json, "voxels");
        checkVoxelCount(voxelJson.size());
        List<TowerModuleTemplate.Voxel> voxels = new ArrayList<>(voxelJson.size());
        for (JsonElement voxel : voxelJson) {
            VoxelData data = readVoxel(voxel);
            voxels.add(new TowerModuleTemplate.Voxel(data.pos(), data.state()));
        }
        return new TowerModuleTemplate(voxels, minY, maxY);
    }

    private static JsonArray writeTreeModules(List<Map<BlockPos, BlockState>> modules) {
        JsonArray result = new JsonArray();
        for (Map<BlockPos, BlockState> module : modules) {
            JsonArray voxels = new JsonArray();
            module.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator
                            .comparingInt((BlockPos pos) -> pos.getY())
                            .thenComparingInt(pos -> pos.getX())
                            .thenComparingInt(pos -> pos.getZ())))
                    .forEach(entry -> voxels.add(writeVoxel(entry.getKey(), entry.getValue())));
            result.add(voxels);
        }
        return result;
    }

    private static List<Map<BlockPos, BlockState>> readTreeModules(JsonArray array) throws TemplateLoadException {
        List<Map<BlockPos, BlockState>> result = new ArrayList<>();
        for (JsonElement moduleElement : array) {
            if (!moduleElement.isJsonArray()) throw new TemplateLoadException("Tree module must be an array");
            JsonArray moduleJson = moduleElement.getAsJsonArray();
            checkVoxelCount(moduleJson.size());
            Map<BlockPos, BlockState> module = new LinkedHashMap<>();
            for (JsonElement voxel : moduleJson) {
                VoxelData data = readVoxel(voxel);
                if (module.put(data.pos(), data.state()) != null) {
                    throw new TemplateLoadException("Duplicate tree voxel at " + data.pos());
                }
            }
            result.add(module);
        }
        return List.copyOf(result);
    }

    private static JsonArray writeRoomTemplates(List<RoomTemplate> rooms) {
        JsonArray result = new JsonArray();
        for (RoomTemplate room : rooms) {
            JsonObject json = new JsonObject();
            json.add("minBounds", writePos(room.minBounds()));
            json.add("maxBounds", writePos(room.maxBounds()));
            JsonArray voxels = new JsonArray();
            room.voxels().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(POSITION_ORDER))
                    .forEach(entry -> voxels.add(writeVoxel(entry.getKey(), entry.getValue())));
            json.add("voxels", voxels);
            JsonArray air = new JsonArray();
            room.interiorAir().stream().sorted(POSITION_ORDER).forEach(position -> air.add(writePos(position)));
            json.add("interiorAir", air);
            JsonArray sockets = new JsonArray();
            for (RoomSocket socket : room.sockets()) {
                JsonObject socketJson = new JsonObject();
                socketJson.add("anchor", writePos(socket.anchor()));
                socketJson.addProperty("facing", socket.facing().getSerializedName());
                socketJson.addProperty("width", socket.apertureWidth());
                socketJson.addProperty("height", socket.apertureHeight());
                socketJson.addProperty("floorEdge", socket.floorEdge());
                sockets.add(socketJson);
            }
            json.add("sockets", sockets);
            result.add(json);
        }
        return result;
    }

    private static List<RoomTemplate> readRoomTemplates(JsonArray array) throws TemplateLoadException {
        List<RoomTemplate> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new TemplateLoadException("Room template must be an object");
            JsonObject json = element.getAsJsonObject();
            BlockPos minBounds = readPos(requireArray(json, "minBounds"));
            BlockPos maxBounds = readPos(requireArray(json, "maxBounds"));
            JsonArray voxelJson = requireArray(json, "voxels");
            checkVoxelCount(voxelJson.size());
            Map<BlockPos, BlockState> voxels = new LinkedHashMap<>();
            for (JsonElement voxel : voxelJson) {
                VoxelData data = readVoxel(voxel);
                if (voxels.put(data.pos(), data.state()) != null) {
                    throw new TemplateLoadException("Duplicate room voxel at " + data.pos());
                }
            }
            JsonArray airJson = requireArray(json, "interiorAir");
            if (airJson.isEmpty() || airJson.size() > MAX_VOXELS_PER_MODULE * 4) {
                throw new TemplateLoadException("Invalid room interior-air count: " + airJson.size());
            }
            java.util.Set<BlockPos> air = new java.util.LinkedHashSet<>();
            for (JsonElement airPosition : airJson) {
                if (!airPosition.isJsonArray()) throw new TemplateLoadException("Room air position must be an array");
                if (!air.add(readPos(airPosition.getAsJsonArray()))) {
                    throw new TemplateLoadException("Duplicate room interior-air position");
                }
            }
            JsonArray socketJson = requireArray(json, "sockets");
            if (socketJson.isEmpty() || socketJson.size() > 64) {
                throw new TemplateLoadException("Room must have between 1 and 64 sockets");
            }
            List<RoomSocket> sockets = new ArrayList<>();
            for (JsonElement socketElement : socketJson) {
                if (!socketElement.isJsonObject()) throw new TemplateLoadException("Room socket must be an object");
                JsonObject socket = socketElement.getAsJsonObject();
                net.minecraft.core.Direction facing = net.minecraft.core.Direction.byName(requireString(socket, "facing"));
                if (facing == null) throw new TemplateLoadException("Unknown room socket facing");
                sockets.add(new RoomSocket(
                        readPos(requireArray(socket, "anchor")), facing,
                        requireInt(socket, "width"), requireInt(socket, "height"),
                        socket.has("floorEdge") && socket.get("floorEdge").getAsBoolean()
                ));
            }
            try {
                result.add(new RoomTemplate(voxels, air, minBounds, maxBounds, sockets));
            } catch (IllegalArgumentException e) {
                throw new TemplateLoadException("Invalid room template: " + e.getMessage(), e);
            }
        }
        return List.copyOf(result);
    }

    private static void validateRoomTemplates(List<RoomTemplate> rooms) throws TemplateLoadException {
        if (rooms.isEmpty()) throw new TemplateLoadException("Room template has no rooms");
        int width = rooms.getFirst().sockets().getFirst().apertureWidth();
        int height = rooms.getFirst().sockets().getFirst().apertureHeight();
        boolean hasEnd = false;
        boolean hasBranch = false;
        for (RoomTemplate room : rooms) {
            hasEnd |= room.doorCount() == 1;
            hasBranch |= room.doorCount() >= 2;
            if (room.voxels().size() > MAX_VOXELS_PER_MODULE || room.volume() > MAX_VOXELS_PER_MODULE * 4) {
                throw new TemplateLoadException("Room exceeds template limits");
            }
            for (RoomSocket socket : room.sockets()) {
                if (socket.apertureWidth() != width || socket.apertureHeight() != height) {
                    throw new TemplateLoadException("Room socket aperture sizes are inconsistent");
                }
            }
        }
        if (!hasEnd || !hasBranch) {
            throw new TemplateLoadException("Room template requires an end room and a room with at least two doors");
        }
    }

    private static JsonObject writeVoxel(BlockPos pos, BlockState state) {
        JsonObject result = new JsonObject();
        result.add("rel", writePos(pos));
        result.add("state", writeState(state));
        return result;
    }

    private static VoxelData readVoxel(JsonElement element) throws TemplateLoadException {
        if (!element.isJsonObject()) throw new TemplateLoadException("Voxel must be an object");
        JsonObject json = element.getAsJsonObject();
        return new VoxelData(readPos(requireArray(json, "rel")), readState(requireObject(json, "state")));
    }

    private static JsonObject writeState(BlockState state) {
        JsonObject result = new JsonObject();
        result.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        for (Property<?> property : state.getProperties()) {
            properties.addProperty(property.getName(), propertyName(state, property));
        }
        result.add("properties", properties);
        return result;
    }

    private static BlockState readState(JsonObject json) throws TemplateLoadException {
        String rawId = requireString(json, "id");
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new TemplateLoadException("Unknown block id: " + rawId);
        }
        var block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        JsonObject properties = json.has("properties") ? requireObject(json, "properties") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new TemplateLoadException("Unknown property " + entry.getKey() + " for " + rawId);
            }
            state = applyProperty(state, property, entry.getValue().getAsString(), rawId);
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(BlockState state, Property property) {
        return property.getName((Comparable) state.getValue(property));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(
            BlockState state,
            Property property,
            String value,
            String blockId
    ) throws TemplateLoadException {
        Optional parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new TemplateLoadException("Invalid value " + value + " for " + blockId + "." + property.getName());
        }
        return state.setValue(property, (Comparable) parsed.get());
    }

    private static JsonArray writePos(BlockPos pos) {
        JsonArray result = new JsonArray();
        result.add(pos.getX());
        result.add(pos.getY());
        result.add(pos.getZ());
        return result;
    }

    private static BlockPos readPos(JsonArray array) throws TemplateLoadException {
        if (array.size() != 3) throw new TemplateLoadException("Position must contain exactly three integers");
        BlockPos result = new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
        if (Math.abs(result.getX()) > MAX_EXTENT || Math.abs(result.getY()) > MAX_EXTENT
                || Math.abs(result.getZ()) > MAX_EXTENT) {
            throw new TemplateLoadException("Relative position exceeds " + MAX_EXTENT + ": " + result);
        }
        return result;
    }

    private static WallJoinSlice.Axis readAxis(JsonObject json, String key) throws TemplateLoadException {
        if (!json.has(key)) return null;
        try {
            return WallJoinSlice.Axis.valueOf(json.get(key).getAsString());
        } catch (IllegalArgumentException e) {
            throw new TemplateLoadException("Unknown wall join axis: " + json.get(key), e);
        }
    }

    private static JsonArray writeStrings(List<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static List<String> readStrings(JsonElement element) throws TemplateLoadException {
        if (element == null) return List.of();
        if (!element.isJsonArray()) throw new TemplateLoadException("blockPriority must be an array");
        List<String> result = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) result.add(value.getAsString());
        return List.copyOf(result);
    }

    private static void checkVoxelCount(int size) throws TemplateLoadException {
        if (size < 1 || size > MAX_VOXELS_PER_MODULE) {
            throw new TemplateLoadException("Module voxel count must be between 1 and " + MAX_VOXELS_PER_MODULE);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String key) throws TemplateLoadException {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new TemplateLoadException("Missing object: " + key);
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray requireArray(JsonObject parent, String key) throws TemplateLoadException {
        if (!parent.has(key) || !parent.get(key).isJsonArray()) {
            throw new TemplateLoadException("Missing array: " + key);
        }
        return parent.getAsJsonArray(key);
    }

    private static String requireString(JsonObject parent, String key) throws TemplateLoadException {
        if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            throw new TemplateLoadException("Missing string: " + key);
        }
        return parent.get(key).getAsString();
    }

    private static void requireString(JsonObject parent, String key, String expected) throws TemplateLoadException {
        String actual = requireString(parent, key);
        if (!expected.equals(actual)) {
            throw new TemplateLoadException("Expected " + key + "=" + expected + ", got " + actual);
        }
    }

    private static String optionalString(JsonObject parent, String key, String fallback) {
        return parent.has(key) ? parent.get(key).getAsString() : fallback;
    }

    private static int requireInt(JsonObject parent, String key) throws TemplateLoadException {
        if (!parent.has(key) || !parent.get(key).isJsonPrimitive()) {
            throw new TemplateLoadException("Missing integer: " + key);
        }
        return parent.get(key).getAsInt();
    }

    private static int optionalInt(JsonObject parent, String key, int fallback) {
        return parent.has(key) ? parent.get(key).getAsInt() : fallback;
    }

    private record VoxelData(BlockPos pos, BlockState state) {}
}
