package ninja.trek.mc.goldgolem.world.entity.strategy.wall;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.WallBuildStrategy;

import java.util.List;

/**
 * Represents a wall module placement operation.
 * Extracted from GoldGolemEntity inner class.
 */
public class ModulePlacement {
    protected final int tplIndex;
    protected final int rot; // 0..3
    protected final boolean mirror;
    protected final Vec3d anchor;
    protected final Vec3d end;
    protected List<WallModuleTemplate.Voxel> voxels;
    protected int cursor = 0;
    protected boolean joinPlaced = false;

    public ModulePlacement(int tplIndex, int rot, boolean mirror, Vec3d anchor, Vec3d end) {
        this.tplIndex = tplIndex;
        this.rot = rot;
        this.mirror = mirror;
        this.anchor = anchor;
        this.end = end;
    }

    public Vec3d anchor() {
        return anchor;
    }

    public Vec3d end() {
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

    public void begin(GoldGolemEntity golem, WallBuildStrategy strategy) {
        var templates = strategy.getWallTemplates();
        if (tplIndex >= 0 && tplIndex < templates.size()) {
            var tpl = templates.get(tplIndex);
            this.voxels = tpl.voxels;
            // Update last direction
            int dx = tpl.bMarker.getX() - tpl.aMarker.getX();
            int dz = tpl.bMarker.getZ() - tpl.aMarker.getZ();
            int[] d = rotateAndMirror(dx, 0, dz, rot, mirror);
            if (Math.abs(d[0]) >= Math.abs(d[2])) {
                strategy.setWallLastDir(Integer.signum(d[0]), 0);
            } else {
                strategy.setWallLastDir(0, Integer.signum(d[2]));
            }
        }
    }

    public void placeSome(GoldGolemEntity golem, WallBuildStrategy strategy, int maxOps) {
        if (!joinPlaced) {
            placeJoinSliceAtAnchor(golem, strategy);
            joinPlaced = true;
        }

        var templates = strategy.getWallTemplates();
        if (tplIndex < 0 || tplIndex >= templates.size()) return;

        var tpl = templates.get(tplIndex);
        int ops = 0;
        // Calculate module height for gradient sampling
        int moduleMinY = tpl.minY;
        int moduleMaxY = tpl.voxels.stream().mapToInt(v -> v.rel.getY()).max().orElse(moduleMinY);
        int moduleHeight = Math.max(1, moduleMaxY - moduleMinY + 1);

        while (cursor < voxels.size() && ops < maxOps) {
            var v = voxels.get(cursor++);
            int rx = v.rel.getX();
            int ry = v.rel.getY();
            int rz = v.rel.getZ();
            int[] d = rotateAndMirror(rx, ry, rz, rot, mirror);
            int wx = MathHelper.floor(anchor.x) + d[0];
            int wy = MathHelper.floor(anchor.y) + d[1];
            int wz = MathHelper.floor(anchor.z) + d[2];

            // Apply gradient sampling for wall mode
            BlockState stateToPlace = v.state;
            String blockId = net.minecraft.registry.Registries.BLOCK.getId(v.state.getBlock()).toString();
            Integer groupIdx = strategy.getWallBlockGroup().get(blockId);
            if (groupIdx != null && groupIdx >= 0 && groupIdx < strategy.getWallGroupSlots().size()) {
                String[] slots = strategy.getWallGroupSlots().get(groupIdx);
                float window = (groupIdx < strategy.getWallGroupWindows().size()) ? strategy.getWallGroupWindows().get(groupIdx) : 1.0f;
                // Calculate relative Y position within module (0 at bottom)
                int relY = ry - moduleMinY;
                int sampledIndex = golem.sampleWallGradient(slots, window, moduleHeight, relY, new BlockPos(wx, wy, wz));
                if (sampledIndex >= 0 && sampledIndex < 9) {
                    String sampledId = slots[sampledIndex];
                    if (sampledId != null && !sampledId.isEmpty()) {
                        BlockState sampledState = golem.getBlockStateFromId(sampledId);
                        if (sampledState != null) {
                            stateToPlace = sampledState;
                        }
                    }
                }
            }

            strategy.placeBlockStateAt(golem, wx, wy, wz, stateToPlace, rot, mirror);
            ops++;
        }
    }

    public boolean done() {
        return cursor >= (voxels == null ? 0 : voxels.size());
    }

    protected void placeJoinSliceAtAnchor(GoldGolemEntity golem, WallBuildStrategy strategy) {
        var joinTemplate = strategy.getWallJoinTemplate();
        if (joinTemplate == null || joinTemplate.isEmpty()) return;

        var templates = strategy.getWallTemplates();
        if (tplIndex < 0 || tplIndex >= templates.size()) return;

        var tpl = templates.get(tplIndex);
        int dxm = tpl.bMarker.getX() - tpl.aMarker.getX();
        int dzm = tpl.bMarker.getZ() - tpl.aMarker.getZ();
        int[] d = rotateAndMirror(dxm, 0, dzm, rot, mirror);
        int fx = Integer.signum(d[0]);
        int fz = Integer.signum(d[2]);
        int px = -fz;
        int pz = fx;
        int ax = MathHelper.floor(anchor.x);
        int ay = MathHelper.floor(anchor.y);
        int az = MathHelper.floor(anchor.z);
        for (JoinEntry e : joinTemplate) {
            if (e.id == null || e.id.isEmpty()) continue;
            int wx = ax + px * e.du;
            int wy = ay + e.dy;
            int wz = az + pz * e.du;
            var ident = net.minecraft.util.Identifier.tryParse(e.id);
            if (ident == null) continue;
            var block = net.minecraft.registry.Registries.BLOCK.get(ident);
            if (block == null) continue;
            strategy.placeBlockStateAt(golem, wx, wy, wz, block.getDefaultState(), rot, mirror);
        }
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
