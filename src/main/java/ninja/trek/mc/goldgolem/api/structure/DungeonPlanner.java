package ninja.trek.mc.goldgolem.api.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import ninja.trek.mc.goldgolem.room.RoomPlacement;
import ninja.trek.mc.goldgolem.room.RoomSocket;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.room.RoomTransform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DungeonPlanner {
    private static final List<RoomTransform> TRANSFORMS = transforms();
    private static final int INF = 1_000_000;
    private static final int MAX_SUCCESSFUL_CANDIDATES = 3;

    private DungeonPlanner() {}

    static DungeonPlanResult plan(GoldGolemTemplate template, DungeonGenerationRequest request) {
        if (template.mode() != TemplateMode.ROOM || template.roomTemplates().isEmpty()) {
            return failure(DungeonPlanResult.Status.INVALID_TEMPLATE,
                    "Dungeon planning requires a ROOM template with captured rooms");
        }
        Catalog catalog = new Catalog(template.roomTemplates(), request);
        String invalid = catalog.validate();
        if (invalid != null) return failure(DungeonPlanResult.Status.INVALID_TEMPLATE, invalid);

        boolean hitLimit = false;
        for (int roomTarget : targetOrder(request.roomCount())) {
            DungeonPlan best = null;
            int successfulCandidates = 0;
            for (int attempt = 0; attempt < request.maxAttempts(); attempt++) {
                SearchBudget budget = new SearchBudget(request.maxSearchSteps());
                DungeonPlan candidate = attempt(template, request, catalog, roomTarget, attempt, budget);
                hitLimit |= budget.exhausted;
                if (candidate != null && (best == null
                        || candidate.stats().styleScore() < best.stats().styleScore())) {
                    best = candidate;
                }
                if (candidate != null && ++successfulCandidates >= MAX_SUCCESSFUL_CANDIDATES) break;
            }
            if (best != null) return new DungeonPlanResult(
                    DungeonPlanResult.Status.SUCCESS, best, List.of());
        }
        return failure(hitLimit ? DungeonPlanResult.Status.SEARCH_LIMIT_REACHED
                        : DungeonPlanResult.Status.UNSATISFIABLE,
                hitLimit
                        ? "Dungeon search exhausted its configured step limit before finding a valid layout"
                        : "No collision-free closed layout satisfies the room, marker, and boss-route constraints");
    }

    private static DungeonPlan attempt(GoldGolemTemplate source, DungeonGenerationRequest request,
                                       Catalog catalog, int target, int attempt, SearchBudget budget) {
        List<TemplateInfo> bosses = new ArrayList<>(catalog.bosses);
        bosses.sort(Comparator.comparingLong(info -> mix(request.seed() ^ ((long) attempt << 32) ^ info.index)));
        for (TemplateInfo boss : bosses) {
            for (RoomTransform transform : TRANSFORMS) {
                RoomPlacement placement = new RoomPlacement(boss.index, transform, BlockPos.ZERO, 0);
                StateRoom room = stateRoom(0, placement, boss);
                List<OpenSocket> opens = new ArrayList<>();
                for (int socket = 0; socket < room.sockets.size(); socket++) {
                    opens.add(new OpenSocket(0, socket, room.sockets.get(socket)));
                }
                int[] quotas = new int[request.specialRooms().size()];
                countQuotas(boss, request, quotas);
                State initial = new State(new ArrayList<>(List.of(room)), opens, new ArrayList<>(),
                        quotas, -1);
                State solved = search(initial, source, request, catalog, target, attempt, budget);
                if (solved != null) return toPlan(source, request, solved);
                if (budget.exhausted) return null;
            }
        }
        return null;
    }

    private static State search(State state, GoldGolemTemplate source, DungeonGenerationRequest request,
                                Catalog catalog, int target, int attempt, SearchBudget budget) {
        if (!budget.step()) return null;
        if (state.rooms.size() == target) {
            State closed = closeExistingSockets(state, budget);
            return closed != null && validCompleted(closed, request, target) ? closed : null;
        }
        if (state.opens.isEmpty()) return null;
        int remaining = target - state.rooms.size();
        if (state.entranceRoom < 0 && remaining < 1) return null;
        if (!quotaFeasible(state.quotaCounts, request, remaining)) return null;

        int activeIndex = chooseActive(state);
        OpenSocket active = state.opens.get(activeIndex);

        for (int otherIndex = 0; otherIndex < state.opens.size(); otherIndex++) {
            if (otherIndex == activeIndex) continue;
            OpenSocket other = state.opens.get(otherIndex);
            if (active.roomIndex == other.roomIndex || !connects(active.socket, other.socket)) continue;
            State next = pairExisting(state, activeIndex, otherIndex, active, other);
            State solved = search(next, source, request, catalog, target, attempt, budget);
            if (solved != null) return solved;
        }

        List<Candidate> candidates = candidates(state, active, source, request, catalog, target, attempt);
        for (Candidate candidate : candidates) {
            State next = placeCandidate(state, activeIndex, active, candidate, request);
            if (next == null) continue;
            State solved = search(next, source, request, catalog, target, attempt, budget);
            if (solved != null) return solved;
        }
        return null;
    }

    private static State closeExistingSockets(State state, SearchBudget budget) {
        if (!budget.step()) return null;
        if (state.opens.isEmpty()) return state;
        OpenSocket active = state.opens.getFirst();
        for (int i = 1; i < state.opens.size(); i++) {
            OpenSocket other = state.opens.get(i);
            if (active.roomIndex == other.roomIndex || !connects(active.socket, other.socket)) continue;
            State paired = pairExisting(state, 0, i, active, other);
            State result = closeExistingSockets(paired, budget);
            if (result != null) return result;
        }
        return null;
    }

    private static State pairExisting(State state, int firstIndex, int secondIndex,
                                      OpenSocket first, OpenSocket second) {
        List<OpenSocket> opens = new ArrayList<>(state.opens);
        opens.remove(Math.max(firstIndex, secondIndex));
        opens.remove(Math.min(firstIndex, secondIndex));
        List<DungeonConnection> connections = new ArrayList<>(state.connections);
        connections.add(new DungeonConnection(
                first.roomIndex, first.socketIndex, second.roomIndex, second.socketIndex));
        return new State(state.rooms, opens, connections, state.quotaCounts, state.entranceRoom);
    }

    private static List<Candidate> candidates(State state, OpenSocket active, GoldGolemTemplate source,
                                              DungeonGenerationRequest request, Catalog catalog,
                                              int target, int attempt) {
        Map<CandidateGeometry, Candidate> unique = new LinkedHashMap<>();
        int remainingAfter = target - state.rooms.size() - 1;
        for (TemplateInfo info : catalog.placeable) {
            boolean entrance = info.entrance;
            if (entrance && state.entranceRoom >= 0) continue;
            if (!entrance && state.entranceRoom < 0 && remainingAfter == 0) continue;
            int[] counts = state.quotaCounts.clone();
            countQuotas(info, request, counts);
            if (!withinQuotaMaximums(counts, request)
                    || !quotaFeasible(counts, request, remainingAfter)) continue;

            for (int inputIndex = 0; inputIndex < info.template.sockets().size(); inputIndex++) {
                RoomSocket input = info.template.sockets().get(inputIndex);
                if (!input.compatibleWith(active.socket)) continue;
                for (RoomTransform transform : TRANSFORMS) {
                    RoomSocket transformed = input.transformed(transform);
                    if (transformed.facing() != active.socket.facing().getOpposite()) continue;
                    RoomPlacement placement = RoomPlacement.connect(
                            info.index, info.template, inputIndex, transform, active.socket);
                    double rank = candidateRank(state, active, info, placement, request, target, attempt);
                    Candidate candidate = new Candidate(info, placement, inputIndex, counts, rank);
                    CandidateGeometry geometry = new CandidateGeometry(
                            info.index, placement.sockets(info.template), placement.footprint(info.template));
                    Candidate previous = unique.get(geometry);
                    if (previous == null || candidate.rank < previous.rank) unique.put(geometry, candidate);
                }
            }
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingDouble(Candidate::rank)
                .thenComparingInt(candidate -> candidate.info.index)
                .thenComparingInt(Candidate::inputSocket));
        return result;
    }

    private static double candidateRank(State state, OpenSocket active, TemplateInfo info,
                                        RoomPlacement placement, DungeonGenerationRequest request,
                                        int target, int attempt) {
        DungeonLayoutProfile profile = request.layout();
        int degree = info.template.doorCount();
        double desiredDegree = 2.0 + profile.branchiness() - profile.deadEndFrequency();
        if (profile.style() == DungeonLayoutStyle.HUB_AND_SPOKE && state.rooms.size() % 12 == 0) {
            desiredDegree = 4.0;
        }
        double degreePenalty = Math.abs(degree - desiredDegree) * 20.0;
        BlockPos center = transformedCenter(info.template, placement);
        double distance = Math.hypot(center.getX(), center.getZ());
        double spatial = (profile.compactness() * 2.0 - 1.0) * distance;
        double repetition = state.rooms.get(active.roomIndex).info.index == info.index ? 25.0 : 0.0;
        double entranceDelay = info.entrance
                ? Math.max(0, request.bossRule().minimumEntranceDistance() - state.rooms.size()) * 50.0 : 0.0;
        double endpointPenalty = endpointPenalty(state, active, info, placement, target);
        double quotaPreference = 0.0;
        for (int i = 0; i < request.specialRooms().size(); i++) {
            SpecialRoomQuota quota = request.specialRooms().get(i);
            if (!info.markers.contains(quota.marker())) continue;
            int range = quota.maximumRooms() - quota.minimumRooms() + 1;
            int preferred = quota.minimumRooms() + Math.floorMod(
                    (int) mix(request.seed() ^ ((long) i << 32)), range);
            quotaPreference += state.quotaCounts[i] < preferred ? -12.0 : 12.0;
        }
        long random = mix(request.seed() ^ ((long) attempt << 48) ^ ((long) state.rooms.size() << 32)
                ^ ((long) info.index << 16) ^ placement.offset().hashCode()
                ^ (placement.transform().rotation() * 2L + (placement.transform().mirror() ? 1L : 0L)));
        return degreePenalty + spatial + repetition + entranceDelay + endpointPenalty + quotaPreference
                + (random >>> 11) * 0x1.0p-53;
    }

    private static double endpointPenalty(State state, OpenSocket active, TemplateInfo info,
                                          RoomPlacement placement, int target) {
        if (info.template.doorCount() != 2 || state.opens.size() != 2) return 0.0;
        OpenSocket other = state.opens.getFirst().equals(active)
                ? state.opens.getLast() : state.opens.getFirst();
        List<RoomSocket> sockets = placement.sockets(info.template);
        RoomSocket output = sockets.getFirst().facing() == active.socket.facing().getOpposite()
                && connects(sockets.getFirst(), active.socket) ? sockets.getLast() : sockets.getFirst();
        int distance = Math.abs(output.anchor().getX() - other.socket.anchor().getX())
                + Math.abs(output.anchor().getY() - other.socket.anchor().getY())
                + Math.abs(output.anchor().getZ() - other.socket.anchor().getZ());
        RoomSocket first = sockets.getFirst();
        RoomSocket second = sockets.getLast();
        int stepSpan = Math.max(1,
                Math.abs(first.anchor().getX() - second.anchor().getX())
                        + Math.abs(first.anchor().getY() - second.anchor().getY())
                        + Math.abs(first.anchor().getZ() - second.anchor().getZ()));
        int remainingAfter = target - state.rooms.size() - 1;
        double desiredDistance = remainingAfter * stepSpan * 0.45;
        return Math.abs(distance - desiredDistance) * 8.0;
    }

    private static State placeCandidate(State state, int activeIndex, OpenSocket active,
                                        Candidate candidate, DungeonGenerationRequest request) {
        int newIndex = state.rooms.size();
        StateRoom room = stateRoom(newIndex, candidate.placement, candidate.info);
        List<SocketMatch> matches = new ArrayList<>();
        matches.add(new SocketMatch(candidate.inputSocket, activeIndex, active));
        Set<Integer> usedCandidateSockets = new HashSet<>();
        Set<Integer> usedOpenIndices = new HashSet<>();
        usedCandidateSockets.add(candidate.inputSocket);
        usedOpenIndices.add(activeIndex);

        for (int socketIndex = 0; socketIndex < room.sockets.size(); socketIndex++) {
            if (usedCandidateSockets.contains(socketIndex)) continue;
            RoomSocket socket = room.sockets.get(socketIndex);
            for (int openIndex = 0; openIndex < state.opens.size(); openIndex++) {
                if (usedOpenIndices.contains(openIndex)) continue;
                OpenSocket open = state.opens.get(openIndex);
                if (connects(socket, open.socket)) {
                    matches.add(new SocketMatch(socketIndex, openIndex, open));
                    usedCandidateSockets.add(socketIndex);
                    usedOpenIndices.add(openIndex);
                    break;
                }
            }
        }
        if (overlaps(state, room, matches)) return null;

        List<StateRoom> rooms = new ArrayList<>(state.rooms);
        rooms.add(room);
        List<OpenSocket> opens = new ArrayList<>();
        for (int i = 0; i < state.opens.size(); i++) {
            if (!usedOpenIndices.contains(i)) opens.add(state.opens.get(i));
        }
        for (int socket = 0; socket < room.sockets.size(); socket++) {
            if (!usedCandidateSockets.contains(socket)) {
                opens.add(new OpenSocket(newIndex, socket, room.sockets.get(socket)));
            }
        }
        List<DungeonConnection> connections = new ArrayList<>(state.connections);
        for (SocketMatch match : matches) {
            connections.add(new DungeonConnection(
                    match.open.roomIndex, match.open.socketIndex, newIndex, match.candidateSocket));
        }
        int entranceRoom = candidate.info.entrance ? newIndex : state.entranceRoom;
        return new State(rooms, opens, connections, candidate.quotaCounts, entranceRoom);
    }

    private static boolean overlaps(State state, StateRoom candidate, List<SocketMatch> matches) {
        Map<Integer, Set<BlockPos>> allowedByRoom = new HashMap<>();
        for (SocketMatch match : matches) {
            Set<BlockPos> allowed = allowedByRoom.computeIfAbsent(match.open.roomIndex,
                    ignored -> new HashSet<>());
            allowed.addAll(candidate.sockets.get(match.candidateSocket).connectionPositions());
            allowed.addAll(match.open.socket.connectionPositions());
        }
        for (StateRoom existing : state.rooms) {
            if (!candidate.bounds.intersects(existing.bounds)) continue;
            Set<BlockPos> allowed = allowedByRoom.getOrDefault(existing.index, Set.of());
            Set<BlockPos> smaller = candidate.footprint.size() <= existing.footprint.size()
                    ? candidate.footprint : existing.footprint;
            Set<BlockPos> larger = smaller == candidate.footprint ? existing.footprint : candidate.footprint;
            for (BlockPos position : smaller) {
                if (larger.contains(position) && !allowed.contains(position)) return true;
            }
        }
        return false;
    }

    private static boolean validCompleted(State state, DungeonGenerationRequest request, int target) {
        if (state.rooms.size() != target || state.entranceRoom < 0 || !state.opens.isEmpty()) return false;
        for (int i = 0; i < request.specialRooms().size(); i++) {
            SpecialRoomQuota quota = request.specialRooms().get(i);
            if (state.quotaCounts[i] < quota.minimumRooms() || state.quotaCounts[i] > quota.maximumRooms()) {
                return false;
            }
        }
        Graph graph = graph(state);
        int entranceDistance = distance(graph.adjacency, state.entranceRoom, 0, -1);
        if (entranceDistance < request.bossRule().minimumEntranceDistance()) return false;
        Set<Integer> bossNeighbors = new LinkedHashSet<>(graph.adjacency.get(0));
        if (bossNeighbors.size() != state.rooms.getFirst().sockets.size() || bossNeighbors.size() < 2) return false;
        for (int neighbor : bossNeighbors) {
            if (distance(graph.adjacency, state.entranceRoom, neighbor, 0) >= INF) return false;
        }
        List<Integer> neighbors = List.copyOf(bossNeighbors);
        for (int i = 0; i < neighbors.size(); i++) {
            for (int j = i + 1; j < neighbors.size(); j++) {
                if (distance(graph.adjacency, neighbors.get(i), neighbors.get(j), 0)
                        < request.bossRule().minimumRouteSeparation()) return false;
            }
        }
        return true;
    }

    private static DungeonPlan toPlan(GoldGolemTemplate source, DungeonGenerationRequest request, State state) {
        Graph graph = graph(state);
        List<PlannedDungeonRoom> rooms = new ArrayList<>();
        Map<Block, Integer> specialCounts = new LinkedHashMap<>();
        for (int i = 0; i < request.specialRooms().size(); i++) {
            specialCounts.put(request.specialRooms().get(i).marker(), state.quotaCounts[i]);
        }
        for (StateRoom room : state.rooms) {
            PlannedDungeonRoom.Role role;
            if (room.index == 0) role = PlannedDungeonRoom.Role.BOSS;
            else if (room.index == state.entranceRoom) role = PlannedDungeonRoom.Role.ENTRANCE;
            else if (request.specialRooms().stream().anyMatch(quota -> room.info.markers.contains(quota.marker()))) {
                role = PlannedDungeonRoom.Role.SPECIAL;
            } else role = PlannedDungeonRoom.Role.ORDINARY;
            rooms.add(new PlannedDungeonRoom(room.index, room.placement, role));
        }
        BlockPos entranceMarker = transformedMarker(
                state.rooms.get(state.entranceRoom), request.entranceMarker());
        DungeonBounds bounds = bounds(state.rooms);
        int loops = state.connections.size() - state.rooms.size() + 1;
        int deadEnds = (int) graph.adjacency.stream().filter(edges -> edges.size() == 1).count();
        int criticalPath = distance(graph.adjacency, state.entranceRoom, 0, -1);
        double score = styleScore(request.layout(), state.rooms.size(), loops, deadEnds,
                criticalPath, bounds);
        DungeonPlanStats stats = new DungeonPlanStats(state.rooms.size(), state.connections.size(),
                loops, deadEnds, criticalPath, specialCounts, score);
        return new DungeonPlan(source, request, rooms, state.connections,
                state.entranceRoom, 0, entranceMarker, bounds, stats);
    }

    private static double styleScore(DungeonLayoutProfile profile, int rooms, int loops,
                                     int deadEnds, int criticalPath, DungeonBounds bounds) {
        double loopRatio = rooms <= 1 ? 0.0 : (double) loops / rooms;
        double deadRatio = (double) deadEnds / rooms;
        double pathRatio = (double) criticalPath / rooms;
        int width = bounds.maximum().getX() - bounds.minimum().getX() + 1;
        int depth = bounds.maximum().getZ() - bounds.minimum().getZ() + 1;
        double spread = Math.sqrt((double) width * depth) / Math.max(1.0, rooms);
        return Math.abs(loopRatio - profile.loopiness() * 0.25) * 100.0
                + Math.abs(deadRatio - profile.deadEndFrequency()) * 50.0
                + Math.abs(pathRatio - profile.criticalPathLength()) * 75.0
                + Math.abs(spread - (1.0 - profile.compactness())) * 20.0;
    }

    private static Graph graph(State state) {
        List<Set<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < state.rooms.size(); i++) adjacency.add(new LinkedHashSet<>());
        for (DungeonConnection edge : state.connections) {
            adjacency.get(edge.firstRoom()).add(edge.secondRoom());
            adjacency.get(edge.secondRoom()).add(edge.firstRoom());
        }
        return new Graph(adjacency);
    }

    private static int distance(List<Set<Integer>> adjacency, int start, int target, int excluded) {
        if (start == excluded || target == excluded) return INF;
        int[] distances = new int[adjacency.size()];
        Arrays.fill(distances, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        distances[start] = 0;
        queue.add(start);
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            if (current == target) return distances[current];
            for (int next : adjacency.get(current)) {
                if (next == excluded || distances[next] >= 0) continue;
                distances[next] = distances[current] + 1;
                queue.addLast(next);
            }
        }
        return INF;
    }

    private static StateRoom stateRoom(int index, RoomPlacement placement, TemplateInfo info) {
        return new StateRoom(index, placement, info,
                placement.sockets(info.template), placement.footprint(info.template),
                transformedBounds(info.template, placement));
    }

    private static LocalBounds transformedBounds(RoomTemplate template, RoomPlacement placement) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos corner : corners(template.minBounds(), template.maxBounds())) {
            BlockPos position = placement.transform().apply(corner).offset(placement.offset());
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return new LocalBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static BlockPos transformedCenter(RoomTemplate template, RoomPlacement placement) {
        BlockPos local = new BlockPos(
                (template.minBounds().getX() + template.maxBounds().getX()) / 2,
                (template.minBounds().getY() + template.maxBounds().getY()) / 2,
                (template.minBounds().getZ() + template.maxBounds().getZ()) / 2);
        return placement.transform().apply(local).offset(placement.offset());
    }

    private static BlockPos transformedMarker(StateRoom room, Block marker) {
        BlockPos local = room.info.markerPositions.get(marker).getFirst();
        return room.placement.transform().apply(local).offset(room.placement.offset());
    }

    private static DungeonBounds bounds(List<StateRoom> rooms) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (StateRoom room : rooms) {
            for (BlockPos corner : corners(room.info.template.minBounds(), room.info.template.maxBounds())) {
                BlockPos transformed = room.placement.transform().apply(corner).offset(room.placement.offset());
                minX = Math.min(minX, transformed.getX());
                minY = Math.min(minY, transformed.getY());
                minZ = Math.min(minZ, transformed.getZ());
                maxX = Math.max(maxX, transformed.getX());
                maxY = Math.max(maxY, transformed.getY());
                maxZ = Math.max(maxZ, transformed.getZ());
            }
        }
        return new DungeonBounds(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    static List<BlockPos> corners(BlockPos min, BlockPos max) {
        List<BlockPos> result = new ArrayList<>(8);
        for (int x : new int[]{min.getX(), max.getX()}) {
            for (int y : new int[]{min.getY(), max.getY()}) {
                for (int z : new int[]{min.getZ(), max.getZ()}) result.add(new BlockPos(x, y, z));
            }
        }
        return result;
    }

    private static int chooseActive(State state) {
        for (int i = 0; i < state.opens.size(); i++) {
            if (state.opens.get(i).roomIndex == 0) return i;
        }
        return 0;
    }

    private static boolean connects(RoomSocket first, RoomSocket second) {
        return first.compatibleWith(second)
                && first.facing() == second.facing().getOpposite()
                && first.anchor().equals(second.anchor().relative(second.right(), second.apertureWidth() - 1));
    }

    private static boolean quotaFeasible(int[] counts, DungeonGenerationRequest request, int remainingRooms) {
        for (int i = 0; i < counts.length; i++) {
            SpecialRoomQuota quota = request.specialRooms().get(i);
            if (counts[i] > quota.maximumRooms() || counts[i] + remainingRooms < quota.minimumRooms()) return false;
        }
        return true;
    }

    private static boolean withinQuotaMaximums(int[] counts, DungeonGenerationRequest request) {
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > request.specialRooms().get(i).maximumRooms()) return false;
        }
        return true;
    }

    private static void countQuotas(TemplateInfo info, DungeonGenerationRequest request, int[] counts) {
        for (int i = 0; i < request.specialRooms().size(); i++) {
            if (info.markers.contains(request.specialRooms().get(i).marker())) counts[i]++;
        }
    }

    private static List<Integer> targetOrder(RoomCountGoal goal) {
        List<Integer> result = new ArrayList<>();
        result.add(goal.target());
        for (int delta = 1; result.size() < goal.maximum() - goal.minimum() + 1; delta++) {
            if (goal.target() - delta >= goal.minimum()) result.add(goal.target() - delta);
            if (goal.target() + delta <= goal.maximum()) result.add(goal.target() + delta);
        }
        return result;
    }

    private static List<RoomTransform> transforms() {
        List<RoomTransform> result = new ArrayList<>(8);
        for (int rotation = 0; rotation < 4; rotation++) {
            result.add(new RoomTransform(rotation, false));
            result.add(new RoomTransform(rotation, true));
        }
        return List.copyOf(result);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static DungeonPlanResult failure(DungeonPlanResult.Status status, String diagnostic) {
        return new DungeonPlanResult(status, null, List.of(diagnostic));
    }

    private record State(List<StateRoom> rooms, List<OpenSocket> opens,
                         List<DungeonConnection> connections, int[] quotaCounts, int entranceRoom) {}
    private record StateRoom(int index, RoomPlacement placement, TemplateInfo info,
                             List<RoomSocket> sockets, Set<BlockPos> footprint, LocalBounds bounds) {}
    private record OpenSocket(int roomIndex, int socketIndex, RoomSocket socket) {}
    private record SocketMatch(int candidateSocket, int openIndex, OpenSocket open) {}
    private record Candidate(TemplateInfo info, RoomPlacement placement, int inputSocket,
                             int[] quotaCounts, double rank) {}
    private record CandidateGeometry(int templateIndex, List<RoomSocket> sockets, Set<BlockPos> footprint) {}
    private record Graph(List<Set<Integer>> adjacency) {}
    private record LocalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private boolean intersects(LocalBounds other) {
            return minX <= other.maxX && maxX >= other.minX
                    && minY <= other.maxY && maxY >= other.minY
                    && minZ <= other.maxZ && maxZ >= other.minZ;
        }
    }

    private static final class SearchBudget {
        private int remaining;
        private boolean exhausted;
        private SearchBudget(int remaining) { this.remaining = remaining; }
        private boolean step() {
            if (--remaining >= 0) return true;
            exhausted = true;
            return false;
        }
    }

    private static final class Catalog {
        private final List<TemplateInfo> all = new ArrayList<>();
        private final List<TemplateInfo> bosses = new ArrayList<>();
        private final List<TemplateInfo> entrances = new ArrayList<>();
        private final List<TemplateInfo> placeable = new ArrayList<>();
        private final DungeonGenerationRequest request;

        private Catalog(List<RoomTemplate> templates, DungeonGenerationRequest request) {
            this.request = request;
            for (int index = 0; index < templates.size(); index++) {
                TemplateInfo info = new TemplateInfo(index, templates.get(index), request);
                all.add(info);
                if (info.boss) bosses.add(info);
                if (info.entrance) entrances.add(info);
                if (!info.boss && (!info.hasEntranceMarker || info.entrance)) placeable.add(info);
            }
        }

        private String validate() {
            if (bosses.isEmpty()) return "No room contains the configured boss marker";
            if (entrances.isEmpty()) return "No room contains exactly one configured entrance marker";
            if (bosses.stream().noneMatch(info -> info.template.doorCount() >= 2)) {
                return "Boss room templates require at least two doorway sockets";
            }
            if (all.stream().anyMatch(info -> info.boss && info.entrance)) {
                return "A room template cannot contain both the entrance and boss markers";
            }
            for (SpecialRoomQuota quota : request.specialRooms()) {
                long matching = all.stream().filter(info -> info.markers.contains(quota.marker())).count();
                if (matching == 0 && quota.minimumRooms() > 0) {
                    return "No room contains required special marker " + quota.marker();
                }
            }
            return null;
        }
    }

    private static final class TemplateInfo {
        private final int index;
        private final RoomTemplate template;
        private final Set<Block> markers;
        private final Map<Block, List<BlockPos>> markerPositions;
        private final boolean hasEntranceMarker;
        private final boolean entrance;
        private final boolean boss;

        private TemplateInfo(int index, RoomTemplate template, DungeonGenerationRequest request) {
            this.index = index;
            this.template = template;
            Map<Block, List<BlockPos>> positions = new LinkedHashMap<>();
            template.voxels().forEach((position, state) -> positions
                    .computeIfAbsent(state.getBlock(), ignored -> new ArrayList<>()).add(position));
            positions.replaceAll((ignored, value) -> List.copyOf(value));
            markerPositions = Map.copyOf(positions);
            markers = Set.copyOf(positions.keySet());
            int entranceMarkers = positions.getOrDefault(request.entranceMarker(), List.of()).size();
            hasEntranceMarker = entranceMarkers > 0;
            entrance = entranceMarkers == 1;
            boss = markers.contains(request.bossRule().marker());
        }
    }
}
