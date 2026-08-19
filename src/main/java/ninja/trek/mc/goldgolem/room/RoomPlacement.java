package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A transformed room instance in world coordinates. */
public record RoomPlacement(
        int templateIndex,
        RoomTransform transform,
        BlockPos offset,
        int inputSocketIndex
) {
    public Map<BlockPos, BlockState> blocks(RoomTemplate template) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        template.voxels().forEach((position, state) -> result.put(
                transform.apply(position).offset(offset), transform.apply(state)));
        return result;
    }

    public Set<BlockPos> requiredAir(RoomTemplate template) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (BlockPos position : template.interiorAir()) {
            result.add(transform.apply(position).offset(offset));
        }
        return Set.copyOf(result);
    }

    public List<RoomSocket> sockets(RoomTemplate template) {
        return template.sockets().stream()
                .map(socket -> socket.transformed(transform).moved(offset))
                .toList();
    }

    public RoomSocket inputSocket(RoomTemplate template) {
        return sockets(template).get(inputSocketIndex);
    }

    public Set<BlockPos> footprint(RoomTemplate template) {
        Set<BlockPos> result = new LinkedHashSet<>(requiredAir(template));
        result.addAll(blocks(template).keySet());
        return Set.copyOf(result);
    }

    public static RoomPlacement connect(
            int templateIndex,
            RoomTemplate template,
            int inputSocketIndex,
            RoomTransform transform,
            RoomSocket target
    ) {
        RoomSocket input = template.sockets().get(inputSocketIndex).transformed(transform);
        if (!input.compatibleWith(target) || input.facing() != target.facing().getOpposite()) {
            throw new IllegalArgumentException("Room input is incompatible with target socket");
        }
        BlockPos desiredAnchor = target.anchor().relative(target.right(), target.apertureWidth() - 1);
        return new RoomPlacement(templateIndex, transform, desiredAnchor.subtract(input.anchor()), inputSocketIndex);
    }
}
