package ninja.trek.mc.goldgolem.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, budget-aware room layout generation with overlap-safe backtracking. */
public final class RoomLayoutGenerator {
    private static final List<RoomTransform> TRANSFORMS = List.of(
            new RoomTransform(0, false), new RoomTransform(0, true),
            new RoomTransform(1, false), new RoomTransform(1, true),
            new RoomTransform(2, false), new RoomTransform(2, true),
            new RoomTransform(3, false), new RoomTransform(3, true)
    );

    private RoomLayoutGenerator() {}

    public static Layout generate(
            List<RoomTemplate> templates,
            BlockPos origin,
            Direction initialDirection,
            long seed,
            int maxRooms
    ) {
        validate(templates, origin, initialDirection, maxRooms);
        RoomTemplate sample = templates.getFirst();
        RoomSocket entrance = new RoomSocket(
                origin,
                initialDirection,
                sample.sockets().getFirst().apertureWidth(),
                sample.sockets().getFirst().apertureHeight(),
                sample.sockets().getFirst().floorEdge()
        );

        List<Candidate> starts = candidatesFor(templates, entrance, seed, 0, false);
        starts.removeIf(candidate -> templates.get(candidate.placement().templateIndex()).doorCount() < 2);
        for (Candidate start : starts) {
            RoomPlacement placement = start.placement();
            RoomTemplate template = templates.get(placement.templateIndex());
            List<RoomPlacement> placed = new ArrayList<>();
            placed.add(placement);
            List<Frontier> frontier = outgoing(template, placement);
            if (1 + frontier.size() > maxRooms) continue;
            if (closeFrontier(templates, seed, maxRooms, placed, frontier, 1)) {
                return new Layout(placed);
            }
        }
        throw new IllegalArgumentException("No closed room layout fits within maxRooms=" + maxRooms);
    }

    private static boolean closeFrontier(
            List<RoomTemplate> templates,
            long seed,
            int maxRooms,
            List<RoomPlacement> placed,
            List<Frontier> frontier,
            int step
    ) {
        if (frontier.isEmpty()) return true;
        if (placed.size() + frontier.size() > maxRooms) return false;

        Frontier active = frontier.removeFirst();
        boolean endingsOnly = placed.size() + frontier.size() + 1 >= maxRooms;
        List<Candidate> candidates = candidatesFor(templates, active.socket(), seed, step, endingsOnly);
        for (Candidate candidate : candidates) {
            RoomPlacement next = candidate.placement();
            RoomTemplate template = templates.get(next.templateIndex());
            if (overlaps(templates, placed, next, active.socket())) continue;

            List<Frontier> added = outgoing(template, next);
            int minimumRooms = placed.size() + 1 + frontier.size() + added.size();
            if (minimumRooms > maxRooms) continue;

            placed.add(next);
            frontier.addAll(added);
            if (closeFrontier(templates, seed, maxRooms, placed, frontier, step + 1)) return true;
            for (int i = 0; i < added.size(); i++) frontier.removeLast();
            placed.removeLast();
        }
        frontier.addFirst(active);
        return false;
    }

    private static List<Candidate> candidatesFor(
            List<RoomTemplate> templates,
            RoomSocket target,
            long seed,
            int step,
            boolean endingsOnly
    ) {
        List<Candidate> candidates = new ArrayList<>();
        for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
            RoomTemplate template = templates.get(templateIndex);
            if (endingsOnly && template.doorCount() != 1) continue;
            for (int inputIndex = 0; inputIndex < template.sockets().size(); inputIndex++) {
                RoomSocket input = template.sockets().get(inputIndex);
                if (!input.compatibleWith(target)) continue;
                for (RoomTransform transform : TRANSFORMS) {
                    RoomSocket transformed = input.transformed(transform);
                    if (transformed.facing() != target.facing().getOpposite()) continue;
                    RoomPlacement placement = RoomPlacement.connect(
                            templateIndex, template, inputIndex, transform, target);
                    long rank = mix(seed
                            ^ ((long) step * 0x9E3779B97F4A7C15L)
                            ^ ((long) templateIndex << 32)
                            ^ ((long) inputIndex << 16)
                            ^ (transform.rotation() * 2L + (transform.mirror() ? 1L : 0L)));
                    candidates.add(new Candidate(placement, rank));
                }
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::rank));
        return candidates;
    }

    private static List<Frontier> outgoing(RoomTemplate template, RoomPlacement placement) {
        List<RoomSocket> sockets = placement.sockets(template);
        List<Frontier> result = new ArrayList<>();
        for (int i = 0; i < sockets.size(); i++) {
            if (i != placement.inputSocketIndex()) result.add(new Frontier(sockets.get(i)));
        }
        return result;
    }

    private static boolean overlaps(
            List<RoomTemplate> templates,
            List<RoomPlacement> placed,
            RoomPlacement candidate,
            RoomSocket matchedSocket
    ) {
        RoomTemplate candidateTemplate = templates.get(candidate.templateIndex());
        Set<BlockPos> candidateFootprint = candidate.footprint(candidateTemplate);
        Set<BlockPos> allowed = new HashSet<>(matchedSocket.connectionPositions());
        allowed.addAll(candidate.inputSocket(candidateTemplate).connectionPositions());
        for (RoomPlacement existing : placed) {
            Set<BlockPos> existingFootprint = existing.footprint(templates.get(existing.templateIndex()));
            for (BlockPos position : candidateFootprint) {
                if (existingFootprint.contains(position) && !allowed.contains(position)) return true;
            }
        }
        return false;
    }

    private static void validate(
            List<RoomTemplate> templates,
            BlockPos origin,
            Direction initialDirection,
            int maxRooms
    ) {
        if (templates == null || templates.isEmpty()) throw new IllegalArgumentException("Room templates are required");
        if (origin == null) throw new IllegalArgumentException("Room origin is required");
        if (initialDirection == null || initialDirection == Direction.UP || initialDirection == Direction.DOWN) {
            throw new IllegalArgumentException("initialDirection must be horizontal");
        }
        if (maxRooms < 1 || maxRooms > 1000) {
            throw new IllegalArgumentException("maxRooms must be between 1 and 1000");
        }
        int width = templates.getFirst().sockets().getFirst().apertureWidth();
        int height = templates.getFirst().sockets().getFirst().apertureHeight();
        boolean hasEnd = false;
        boolean hasBranch = false;
        for (RoomTemplate template : templates) {
            hasEnd |= template.doorCount() == 1;
            hasBranch |= template.doorCount() >= 2;
            for (RoomSocket socket : template.sockets()) {
                if (socket.apertureWidth() != width || socket.apertureHeight() != height) {
                    throw new IllegalArgumentException("All room sockets must use the same aperture dimensions");
                }
            }
        }
        if (!hasEnd || !hasBranch) {
            throw new IllegalArgumentException("Room templates require an end room and a room with at least two doors");
        }
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    public record Layout(List<RoomPlacement> rooms) {
        public Layout {
            rooms = List.copyOf(rooms);
        }

        public Set<BlockPos> requiredAir(List<RoomTemplate> templates) {
            Set<BlockPos> result = new LinkedHashSet<>();
            for (RoomPlacement room : rooms) result.addAll(room.requiredAir(templates.get(room.templateIndex())));
            return Set.copyOf(result);
        }
    }

    private record Frontier(RoomSocket socket) {}

    private record Candidate(RoomPlacement placement, long rank) {}
}
