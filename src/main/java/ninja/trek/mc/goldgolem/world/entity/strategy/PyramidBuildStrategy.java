package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.tower.PyramidResampler;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tower-style builder whose repeated source slices taper through weighted resampling. */
public final class PyramidBuildStrategy extends TowerBuildStrategy {
    private static final int PLAN_CACHE_SIZE = 8;
    private final Map<Integer, Map<BlockPos, BlockState>> layerPlans =
            new LinkedHashMap<>(PLAN_CACHE_SIZE + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Map<BlockPos, BlockState>> eldest) {
                    return size() > PLAN_CACHE_SIZE;
                }
            };

    @Override
    public BuildMode getMode() {
        return BuildMode.PYRAMID;
    }

    @Override
    public String getNbtPrefix() {
        return "Pyramid";
    }

    @Override
    protected List<BlockPos> getLayerVoxels(GoldGolemEntity golem, TowerModuleTemplate template,
                                             BlockPos origin, int layerY) {
        Map<BlockPos, BlockState> plan = getOrCreatePlan(golem, template, origin, layerY);
        return new ArrayList<>(plan.keySet());
    }

    @Override
    protected BlockState getTowerBlockStateAt(TowerModuleTemplate template, BlockPos origin, BlockPos pos) {
        if (template == null || origin == null || entity == null) return null;
        int layerY = getLayerY(origin, pos);
        return getOrCreatePlan(entity, template, origin, layerY).get(pos);
    }

    private Map<BlockPos, BlockState> getOrCreatePlan(GoldGolemEntity golem, TowerModuleTemplate template,
                                                       BlockPos origin, int layerY) {
        return layerPlans.computeIfAbsent(layerY, ignored -> PyramidResampler.buildLayer(
                template, origin, layerY, golem.getTowerHeight(), golem.getPyramidCurvature(),
                golem.getPyramidPriority()));
    }

    @Override
    public void clearState() {
        layerPlans.clear();
        super.clearState();
    }

    @Override
    public void onConfigurationChanged(String configKey) {
        if ("pyramidCurvature".equals(configKey) || "pyramidPriority".equals(configKey)) {
            clearState();
            return;
        }
        super.onConfigurationChanged(configKey);
    }
}
