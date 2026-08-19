package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable captured room: solid voxels, required interior air, bounds, and doorway sockets. */
public final class RoomTemplate {
    private final Map<BlockPos, BlockState> voxels;
    private final Set<BlockPos> interiorAir;
    private final BlockPos minBounds;
    private final BlockPos maxBounds;
    private final List<RoomSocket> sockets;

    public RoomTemplate(
            Map<BlockPos, BlockState> voxels,
            Set<BlockPos> interiorAir,
            BlockPos minBounds,
            BlockPos maxBounds,
            List<RoomSocket> sockets
    ) {
        if (minBounds == null || maxBounds == null) {
            throw new IllegalArgumentException("Room bounds are required");
        }
        if (minBounds.getX() > maxBounds.getX()
                || minBounds.getY() > maxBounds.getY()
                || minBounds.getZ() > maxBounds.getZ()) {
            throw new IllegalArgumentException("Invalid room bounds");
        }
        this.voxels = Collections.unmodifiableMap(new LinkedHashMap<>(
                voxels == null ? Map.of() : voxels));
        this.interiorAir = Collections.unmodifiableSet(new LinkedHashSet<>(
                interiorAir == null ? Set.of() : interiorAir));
        this.minBounds = minBounds.immutable();
        this.maxBounds = maxBounds.immutable();
        this.sockets = sockets == null ? List.of() : List.copyOf(sockets);
        if (this.voxels.isEmpty()) throw new IllegalArgumentException("Room has no solid voxels");
        if (this.interiorAir.isEmpty()) throw new IllegalArgumentException("Room has no enclosed air");
        if (this.sockets.isEmpty()) throw new IllegalArgumentException("Room has no doorway sockets");
    }

    public Map<BlockPos, BlockState> voxels() {
        return voxels;
    }

    public Set<BlockPos> interiorAir() {
        return interiorAir;
    }

    public BlockPos minBounds() {
        return minBounds;
    }

    public BlockPos maxBounds() {
        return maxBounds;
    }

    public List<RoomSocket> sockets() {
        return sockets;
    }

    public int doorCount() {
        return sockets.size();
    }

    public int volume() {
        return (maxBounds.getX() - minBounds.getX() + 1)
                * (maxBounds.getY() - minBounds.getY() + 1)
                * (maxBounds.getZ() - minBounds.getZ() + 1);
    }
}
