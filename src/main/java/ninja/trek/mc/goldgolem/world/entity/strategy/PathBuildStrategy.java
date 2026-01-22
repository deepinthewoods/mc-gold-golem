package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strategy for Path building mode.
 * Tracks the player's movement and builds a path following them.
 * Uses shared tracking fields from GoldGolemEntity (trackStart, pendingLines, currentLine).
 */
public class PathBuildStrategy extends AbstractBuildStrategy {

    // Alternating hand for placement animation
    private boolean leftHandActive = false;

    @Override
    public BuildMode getMode() {
        return BuildMode.PATH;
    }

    @Override
    public String getNbtPrefix() {
        return "G"; // Path mode uses "G" prefix for gradients
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        clearState();
    }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        tickPathMode(golem, owner);
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        // Path mode never completes on its own - it follows the player
        return false;
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (entity == null) return;

        // Save trackStart
        Vec3d trackStart = entity.getTrackStart();
        if (trackStart != null) {
            nbt.putDouble("TrackStartX", trackStart.x);
            nbt.putDouble("TrackStartY", trackStart.y);
            nbt.putDouble("TrackStartZ", trackStart.z);
        }

        // Save pending lines count and data (flat format to avoid NbtList API issues)
        var pendingLines = entity.getPendingLines();
        nbt.putInt("PendingLinesCount", pendingLines.size());
        int idx = 0;
        for (LineSeg seg : pendingLines) {
            nbt.putDouble("PL" + idx + "AX", seg.a.x);
            nbt.putDouble("PL" + idx + "AY", seg.a.y);
            nbt.putDouble("PL" + idx + "AZ", seg.a.z);
            nbt.putDouble("PL" + idx + "BX", seg.b.x);
            nbt.putDouble("PL" + idx + "BY", seg.b.y);
            nbt.putDouble("PL" + idx + "BZ", seg.b.z);
            idx++;
        }

        // Save current line
        LineSeg currentLine = entity.getCurrentLine();
        if (currentLine != null) {
            nbt.putDouble("CurrentLineAX", currentLine.a.x);
            nbt.putDouble("CurrentLineAY", currentLine.a.y);
            nbt.putDouble("CurrentLineAZ", currentLine.a.z);
            nbt.putDouble("CurrentLineBX", currentLine.b.x);
            nbt.putDouble("CurrentLineBY", currentLine.b.y);
            nbt.putDouble("CurrentLineBZ", currentLine.b.z);
            nbt.putInt("CurrentLineScanBit", currentLine.scanBit);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (entity == null) return;

        // Load trackStart
        if (nbt.contains("TrackStartX")) {
            double x = nbt.getDouble("TrackStartX", 0.0);
            double y = nbt.getDouble("TrackStartY", 0.0);
            double z = nbt.getDouble("TrackStartZ", 0.0);
            entity.setTrackStart(new Vec3d(x, y, z));
        }

        // Load pending lines (flat format)
        int count = nbt.getInt("PendingLinesCount", 0);
        entity.getPendingLines().clear();
        for (int i = 0; i < count; i++) {
            if (nbt.contains("PL" + i + "AX")) {
                Vec3d a = new Vec3d(
                    nbt.getDouble("PL" + i + "AX", 0.0),
                    nbt.getDouble("PL" + i + "AY", 0.0),
                    nbt.getDouble("PL" + i + "AZ", 0.0)
                );
                Vec3d b = new Vec3d(
                    nbt.getDouble("PL" + i + "BX", 0.0),
                    nbt.getDouble("PL" + i + "BY", 0.0),
                    nbt.getDouble("PL" + i + "BZ", 0.0)
                );
                entity.getPendingLines().addLast(new LineSeg(a, b));
            }
        }

        // Load current line
        if (nbt.contains("CurrentLineAX")) {
            Vec3d a = new Vec3d(
                nbt.getDouble("CurrentLineAX", 0.0),
                nbt.getDouble("CurrentLineAY", 0.0),
                nbt.getDouble("CurrentLineAZ", 0.0)
            );
            Vec3d b = new Vec3d(
                nbt.getDouble("CurrentLineBX", 0.0),
                nbt.getDouble("CurrentLineBY", 0.0),
                nbt.getDouble("CurrentLineBZ", 0.0)
            );
            LineSeg currentLine = new LineSeg(a, b);
            currentLine.begin(entity);
            currentLine.scanBit = nbt.getInt("CurrentLineScanBit", 0);
            entity.setCurrentLine(currentLine);
        }
    }

    @Override
    public boolean usesGradientUI() {
        return true;
    }

    @Override
    public boolean usesPlayerTracking() {
        return true;
    }

    /**
     * Clear all path mode state.
     */
    public void clearState() {
        if (entity != null) {
            entity.setTrackStart(null);
            entity.getPendingLines().clear();
            entity.setCurrentLine(null);
            entity.clearPlacementTracking();
        }
        leftHandActive = false;
    }

    // ========== Polymorphic Dispatch Methods ==========

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        // Path mode: always starts when nugget is fed
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        // Clear path mode state
        clearState();
    }

    // ========== Main tick logic ==========

    private void tickPathMode(GoldGolemEntity golem, PlayerEntity owner) {
        Vec3d trackStart = golem.getTrackStart();
        var pendingLines = golem.getPendingLines();
        LineSeg currentLine = golem.getCurrentLine();

        // Track lines while owner moves (require grounded for stability)
        if (owner != null && owner.isOnGround()) {
            // Capture slightly above the player's feet at creation time
            Vec3d p = new Vec3d(owner.getX(), owner.getY() + 0.05, owner.getZ());
            if (trackStart == null) {
                golem.setTrackStart(p);
                trackStart = p;
            } else {
                // Only create a new 3m segment once the player is 4m away from the current anchor
                double dist = trackStart.distanceTo(p);
                while (dist >= 4.0) {
                    Vec3d dir = p.subtract(trackStart);
                    double len = dir.length();
                    if (len < 1e-6) break;
                    Vec3d unit = dir.multiply(1.0 / len);
                    Vec3d end = trackStart.add(unit.multiply(3.0));
                    enqueueLine(golem, trackStart, end);
                    trackStart = end;
                    golem.setTrackStart(trackStart);
                    dist = trackStart.distanceTo(p);
                }
            }
        }

        // Process current line
        if (currentLine == null) {
            currentLine = pendingLines.pollFirst();
            if (currentLine != null) {
                currentLine.begin(golem);
                golem.setCurrentLine(currentLine);
                // Kick off movement toward the end of the line
                int endIdx = Math.max(0, currentLine.cells.size() - 1);
                Vec3d tgt = currentLine.pointAtIndex(endIdx);
                double ty0 = golem.computeGroundTargetY(tgt);
                golem.getNavigation().startMovingTo(tgt.x, ty0, tgt.z, 1.1);
                // Notify client that current line started
                sendLinesToClient(golem);
            }
        }

        if (currentLine != null) {
            // Placement paced by golem progress along the line
            // Place 1 block every 2 ticks, alternating hands
            if (placementTickCounter == 0) {
                int endIdxPl = Math.max(0, currentLine.cells.size() - 1);
                int progressCell = currentLine.progressCellIndex(golem.getX(), golem.getZ());
                Vec3d endPtPl = currentLine.pointAtIndex(endIdxPl);
                double exPl = golem.getX() - endPtPl.x;
                double ezPl = golem.getZ() - endPtPl.z;
                boolean nearEndPl = (exPl * exPl + ezPl * ezPl) <= (1.25 * 1.25);
                int boundCell = (nearEndPl || progressCell >= (endIdxPl - 1)) ? endIdxPl : progressCell;

                // Place exactly 1 block and get its position
                BlockPos placedBlock = currentLine.placeNextBlock(golem, boundCell);

                if (placedBlock != null) {
                    BlockPos previewBlock = currentLine.getNextUnplacedBlock(boundCell);
                    golem.beginHandAnimation(leftHandActive, placedBlock, previewBlock);

                    // Alternate hands
                    leftHandActive = !leftHandActive;
                }
            }

            // Always path toward the end of the current segment
            int endIdx = Math.max(0, currentLine.cells.size() - 1);
            Vec3d end = currentLine.pointAtIndex(endIdx);
            double ty = golem.computeGroundTargetY(end);
            golem.getNavigation().startMovingTo(end.x, ty, end.z, 1.1);

            // Detect stuck navigation and recover by teleporting
            double dx = golem.getX() - end.x;
            double dz = golem.getZ() - end.z;
            double distSq = dx * dx + dz * dz;
            if (golem.getNavigation().isIdle() && distSq > 1.0) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    if (golem.getEntityWorld() instanceof ServerWorld sw) {
                        sw.spawnParticles(ParticleTypes.PORTAL, golem.getX(), golem.getY() + 0.5, golem.getZ(),
                            40, 0.5, 0.5, 0.5, 0.2);
                        sw.spawnParticles(ParticleTypes.PORTAL, end.x, ty + 0.5, end.z,
                            40, 0.5, 0.5, 0.5, 0.2);
                    }
                    golem.refreshPositionAndAngles(end.x, ty, end.z, golem.getYaw(), golem.getPitch());
                    golem.getNavigation().stop();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }

            // Complete the line only when all pending done AND we've reached the end
            if (currentLine.isFullyProcessed()) {
                if (distSq <= 0.75 * 0.75 || golem.getNavigation().isIdle()) {
                    LineSeg done = currentLine;
                    LineSeg next = pendingLines.peekFirst();
                    if (next != null) {
                        placeCornerFill(golem, done, next);
                    }
                    golem.setCurrentLine(null);
                    // Update client after completing a line
                    sendLinesToClient(golem);
                }
            }
        }
    }

    /**
     * Enqueue a new line segment for processing.
     */
    private void enqueueLine(GoldGolemEntity golem, Vec3d a, Vec3d b) {
        LineSeg seg = new LineSeg(a, b);
        golem.getPendingLines().addLast(seg);
        // Sync to client for debug rendering
        sendLinesToClient(golem);
    }

    /**
     * Send current lines to the client for rendering.
     */
    private void sendLinesToClient(GoldGolemEntity golem) {
        if (golem.getEntityWorld() instanceof ServerWorld) {
            PlayerEntity owner = golem.getOwnerPlayer();
            if (owner instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                List<Vec3d> list = new ArrayList<>();
                LineSeg currentLine = golem.getCurrentLine();
                if (currentLine != null) {
                    list.add(currentLine.a);
                    list.add(currentLine.b);
                }
                for (LineSeg s : golem.getPendingLines()) {
                    list.add(s.a);
                    list.add(s.b);
                }
                Optional<Vec3d> anchor = Optional.ofNullable(golem.getTrackStart());
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp, golem.getId(), list, anchor);
            }
        }
    }

    /**
     * Fill corner blocks when transitioning between line segments.
     */
    private void placeCornerFill(GoldGolemEntity golem, LineSeg prev, LineSeg next) {
        // Compute end position of prev and start of next
        BlockPos endCell = prev.cells.isEmpty() ? BlockPos.ofFloored(prev.b) : prev.cells.get(prev.cells.size() - 1);
        double yPrev = prev.b.y;
        double x = endCell.getX() + 0.5;
        double z = endCell.getZ() + 0.5;

        // prev normal
        double len1 = Math.sqrt(prev.dirX * prev.dirX + prev.dirZ * prev.dirZ);
        double px1 = len1 > 1e-4 ? (-prev.dirZ / len1) : 0.0;
        double pz1 = len1 > 1e-4 ? (prev.dirX / len1) : 0.0;

        // next normal
        double len2 = Math.sqrt(next.dirX * next.dirX + next.dirZ * next.dirZ);
        double px2 = len2 > 1e-4 ? (-next.dirZ / len2) : 0.0;
        double pz2 = len2 > 1e-4 ? (next.dirX / len2) : 0.0;

        // Expand width by +1 to help fill gaps
        int old = golem.getPathWidth();
        golem.setPathWidth(Math.min(9, old + 1));
        golem.placeStripAt(x, yPrev, z, px1, pz1);
        golem.placeStripAt(x, yPrev, z, px2, pz2);
        golem.setPathWidth(old);
    }
}
