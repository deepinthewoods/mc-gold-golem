package ninja.trek.mc.goldgolem.api.structure;

/** Marker interface for mode-specific instant build requests. */
public sealed interface StructureBuildRequest
        permits WallBuildRequest, TowerBuildRequest, PyramidBuildRequest, TreeBuildRequest, RoomBuildRequest {
    TemplateMode mode();
}
