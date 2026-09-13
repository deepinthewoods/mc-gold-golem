package ninja.trek.mc.goldgolem.client.screen;

import ninja.trek.mc.goldgolem.BuildMode;

/** Group-gradient presentation for captured room templates. */
public final class RoomModeStrategy extends WallModeStrategy {
    @Override
    public BuildMode getMode() {
        return BuildMode.ROOM;
    }
}
