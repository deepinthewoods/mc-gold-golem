package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.util.GradientGroupManager;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.wall.GapPlacement;
import ninja.trek.mc.goldgolem.world.entity.strategy.wall.JoinEntry;
import ninja.trek.mc.goldgolem.world.entity.strategy.wall.ModulePlacement;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Strategy for Wall building mode.
 * Uses PlacementPlanner for reach-aware block placement - the golem moves
 * within reach of each block before placing it.
 */
public class WallBuildStrategy extends AbstractBuildStrategy {

    // Gradient group manager for wall blocks
    private final GradientGroupManager groups = new GradientGroupManager();

    // Wall-mode captured data
    private List<String> wallUniqueBlockIds = Collections.emptyList();
    private BlockPos wallOrigin = null;
    private String wallJsonFile = null;
    private String wallJoinSignature = null;
    private WallJoinSlice.Axis wallJoinAxis = null;
    private int wallJoinUSize = 1;
    private int wallModuleCount = 0;
    private int wallLongestModule = 0;
    private boolean wallSliceSymmetric = true;
    private List<WallModuleTemplate> wallTemplates = Collections.emptyList();
    private List<JoinEntry> wallJoinTemplate = Collections.emptyList();
    private int wallLastDirX = 1;
    private int wallLastDirZ = 0;

    // Runtime state
    private ModulePlacement currentModulePlacement = null;
    private final ArrayDeque<ModulePlacement> pendingModules = new ArrayDeque<>();

    // PlacementPlanner for reach-aware building
    private PlacementPlanner planner = null;
    private boolean moduleBlocksLoaded = false;

    // Gradient mining helper for mine-action slots
    private final GradientMiningHelper gradientMiner = new GradientMiningHelper();

    @Override
    public BuildMode getMode() {
        return BuildMode.WALL;
    }

    @Override
    public String getNbtPrefix() {
        return "Wall";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }
    }

    @Override
    public void tick(GoldGolemEntity golem, Player owner) {
        tickWallMode(golem, owner);
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        // Wall mode never completes on its own - it follows the player
        return false;
    }

    @Override
    public void writeNbt(CompoundTag nbt) {
        // Save origin
        if (wallOrigin != null) {
            nbt.putInt("OriginX", wallOrigin.getX());
            nbt.putInt("OriginY", wallOrigin.getY());
            nbt.putInt("OriginZ", wallOrigin.getZ());
        }

        // Save JSON file
        if (wallJsonFile != null) {
            nbt.putString("JsonFile", wallJsonFile);
        }

        // Save unique block IDs
        nbt.putInt("UniqCount", wallUniqueBlockIds.size());
        for (int i = 0; i < wallUniqueBlockIds.size(); i++) {
            nbt.putString("Uniq" + i, wallUniqueBlockIds.get(i));
        }

        // Save join info
        if (wallJoinSignature != null) {
            nbt.putString("JoinSig", wallJoinSignature);
        }
        if (wallJoinAxis != null) {
            nbt.putString("JoinAxis", wallJoinAxis.name());
        }
        nbt.putInt("JoinUSize", wallJoinUSize);
        nbt.putInt("ModCount", wallModuleCount);
        nbt.putInt("ModLongest", wallLongestModule);
        nbt.putBoolean("SliceSym", wallSliceSymmetric);

        // Save join template
        nbt.putInt("JoinTplCount", wallJoinTemplate.size());
        for (int i = 0; i < wallJoinTemplate.size(); i++) {
            JoinEntry e = wallJoinTemplate.get(i);
            nbt.putInt("JT_dy" + i, e.dy);
            nbt.putInt("JT_du" + i, e.du);
            nbt.putString("JT_id" + i, e.id);
        }

        // Save direction
        nbt.putInt("LastDirX", wallLastDirX);
        nbt.putInt("LastDirZ", wallLastDirZ);

        // Save planner state
        if (planner != null) {
            CompoundTag plannerNbt = new CompoundTag();
            planner.writeNbt(plannerNbt);
            nbt.put("Planner", plannerNbt);
        }

        // Save gradient groups
        groups.writeToNbt(nbt, "Groups");
    }

    @Override
    public void readNbt(CompoundTag nbt) {
        // Load origin
        if (nbt.contains("OriginX")) {
            wallOrigin = new BlockPos(
                nbt.getIntOr("OriginX", 0),
                nbt.getIntOr("OriginY", 0),
                nbt.getIntOr("OriginZ", 0)
            );
        } else {
            wallOrigin = null;
        }

        // Load JSON file
        wallJsonFile = nbt.contains("JsonFile") ? nbt.getStringOr("JsonFile", null) : null;

        // Load unique block IDs
        int uniqCount = nbt.getIntOr("UniqCount", 0);
        if (uniqCount > 0) {
            List<String> ids = new ArrayList<>(uniqCount);
            for (int i = 0; i < uniqCount; i++) {
                ids.add(nbt.getStringOr("Uniq" + i, ""));
            }
            wallUniqueBlockIds = ids;
        } else {
            wallUniqueBlockIds = Collections.emptyList();
        }

        // Load join info
        wallJoinSignature = nbt.contains("JoinSig") ? nbt.getStringOr("JoinSig", null) : null;
        String axisStr = nbt.contains("JoinAxis") ? nbt.getStringOr("JoinAxis", null) : null;
        if (axisStr != null) {
            try {
                wallJoinAxis = WallJoinSlice.Axis.valueOf(axisStr);
            } catch (IllegalArgumentException ignored) {
                wallJoinAxis = null;
            }
        } else {
            wallJoinAxis = null;
        }
        wallJoinUSize = Math.max(1, nbt.getIntOr("JoinUSize", 1));
        wallModuleCount = nbt.getIntOr("ModCount", 0);
        wallLongestModule = nbt.getIntOr("ModLongest", 0);
        wallSliceSymmetric = nbt.getBooleanOr("SliceSym", true);

        // Load join template
        int joinTplCount = nbt.getIntOr("JoinTplCount", 0);
        if (joinTplCount > 0) {
            List<JoinEntry> list = new ArrayList<>(joinTplCount);
            for (int i = 0; i < joinTplCount; i++) {
                int dy = nbt.getIntOr("JT_dy" + i, 0);
                int du = nbt.getIntOr("JT_du" + i, 0);
                String id = nbt.getStringOr("JT_id" + i, "");
                list.add(new JoinEntry(dy, du, id));
            }
            wallJoinTemplate = list;
        } else {
            wallJoinTemplate = Collections.emptyList();
        }

        // Load direction
        wallLastDirX = nbt.getIntOr("LastDirX", 1);
        wallLastDirZ = nbt.getIntOr("LastDirZ", 0);
        if (planner != null) {
            nbt.getCompound("Planner").ifPresent(planner::readNbt);
        }

        // Load gradient groups
        groups.readFromNbt(nbt, "Groups");
    }

    @Override
    public void writeLegacyNbt(net.minecraft.world.level.storage.ValueOutput view) {
        view.putBoolean("WallModuleBlocksLoaded", moduleBlocksLoaded);
        if (planner != null) {
            planner.writeView(view.child("WallPlanner"));
        }
    }

    @Override
    public void readLegacyNbt(net.minecraft.world.level.storage.ValueInput view) {
        moduleBlocksLoaded = view.getBooleanOr("WallModuleBlocksLoaded", false);
        if (planner == null && entity != null) {
            planner = new PlacementPlanner(entity);
        }
        if (planner != null) {
            view.child("WallPlanner").ifPresent(planner::readView);
        }
    }

    @Override
    public boolean usesGroupUI() {
        return true;
    }

    @Override
    public boolean usesPlayerTracking() {
        return true;
    }

    // ========== Configuration ==========

    /**
     * Configure wall mode from captured data.
     */
    public void setConfig(
            BlockPos origin,
            String jsonFile,
            List<String> uniqueBlockIds,
            String joinSignature,
            WallJoinSlice.Axis joinAxis,
            int joinUSize,
            int moduleCount,
            int longestModule,
            boolean sliceSymmetric,
            List<WallModuleTemplate> templates,
            List<JoinEntry> joinTemplate
    ) {
        this.wallOrigin = origin;
        this.wallJsonFile = jsonFile;
        this.wallUniqueBlockIds = uniqueBlockIds != null ? new ArrayList<>(uniqueBlockIds) : Collections.emptyList();
        this.wallJoinSignature = joinSignature;
        this.wallJoinAxis = joinAxis;
        this.wallJoinUSize = Math.max(1, joinUSize);
        this.wallModuleCount = moduleCount;
        this.wallLongestModule = longestModule;
        this.wallSliceSymmetric = sliceSymmetric;
        this.wallTemplates = templates != null ? new ArrayList<>(templates) : Collections.emptyList();
        this.wallJoinTemplate = joinTemplate != null ? new ArrayList<>(joinTemplate) : Collections.emptyList();
    }

    /**
     * Clear runtime state.
     */
    public void clearState() {
        currentModulePlacement = null;
        pendingModules.clear();
        moduleBlocksLoaded = false;
        if (planner != null) {
            planner.clear();
        }
        if (entity != null) {
            entity.setTrackStart(null);
        }
    }

    // ========== Getters ==========

    public List<String> getWallUniqueBlockIds() { return wallUniqueBlockIds; }
    public BlockPos getWallOrigin() { return wallOrigin; }
    public String getWallJsonFile() { return wallJsonFile; }
    public String getWallJoinSignature() { return wallJoinSignature; }
    public WallJoinSlice.Axis getWallJoinAxis() { return wallJoinAxis; }
    public int getWallJoinUSize() { return wallJoinUSize; }
    public int getWallModuleCount() { return wallModuleCount; }
    public int getWallLongestModule() { return wallLongestModule; }
    public List<WallModuleTemplate> getWallTemplates() { return wallTemplates; }
    public List<JoinEntry> getWallJoinTemplate() { return wallJoinTemplate; }
    public int getWallLastDirX() { return wallLastDirX; }
    public int getWallLastDirZ() { return wallLastDirZ; }

    public void setWallLastDir(int x, int z) {
        this.wallLastDirX = x;
        this.wallLastDirZ = z;
    }

    /**
     * Get the gradient group manager for wall blocks.
     */
    public GradientGroupManager getGroups() {
        return groups;
    }

    // UI state accessors (delegate to entity)
    public List<String[]> getWallGroupSlots() {
        return entity != null ? entity.getWallGroupSlots() : Collections.emptyList();
    }

    public List<Float> getWallGroupWindows() {
        return entity != null ? entity.getWallGroupWindows() : Collections.emptyList();
    }

    public List<Integer> getWallGroupNoiseScales() {
        return entity != null ? entity.getWallGroupNoiseScales() : Collections.emptyList();
    }

    public Map<String, Integer> getWallBlockGroup() {
        return entity != null ? entity.getWallBlockGroup() : Collections.emptyMap();
    }

    // ========== Polymorphic Dispatch Methods ==========

    @Override
    public FeedResult handleFeedInteraction(Player player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        // Wall mode: always starts when nugget is fed
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        // Clear wall mode state
        clearState();
    }

    // ========== Main tick logic ==========

    private void tickWallMode(GoldGolemEntity golem, Player owner) {
        Vec3 trackStart = golem.getTrackStart();

        // Ensure planner exists
        if (planner == null) {
            planner = new PlacementPlanner(golem);
        }

        // Track anchors and enqueue modules based on movement
        if (owner != null && owner.onGround()) {
            Vec3 p = new Vec3(owner.getX(), owner.getY() + 0.05, owner.getZ());
            if (trackStart == null) {
                golem.setTrackStart(p);
                trackStart = p;
            } else {
                double threshold = Math.max(2.0, getWallLongestHoriz() + 1.0);
                double dist = Math.sqrt((p.x - trackStart.x) * (p.x - trackStart.x) + (p.z - trackStart.z) * (p.z - trackStart.z));
                if (dist >= threshold) {
                    var cand = chooseNextModule(trackStart, p);
                    if (cand != null) {
                        pendingModules.addLast(cand);
                        // Update anchor to end
                        golem.setTrackStart(cand.end());
                        trackStart = cand.end();
                        // Preview
                        if (golem.level() instanceof ServerLevel) {
                            var owner2 = golem.getOwnerPlayer();
                            if (owner2 instanceof net.minecraft.server.level.ServerPlayer sp2) {
                                List<Vec3> list = new ArrayList<>();
                                list.add(cand.anchor());
                                list.add(cand.end());
                                Optional<Vec3> anchor = Optional.ofNullable(golem.getTrackStart());
                                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp2, golem.getId(), list, anchor);
                            }
                        }
                    }
                }
            }
        }

        // Start new module if needed
        if (currentModulePlacement == null) {
            currentModulePlacement = pendingModules.pollFirst();
            if (currentModulePlacement != null) {
                currentModulePlacement.begin(golem, this);
                moduleBlocksLoaded = false;
            }
        }

        // Process current module with PlacementPlanner
        if (currentModulePlacement != null) {
            // Load module blocks into planner if not done
            if (!moduleBlocksLoaded) {
                List<BlockPos> moduleBlocks = currentModulePlacement.getRemainingBlockPositions(golem, this);
                if (!moduleBlocks.isEmpty()) {
                    // Use block checker to skip already-correct blocks
                    planner.setBlocks(moduleBlocks, pos -> currentModulePlacement.isBlockAlreadyCorrect(golem, pos));

                    // Set up exclusion zone filter (inverted pyramid above golem)
                    planner.setBlockFilter(pos -> {
                        BlockPos golemFeet = golem.blockPosition();
                        int dy = pos.getY() - golemFeet.getY();
                        if (dy <= 0) return false;
                        int dxAbs = Math.abs(pos.getX() - golemFeet.getX());
                        int dzAbs = Math.abs(pos.getZ() - golemFeet.getZ());
                        int chebyshev = Math.max(dxAbs, dzAbs);
                        return chebyshev <= dy;
                    });

                    // Set up neighbor scorer
                    planner.setBlockScorer(pos -> {
                        var world = golem.level();
                        int neighbors = 0;
                        if (!world.getBlockState(pos.north()).isAir()) neighbors++;
                        if (!world.getBlockState(pos.south()).isAir()) neighbors++;
                        if (!world.getBlockState(pos.east()).isAir()) neighbors++;
                        if (!world.getBlockState(pos.west()).isAir()) neighbors++;
                        if (!world.getBlockState(pos.below()).isAir()) neighbors++;
                        return neighbors;
                    });
                }
                moduleBlocksLoaded = true;
            }

            // Tick gradient mining if active
            if (gradientMiner.isMining()) {
                boolean done = gradientMiner.tickMining(golem, isLeftHandActive());
                if (done) {
                    gradientMiner.reset(golem);
                }
                return;
            }

            // Tick with 2-tick pacing
            if (!shouldPlaceThisTick()) {
                return;
            }

            // Use planner to handle movement and placement
            PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
                return placeWallBlockAt(golem, pos, nextPos);
            });

            switch (result) {
                case PLACED_BLOCK:
                    alternateHand();
                    currentModulePlacement.incrementProgress();
                    break;

                case COMPLETED:
                    // Module complete, move to next
                    currentModulePlacement = null;
                    moduleBlocksLoaded = false;
                    break;

                case DEFERRED:
                    // Block was deferred, planner will retry later
                    break;

                case WORKING:
                case IDLE:
                    // Still working or nothing to do
                    break;
            }

            // Check if module is done (backup check)
            if (currentModulePlacement != null && currentModulePlacement.done()) {
                currentModulePlacement = null;
                moduleBlocksLoaded = false;
            }
        }
    }

    private double getWallLongestHoriz() {
        double longest = 0.0;
        for (var t : wallTemplates) {
            longest = Math.max(longest, t.horizLen());
        }
        return longest;
    }

    private ModulePlacement chooseNextModule(Vec3 anchor, Vec3 playerPos) {
        if (wallTemplates == null || wallTemplates.isEmpty()) return null;

        double bestScore = Double.POSITIVE_INFINITY;
        ModulePlacement best = null;

        for (int ti = 0; ti < wallTemplates.size(); ti++) {
            var tpl = wallTemplates.get(ti);
            int dyModule = tpl.bMarker.getY() - tpl.aMarker.getY();
            int dxModule = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dzModule = tpl.bMarker.getZ() - tpl.aMarker.getZ();

            int mirrorMax = wallSliceSymmetric ? 2 : 1;
            for (int rot = 0; rot < 4; rot++) {
                for (int mir = 0; mir < mirrorMax; mir++) {
                    int[] d = ModulePlacement.rotateAndMirror(dxModule, dyModule, dzModule, rot, mir == 1);
                    Vec3 end = new Vec3(anchor.x + d[0], anchor.y + d[1], anchor.z + d[2]);
                    // Y rule: toward player Y and no overshoot
                    double dyNeed = playerPos.y - anchor.y;
                    double dyStep = d[1];
                    boolean okY = Math.signum(dyStep) == Math.signum(dyNeed) || Math.abs(dyNeed) < 1e-6 || dyStep == 0.0;
                    if (okY) okY = Math.abs(dyStep) <= Math.abs(dyNeed) + 1e-6;
                    double yScore = Math.abs(dyNeed - dyStep);
                    double xz = Math.hypot(end.x - playerPos.x, end.z - playerPos.z);
                    double score = (okY ? 0.0 : 1000.0) + yScore * 10.0 + xz;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new ModulePlacement(ti, rot, mir == 1, anchor, end);
                    }
                }
            }
        }

        // Consider empty corner (gap only) turning left/right by wall thickness
        int t = Math.max(1, wallJoinUSize);
        int lx = wallLastDirX, lz = wallLastDirZ;
        int[][] perps = new int[][]{ new int[]{-lz, lx}, new int[]{lz, -lx} };
        for (int[] pv : perps) {
            int dxGap = pv[0] * t;
            int dzGap = pv[1] * t;
            Vec3 end = new Vec3(anchor.x + dxGap, anchor.y, anchor.z + dzGap);
            double dyNeed = playerPos.y - anchor.y;
            double yScore = Math.abs(dyNeed);
            double xz = Math.hypot(end.x - playerPos.x, end.z - playerPos.z);
            double score = yScore * 10.0 + xz + 0.5; // slight penalty vs real module
            if (score < bestScore) {
                bestScore = score;
                best = new GapPlacement(dxGap, dzGap, anchor, end, pv[0], pv[1]);
            }
        }

        return best;
    }

    // ========== Block placement helper ==========

    /**
     * Place a wall block at the given position using the current module's context.
     * @return true if the block was placed successfully
     */
    private boolean placeWallBlockAt(GoldGolemEntity golem, BlockPos pos, BlockPos nextPos) {
        if (currentModulePlacement == null) return false;
        // Check for mine action
        if (currentModulePlacement.isMinePosition(pos)) {
            gradientMiner.startMining(pos);
            return false; // will mine over subsequent ticks
        }
        return currentModulePlacement.placeBlockAt(golem, this, pos, nextPos);
    }

    public boolean placeBlockStateAt(GoldGolemEntity golem, int wx, int wy, int wz, BlockState baseState, int rot, boolean mirror) {
        return placeBlockStateAt(golem, wx, wy, wz, baseState, rot, mirror, null);
    }

    public boolean placeBlockStateAt(GoldGolemEntity golem, int wx, int wy, int wz, BlockState baseState, int rot, boolean mirror, BlockPos nextPos) {
        var world = golem.level();
        BlockPos pos = new BlockPos(wx, wy, wz);
        net.minecraft.world.level.block.Block block = baseState.getBlock();
        var current = world.getBlockState(pos);
        if (!current.isAir() && current.is(block)) return true;

        long key = pos.asLong();
        if (!golem.recordPlaced(key)) return false;

        int invSlot = golem.findItem(block.asItem());
        if (invSlot < 0) {
            golem.unrecordPlaced(key);
            golem.handleMissingBuildingBlock();
            return false;
        }

        net.minecraft.world.level.block.Rotation rotation = switch (rot & 3) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
        net.minecraft.world.level.block.Mirror mir = mirror ? net.minecraft.world.level.block.Mirror.LEFT_RIGHT : net.minecraft.world.level.block.Mirror.NONE;
        BlockState place = baseState;
        try { place = place.rotate(rotation); } catch (Throwable ignored) {}
        try { place = place.mirror(mir); } catch (Throwable ignored) {}
        try {
            if (place.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                place = place.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Throwable ignored) {}

        world.setBlock(pos, place, 3);
        golem.decrementInventorySlot(invSlot);
        golem.beginHandAnimation(isLeftHandActive(), pos, nextPos);
        return true;
    }
}
