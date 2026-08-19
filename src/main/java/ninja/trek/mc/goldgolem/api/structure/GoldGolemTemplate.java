package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable procedural template consumed by {@link GoldGolemStructures}. */
public final class GoldGolemTemplate {
    public static final int CURRENT_FORMAT_VERSION = 2;
    public static final String FORMAT_ID = "gold-golem:procedural_template";

    private final int formatVersion;
    private final TemplateMode mode;
    private final List<WallModuleTemplate> wallModules;
    private final TowerModuleTemplate towerModule;
    private final List<Map<BlockPos, BlockState>> treeModules;
    private final List<RoomTemplate> roomTemplates;
    private final TilingPreset treeTilingPreset;
    private final String treeGroundBlockId;
    private final List<String> blockPriority;
    private final GradientConfig gradient;
    private final int defaultHeight;
    private final int defaultCurvature;

    GoldGolemTemplate(
            int formatVersion,
            TemplateMode mode,
            List<WallModuleTemplate> wallModules,
            TowerModuleTemplate towerModule,
            List<Map<BlockPos, BlockState>> treeModules,
            TilingPreset treeTilingPreset,
            String treeGroundBlockId,
            List<String> blockPriority,
            int defaultHeight,
            int defaultCurvature
    ) {
        this(
                formatVersion, mode, wallModules, towerModule, treeModules, List.of(), treeTilingPreset,
                treeGroundBlockId, blockPriority, GradientConfig.EMPTY, defaultHeight, defaultCurvature
        );
    }

    GoldGolemTemplate(
            int formatVersion,
            TemplateMode mode,
            List<WallModuleTemplate> wallModules,
            TowerModuleTemplate towerModule,
            List<Map<BlockPos, BlockState>> treeModules,
            TilingPreset treeTilingPreset,
            String treeGroundBlockId,
            List<String> blockPriority,
            GradientConfig gradient,
            int defaultHeight,
            int defaultCurvature
    ) {
        this(formatVersion, mode, wallModules, towerModule, treeModules, List.of(), treeTilingPreset,
                treeGroundBlockId, blockPriority, gradient, defaultHeight, defaultCurvature);
    }

    GoldGolemTemplate(
            int formatVersion,
            TemplateMode mode,
            List<WallModuleTemplate> wallModules,
            TowerModuleTemplate towerModule,
            List<Map<BlockPos, BlockState>> treeModules,
            List<RoomTemplate> roomTemplates,
            TilingPreset treeTilingPreset,
            String treeGroundBlockId,
            List<String> blockPriority,
            GradientConfig gradient,
            int defaultHeight,
            int defaultCurvature
    ) {
        this.formatVersion = formatVersion;
        this.mode = mode;
        this.wallModules = wallModules == null ? List.of() : List.copyOf(wallModules);
        this.towerModule = towerModule;
        this.treeModules = copyTreeModules(treeModules);
        this.roomTemplates = roomTemplates == null ? List.of() : List.copyOf(roomTemplates);
        this.treeTilingPreset = treeTilingPreset == null ? TilingPreset.SMALL_3x3 : treeTilingPreset;
        this.treeGroundBlockId = treeGroundBlockId;
        this.blockPriority = blockPriority == null ? List.of() : List.copyOf(blockPriority);
        this.gradient = gradient == null ? GradientConfig.EMPTY : gradient;
        this.defaultHeight = defaultHeight;
        this.defaultCurvature = defaultCurvature;
    }

    public int formatVersion() {
        return formatVersion;
    }

    public TemplateMode mode() {
        return mode;
    }

    List<WallModuleTemplate> wallModules() {
        return wallModules;
    }

    TowerModuleTemplate towerModule() {
        return towerModule;
    }

    List<Map<BlockPos, BlockState>> treeModules() {
        return treeModules;
    }

    public List<RoomTemplate> roomTemplates() {
        return roomTemplates;
    }

    TilingPreset treeTilingPreset() {
        return treeTilingPreset;
    }

    String treeGroundBlockId() {
        return treeGroundBlockId;
    }

    List<String> blockPriority() {
        return blockPriority;
    }

    GradientConfig gradient() {
        return gradient;
    }

    public int defaultHeight() {
        return defaultHeight;
    }

    public int defaultCurvature() {
        return defaultCurvature;
    }

    /** Captures the reusable procedural data from a configured golem. */
    public static GoldGolemTemplate fromGolem(GoldGolemEntity golem) {
        BuildMode buildMode = golem.getBuildMode();
        TemplateMode templateMode = switch (buildMode) {
            case WALL -> TemplateMode.WALL;
            case TOWER -> TemplateMode.TOWER;
            case PYRAMID -> TemplateMode.PYRAMID;
            case TREE -> TemplateMode.TREE;
            case ROOM -> TemplateMode.ROOM;
            default -> throw new IllegalArgumentException("Build mode " + buildMode + " has no procedural template");
        };
        GradientConfig gradient = switch (templateMode) {
            case WALL -> GradientConfig.capture(
                    golem.getWallBlockGroup(), golem.getWallGroupSlots(),
                    golem.getWallGroupWindows(), golem.getWallGroupNoiseScales());
            case TOWER, PYRAMID -> GradientConfig.capture(
                    golem.getTowerBlockGroup(), golem.getTowerGroupSlots(),
                    golem.getTowerGroupWindows(), golem.getTowerGroupNoiseScales());
            case TREE -> GradientConfig.capture(
                    golem.getTreeBlockGroup(), golem.getTreeGroupSlots(),
                    golem.getTreeGroupWindows(), golem.getTreeGroupNoiseScales());
            case ROOM -> GradientConfig.capture(
                    golem.getRoomBlockGroup(), golem.getRoomGroupSlots(),
                    golem.getRoomGroupWindows(), golem.getRoomGroupNoiseScales());
        };
        return new GoldGolemTemplate(
                CURRENT_FORMAT_VERSION,
                templateMode,
                golem.getWallTemplates(),
                golem.getTowerTemplate(),
                golem.getTreeModuleBlockStates(),
                golem.getRoomTemplates(),
                golem.getTreeTilingPreset(),
                golem.getTreeGroundBlockId(),
                golem.getPyramidPriority(),
                gradient,
                Math.max(1, golem.getTowerHeight()),
                golem.getPyramidCurvature()
        );
    }

    private static List<Map<BlockPos, BlockState>> copyTreeModules(
            List<Map<BlockPos, BlockState>> modules
    ) {
        if (modules == null || modules.isEmpty()) return List.of();
        List<Map<BlockPos, BlockState>> copy = new ArrayList<>(modules.size());
        for (Map<BlockPos, BlockState> module : modules) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(module)));
        }
        return Collections.unmodifiableList(copy);
    }
}
