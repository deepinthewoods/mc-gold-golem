package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

public final class PyramidModeStrategy extends TowerModeStrategy {
    @Override
    public BuildMode getMode() {
        return BuildMode.PYRAMID;
    }
}
