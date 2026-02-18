package ninja.trek.mc.goldgolem.world.entity.strategy.wall;

import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.util.GradientSlotUtil;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Represents a wall module placement operation.
 * Supports both bulk placement and individual block placement via PlacementPlanner.
 */
public class ModulePlacement {
    protected final int tplIndex;
    protected final int rot; // 0..3
    protected final boolean mirror;
    protected final boolean reversed; // true = module placed B→A instead of A→B
    protected final Vec3 anchor;
    protected final Vec3 end;
    protected List<WallModuleTemplate.Voxel> voxels;
    protected int cursor = 0;
    protected boolean joinPlaced = false;

    // Cached block positions and states for PlacementPlanner integration
    protected Map<BlockPos, BlockState> blockStatesMap = null;
    // Positions where gradient sampled a mine action (instead of placing a block)
    protected Set<BlockPos> minePositions = new HashSet<>();
    protected int moduleMinY = 0;
    protected int moduleHeight = 1;
    protected int incomingDirX = 1;
    protected int incomingDirZ = 0;

    public ModulePlacement(int tplIndex, int rot, boolean mirror, Vec3 anchor, Vec3 end) {
        this(tplIndex, rot, mirror, false, anchor, end);
    }

    public ModulePlacement(int tplIndex, int rot, boolean mirror, boolean reversed, Vec3 anchor, Vec3 end) {
        this.tplIndex = tplIndex;
        this.rot = rot;
        this.mirror = mirror;
        this.reversed = reversed;
        this.anchor = anchor;
        this.end = end;
    }

    public Vec3 anchor() {
        return anchor;
    }

    public Vec3 end() {
        return end;
    }

    public int getTplIndex() {
        return tplIndex;
    }

    public int getRot() {
        return rot;
    }

    public boolean isMirror() {
        return mirror;
    }

    public boolean isReversed() {
        return reversed;
    }

    public void begin(GoldGolemEntity golem, WallBuildStrategy strategy) {
        var templates = strategy.getWallTemplates();
        if (tplIndex >= 0 && tplIndex < templates.size()) {
            var tpl = templates.get(tplIndex);
            this.voxels = tpl.voxels;
            // Save incoming direction for join slice perpendicular
            this.incomingDirX = strategy.getWallLastDirX();
            this.incomingDirZ = strategy.getWallLastDirZ();

            // Compute the module direction (A→B or B→A when reversed)
            int dx = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dz = tpl.bMarker.getZ() - tpl.aMarker.getZ();
            if (reversed) { dx = -dx; dz = -dz; }
            int[] d = rotateAndMirror(dx, 0, dz, rot, mirror);

            // Update last direction using the OUTPUT-side slice axis.
            // For normal placement the output side is B; for reversed it's A.
            WallJoinSlice.Axis outputAxis = reversed ? tpl.aSliceAxis : tpl.bSliceAxis;
            // Rotation swaps axes: rot 1,3 flip X_THICK <-> Z_THICK
            if (outputAxis != null && (rot == 1 || rot == 3)) {
                outputAxis = (outputAxis == WallJoinSlice.Axis.X_THICK)
                        ? WallJoinSlice.Axis.Z_THICK : WallJoinSlice.Axis.X_THICK;
            }
            if (outputAxis != null) {
                if (outputAxis == WallJoinSlice.Axis.X_THICK) {
                    // Output slice perpendicular to X → outgoing direction along X
                    strategy.setWallLastDir(Integer.signum(d[0]), 0);
                } else {
                    // Output slice perpendicular to Z → outgoing direction along Z
                    strategy.setWallLastDir(0, Integer.signum(d[2]));
                }
            } else {
                // Fallback: use dominant axis of rotated delta
                if (Math.abs(d[0]) >= Math.abs(d[2])) {
                    strategy.setWallLastDir(Integer.signum(d[0]), 0);
                } else {
                    strategy.setWallLastDir(0, Integer.signum(d[2]));
                }
            }

            // Cache module height info
            this.moduleMinY = tpl.minY;
            int moduleMaxY = tpl.voxels.stream().mapToInt(v -> v.rel.getY()).max().orElse(moduleMinY);
            this.moduleHeight = Math.max(1, moduleMaxY - moduleMinY + 1);

            // Build block states map for individual placement
            buildBlockStatesMap(golem, strategy, tpl);
        }
    }

    /**
     * Build a map of world positions to block states for this module.
     */
    protected void buildBlockStatesMap(GoldGolemEntity golem, WallBuildStrategy strategy, WallModuleTemplate tpl) {
        blockStatesMap = new HashMap<>();

        // Reversal offset: shift voxels from A-relative to B-relative coordinates
        int revOffX = 0, revOffY = 0, revOffZ = 0;
        if (reversed) {
            revOffX = -(tpl.bMarker.getX() - tpl.aMarker.getX());
            revOffY = -(tpl.bMarker.getY() - tpl.aMarker.getY());
            revOffZ = -(tpl.bMarker.getZ() - tpl.aMarker.getZ());
        }

        // Add voxel blocks
        for (var v : voxels) {
            int origRy = v.rel.getY(); // original Y for gradient sampling
            int rx = v.rel.getX() + revOffX;
            int ry = v.rel.getY() + revOffY;
            int rz = v.rel.getZ() + revOffZ;
            int[] d = rotateAndMirror(rx, ry, rz, rot, mirror);
            int wx = Mth.floor(anchor.x) + d[0];
            int wy = Mth.floor(anchor.y) + d[1];
            int wz = Mth.floor(anchor.z) + d[2];

            // Apply gradient sampling (use original Y for height-based gradients)
            BlockState stateToPlace = v.state;
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(v.state.getBlock()).toString();
            Integer groupIdx = strategy.getWallBlockGroup().get(blockId);
            boolean hasGradientGroup = groupIdx != null && groupIdx >= 0 && groupIdx < strategy.getWallGroupSlots().size();
            boolean skipBlock = false;
            if (hasGradientGroup) {
                String[] slots = strategy.getWallGroupSlots().get(groupIdx);
                float window = (groupIdx < strategy.getWallGroupWindows().size()) ? strategy.getWallGroupWindows().get(groupIdx) : 1.0f;
                int noiseScale = (groupIdx < strategy.getWallGroupNoiseScales().size()) ? strategy.getWallGroupNoiseScales().get(groupIdx) : 1;
                int relY = origRy - moduleMinY;
                int sampledIndex = golem.sampleWallGradient(slots, window, noiseScale, moduleHeight, relY, new BlockPos(wx, wy, wz));
                if (sampledIndex >= 0 && sampledIndex < 9) {
                    String sampledId = slots[sampledIndex];
                    if (sampledId != null && !sampledId.isEmpty()) {
                        if (GradientSlotUtil.isMineAction(sampledId)) {
                            // Mine action - record for mining instead of placing
                            minePositions.add(new BlockPos(wx, wy, wz));
                            skipBlock = true;
                        } else {
                            BlockState sampledState = golem.getBlockStateFromId(sampledId);
                            if (sampledState != null) {
                                BlockPos worldPos = new BlockPos(wx, wy, wz);
                                stateToPlace = golem.getPlacementStateForBlock(worldPos, sampledState.getBlock(), v.state, 0, false);
                            } else {
                                skipBlock = true;
                            }
                        }
                    } else {
                        skipBlock = true;
                    }
                } else {
                    skipBlock = true;
                }
            }

            if (!skipBlock) {
                blockStatesMap.put(new BlockPos(wx, wy, wz), stateToPlace);
            }
        }
    }

    /**
     * Get all remaining block positions for this module.
     * Used by PlacementPlanner to determine what blocks need to be placed.
     */
    public List<BlockPos> getRemainingBlockPositions(GoldGolemEntity golem, WallBuildStrategy strategy) {
        List<BlockPos> positions = new ArrayList<>();
        if (blockStatesMap != null) {
            positions.addAll(blockStatesMap.keySet());
        }
        positions.addAll(minePositions);
        return positions;
    }

    /**
     * Check if a position is marked for mining (not placing).
     */
    public boolean isMinePosition(BlockPos pos) {
        return minePositions.contains(pos);
    }

    /**
     * Check if the correct block is already at the given position.
     * Used to skip blocks when resuming a build.
     */
    public boolean isBlockAlreadyCorrect(GoldGolemEntity golem, BlockPos pos) {
        // Mine positions: "correct" if already air
        if (minePositions.contains(pos)) {
            return golem.level().getBlockState(pos).isAir();
        }

        if (blockStatesMap == null) return true;

        BlockState expected = blockStatesMap.get(pos);
        if (expected == null) return true; // Not in our map, skip it

        BlockState current = golem.level().getBlockState(pos);
        return current.getBlock() == expected.getBlock();
    }

    /**
     * Place a single block at the given position.
     * Used by PlacementPlanner for reach-aware placement.
     * @return true if the block was placed successfully
     */
    public boolean placeBlockAt(GoldGolemEntity golem, WallBuildStrategy strategy, BlockPos pos, BlockPos nextPos) {
        if (blockStatesMap == null) return false;

        BlockState stateToPlace = blockStatesMap.get(pos);
        if (stateToPlace == null) return false;

        boolean placed = strategy.placeBlockStateAt(golem, pos.getX(), pos.getY(), pos.getZ(), stateToPlace, rot, mirror, nextPos);
        if (!placed) {
            return false;
        }

        // Remove from map so we don't place again
        blockStatesMap.remove(pos);
        return true;
    }

    /**
     * Increment the progress counter.
     */
    public void incrementProgress() {
        cursor++;
    }

    public void placeSome(GoldGolemEntity golem, WallBuildStrategy strategy, int maxOps) {
        if (!joinPlaced) {
            placeJoinSliceAtAnchor(golem, strategy);
            joinPlaced = true;
        }

        var templates = strategy.getWallTemplates();
        if (tplIndex < 0 || tplIndex >= templates.size()) return;

        var tpl = templates.get(tplIndex);
        int revOffX = 0, revOffY = 0, revOffZ = 0;
        if (reversed) {
            revOffX = -(tpl.bMarker.getX() - tpl.aMarker.getX());
            revOffY = -(tpl.bMarker.getY() - tpl.aMarker.getY());
            revOffZ = -(tpl.bMarker.getZ() - tpl.aMarker.getZ());
        }
        int ops = 0;

        while (cursor < voxels.size() && ops < maxOps) {
            var v = voxels.get(cursor++);
            int origRy = v.rel.getY();
            int rx = v.rel.getX() + revOffX;
            int ry = v.rel.getY() + revOffY;
            int rz = v.rel.getZ() + revOffZ;
            int[] d = rotateAndMirror(rx, ry, rz, rot, mirror);
            int wx = Mth.floor(anchor.x) + d[0];
            int wy = Mth.floor(anchor.y) + d[1];
            int wz = Mth.floor(anchor.z) + d[2];

            BlockState stateToPlace = v.state;
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(v.state.getBlock()).toString();
            Integer groupIdx = strategy.getWallBlockGroup().get(blockId);
            boolean hasGradientGroup = groupIdx != null && groupIdx >= 0 && groupIdx < strategy.getWallGroupSlots().size();
            boolean skipBlock = false;
            if (hasGradientGroup) {
                String[] slots = strategy.getWallGroupSlots().get(groupIdx);
                float window = (groupIdx < strategy.getWallGroupWindows().size()) ? strategy.getWallGroupWindows().get(groupIdx) : 1.0f;
                int noiseScale = (groupIdx < strategy.getWallGroupNoiseScales().size()) ? strategy.getWallGroupNoiseScales().get(groupIdx) : 1;
                int relY = origRy - moduleMinY;
                int sampledIndex = golem.sampleWallGradient(slots, window, noiseScale, moduleHeight, relY, new BlockPos(wx, wy, wz));
                if (sampledIndex >= 0 && sampledIndex < 9) {
                    String sampledId = slots[sampledIndex];
                    if (sampledId != null && !sampledId.isEmpty()) {
                        BlockState sampledState = golem.getBlockStateFromId(sampledId);
                        if (sampledState != null) {
                            BlockPos worldPos = new BlockPos(wx, wy, wz);
                            stateToPlace = golem.getPlacementStateForBlock(worldPos, sampledState.getBlock(), v.state, 0, false);
                        } else {
                            skipBlock = true;
                        }
                    } else {
                        skipBlock = true;
                    }
                } else {
                    skipBlock = true;
                }
            }

            if (!skipBlock) {
                strategy.placeBlockStateAt(golem, wx, wy, wz, stateToPlace, rot, mirror, null);
            }
            ops++;
        }
    }

    public boolean done() {
        // Done when either cursor reaches voxels size OR blockStatesMap is empty
        if (blockStatesMap != null && blockStatesMap.isEmpty()) {
            return true;
        }
        return cursor >= (voxels == null ? 0 : voxels.size());
    }

    protected void placeJoinSliceAtAnchor(GoldGolemEntity golem, WallBuildStrategy strategy) {
        // Join template placement removed — gold marker positions are now included
        // directly in each module's voxel list, filling the 1-block gaps at boundaries.
    }

    public static int[] rotateAndMirror(int x, int y, int z, int rot, boolean mirror) {
        int rx = x, rz = z;
        switch (rot & 3) {
            case 1 -> { int ox = rx; rx = -rz; rz = ox; }
            case 2 -> { rx = -rx; rz = -rz; }
            case 3 -> { int ox = rx; rx = rz; rz = -ox; }
        }
        if (mirror) rx = -rx;
        return new int[]{rx, y, rz};
    }
}
