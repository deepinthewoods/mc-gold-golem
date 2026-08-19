package ninja.trek.mc.goldgolem.world.entity.strategy;

import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

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
    public void tick(GoldGolemEntity golem, Player owner) {
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
    public void writeNbt(CompoundTag nbt) {
        if (entity == null) return;

        // Save trackStart
        Vec3 trackStart = entity.getTrackStart();
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
    public void readNbt(CompoundTag nbt) {
        if (entity == null) return;

        // Load trackStart
        if (nbt.contains("TrackStartX")) {
            double x = nbt.getDoubleOr("TrackStartX", 0.0);
            double y = nbt.getDoubleOr("TrackStartY", 0.0);
            double z = nbt.getDoubleOr("TrackStartZ", 0.0);
            entity.setTrackStart(new Vec3(x, y, z));
        }

        // Load pending lines (flat format)
        int count = nbt.getIntOr("PendingLinesCount", 0);
        entity.getPendingLines().clear();
        for (int i = 0; i < count; i++) {
            if (nbt.contains("PL" + i + "AX")) {
                Vec3 a = new Vec3(
                    nbt.getDoubleOr("PL" + i + "AX", 0.0),
                    nbt.getDoubleOr("PL" + i + "AY", 0.0),
                    nbt.getDoubleOr("PL" + i + "AZ", 0.0)
                );
                Vec3 b = new Vec3(
                    nbt.getDoubleOr("PL" + i + "BX", 0.0),
                    nbt.getDoubleOr("PL" + i + "BY", 0.0),
                    nbt.getDoubleOr("PL" + i + "BZ", 0.0)
                );
                entity.getPendingLines().addLast(new LineSeg(a, b));
            }
        }

        // Load current line
        if (nbt.contains("CurrentLineAX")) {
            Vec3 a = new Vec3(
                nbt.getDoubleOr("CurrentLineAX", 0.0),
                nbt.getDoubleOr("CurrentLineAY", 0.0),
                nbt.getDoubleOr("CurrentLineAZ", 0.0)
            );
            Vec3 b = new Vec3(
                nbt.getDoubleOr("CurrentLineBX", 0.0),
                nbt.getDoubleOr("CurrentLineBY", 0.0),
                nbt.getDoubleOr("CurrentLineBZ", 0.0)
            );
            LineSeg currentLine = new LineSeg(a, b);
            currentLine.begin(entity);
            currentLine.scanBit = nbt.getIntOr("CurrentLineScanBit", 0);
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

    @Override
    public void writeLegacyNbt(ValueOutput view) {
        if (entity == null) return;

        // Save trackStart
        Vec3 trackStart = entity.getTrackStart();
        if (trackStart != null) {
            view.putDouble("PathTrackStartX", trackStart.x);
            view.putDouble("PathTrackStartY", trackStart.y);
            view.putDouble("PathTrackStartZ", trackStart.z);
        }

        // Save pending lines count and data (flat format)
        var pendingLines = entity.getPendingLines();
        view.putInt("PathPendingLinesCount", pendingLines.size());
        int idx = 0;
        for (LineSeg seg : pendingLines) {
            view.putDouble("PathPL" + idx + "AX", seg.a.x);
            view.putDouble("PathPL" + idx + "AY", seg.a.y);
            view.putDouble("PathPL" + idx + "AZ", seg.a.z);
            view.putDouble("PathPL" + idx + "BX", seg.b.x);
            view.putDouble("PathPL" + idx + "BY", seg.b.y);
            view.putDouble("PathPL" + idx + "BZ", seg.b.z);
            idx++;
        }

        // Save current line
        LineSeg currentLine = entity.getCurrentLine();
        if (currentLine != null) {
            view.putDouble("PathCurrentLineAX", currentLine.a.x);
            view.putDouble("PathCurrentLineAY", currentLine.a.y);
            view.putDouble("PathCurrentLineAZ", currentLine.a.z);
            view.putDouble("PathCurrentLineBX", currentLine.b.x);
            view.putDouble("PathCurrentLineBY", currentLine.b.y);
            view.putDouble("PathCurrentLineBZ", currentLine.b.z);
            view.putInt("PathCurrentLineScanBit", currentLine.scanBit);
        }
    }

    @Override
    public void readLegacyNbt(ValueInput view) {
        if (entity == null) return;

        // Load trackStart
        if (view.contains("PathTrackStartX")) {
            double x = view.getDoubleOr("PathTrackStartX", 0.0);
            double y = view.getDoubleOr("PathTrackStartY", 0.0);
            double z = view.getDoubleOr("PathTrackStartZ", 0.0);
            entity.setTrackStart(new Vec3(x, y, z));
        }

        // Load pending lines (flat format)
        int count = view.getIntOr("PathPendingLinesCount", 0);
        entity.getPendingLines().clear();
        for (int i = 0; i < count; i++) {
            if (view.contains("PathPL" + i + "AX")) {
                Vec3 a = new Vec3(
                    view.getDoubleOr("PathPL" + i + "AX", 0.0),
                    view.getDoubleOr("PathPL" + i + "AY", 0.0),
                    view.getDoubleOr("PathPL" + i + "AZ", 0.0)
                );
                Vec3 b = new Vec3(
                    view.getDoubleOr("PathPL" + i + "BX", 0.0),
                    view.getDoubleOr("PathPL" + i + "BY", 0.0),
                    view.getDoubleOr("PathPL" + i + "BZ", 0.0)
                );
                entity.getPendingLines().addLast(new LineSeg(a, b));
            }
        }

        // Load current line
        if (view.contains("PathCurrentLineAX")) {
            Vec3 a = new Vec3(
                view.getDoubleOr("PathCurrentLineAX", 0.0),
                view.getDoubleOr("PathCurrentLineAY", 0.0),
                view.getDoubleOr("PathCurrentLineAZ", 0.0)
            );
            Vec3 b = new Vec3(
                view.getDoubleOr("PathCurrentLineBX", 0.0),
                view.getDoubleOr("PathCurrentLineBY", 0.0),
                view.getDoubleOr("PathCurrentLineBZ", 0.0)
            );
            LineSeg currentLine = new LineSeg(a, b);
            currentLine.begin(entity);
            currentLine.scanBit = view.getIntOr("PathCurrentLineScanBit", 0);
            entity.setCurrentLine(currentLine);
        }
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
    public FeedResult handleFeedInteraction(Player player) {
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

    private void tickPathMode(GoldGolemEntity golem, Player owner) {
        // Process pending path-mode mining
        var pathMiner = golem.getPathGradientMiner();
        if (pathMiner.isMining()) {
            boolean done = pathMiner.tickMining(golem, leftHandActive);
            if (done) {
                pathMiner.reset(golem);
            }
            return; // busy mining
        }
        // Start next mine if queued
        var pendingMines = golem.getPathPendingMines();
        if (!pendingMines.isEmpty()) {
            BlockPos mineTarget = pendingMines.pollFirst();
            if (!golem.level().getBlockState(mineTarget).isAir()) {
                pathMiner.startMining(mineTarget);
                return;
            }
        }

        Vec3 trackStart = golem.getTrackStart();
        var pendingLines = golem.getPendingLines();
        LineSeg currentLine = golem.getCurrentLine();

        // Track lines while owner moves (require grounded for stability)
        if (owner != null && owner.onGround()) {
            // Capture slightly above the player's feet at creation time
            Vec3 p = new Vec3(owner.getX(), owner.getY() + 0.05, owner.getZ());
            if (trackStart == null) {
                golem.setTrackStart(p);
                trackStart = p;
            } else {
                // Only create a new 3m segment once the player is 4m away from the current anchor
                double dist = trackStart.distanceTo(p);
                while (dist >= 4.0) {
                    Vec3 dir = p.subtract(trackStart);
                    double len = dir.length();
                    if (len < 1e-6) break;
                    Vec3 unit = dir.scale(1.0 / len);
                    Vec3 end = trackStart.add(unit.scale(3.0));
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
                Vec3 tgt = currentLine.pointAtIndex(endIdx);
                double ty0 = golem.computeGroundTargetY(tgt);
                golem.getNavigation().moveTo(tgt.x, ty0, tgt.z, 1.1);
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
                Vec3 endPtPl = currentLine.pointAtIndex(endIdxPl);
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
            Vec3 end = currentLine.pointAtIndex(endIdx);
            double ty = golem.computeGroundTargetY(end);
            golem.getNavigation().moveTo(end.x, ty, end.z, 1.1);

            // Detect stuck navigation and recover by teleporting
            double dx = golem.getX() - end.x;
            double dz = golem.getZ() - end.z;
            double distSq = dx * dx + dz * dz;
            if (golem.getNavigation().isDone() && distSq > 1.0) {
                stuckTicks++;
                if (stuckTicks >= 20) {
                    BlockPos targetPos = new BlockPos((int) Math.floor(end.x), (int) Math.floor(ty), (int) Math.floor(end.z));
                    golem.teleportWithParticles(targetPos);
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }

            // Complete the line only when all pending done AND we've reached the end
            if (currentLine.isFullyProcessed()) {
                if (distSq <= 0.75 * 0.75 || golem.getNavigation().isDone()) {
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
    private void enqueueLine(GoldGolemEntity golem, Vec3 a, Vec3 b) {
        LineSeg seg = new LineSeg(a, b);
        golem.getPendingLines().addLast(seg);
        // Sync to client for debug rendering
        sendLinesToClient(golem);
    }

    /**
     * Send current lines to the client for rendering.
     */
    private void sendLinesToClient(GoldGolemEntity golem) {
        if (golem.level() instanceof ServerLevel) {
            Player owner = golem.getOwnerPlayer();
            if (owner instanceof net.minecraft.server.level.ServerPlayer sp) {
                List<Vec3> list = new ArrayList<>();
                LineSeg currentLine = golem.getCurrentLine();
                if (currentLine != null) {
                    list.add(currentLine.a);
                    list.add(currentLine.b);
                }
                for (LineSeg s : golem.getPendingLines()) {
                    list.add(s.a);
                    list.add(s.b);
                }
                Optional<Vec3> anchor = Optional.ofNullable(golem.getTrackStart());
                ninja.trek.mc.goldgolem.net.ServerNet.sendLines(sp, golem.getId(), list, anchor, false);
            }
        }
    }

    /**
     * Fill corner blocks when transitioning between line segments.
     */
    private void placeCornerFill(GoldGolemEntity golem, LineSeg prev, LineSeg next) {
        // Compute end position of prev and start of next
        BlockPos endCell = prev.cells.isEmpty() ? BlockPos.containing(prev.b) : prev.cells.get(prev.cells.size() - 1);
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
