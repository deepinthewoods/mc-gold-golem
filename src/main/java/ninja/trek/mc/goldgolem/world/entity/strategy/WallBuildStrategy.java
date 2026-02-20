package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.util.GradientGroupManager;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
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
    private WallJoinSlice currentOutputSlice = null;

    // Runtime state
    private ModulePlacement currentModulePlacement = null;
    private final ArrayDeque<ModulePlacement> pendingModules = new ArrayDeque<>();

    // PlacementPlanner for reach-aware building
    private PlacementPlanner planner = null;
    private boolean moduleBlocksLoaded = false;

    // Gradient mining helper for mine-action slots
    private final GradientMiningHelper gradientMiner = new GradientMiningHelper();
    private BlockPos currentMineTarget = null;  // Track position being mined so planner can mark it done

    // Reconstruction state for current module after world reload
    private boolean needsReconstruction = false;
    private Set<BlockPos> savedRemainingPositions = null;

    // Stuck state detection
    private int noModuleTicks = 0;

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

        // Save current output slice
        if (currentOutputSlice != null) {
            nbt.putString("OutSliceAxis", currentOutputSlice.axis.name());
            List<WallJoinSlice.Point> sorted = new ArrayList<>(currentOutputSlice.points);
            sorted.sort(Comparator.<WallJoinSlice.Point>comparingInt(WallJoinSlice.Point::dy).thenComparingInt(WallJoinSlice.Point::du));
            nbt.putInt("OutSliceCount", sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                var p = sorted.get(i);
                nbt.putInt("OS_dy" + i, p.dy());
                nbt.putInt("OS_du" + i, p.du());
                String id = currentOutputSlice.blockIds.get(p);
                nbt.putString("OS_id" + i, id != null ? id : "");
            }
        }

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

        // Load current output slice
        String outSliceAxisStr = nbt.contains("OutSliceAxis") ? nbt.getStringOr("OutSliceAxis", null) : null;
        if (outSliceAxisStr != null) {
            try {
                WallJoinSlice.Axis osAxis = WallJoinSlice.Axis.valueOf(outSliceAxisStr);
                int count = nbt.getIntOr("OutSliceCount", 0);
                Set<WallJoinSlice.Point> pts = new HashSet<>();
                Map<WallJoinSlice.Point, String> ids = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    int dy = nbt.getIntOr("OS_dy" + i, 0);
                    int du = nbt.getIntOr("OS_du" + i, 0);
                    String id = nbt.getStringOr("OS_id" + i, "");
                    WallJoinSlice.Point p = new WallJoinSlice.Point(dy, du);
                    pts.add(p);
                    if (!id.isEmpty()) ids.put(p, id);
                }
                currentOutputSlice = WallJoinSlice.fromData(osAxis, pts, ids);
            } catch (IllegalArgumentException ignored) {
                currentOutputSlice = null;
            }
        } else {
            currentOutputSlice = null;
        }

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

        // 3a: Save waitingForResources, direction, output slice
        view.putBoolean("WallWaiting", waitingForResources);
        view.putInt("WallDirX", wallLastDirX);
        view.putInt("WallDirZ", wallLastDirZ);
        if (currentOutputSlice != null) {
            view.putString("WallOutSliceAxis", currentOutputSlice.axis.name());
            List<WallJoinSlice.Point> sorted = new ArrayList<>(currentOutputSlice.points);
            sorted.sort(Comparator.<WallJoinSlice.Point>comparingInt(WallJoinSlice.Point::dy).thenComparingInt(WallJoinSlice.Point::du));
            view.putInt("WallOutSliceCnt", sorted.size());
            for (int i = 0; i < sorted.size(); i++) {
                var p = sorted.get(i);
                view.putInt("WallOS_dy" + i, p.dy());
                view.putInt("WallOS_du" + i, p.du());
                String id = currentOutputSlice.blockIds.get(p);
                view.putString("WallOS_id" + i, id != null ? id : "");
            }
        } else {
            view.putInt("WallOutSliceCnt", 0);
        }

        // 3b: Save pending modules
        view.putInt("WallPendCount", pendingModules.size());
        int pi = 0;
        for (ModulePlacement mod : pendingModules) {
            mod.writeTo(view, "WallPend" + pi + "_");
            pi++;
        }

        // 3c: Save current module placement
        view.putBoolean("WallCurMod_exists", currentModulePlacement != null);
        if (currentModulePlacement != null) {
            currentModulePlacement.writeTo(view, "WallCurMod_");
            // Save remaining block positions (blockStatesMap keys + minePositions)
            List<BlockPos> remaining = currentModulePlacement.getRemainingBlockPositions(
                    entity, this);
            int[] posArray = new int[remaining.size() * 3];
            for (int i = 0; i < remaining.size(); i++) {
                BlockPos bp = remaining.get(i);
                posArray[i * 3] = bp.getX();
                posArray[i * 3 + 1] = bp.getY();
                posArray[i * 3 + 2] = bp.getZ();
            }
            view.putIntArray("WallCurMod_rem", posArray);
        }
    }

    @Override
    public void readLegacyNbt(net.minecraft.world.level.storage.ValueInput view) {
        // Planner and moduleBlocksLoaded are reconstructed, not restored directly
        moduleBlocksLoaded = false;
        if (planner == null && entity != null) {
            planner = new PlacementPlanner(entity);
        }
        if (planner != null) {
            planner.clear();
        }

        // 3a: Restore waitingForResources, direction, output slice
        waitingForResources = view.getBooleanOr("WallWaiting", false);
        wallLastDirX = view.getIntOr("WallDirX", 1);
        wallLastDirZ = view.getIntOr("WallDirZ", 0);
        int osCnt = view.getIntOr("WallOutSliceCnt", 0);
        if (osCnt > 0 && view.contains("WallOutSliceAxis")) {
            String axisStr = view.getStringOr("WallOutSliceAxis", null);
            if (axisStr != null) {
                try {
                    WallJoinSlice.Axis osAxis = WallJoinSlice.Axis.valueOf(axisStr);
                    Set<WallJoinSlice.Point> pts = new HashSet<>();
                    Map<WallJoinSlice.Point, String> ids = new HashMap<>();
                    for (int i = 0; i < osCnt; i++) {
                        int dy = view.getIntOr("WallOS_dy" + i, 0);
                        int du = view.getIntOr("WallOS_du" + i, 0);
                        String id = view.getStringOr("WallOS_id" + i, "");
                        WallJoinSlice.Point p = new WallJoinSlice.Point(dy, du);
                        pts.add(p);
                        if (!id.isEmpty()) ids.put(p, id);
                    }
                    currentOutputSlice = WallJoinSlice.fromData(osAxis, pts, ids);
                } catch (IllegalArgumentException ignored) {
                    currentOutputSlice = null;
                }
            }
        } else {
            currentOutputSlice = null;
        }

        // 3b: Restore pending modules
        pendingModules.clear();
        int pendCount = view.getIntOr("WallPendCount", 0);
        for (int i = 0; i < pendCount; i++) {
            ModulePlacement mod = ModulePlacement.readFrom(view, "WallPend" + i + "_");
            if (mod != null) {
                pendingModules.addLast(mod);
            }
        }

        // 3c: Restore current module (lazy reconstruction)
        if (view.getBooleanOr("WallCurMod_exists", false)) {
            currentModulePlacement = ModulePlacement.readFrom(view, "WallCurMod_");
            if (currentModulePlacement != null) {
                // Load saved remaining positions for reconstruction filtering
                int[] posArray = view.getIntArray("WallCurMod_rem").orElseGet(() -> new int[0]);
                savedRemainingPositions = new HashSet<>();
                for (int i = 0; i + 2 < posArray.length; i += 3) {
                    savedRemainingPositions.add(new BlockPos(posArray[i], posArray[i + 1], posArray[i + 2]));
                }
                needsReconstruction = true;
            }
        } else {
            currentModulePlacement = null;
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
        currentMineTarget = null;
        currentOutputSlice = null;
        noModuleTicks = 0;
        if (entity != null) gradientMiner.reset(entity);
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

    public WallJoinSlice getCurrentOutputSlice() { return currentOutputSlice; }
    public void setCurrentOutputSlice(WallJoinSlice slice) { this.currentOutputSlice = slice; }

    /**
     * Get the resume track start position for wall mode.
     * Returns the end of the last pending module, or the end of the current module,
     * or null if there's no module state to resume from.
     */
    public Vec3 getResumeTrackStart() {
        if (!pendingModules.isEmpty()) {
            return pendingModules.peekLast().end();
        }
        if (currentModulePlacement != null) {
            return currentModulePlacement.end();
        }
        return null;
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
        // Wall mode: fresh start — clear stale runtime state from any previous build
        clearState();
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

        // Retry template loading if needed (e.g. after world reload where
        // lazy-load failed because the level wasn't fully ready yet)
        if ((wallTemplates == null || wallTemplates.isEmpty()) && entity != null) {
            var reloaded = entity.getWallTemplates();
            if (reloaded != null && !reloaded.isEmpty()) {
                wallTemplates = new ArrayList<>(reloaded);
            }
        }

        // Reconstruct current module after world reload (lazy, first tick)
        if (needsReconstruction && currentModulePlacement != null) {
            if (wallTemplates != null && !wallTemplates.isEmpty()) {
                currentModulePlacement.begin(golem, this);
                // Remove entries where the world already has the correct block
                currentModulePlacement.removeCorrectBlocks(golem);
                // Also remove entries not in the saved remaining positions
                currentModulePlacement.retainOnlyPositions(savedRemainingPositions);
                moduleBlocksLoaded = false;
                needsReconstruction = false;
                savedRemainingPositions = null;
            }
            // If templates aren't loaded yet, keep the flag and retry next tick
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
                    // Initialize lastDir from walking direction for first module
                    if (pendingModules.isEmpty() && currentModulePlacement == null) {
                        double initDx = p.x - trackStart.x;
                        double initDz = p.z - trackStart.z;
                        if (Math.abs(initDx) >= Math.abs(initDz)) {
                            wallLastDirX = initDx >= 0 ? 1 : -1;
                            wallLastDirZ = 0;
                        } else {
                            wallLastDirX = 0;
                            wallLastDirZ = initDz >= 0 ? 1 : -1;
                        }
                    }
                    var cand = chooseNextModule(trackStart, p);
                    if (cand != null) {
                        // Module stays on the guide line — enqueue it
                        pendingModules.addLast(cand);
                        golem.setTrackStart(cand.end());
                        trackStart = cand.end();
                        sendPreviewLines(golem);
                    } else {
                        // No valid module: all candidates diverge from the
                        // guide line. Send red preview so the player knows
                        // to reposition.
                        sendPreviewLines(golem, true);
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
                golem.setBuildingPaths(true);
            }
        }

        // Golem waits patiently for the player to walk and queue modules

        // Process current module with PlacementPlanner
        if (currentModulePlacement != null) {
            // Load module blocks into planner if not done
            if (!moduleBlocksLoaded) {
                List<BlockPos> moduleBlocks = currentModulePlacement.getRemainingBlockPositions(golem, this);
                if (!moduleBlocks.isEmpty()) {
                    planner.setBlocks(moduleBlocks);

                    // Set up exclusion zone filter (inverted pyramid above golem).
                    // Uses planner.getFilterPosition() so the pyramid is based on
                    // where the golem is heading (stand pos / wander target), not
                    // its live position — prevents the filter from excluding blocks
                    // that will be reachable once the golem arrives.
                    planner.setBlockFilter(pos -> {
                        BlockPos feet = planner.getFilterPosition();
                        int dy = pos.getY() - feet.getY();
                        if (dy <= 0) return false;
                        int dxAbs = Math.abs(pos.getX() - feet.getX());
                        int dzAbs = Math.abs(pos.getZ() - feet.getZ());
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
                    // Mark the mined position as done in the planner so it doesn't retry it
                    if (currentMineTarget != null && planner != null) {
                        planner.markBlockDone(currentMineTarget);
                        // Also remove from module's mine positions so done() tracks correctly
                        if (currentModulePlacement != null) {
                            currentModulePlacement.removeMinePosition(currentMineTarget);
                        }
                        currentMineTarget = null;
                    }
                }
                return;
            }

            // Use planner to handle movement and placement
            // Navigation always ticks; block placement is gated by 2-tick pacing
            boolean canPlace = shouldPlaceThisTick();
            PlacementPlanner.TickResult result = planner.tick((pos, nextPos) -> {
                return placeWallBlockAt(golem, pos, nextPos);
            }, canPlace);

            switch (result) {
                case PLACED_BLOCK:
                    alternateHand();
                    break;

                case COMPLETED:
                    if (currentModulePlacement != null && !currentModulePlacement.done()) {
                        // Module has remaining blocks — reload them into planner
                        moduleBlocksLoaded = false;
                    } else {
                        // Module truly complete, move to next
                        currentModulePlacement = null;
                        moduleBlocksLoaded = false;
                        sendPreviewLines(golem);
                    }
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
                sendPreviewLines(golem);
            }
        }
    }

    private void sendPreviewLines(GoldGolemEntity golem) {
        sendPreviewLines(golem, false);
    }

    private void sendPreviewLines(GoldGolemEntity golem, boolean noValid) {
        if (!(golem.level() instanceof ServerLevel)) return;
        var owner = golem.getOwnerPlayer();
        if (!(owner instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        List<Vec3> list = new ArrayList<>();
        if (currentModulePlacement != null) {
            list.add(currentModulePlacement.anchor());
            list.add(currentModulePlacement.end());
        }
        for (var mod : pendingModules) {
            list.add(mod.anchor());
            list.add(mod.end());
        }
        java.util.Optional<Vec3> anchor = java.util.Optional.ofNullable(golem.getTrackStart());
        ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp, golem.getId(), list, anchor, noValid);
    }

    private double getWallLongestHoriz() {
        double longest = 0.0;
        for (var t : wallTemplates) {
            longest = Math.max(longest, t.horizLen());
        }
        return longest;
    }

    /**
     * Perpendicular distance from point (px, pz) to the line through the origin
     * with direction (ldx, ldz) of length lLen. Falls back to point distance
     * from origin if the line has zero length.
     */
    /**
     * Distance from point P to the line SEGMENT from A to B in XZ.
     * If the projection falls outside [0,1], clamps to the nearest endpoint.
     */
    private static double distToSegmentXZ(double px, double pz,
                                           double ax, double az,
                                           double bx, double bz) {
        double abx = bx - ax, abz = bz - az;
        double len2 = abx * abx + abz * abz;
        if (len2 < 1e-18) return Math.hypot(px - ax, pz - az);
        double t = ((px - ax) * abx + (pz - az) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * abx, cz = az + t * abz;
        return Math.hypot(px - cx, pz - cz);
    }

    /**
     * Build a reference join slice from the wallJoinTemplate for a given axis.
     * This is the canonical wall cross-section profile used for profile matching.
     */
    private WallJoinSlice buildReferenceSlice(WallJoinSlice.Axis axis) {
        if (wallJoinTemplate.isEmpty() || axis == null) return null;
        Set<WallJoinSlice.Point> pts = new HashSet<>();
        Map<WallJoinSlice.Point, String> ids = new HashMap<>();
        for (JoinEntry e : wallJoinTemplate) {
            WallJoinSlice.Point p = new WallJoinSlice.Point(e.dy, e.du);
            pts.add(p);
            if (e.id != null && !e.id.isEmpty()) {
                // Strip property suffix (e.g. "minecraft:oak_stairs[facing=north]" -> "minecraft:oak_stairs")
                // because template slices store plain block IDs from the registry
                String id = e.id;
                int bracket = id.indexOf('[');
                if (bracket >= 0) id = id.substring(0, bracket);
                ids.put(p, id);
            }
        }
        return WallJoinSlice.fromData(axis, pts, ids);
    }

    /**
     * A scored candidate from the module selection process.
     * Stores the placement, its score, and the output direction/slice it would produce.
     */
    private static class ScoredCandidate {
        final ModulePlacement placement;
        final double score;
        final int outDirX, outDirZ;
        final WallJoinSlice outSlice;

        ScoredCandidate(ModulePlacement placement, double score, int outDirX, int outDirZ, WallJoinSlice outSlice) {
            this.placement = placement;
            this.score = score;
            this.outDirX = outDirX;
            this.outDirZ = outDirZ;
            this.outSlice = outSlice;
        }
    }

    /**
     * Score all valid module candidates for a given anchor/playerPos/direction/slice.
     * Returns a list of ScoredCandidates sorted by score (best first).
     * @param log if true, prints [WallChoose] debug lines
     */
    private List<ScoredCandidate> scoreCandidates(Vec3 anchor, Vec3 playerPos,
                                                   int effectiveDirX, int effectiveDirZ,
                                                   WallJoinSlice effectiveSlice, boolean log,
                                                   boolean isFirstModule) {
        List<ScoredCandidate> candidates = new ArrayList<>();

        for (int ti = 0; ti < wallTemplates.size(); ti++) {
            var tpl = wallTemplates.get(ti);
            int dxModule = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dyModule = tpl.bMarker.getY() - tpl.aMarker.getY();
            int dzModule = tpl.bMarker.getZ() - tpl.aMarker.getZ();

            // When slice is symmetric, also consider reversed (B→A) placement
            boolean[] reverseOptions = wallSliceSymmetric ? new boolean[]{false, true} : new boolean[]{false};
            int mirrorMax = wallSliceSymmetric ? 2 : 1;

            for (boolean rev : reverseOptions) {
                int dx = rev ? -dxModule : dxModule;
                int dy = rev ? -dyModule : dyModule;
                int dz = rev ? -dzModule : dzModule;

                for (int rot = 0; rot < 4; rot++) {
                    for (int mir = 0; mir < mirrorMax; mir++) {
                        int[] d = ModulePlacement.rotateAndMirror(dx, dy, dz, rot, mir == 1);
                        Vec3 end = new Vec3(anchor.x + d[0], anchor.y + d[1], anchor.z + d[2]);

                        // Y rule: toward player Y and no overshoot
                        double dyNeed = playerPos.y - anchor.y;
                        double dyStep = d[1];
                        boolean okY = Math.signum(dyStep) == Math.signum(dyNeed) || Math.abs(dyNeed) < 1e-6 || dyStep == 0.0;
                        if (okY) okY = Math.abs(dyStep) <= Math.abs(dyNeed) + 1e-6;
                        double yScore = Math.abs(dyNeed - dyStep);
                        // Distance from endpoint to the anchor → player segment
                        double xz = distToSegmentXZ(end.x, end.z,
                                anchor.x, anchor.z, playerPos.x, playerPos.z);

                        String candidateTag = "tpl=" + ti + " rot=" + rot + " mir=" + mir + " rev=" + rev;

                        // Incoming direction constraint: input-side axis must match effective wallLastDir
                        // Skip for the first module — there's no previous module to chain from,
                        // so the forward-dot check and scoring handle orientation instead.
                        if (!isFirstModule) {
                            WallJoinSlice.Axis inputAxis = rev ? tpl.bSliceAxis : tpl.aSliceAxis;
                            if (inputAxis != null) {
                                WallJoinSlice.Axis rotatedInputAxis = inputAxis;
                                if (rot == 1 || rot == 3) {
                                    rotatedInputAxis = (inputAxis == WallJoinSlice.Axis.X_THICK)
                                            ? WallJoinSlice.Axis.Z_THICK : WallJoinSlice.Axis.X_THICK;
                                }
                                boolean axisMatch;
                                if (effectiveDirX != 0 && effectiveDirZ == 0) {
                                    axisMatch = (rotatedInputAxis == WallJoinSlice.Axis.X_THICK);
                                } else if (effectiveDirZ != 0 && effectiveDirX == 0) {
                                    axisMatch = (rotatedInputAxis == WallJoinSlice.Axis.Z_THICK);
                                } else {
                                    axisMatch = true;
                                }
                                if (!axisMatch) {
                                    if (log) System.out.println("[WallChoose] " + candidateTag + " → rejected(axisMatch)");
                                    continue;
                                }
                            }
                        }

                        // Slice profile constraint — skip for first module (no predecessor to match)
                        if (!isFirstModule) {
                            if (!wallJoinTemplate.isEmpty()) {
                                WallJoinSlice inputSlice = rev ? tpl.getBSlice() : tpl.getASlice();
                                if (inputSlice != null) {
                                    WallJoinSlice transformedInput = inputSlice.transformedDu(rot, mir == 1);
                                    WallJoinSlice reference = buildReferenceSlice(transformedInput.axis);
                                    if (reference != null && !reference.profileEquals(transformedInput)) {
                                        if (log) System.out.println("[WallChoose] " + candidateTag + " → rejected(sliceProfile)");
                                        continue;
                                    }
                                }
                            } else if (effectiveSlice != null) {
                                WallJoinSlice inputSlice = rev ? tpl.getBSlice() : tpl.getASlice();
                                if (inputSlice != null) {
                                    WallJoinSlice transformedInput = inputSlice.transformedDu(rot, mir == 1);
                                    if (!effectiveSlice.profileEquals(transformedInput)) {
                                        if (log) System.out.println("[WallChoose] " + candidateTag + " → rejected(sliceFallback)");
                                        continue;
                                    }
                                }
                            }
                        }

                        // Forward direction constraint: module must not go backward
                        int fwdDot = d[0] * effectiveDirX + d[2] * effectiveDirZ;
                        if (fwdDot < 0) {
                            if (log) System.out.println("[WallChoose] " + candidateTag + " → rejected(backward)");
                            continue;
                        }

                        // Compute output direction
                        int outDirX, outDirZ;
                        WallJoinSlice.Axis outputAxis = rev ? tpl.aSliceAxis : tpl.bSliceAxis;
                        if (outputAxis != null && (rot == 1 || rot == 3)) {
                            outputAxis = (outputAxis == WallJoinSlice.Axis.X_THICK)
                                    ? WallJoinSlice.Axis.Z_THICK : WallJoinSlice.Axis.X_THICK;
                        }
                        if (outputAxis == WallJoinSlice.Axis.X_THICK && d[0] != 0) {
                            outDirX = Integer.signum(d[0]); outDirZ = 0;
                        } else if (outputAxis == WallJoinSlice.Axis.Z_THICK && d[2] != 0) {
                            outDirX = 0; outDirZ = Integer.signum(d[2]);
                        } else if (Math.abs(d[0]) >= Math.abs(d[2])) {
                            outDirX = Integer.signum(d[0]); outDirZ = 0;
                        } else {
                            outDirX = 0; outDirZ = Integer.signum(d[2]);
                        }
                        double score = (okY ? 0.0 : 1000.0) + yScore * 10.0 + xz;
                        if (log) {
                            System.out.println("[WallChoose] " + candidateTag
                                    + " → score=" + String.format("%.2f", score)
                                    + " (okY=" + okY + " yS=" + String.format("%.1f", yScore)
                                    + " xz=" + String.format("%.2f", xz)
                                    + " outDir=(" + outDirX + "," + outDirZ + "))");
                        }

                        // Compute output slice
                        WallJoinSlice outSlice = rev ? tpl.getASlice() : tpl.getBSlice();
                        if (outSlice != null) outSlice = outSlice.transformedDu(rot, mir == 1);

                        candidates.add(new ScoredCandidate(
                                new ModulePlacement(ti, rot, mir == 1, rev, anchor, end),
                                score, outDirX, outDirZ, outSlice));
                    }
                }
            }
        }

        // Sort by score (best first)
        candidates.sort(Comparator.comparingDouble(c -> c.score));
        return candidates;
    }

    /** Maximum allowed distance from a module endpoint to the guide line segment. */
    private static final double DIVERGE_TOLERANCE = 1.5;

    private ModulePlacement chooseNextModule(Vec3 anchor, Vec3 playerPos) {
        if (wallTemplates == null || wallTemplates.isEmpty()) {
            System.out.println("[WallChoose] No templates available");
            return null;
        }

        // Simulate wallLastDir forward through pending queue so we score
        // against the direction the wall will actually be going when this
        // module starts, not the direction of the last *begun* module.
        int effectiveDirX = wallLastDirX;
        int effectiveDirZ = wallLastDirZ;
        WallJoinSlice effectiveSlice = currentOutputSlice;
        for (ModulePlacement pending : pendingModules) {
            int[] outDir = pending.computeOutputDir(wallTemplates);
            if (outDir != null) { effectiveDirX = outDir[0]; effectiveDirZ = outDir[1]; }
            WallJoinSlice s = pending.computeOutputSlice(wallTemplates);
            if (s != null) effectiveSlice = s;
        }

        // First module has no predecessor to chain from — skip the axis constraint
        // so any direction is accepted (scored by proximity/forward-dot instead).
        boolean isFirstModule = pendingModules.isEmpty()
                && currentModulePlacement == null
                && currentOutputSlice == null;

        // Score all candidates sorted by score (best first)
        List<ScoredCandidate> candidates = scoreCandidates(anchor, playerPos, effectiveDirX, effectiveDirZ, effectiveSlice, true, isFirstModule);

        System.out.println("[WallChoose] isFirstModule=" + isFirstModule
                + " candidates=" + candidates.size()
                + " templates=" + wallTemplates.size()
                + " joinTpl=" + wallJoinTemplate.size()
                + " outSlice=" + (currentOutputSlice != null));

        // Greedy pick: take the best-scoring candidate that doesn't diverge
        // from the anchor→player guide line beyond the tolerance.
        ModulePlacement best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestEndDist = Double.POSITIVE_INFINITY;

        for (ScoredCandidate c : candidates) {
            if (c.score > 100.0) continue; // skip Y-invalid candidates
            double endDist = distToSegmentXZ(c.placement.end().x, c.placement.end().z,
                    anchor.x, anchor.z, playerPos.x, playerPos.z);
            // First module: skip diverge check — the player's walking path doesn't
            // necessarily align with the wall axis yet. Forward-dot + scoring suffice.
            if (isFirstModule || endDist <= DIVERGE_TOLERANCE) {
                best = c.placement;
                bestScore = c.score;
                bestEndDist = endDist;
                break; // already sorted by score, first passing one wins
            }
        }

        if (best != null) {
            System.out.println("[WallChoose] Selected: tpl=" + best.getTplIndex()
                    + " rot=" + best.getRot() + " mir=" + best.isMirror()
                    + " rev=" + best.isReversed()
                    + " score=" + String.format("%.1f", bestScore)
                    + " endDist=" + String.format("%.2f", bestEndDist)
                    + " anchor=" + String.format("(%.1f,%.1f,%.1f)", anchor.x, anchor.y, anchor.z)
                    + " end=" + String.format("(%.1f,%.1f,%.1f)", best.end().x, best.end().y, best.end().z)
                    + " effDir=(" + effectiveDirX + "," + effectiveDirZ + ")");
        } else {
            System.out.println("[WallChoose] No valid module (all diverge). templates=" + wallTemplates.size()
                    + " effDir=(" + effectiveDirX + "," + effectiveDirZ + ")"
                    + " anchor=" + String.format("(%.1f,%.1f,%.1f)", anchor.x, anchor.y, anchor.z)
                    + " player=" + String.format("(%.1f,%.1f,%.1f)", playerPos.x, playerPos.y, playerPos.z));
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
            currentMineTarget = pos;
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

        net.minecraft.world.level.block.Rotation rotation = switch (rot & 3) {
            case 1 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_90;
            case 2 -> net.minecraft.world.level.block.Rotation.CLOCKWISE_180;
            case 3 -> net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90;
            default -> net.minecraft.world.level.block.Rotation.NONE;
        };
        net.minecraft.world.level.block.Mirror mir = mirror ? net.minecraft.world.level.block.Mirror.LEFT_RIGHT : net.minecraft.world.level.block.Mirror.NONE;
        BlockState place = baseState;
        try { place = place.rotate(rotation); } catch (Exception ignored) {}
        try { place = place.mirror(mir); } catch (Exception ignored) {}
        try {
            if (place.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) {
                place = place.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, Boolean.FALSE);
            }
        } catch (Exception ignored) {}

        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        if (!golem.consumeBlockFromInventory(blockId)) {
            golem.unrecordPlaced(key);
            golem.handleMissingBuildingBlock();
            return false;
        }

        world.setBlock(pos, place, 3);
        golem.beginHandAnimation(isLeftHandActive(), pos, nextPos);
        return true;
    }
}
