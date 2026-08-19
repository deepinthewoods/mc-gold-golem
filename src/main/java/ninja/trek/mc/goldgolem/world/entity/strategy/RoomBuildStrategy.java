package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.room.RoomPlacement;
import ninja.trek.mc.goldgolem.room.RoomSocket;
import ninja.trek.mc.goldgolem.room.RoomTemplate;
import ninja.trek.mc.goldgolem.room.RoomTransform;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interactive room-path builder with persistent branches, endings, collision fallback, and excavation. */
public final class RoomBuildStrategy extends AbstractBuildStrategy {
    private static final List<RoomTransform> TRANSFORMS = List.of(
            new RoomTransform(0, false), new RoomTransform(0, true),
            new RoomTransform(1, false), new RoomTransform(1, true),
            new RoomTransform(2, false), new RoomTransform(2, true),
            new RoomTransform(3, false), new RoomTransform(3, true)
    );
    private static final int DOORWAY_MARGIN = 2;

    private List<RoomTemplate> templates = List.of();
    private PlacementPlanner planner;
    private final List<RoomPlacement> placedRooms = new ArrayList<>();
    private final List<Branch> unusedBranches = new ArrayList<>();
    private RoomSocket activeSocket;
    private boolean endingQueued;
    private int placementCount;

    private RoomPlacement currentPlacement;
    private final ArrayDeque<BlockPos> excavation = new ArrayDeque<>();
    private final Map<BlockPos, BlockState> remainingStates = new LinkedHashMap<>();
    private boolean plannerLoaded;
    private boolean finishAfterPlacement;
    private boolean limitMessageSent;
    private boolean needsReconstruction;
    private Set<BlockPos> savedRemaining = Set.of();
    private Set<BlockPos> savedExcavation = Set.of();

    @Override
    public BuildMode getMode() {
        return BuildMode.ROOM;
    }

    @Override
    public String getNbtPrefix() {
        return "Room";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        if (planner == null) planner = new PlacementPlanner(golem);
    }

    public void setTemplates(List<RoomTemplate> templates) {
        this.templates = templates == null ? List.of() : List.copyOf(templates);
    }

    @Override
    public void tick(GoldGolemEntity golem, Player owner) {
        if (templates.isEmpty()) {
            setTemplates(golem.getRoomTemplates());
            if (templates.isEmpty()) return;
        }
        if (planner == null) planner = new PlacementPlanner(golem);
        if (needsReconstruction) reconstructCurrent(golem);

        if (currentPlacement != null) {
            tickCurrentPlacement(golem);
            return;
        }
        if (activeSocket == null || owner == null || !owner.onGround()) return;
        if (placedRooms.size() >= golem.getRoomMemoryLimit()) {
            pauseAtLimit(golem);
            return;
        }

        Vec3 anchor = Vec3.atCenterOf(activeSocket.anchor());
        Vec3 playerPosition = owner.position();
        double dx = playerPosition.x - anchor.x;
        double dz = playerPosition.z - anchor.z;
        if (Math.hypot(dx, dz) < trackingThreshold()) return;

        Candidate accepted = chooseCandidate(golem, playerPosition);
        if (accepted == null) {
            if (owner instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendOverlayMessage(Component.literal(
                        "[Gold Golem] No room fits this doorway; the exit remains open"));
            }
            return;
        }
        acceptCandidate(golem, accepted);
    }

    private double trackingThreshold() {
        int longest = 1;
        for (RoomTemplate template : templates) {
            int width = template.maxBounds().getX() - template.minBounds().getX() + 1;
            int depth = template.maxBounds().getZ() - template.minBounds().getZ() + 1;
            longest = Math.max(longest, Math.max(width, depth));
        }
        return longest + 1.0;
    }

    private Candidate chooseCandidate(GoldGolemEntity golem, Vec3 playerPosition) {
        List<Candidate> candidates = new ArrayList<>();
        for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
            RoomTemplate template = templates.get(templateIndex);
            if (endingQueued != (template.doorCount() == 1)) continue;
            for (int inputIndex = 0; inputIndex < template.sockets().size(); inputIndex++) {
                RoomSocket input = template.sockets().get(inputIndex);
                if (!input.compatibleWith(activeSocket)) continue;
                for (RoomTransform transform : TRANSFORMS) {
                    RoomSocket transformedInput = input.transformed(transform);
                    if (transformedInput.facing() != activeSocket.facing().getOpposite()) continue;
                    RoomPlacement placement = RoomPlacement.connect(
                            templateIndex, template, inputIndex, transform, activeSocket);
                    List<RoomSocket> sockets = placement.sockets(template);
                    if (template.doorCount() == 1) {
                        candidates.add(new Candidate(placement, -1, 0.0, tieBreak(golem, templateIndex,
                                inputIndex, transform, -1)));
                    } else {
                        for (int outgoing = 0; outgoing < sockets.size(); outgoing++) {
                            if (outgoing == inputIndex) continue;
                            double score = pathScore(activeSocket, sockets.get(outgoing), playerPosition);
                            candidates.add(new Candidate(placement, outgoing, score, tieBreak(golem,
                                    templateIndex, inputIndex, transform, outgoing)));
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).thenComparingLong(Candidate::tieBreak));
        for (Candidate candidate : candidates) {
            if (prepareCandidate(golem, candidate)) return candidate;
        }
        return null;
    }

    private static double pathScore(RoomSocket input, RoomSocket output, Vec3 player) {
        double guideX = player.x - (input.anchor().getX() + 0.5);
        double guideZ = player.z - (input.anchor().getZ() + 0.5);
        double guideLength = Math.max(1.0e-6, Math.hypot(guideX, guideZ));
        double outX = output.anchor().getX() - input.anchor().getX();
        double outZ = output.anchor().getZ() - input.anchor().getZ();
        double outLength = Math.max(1.0e-6, Math.hypot(outX, outZ));
        double alignment = (outX * guideX + outZ * guideZ) / (outLength * guideLength);
        double distance = Math.hypot(player.x - output.anchor().getX(), player.z - output.anchor().getZ());
        double facingAlignment = (output.facing().getStepX() * guideX
                + output.facing().getStepZ() * guideZ) / guideLength;
        return distance - alignment * 6.0 - facingAlignment * 2.0;
    }

    private long tieBreak(
            GoldGolemEntity golem, int templateIndex, int inputIndex, RoomTransform transform, int outgoing
    ) {
        long value = golem.getUUID().getMostSignificantBits() ^ golem.getUUID().getLeastSignificantBits();
        value ^= (long) placementCount * 0x9E3779B97F4A7C15L;
        value ^= (long) templateIndex << 32;
        value ^= (long) inputIndex << 20;
        value ^= (long) (outgoing + 1) << 8;
        value ^= transform.rotation() * 2L + (transform.mirror() ? 1L : 0L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }

    private boolean prepareCandidate(GoldGolemEntity golem, Candidate candidate) {
        RoomTemplate template = templates.get(candidate.placement().templateIndex());
        Set<BlockPos> gradientExcavation = new LinkedHashSet<>();
        Map<BlockPos, BlockState> states = transformedGradientStates(
                golem, candidate.placement(), template, gradientExcavation);
        Set<BlockPos> requiredAir = new LinkedHashSet<>(candidate.placement().requiredAir(template));
        requiredAir.addAll(gradientExcavation);
        RoomSocket candidateInput = candidate.placement().inputSocket(template);
        Set<BlockPos> targetFrame = activeSocket.framePositions();
        Set<BlockPos> candidateFrame = candidateInput.framePositions();
        Set<BlockPos> allowedOverlap = new HashSet<>(activeSocket.connectionPositions());
        allowedOverlap.addAll(candidateInput.connectionPositions());

        Set<BlockPos> candidateFootprint = new HashSet<>(states.keySet());
        candidateFootprint.addAll(requiredAir);
        for (RoomPlacement placed : placedRooms) {
            RoomTemplate placedTemplate = templates.get(placed.templateIndex());
            for (BlockPos position : placed.footprint(placedTemplate)) {
                if (candidateFootprint.contains(position) && !allowedOverlap.contains(position)) return false;
            }
        }

        ArrayDeque<BlockPos> toExcavate = new ArrayDeque<>();
        Set<BlockPos> preserveTargetFrame = new HashSet<>(targetFrame);
        preserveTargetFrame.removeAll(candidateFrame);
        Set<BlockPos> preservedStates = new HashSet<>();
        for (Map.Entry<BlockPos, BlockState> entry : states.entrySet()) {
            BlockState current = golem.level().getBlockState(entry.getKey());
            if (current.equals(entry.getValue()) || current.isAir() || current.canBeReplaced()) continue;
            if (preserveTargetFrame.contains(entry.getKey())) {
                preservedStates.add(entry.getKey());
                continue;
            }
            if (!candidateFrame.contains(entry.getKey()) || targetFrame.contains(entry.getKey())) return false;
        }
        preservedStates.forEach(states::remove);
        for (BlockPos position : requiredAir) {
            if (states.containsKey(position)) continue;
            BlockState current = golem.level().getBlockState(position);
            if (current.isAir()) continue;
            if (!current.getFluidState().isEmpty() || current.getDestroySpeed(golem.level(), position) < 0.0f) {
                return false;
            }
            toExcavate.add(position);
        }

        remainingStates.clear();
        for (Map.Entry<BlockPos, BlockState> entry : states.entrySet()) {
            if (!golem.level().getBlockState(entry.getKey()).equals(entry.getValue())) {
                remainingStates.put(entry.getKey(), entry.getValue());
            }
        }
        excavation.clear();
        excavation.addAll(toExcavate);
        return true;
    }

    private Map<BlockPos, BlockState> transformedGradientStates(
            GoldGolemEntity golem, RoomPlacement placement, RoomTemplate template,
            Set<BlockPos> gradientExcavation
    ) {
        Map<BlockPos, BlockState> states = placement.blocks(template);
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : states.entrySet()) {
            BlockState state = entry.getValue();
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            Integer group = golem.getRoomBlockGroup().get(blockId);
            if (group == null || group < 0 || group >= golem.getRoomGroupSlots().size()) {
                result.put(entry.getKey(), state);
                continue;
            }
            String[] slots = golem.getRoomGroupSlots().get(group);
            boolean populated = false;
            for (String slot : slots) populated |= slot != null && !slot.isEmpty();
            if (!populated) {
                result.put(entry.getKey(), state);
                continue;
            }
            String sampled = sampleGradient(slots, golem.getRoomGroupWindows().get(group),
                    golem.getRoomGroupNoiseScales().get(group), entry.getKey());
            if (sampled == null || sampled.isEmpty()) continue;
            if (GradientSlotUtil.isMineAction(sampled)) {
                gradientExcavation.add(entry.getKey());
                continue;
            }
            BlockState sampledState = golem.getBlockStateFromId(sampled);
            if (sampledState != null) result.put(entry.getKey(), sampledState);
        }
        return result;
    }

    private void acceptCandidate(GoldGolemEntity golem, Candidate candidate) {
        RoomTemplate template = templates.get(candidate.placement().templateIndex());
        currentPlacement = candidate.placement();
        placedRooms.add(currentPlacement);
        int roomIndex = placedRooms.size() - 1;
        List<RoomSocket> sockets = currentPlacement.sockets(template);
        for (int i = 0; i < sockets.size(); i++) {
            if (i == currentPlacement.inputSocketIndex() || i == candidate.outgoingSocket()) continue;
            unusedBranches.add(new Branch(sockets.get(i), roomIndex));
        }
        activeSocket = candidate.outgoingSocket() < 0 ? null : sockets.get(candidate.outgoingSocket());
        finishAfterPlacement = candidate.outgoingSocket() < 0;
        endingQueued = false;
        placementCount++;
        planner.clear();
        plannerLoaded = false;
        limitMessageSent = false;
        if (activeSocket != null) golem.setTrackStart(Vec3.atCenterOf(activeSocket.anchor()));
    }

    private void tickCurrentPlacement(GoldGolemEntity golem) {
        if (!excavation.isEmpty()) {
            if (!shouldPlaceThisTick()) return;
            BlockPos target = excavation.removeFirst();
            BlockState state = golem.level().getBlockState(target);
            if (!state.isAir()) {
                if (!state.getFluidState().isEmpty() || state.getDestroySpeed(golem.level(), target) < 0.0f) {
                    excavation.addFirst(target);
                    return;
                }
                golem.level().destroyBlock(target, false);
                golem.beginHandAnimation(isLeftHandActive(), target, excavation.peekFirst());
                alternateHand();
            }
            return;
        }

        if (!plannerLoaded) {
            planner.setBlocks(new ArrayList<>(remainingStates.keySet()),
                    position -> golem.level().getBlockState(position).equals(remainingStates.get(position)));
            plannerLoaded = true;
        }
        PlacementPlanner.TickResult result = planner.tick((position, next) -> {
            BlockState expected = remainingStates.get(position);
            if (expected == null || golem.level().getBlockState(position).equals(expected)) {
                remainingStates.remove(position);
                return true;
            }
            long key = position.asLong();
            if (!golem.recordPlaced(key)) return false;
            String blockId = BuiltInRegistries.BLOCK.getKey(expected.getBlock()).toString();
            if (!golem.consumeBlockFromInventory(blockId)) {
                golem.unrecordPlaced(key);
                golem.handleMissingBuildingBlock();
                return false;
            }
            if (!golem.level().setBlock(position, expected, 3)) {
                golem.unrecordPlaced(key);
                return false;
            }
            remainingStates.remove(position);
            golem.beginHandAnimation(isLeftHandActive(), position, next);
            return true;
        }, shouldPlaceThisTick());
        if (result == PlacementPlanner.TickResult.PLACED_BLOCK) alternateHand();
        if ((result == PlacementPlanner.TickResult.COMPLETED || planner.isComplete()) && remainingStates.isEmpty()) {
            currentPlacement = null;
            plannerLoaded = false;
            planner.clear();
            if (finishAfterPlacement) {
                finishAfterPlacement = false;
                golem.setBuildingPaths(false);
                golem.setTrackStart(null);
            }
        }
    }

    private void reconstructCurrent(GoldGolemEntity golem) {
        needsReconstruction = false;
        if (currentPlacement == null || currentPlacement.templateIndex() < 0
                || currentPlacement.templateIndex() >= templates.size()) return;
        RoomTemplate template = templates.get(currentPlacement.templateIndex());
        Map<BlockPos, BlockState> reconstructed = transformedGradientStates(
                golem, currentPlacement, template, new LinkedHashSet<>());
        remainingStates.clear();
        for (BlockPos position : savedRemaining) {
            BlockState expected = reconstructed.get(position);
            if (expected != null && !golem.level().getBlockState(position).equals(expected)) {
                remainingStates.put(position, expected);
            }
        }
        excavation.clear();
        for (BlockPos position : savedExcavation) {
            if (!golem.level().getBlockState(position).isAir()) excavation.add(position);
        }
        savedRemaining = Set.of();
        savedExcavation = Set.of();
        plannerLoaded = false;
    }

    private void pauseAtLimit(GoldGolemEntity golem) {
        if (activeSocket != null && !placedRooms.isEmpty()) {
            Branch branch = new Branch(activeSocket, placedRooms.size() - 1);
            if (!unusedBranches.contains(branch)) unusedBranches.add(branch);
            activeSocket = null;
        }
        golem.setBuildingPaths(false);
        if (!limitMessageSent && golem.getOwnerPlayer() instanceof ServerPlayer owner) {
            owner.sendOverlayMessage(Component.literal(
                    "[Gold Golem] Room memory limit reached (" + golem.getRoomMemoryLimit() + ")"));
            limitMessageSent = true;
        }
    }

    @Override
    public FeedResult handleFeedInteraction(Player player) {
        if (waitingForResources) {
            waitingForResources = false;
            return FeedResult.RESUMED;
        }
        if (currentPlacement != null) return FeedResult.RESUMED;
        if (templates.isEmpty() && entity != null) setTemplates(entity.getRoomTemplates());
        if (templates.isEmpty()) return FeedResult.NOT_HANDLED;
        if (placedRooms.size() >= entity.getRoomMemoryLimit()) {
            pauseAtLimit(entity);
            return FeedResult.ALREADY_ACTIVE;
        }

        Branch nearest = nearestLocalBranch(entity.blockPosition());
        if (nearest != null) {
            unusedBranches.remove(nearest);
            activeSocket = nearest.socket();
        } else {
            RoomSocket sample = templates.getFirst().sockets().getFirst();
            activeSocket = new RoomSocket(
                    entity.blockPosition(), player.getDirection(),
                    sample.apertureWidth(), sample.apertureHeight(), sample.floorEdge());
        }
        entity.setTrackStart(Vec3.atCenterOf(activeSocket.anchor()));
        limitMessageSent = false;
        return FeedResult.STARTED;
    }

    private Branch nearestLocalBranch(BlockPos position) {
        int containingRoom = -1;
        for (int i = 0; i < placedRooms.size(); i++) {
            Set<BlockPos> footprint = placedRooms.get(i).footprint(templates.get(placedRooms.get(i).templateIndex()));
            if (containsWithMargin(footprint, position, DOORWAY_MARGIN)) {
                containingRoom = i;
                break;
            }
        }
        if (containingRoom < 0) return null;
        final int roomIndex = containingRoom;
        return unusedBranches.stream()
                .filter(branch -> branch.roomIndex() == roomIndex)
                .min(Comparator.comparingDouble(branch -> branch.socket().anchor().distSqr(position)))
                .orElse(null);
    }

    private static boolean containsWithMargin(Set<BlockPos> positions, BlockPos target, int margin) {
        if (positions.isEmpty()) return false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos position : positions) {
            minX = Math.min(minX, position.getX());
            minY = Math.min(minY, position.getY());
            minZ = Math.min(minZ, position.getZ());
            maxX = Math.max(maxX, position.getX());
            maxY = Math.max(maxY, position.getY());
            maxZ = Math.max(maxZ, position.getZ());
        }
        return target.getX() >= minX - margin && target.getX() <= maxX + margin
                && target.getY() >= minY - margin && target.getY() <= maxY + margin
                && target.getZ() >= minZ - margin && target.getZ() <= maxZ + margin;
    }

    @Override
    public boolean handleOwnerAttack(Player player) {
        if (entity == null || !entity.isBuildingPaths() || endingQueued || finishAfterPlacement) return false;
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!stack.is(Items.GOLD_NUGGET)) stack = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!stack.is(Items.GOLD_NUGGET)) return false;
        if (!player.isCreative()) stack.shrink(1);
        endingQueued = true;
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendOverlayMessage(Component.literal("[Gold Golem] End room queued"));
        }
        return true;
    }

    @Override
    public void handleOwnerDamage() {
        if (activeSocket != null) {
            int room = Math.max(0, placedRooms.size() - 1);
            unusedBranches.add(new Branch(activeSocket, room));
        }
        activeSocket = null;
    }

    @Override
    public boolean usesGroupUI() {
        return true;
    }

    @Override
    public boolean usesPlayerTracking() {
        return true;
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public void stop(GoldGolemEntity golem) {
        handleOwnerDamage();
        if (planner != null) planner.clear();
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        if (planner != null) planner.clear();
    }

    @Override
    public void writeNbt(CompoundTag nbt) {
        nbt.putBoolean("EndingQueued", endingQueued);
        nbt.putInt("PlacementCount", placementCount);
    }

    @Override
    public void readNbt(CompoundTag nbt) {
        endingQueued = nbt.getBooleanOr("EndingQueued", false);
        placementCount = nbt.getIntOr("PlacementCount", 0);
    }

    @Override
    public void writeLegacyNbt(ValueOutput view) {
        view.putBoolean("RoomEndingQueued", endingQueued);
        view.putInt("RoomPlacementCount", placementCount);
        view.putBoolean("RoomFinishAfter", finishAfterPlacement);
        view.putInt("RoomPlacedCount", placedRooms.size());
        for (int i = 0; i < placedRooms.size(); i++) writePlacement(view, "RoomPlaced" + i, placedRooms.get(i));
        view.putInt("RoomBranchCount", unusedBranches.size());
        for (int i = 0; i < unusedBranches.size(); i++) {
            writeSocket(view, "RoomBranch" + i, unusedBranches.get(i).socket());
            view.putInt("RoomBranch" + i + "Owner", unusedBranches.get(i).roomIndex());
        }
        view.putBoolean("RoomActiveExists", activeSocket != null);
        if (activeSocket != null) writeSocket(view, "RoomActive", activeSocket);
        view.putBoolean("RoomCurrentExists", currentPlacement != null);
        if (currentPlacement != null) writePlacement(view, "RoomCurrent", currentPlacement);
        view.putIntArray("RoomRemaining", packPositions(remainingStates.keySet()));
        view.putIntArray("RoomExcavation", packPositions(excavation));
        if (entity != null && entity.getTrackStart() != null) {
            view.putBoolean("RoomTrackExists", true);
            view.putDouble("RoomTrackX", entity.getTrackStart().x);
            view.putDouble("RoomTrackY", entity.getTrackStart().y);
            view.putDouble("RoomTrackZ", entity.getTrackStart().z);
        }
    }

    @Override
    public void readLegacyNbt(ValueInput view) {
        endingQueued = view.getBooleanOr("RoomEndingQueued", false);
        placementCount = view.getIntOr("RoomPlacementCount", 0);
        finishAfterPlacement = view.getBooleanOr("RoomFinishAfter", false);
        placedRooms.clear();
        int placedCount = view.getIntOr("RoomPlacedCount", 0);
        for (int i = 0; i < placedCount; i++) {
            RoomPlacement placement = readPlacement(view, "RoomPlaced" + i);
            if (placement != null) placedRooms.add(placement);
        }
        unusedBranches.clear();
        int branchCount = view.getIntOr("RoomBranchCount", 0);
        for (int i = 0; i < branchCount; i++) {
            RoomSocket socket = readSocket(view, "RoomBranch" + i);
            if (socket != null) unusedBranches.add(new Branch(
                    socket, view.getIntOr("RoomBranch" + i + "Owner", 0)));
        }
        activeSocket = view.getBooleanOr("RoomActiveExists", false) ? readSocket(view, "RoomActive") : null;
        currentPlacement = view.getBooleanOr("RoomCurrentExists", false)
                ? readPlacement(view, "RoomCurrent") : null;
        savedRemaining = unpackPositions(view.getIntArray("RoomRemaining").orElseGet(() -> new int[0]));
        savedExcavation = unpackPositions(view.getIntArray("RoomExcavation").orElseGet(() -> new int[0]));
        needsReconstruction = currentPlacement != null;
        if (entity != null && view.getBooleanOr("RoomTrackExists", false)) {
            entity.setTrackStart(new Vec3(
                    view.getDoubleOr("RoomTrackX", 0.0),
                    view.getDoubleOr("RoomTrackY", 0.0),
                    view.getDoubleOr("RoomTrackZ", 0.0)));
        }
    }

    private static void writePlacement(ValueOutput view, String prefix, RoomPlacement placement) {
        view.putInt(prefix + "Template", placement.templateIndex());
        view.putInt(prefix + "Rotation", placement.transform().rotation());
        view.putBoolean(prefix + "Mirror", placement.transform().mirror());
        view.putInt(prefix + "OffsetX", placement.offset().getX());
        view.putInt(prefix + "OffsetY", placement.offset().getY());
        view.putInt(prefix + "OffsetZ", placement.offset().getZ());
        view.putInt(prefix + "Input", placement.inputSocketIndex());
    }

    private static RoomPlacement readPlacement(ValueInput view, String prefix) {
        int template = view.getIntOr(prefix + "Template", -1);
        if (template < 0) return null;
        return new RoomPlacement(
                template,
                new RoomTransform(view.getIntOr(prefix + "Rotation", 0),
                        view.getBooleanOr(prefix + "Mirror", false)),
                new BlockPos(
                        view.getIntOr(prefix + "OffsetX", 0),
                        view.getIntOr(prefix + "OffsetY", 0),
                        view.getIntOr(prefix + "OffsetZ", 0)),
                view.getIntOr(prefix + "Input", 0)
        );
    }

    private static void writeSocket(ValueOutput view, String prefix, RoomSocket socket) {
        view.putInt(prefix + "X", socket.anchor().getX());
        view.putInt(prefix + "Y", socket.anchor().getY());
        view.putInt(prefix + "Z", socket.anchor().getZ());
        view.putString(prefix + "Facing", socket.facing().name());
        view.putInt(prefix + "Width", socket.apertureWidth());
        view.putInt(prefix + "Height", socket.apertureHeight());
        view.putBoolean(prefix + "Floor", socket.floorEdge());
    }

    private static RoomSocket readSocket(ValueInput view, String prefix) {
        String facingName = view.getStringOr(prefix + "Facing", "");
        if (facingName.isEmpty()) return null;
        try {
            return new RoomSocket(
                    new BlockPos(view.getIntOr(prefix + "X", 0), view.getIntOr(prefix + "Y", 0),
                            view.getIntOr(prefix + "Z", 0)),
                    Direction.valueOf(facingName),
                    view.getIntOr(prefix + "Width", 1), view.getIntOr(prefix + "Height", 1),
                    view.getBooleanOr(prefix + "Floor", false));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int[] packPositions(Iterable<BlockPos> positions) {
        List<Integer> packed = new ArrayList<>();
        for (BlockPos position : positions) {
            packed.add(position.getX());
            packed.add(position.getY());
            packed.add(position.getZ());
        }
        int[] result = new int[packed.size()];
        for (int i = 0; i < packed.size(); i++) result[i] = packed.get(i);
        return result;
    }

    private static Set<BlockPos> unpackPositions(int[] packed) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (int i = 0; i + 2 < packed.length; i += 3) {
            result.add(new BlockPos(packed[i], packed[i + 1], packed[i + 2]));
        }
        return Set.copyOf(result);
    }

    public int getPlacedRoomCount() {
        return placedRooms.size();
    }

    public int getUnusedBranchCount() {
        return unusedBranches.size();
    }

    public boolean isEndingQueued() {
        return endingQueued;
    }

    public Vec3 getActiveTrackStart() {
        return activeSocket == null ? null : Vec3.atCenterOf(activeSocket.anchor());
    }

    private record Candidate(RoomPlacement placement, int outgoingSocket, double score, long tieBreak) {}

    private record Branch(RoomSocket socket, int roomIndex) {}
}
