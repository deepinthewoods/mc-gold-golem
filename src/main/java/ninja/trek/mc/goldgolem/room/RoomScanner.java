package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects gold-framed doorways and extracts the enclosed rooms separated by their aperture planes. */
public final class RoomScanner {
    private static final Direction[] CARDINALS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final int MAX_GOLD_BLOCKS = 4096;
    private static final int MAX_APERTURE_WIDTH = 16;
    private static final int MAX_APERTURE_HEIGHT = 16;
    private static final int HORIZONTAL_MARGIN = 24;
    private static final int VERTICAL_MARGIN = 16;
    private static final int MAX_SCAN_VOLUME = 1_500_000;
    private static final int MAX_ROOM_VOLUME = 64_000;
    private static final int MAX_ROOM_VOXELS = 32_000;

    private RoomScanner() {}

    public static ScanResult scan(BlockGetter world, BlockPos summonTop) {
        return world == null ? ScanResult.failure("Missing room scan input")
                : scan((BlockAccess) world::getBlockState, summonTop);
    }

    /** Lightweight overload for data generators and tests that already have a block-state lookup. */
    public static ScanResult scan(BlockAccess world, BlockPos summonTop) {
        if (world == null || summonTop == null) return ScanResult.failure("Missing room scan input");
        if (!world.getBlockState(summonTop).is(Blocks.GOLD_BLOCK)) {
            return ScanResult.failure("The pumpkin must be above a gold doorway top");
        }

        try {
            Set<BlockPos> gold = collectConnectedGold(world, summonTop);
            if (gold.size() >= MAX_GOLD_BLOCKS) {
                return ScanResult.failure("Connected gold doorway network is too large");
            }
            List<Frame> frames = detectFrames(world, gold);
            if (frames.isEmpty() || frames.stream().noneMatch(frame -> isTopEdge(frame, summonTop))) {
                return ScanResult.failure("The summon block is not part of a valid gold doorway top");
            }

            int apertureWidth = frames.getFirst().width();
            int apertureHeight = frames.getFirst().height();
            for (Frame frame : frames) {
                if (frame.width() != apertureWidth || frame.height() != apertureHeight) {
                    return ScanResult.failure("Every room doorway must use the same aperture size");
                }
            }

            Bounds scanBounds = scanBounds(gold);
            if (scanBounds.volume() > MAX_SCAN_VOLUME) {
                return ScanResult.failure("Room capture exceeds the scan volume limit");
            }
            Set<BlockPos> sealed = new HashSet<>();
            for (Frame frame : frames) sealed.addAll(frame.aperture());
            AirComponents components = findInteriorAir(world, scanBounds, sealed);
            if (components.components().isEmpty()) {
                return ScanResult.failure("No enclosed room air was found behind the doorway frames");
            }

            Map<Integer, List<AssociatedFrame>> associated = associateFrames(frames, components.componentByPosition());
            if (associated.isEmpty()) {
                return ScanResult.failure("Doorway room-facing sides could not be determined");
            }

            List<CapturedRoom> captured = new ArrayList<>();
            for (Map.Entry<Integer, List<AssociatedFrame>> entry : associated.entrySet()) {
                Set<BlockPos> air = components.components().get(entry.getKey());
                captured.add(captureRoom(world, air, entry.getValue()));
            }
            captured.sort(Comparator
                    .comparingInt((CapturedRoom room) -> room.worldBounds().min().getX())
                    .thenComparingInt(room -> room.worldBounds().min().getY())
                    .thenComparingInt(room -> room.worldBounds().min().getZ()));

            boolean hasEnd = captured.stream().anyMatch(room -> room.template().doorCount() == 1);
            boolean hasBranch = captured.stream().anyMatch(room -> room.template().doorCount() >= 2);
            if (!hasEnd || !hasBranch) {
                return ScanResult.failure("Room capture requires a one-door end and a room with at least two doors");
            }

            LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
            for (CapturedRoom room : captured) {
                room.template().voxels().values().forEach(state -> uniqueIds.add(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()));
            }
            return ScanResult.success(new RoomDefinition(
                    summonTop,
                    captured.stream().map(CapturedRoom::template).toList(),
                    List.copyOf(uniqueIds),
                    apertureWidth,
                    apertureHeight
            ));
        } catch (ScanFailure failure) {
            return ScanResult.failure(failure.getMessage());
        }
    }

    private static Set<BlockPos> collectConnectedGold(BlockAccess world, BlockPos start) {
        Set<BlockPos> result = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        result.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty() && result.size() < MAX_GOLD_BLOCKS) {
            BlockPos position = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = position.relative(direction);
                if (result.contains(next) || !world.getBlockState(next).is(Blocks.GOLD_BLOCK)) continue;
                result.add(next.immutable());
                queue.addLast(next.immutable());
            }
        }
        return Set.copyOf(result);
    }

    private static List<Frame> detectFrames(BlockAccess world, Set<BlockPos> gold) throws ScanFailure {
        Map<Set<BlockPos>, Frame> unique = new LinkedHashMap<>();
        for (BlockPos topLeft : gold) {
            for (Direction lateral : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                for (int width = 1; width <= MAX_APERTURE_WIDTH; width++) {
                    if (!isGoldRun(world, topLeft, lateral, 0, width + 1)) break;
                    for (int height = 1; height <= MAX_APERTURE_HEIGHT; height++) {
                        BlockPos left = topLeft.below(height);
                        BlockPos right = topLeft.relative(lateral, width + 1).below(height);
                        if (!world.getBlockState(left).is(Blocks.GOLD_BLOCK)
                                || !world.getBlockState(right).is(Blocks.GOLD_BLOCK)) {
                            break;
                        }
                        BlockPos anchor = topLeft.relative(lateral).below(height);
                        if (!isAirRectangle(world, anchor, lateral, width, height)) continue;

                        BlockPos bottomLeft = topLeft.below(height + 1);
                        int bottomGold = goldCount(world, bottomLeft, lateral, 0, width + 1);
                        boolean floor = bottomGold == width + 2;
                        boolean openBottom = bottomGold == 0;
                        if (!floor && !openBottom) {
                            if (!world.getBlockState(bottomLeft).is(Blocks.GOLD_BLOCK)
                                    && !world.getBlockState(bottomLeft.relative(lateral, width + 1))
                                    .is(Blocks.GOLD_BLOCK)) {
                                throw new ScanFailure("Doorway floor edge is only partially gold");
                            }
                            continue;
                        }
                        Set<BlockPos> frame = framePositions(anchor, lateral, width, height, floor);
                        if (!gold.containsAll(frame)) continue;
                        Frame candidate = new Frame(anchor, lateral, width, height, floor,
                                aperturePositions(anchor, lateral, width, height), frame);
                        Frame previous = unique.putIfAbsent(candidate.aperture(), candidate);
                        if (previous != null && !previous.frame().equals(candidate.frame())) {
                            throw new ScanFailure("Ambiguous doorway frame geometry");
                        }
                    }
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private static boolean isGoldRun(
            BlockAccess world, BlockPos start, Direction lateral, int first, int last
    ) {
        for (int u = first; u <= last; u++) {
            if (!world.getBlockState(start.relative(lateral, u)).is(Blocks.GOLD_BLOCK)) return false;
        }
        return true;
    }

    private static boolean isTopEdge(Frame frame, BlockPos position) {
        BlockPos topLeft = frame.anchor().above(frame.height()).relative(frame.lateral().getOpposite());
        for (int u = 0; u <= frame.width() + 1; u++) {
            if (topLeft.relative(frame.lateral(), u).equals(position)) return true;
        }
        return false;
    }

    private static int goldCount(
            BlockAccess world, BlockPos start, Direction lateral, int first, int last
    ) {
        int result = 0;
        for (int u = first; u <= last; u++) {
            if (world.getBlockState(start.relative(lateral, u)).is(Blocks.GOLD_BLOCK)) result++;
        }
        return result;
    }

    private static boolean isAirRectangle(
            BlockAccess world, BlockPos topLeftAir, Direction lateral, int width, int height
    ) {
        for (int y = 0; y < height; y++) {
            for (int u = 0; u < width; u++) {
                if (!world.getBlockState(topLeftAir.relative(lateral, u).above(y)).isAir()) return false;
            }
        }
        return true;
    }

    private static Set<BlockPos> aperturePositions(
            BlockPos anchor, Direction lateral, int width, int height
    ) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int y = 0; y < height; y++) {
            for (int u = 0; u < width; u++) result.add(anchor.relative(lateral, u).above(y));
        }
        return Set.copyOf(result);
    }

    private static Set<BlockPos> framePositions(
            BlockPos anchor, Direction lateral, int width, int height, boolean floor
    ) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int y = 0; y < height; y++) {
            result.add(anchor.relative(lateral.getOpposite()).above(y));
            result.add(anchor.relative(lateral, width).above(y));
        }
        for (int u = -1; u <= width; u++) {
            result.add(anchor.relative(lateral, u).above(height));
            if (floor) result.add(anchor.relative(lateral, u).below());
        }
        return Set.copyOf(result);
    }

    private static Bounds scanBounds(Set<BlockPos> gold) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos position : gold) {
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return new Bounds(
                new BlockPos(minX - HORIZONTAL_MARGIN, minY - VERTICAL_MARGIN, minZ - HORIZONTAL_MARGIN),
                new BlockPos(maxX + HORIZONTAL_MARGIN, maxY + VERTICAL_MARGIN, maxZ + HORIZONTAL_MARGIN)
        );
    }

    private static AirComponents findInteriorAir(
            BlockAccess world, Bounds bounds, Set<BlockPos> sealed
    ) {
        Set<BlockPos> exterior = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
            for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
                enqueueOpen(world, new BlockPos(x, y, bounds.min().getZ()), bounds, sealed, exterior, queue);
                enqueueOpen(world, new BlockPos(x, y, bounds.max().getZ()), bounds, sealed, exterior, queue);
            }
        }
        for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
            for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++) {
                enqueueOpen(world, new BlockPos(bounds.min().getX(), y, z), bounds, sealed, exterior, queue);
                enqueueOpen(world, new BlockPos(bounds.max().getX(), y, z), bounds, sealed, exterior, queue);
            }
        }
        for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++) {
            for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++) {
                enqueueOpen(world, new BlockPos(x, bounds.min().getY(), z), bounds, sealed, exterior, queue);
                enqueueOpen(world, new BlockPos(x, bounds.max().getY(), z), bounds, sealed, exterior, queue);
            }
        }
        flood(world, bounds, sealed, exterior, queue);

        List<Set<BlockPos>> components = new ArrayList<>();
        Map<BlockPos, Integer> componentByPosition = new HashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
            BlockPos position = cursor.immutable();
            if (sealed.contains(position) || exterior.contains(position)
                    || componentByPosition.containsKey(position) || !world.getBlockState(position).isAir()) continue;
            Set<BlockPos> component = new LinkedHashSet<>();
            ArrayDeque<BlockPos> componentQueue = new ArrayDeque<>();
            component.add(position);
            componentQueue.add(position);
            flood(world, bounds, sealed, component, componentQueue);
            int id = components.size();
            components.add(Set.copyOf(component));
            for (BlockPos member : component) componentByPosition.put(member, id);
        }
        return new AirComponents(List.copyOf(components), Map.copyOf(componentByPosition));
    }

    private static void enqueueOpen(
            BlockAccess world,
            BlockPos position,
            Bounds bounds,
            Set<BlockPos> sealed,
            Set<BlockPos> visited,
            ArrayDeque<BlockPos> queue
    ) {
        if (bounds.contains(position) && !sealed.contains(position)
                && world.getBlockState(position).isAir() && visited.add(position.immutable())) {
            queue.add(position.immutable());
        }
    }

    private static void flood(
            BlockAccess world,
            Bounds bounds,
            Set<BlockPos> sealed,
            Set<BlockPos> visited,
            ArrayDeque<BlockPos> queue
    ) {
        while (!queue.isEmpty()) {
            BlockPos position = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                enqueueOpen(world, position.relative(direction), bounds, sealed, visited, queue);
            }
        }
    }

    private static Map<Integer, List<AssociatedFrame>> associateFrames(
            List<Frame> frames, Map<BlockPos, Integer> componentByPosition
    ) throws ScanFailure {
        Map<Integer, List<AssociatedFrame>> result = new LinkedHashMap<>();
        for (Frame frame : frames) {
            Direction normal = frame.lateral() == Direction.EAST ? Direction.SOUTH : Direction.EAST;
            Set<Integer> positive = adjacentComponents(frame.aperture(), normal, componentByPosition);
            Set<Integer> negative = adjacentComponents(frame.aperture(), normal.getOpposite(), componentByPosition);
            if (positive.size() > 1 || negative.size() > 1) {
                throw new ScanFailure("A doorway touches multiple rooms on the same side");
            }
            if (!positive.isEmpty() && positive.equals(negative)) {
                throw new ScanFailure("A doorway's room-facing side is ambiguous");
            }
            if (positive.isEmpty() && negative.isEmpty()) {
                throw new ScanFailure("A gold frame is not attached to an enclosed room");
            }
            if (!positive.isEmpty()) {
                int id = positive.iterator().next();
                addAssociated(result, id, associated(frame, normal.getOpposite()));
            }
            if (!negative.isEmpty()) {
                int id = negative.iterator().next();
                addAssociated(result, id, associated(frame, normal));
            }
        }
        return result;
    }

    private static Set<Integer> adjacentComponents(
            Set<BlockPos> aperture, Direction side, Map<BlockPos, Integer> componentByPosition
    ) {
        Set<Integer> result = new HashSet<>();
        for (BlockPos position : aperture) {
            Integer id = componentByPosition.get(position.relative(side));
            if (id != null) result.add(id);
        }
        return result;
    }

    private static AssociatedFrame associated(Frame frame, Direction outwardFacing) {
        BlockPos anchor = frame.anchor();
        if (RoomSocket.rightOf(outwardFacing) == frame.lateral().getOpposite()) {
            anchor = anchor.relative(frame.lateral(), frame.width() - 1);
        }
        return new AssociatedFrame(
                new RoomSocket(anchor, outwardFacing, frame.width(), frame.height(), frame.floor()),
                frame.frame()
        );
    }

    private static void addAssociated(
            Map<Integer, List<AssociatedFrame>> result, int id, AssociatedFrame frame
    ) {
        result.computeIfAbsent(id, ignored -> new ArrayList<>()).add(frame);
    }

    private static CapturedRoom captureRoom(
            BlockAccess world, Set<BlockPos> enclosedAir, List<AssociatedFrame> frames
    ) throws ScanFailure {
        Bounds bounds = boundsOf(enclosedAir).expand(1);
        Set<BlockPos> applicableGold = new HashSet<>();
        for (AssociatedFrame frame : frames) {
            applicableGold.addAll(frame.framePositions());
            bounds = bounds.include(frame.framePositions());
        }
        if (bounds.volume() > MAX_ROOM_VOLUME) throw new ScanFailure("A captured room is too large");

        Map<BlockPos, BlockState> worldVoxels = new LinkedHashMap<>();
        for (BlockPos cursor : BlockPos.betweenClosed(bounds.min(), bounds.max())) {
            BlockPos position = cursor.immutable();
            BlockState state = world.getBlockState(position);
            if (state.isAir()) continue;
            if (state.is(Blocks.GOLD_BLOCK) && !applicableGold.contains(position)) continue;
            worldVoxels.put(position, state);
            if (worldVoxels.size() > MAX_ROOM_VOXELS) throw new ScanFailure("A captured room has too many voxels");
        }

        Set<BlockPos> requiredAir = new LinkedHashSet<>(enclosedAir);
        for (AssociatedFrame frame : frames) requiredAir.addAll(frame.socket().aperturePositions());
        BlockPos origin = bounds.min();
        Map<BlockPos, BlockState> localVoxels = new LinkedHashMap<>();
        worldVoxels.forEach((position, state) -> localVoxels.put(position.subtract(origin), state));
        Set<BlockPos> localAir = new LinkedHashSet<>();
        for (BlockPos position : requiredAir) localAir.add(position.subtract(origin));
        List<RoomSocket> sockets = frames.stream()
                .map(AssociatedFrame::socket)
                .map(socket -> new RoomSocket(
                        socket.anchor().subtract(origin), socket.facing(),
                        socket.apertureWidth(), socket.apertureHeight(), socket.floorEdge()))
                .toList();
        RoomTemplate template = new RoomTemplate(
                localVoxels,
                localAir,
                BlockPos.ZERO,
                bounds.max().subtract(origin),
                sockets
        );
        return new CapturedRoom(template, bounds);
    }

    private static Bounds boundsOf(Set<BlockPos> positions) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos position : positions) {
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return new Bounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    public record ScanResult(RoomDefinition definition, String error) {
        public boolean ok() {
            return definition != null;
        }

        public static ScanResult success(RoomDefinition definition) {
            return new ScanResult(definition, null);
        }

        public static ScanResult failure(String error) {
            return new ScanResult(null, error == null ? "Room capture failed" : error);
        }
    }

    @FunctionalInterface
    public interface BlockAccess {
        BlockState getBlockState(BlockPos position);
    }

    private record Frame(
            BlockPos anchor,
            Direction lateral,
            int width,
            int height,
            boolean floor,
            Set<BlockPos> aperture,
            Set<BlockPos> frame
    ) {}

    private record AssociatedFrame(RoomSocket socket, Set<BlockPos> framePositions) {}

    private record AirComponents(
            List<Set<BlockPos>> components,
            Map<BlockPos, Integer> componentByPosition
    ) {}

    private record CapturedRoom(RoomTemplate template, Bounds worldBounds) {}

    private record Bounds(BlockPos min, BlockPos max) {
        boolean contains(BlockPos position) {
            return position.getX() >= min.getX() && position.getX() <= max.getX()
                    && position.getY() >= min.getY() && position.getY() <= max.getY()
                    && position.getZ() >= min.getZ() && position.getZ() <= max.getZ();
        }

        int volume() {
            long volume = (long) (max.getX() - min.getX() + 1)
                    * (max.getY() - min.getY() + 1)
                    * (max.getZ() - min.getZ() + 1);
            return volume > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) volume;
        }

        Bounds expand(int amount) {
            return new Bounds(min.offset(-amount, -amount, -amount), max.offset(amount, amount, amount));
        }

        Bounds include(Set<BlockPos> positions) {
            BlockPos newMin = min;
            BlockPos newMax = max;
            for (BlockPos position : positions) {
                newMin = new BlockPos(
                        Math.min(newMin.getX(), position.getX()),
                        Math.min(newMin.getY(), position.getY()),
                        Math.min(newMin.getZ(), position.getZ()));
                newMax = new BlockPos(
                        Math.max(newMax.getX(), position.getX()),
                        Math.max(newMax.getY(), position.getY()),
                        Math.max(newMax.getZ(), position.getZ()));
            }
            return new Bounds(newMin, newMax);
        }
    }

    private static final class ScanFailure extends Exception {
        private ScanFailure(String message) {
            super(message);
        }
    }
}
