package ninja.trek.mc.goldgolem.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Dependency-free bridge to Craneshot. The value placed in ObjectShare is a
 * JDK Consumer, while the payload is versioned JSON, so neither mod needs the
 * other on its compile classpath.
 */
public final class TimelapseBuildEvents {
    public static final String EVENT_SINK_KEY = "craneshot:gold-golem-build-event-v1";

    private TimelapseBuildEvents() {
    }

    public static boolean supports(BuildMode mode) {
        return mode == BuildMode.TOWER || mode == BuildMode.PYRAMID
                || mode == BuildMode.WALL || mode == BuildMode.PATH
                || mode == BuildMode.TREE;
    }

    public static boolean usesTrackingRig(BuildMode mode) {
        return mode == BuildMode.WALL || mode == BuildMode.PATH || mode == BuildMode.TREE;
    }

    @SuppressWarnings("unchecked")
    public static void emit(GoldGolemEntity golem, UUID sessionId, String state) {
        if (golem == null || sessionId == null || golem.level().isClientSide()) return;
        BuildMode mode = golem.getBuildMode();
        if (!supports(mode)) return;

        Object value = FabricLoader.getInstance().getObjectShare().get(EVENT_SINK_KEY);
        if (!(value instanceof Consumer<?>)) return;

        JsonObject event = new JsonObject();
        event.addProperty("version", 1);
        event.addProperty("state", state);
        event.addProperty("sessionId", sessionId.toString());
        event.addProperty("golemId", golem.getUUID().toString());
        if (golem.getOwnerUuid() != null) {
            event.addProperty("ownerId", golem.getOwnerUuid().toString());
        }
        event.addProperty("dimension", golem.level().dimension().identifier().toString());
        event.addProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        event.addProperty("yaw", golem.getYRot());
        addPosition(event, "origin", golem.blockPosition());

        if (mode == BuildMode.TOWER || mode == BuildMode.PYRAMID) {
            addTowerBounds(event, golem);
        }

        ((Consumer<String>) value).accept(event.toString());
    }

    private static void addTowerBounds(JsonObject event, GoldGolemEntity golem) {
        BlockPos origin = golem.getTowerOrigin();
        TowerModuleTemplate template = golem.getTowerTemplate();
        if (origin == null || template == null || template.voxels.isEmpty()) return;

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TowerModuleTemplate.Voxel voxel : template.voxels) {
            minX = Math.min(minX, origin.getX() + voxel.rel.getX());
            maxX = Math.max(maxX, origin.getX() + voxel.rel.getX());
            minZ = Math.min(minZ, origin.getZ() + voxel.rel.getZ());
            maxZ = Math.max(maxZ, origin.getZ() + voxel.rel.getZ());
        }

        JsonObject bounds = new JsonObject();
        bounds.add("min", positionArray(minX, origin.getY() - 1, minZ));
        bounds.add("max", positionArray(maxX + 1, origin.getY() - 1 + golem.getTowerHeight(), maxZ + 1));
        event.add("bounds", bounds);
    }

    private static void addPosition(JsonObject event, String key, BlockPos pos) {
        event.add(key, positionArray(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private static JsonArray positionArray(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }
}
