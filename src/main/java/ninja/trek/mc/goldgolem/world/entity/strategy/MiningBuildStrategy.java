package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.OreMiningMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.registry.RegistryKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Strategy for Mining mode.
 * Digs tunnels in a branch mining pattern and deposits ore in a chest.
 */
public class MiningBuildStrategy extends BaseMiningStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(MiningBuildStrategy.class);

    // Mining configuration
    private BlockPos chestPos = null;
    private Direction direction = null;
    private BlockPos startPos = null;
    private int branchDepth = 16;
    private int branchSpacing = 3;
    private int tunnelHeight = 2;
    private OreMiningMode oreMiningMode = OreMiningMode.SILK_TOUCH_FORTUNE;

    // Mining progress state
    private int primaryProgress = 0;
    private int currentBranch = -1;
    private boolean branchLeft = true;
    private int branchProgress = 0;
    private boolean returningToChest = false;
    private boolean idleAtChest = false;

    // Mining runtime state (not persisted)
    private final Set<BlockPos> pendingOres = new HashSet<>();

    @Override
    public BuildMode getMode() {
        return BuildMode.MINING;
    }

    @Override
    public String getNbtPrefix() {
        return "Mining";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        // Reset transient state
        pendingOres.clear();
    }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        if (entity == null) return;
        tickMiningMode();
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        return false; // Mining never completes on its own
    }

    @Override
    public boolean usesPlayerTracking() {
        return false; // Mining operates autonomously
    }

    // ==================== Block Mining Callbacks ====================

    @Override
    protected void onBlockAlreadyAir(BlockPos pos, boolean isLeftHand) {
        pendingOres.remove(pos);
    }

    @Override
    protected void onBlockBroken(BlockPos pos, boolean isLeftHand, BlockState brokenState) {
        pendingOres.remove(pos);

        String blockId = Registries.BLOCK.getId(brokenState.getBlock()).toString();
        if (isOreBlock(blockId)) {
            mineOreSurroundings(pos);
        }
    }

    // ==================== Configuration Methods ====================

    public void setConfig(BlockPos chestPos, Direction miningDir, BlockPos startPos) {
        this.chestPos = chestPos;
        this.direction = miningDir;
        this.startPos = startPos;
        this.idleAtChest = true; // Start idle, waiting for gold nugget
    }

    public void setSliders(int branchDepth, int branchSpacing, int tunnelHeight) {
        this.branchDepth = Math.max(1, Math.min(512, branchDepth));
        this.branchSpacing = Math.max(1, Math.min(16, branchSpacing));
        this.tunnelHeight = Math.max(2, Math.min(6, tunnelHeight));
    }

    public int getBranchDepth() { return branchDepth; }
    public int getBranchSpacing() { return branchSpacing; }
    public int getTunnelHeight() { return tunnelHeight; }
    public OreMiningMode getOreMiningMode() { return oreMiningMode; }
    public void setOreMiningMode(OreMiningMode mode) { this.oreMiningMode = mode != null ? mode : OreMiningMode.ALWAYS; }

    public boolean isIdleAtChest() { return idleAtChest; }
    public void setIdleAtChest(boolean idle) { this.idleAtChest = idle; }

    public void resetToIdle() {
        idleAtChest = true;
        returningToChest = false;
        resetMiningState();
    }

    public void startFromIdle() {
        idleAtChest = false;
    }

    public void clearState() {
        primaryProgress = 0;
        currentBranch = -1;
        branchProgress = 0;
        returningToChest = false;
        idleAtChest = false;
        pendingOres.clear();
        resetMiningState();
    }

    // ==================== NBT Serialization ====================

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (chestPos != null) {
            nbt.putInt("ChestX", chestPos.getX());
            nbt.putInt("ChestY", chestPos.getY());
            nbt.putInt("ChestZ", chestPos.getZ());
        }
        if (direction != null) {
            nbt.putString("Dir", direction.name());
        }
        if (startPos != null) {
            nbt.putInt("StartX", startPos.getX());
            nbt.putInt("StartY", startPos.getY());
            nbt.putInt("StartZ", startPos.getZ());
        }
        nbt.putInt("BranchDepth", branchDepth);
        nbt.putInt("BranchSpacing", branchSpacing);
        nbt.putInt("TunnelHeight", tunnelHeight);
        nbt.putInt("PrimaryProgress", primaryProgress);
        nbt.putInt("CurrentBranch", currentBranch);
        nbt.putBoolean("BranchLeft", branchLeft);
        nbt.putInt("BranchProgress", branchProgress);
        nbt.putBoolean("ReturningToChest", returningToChest);
        nbt.putBoolean("IdleAtChest", idleAtChest);
        nbt.putInt("OreMiningMode", oreMiningMode.ordinal());
        writeBaseMiningNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.contains("ChestX")) {
            chestPos = new BlockPos(nbt.getInt("ChestX", 0), nbt.getInt("ChestY", 0), nbt.getInt("ChestZ", 0));
        } else {
            chestPos = null;
        }
        if (nbt.contains("Dir")) {
            try {
                direction = Direction.valueOf(nbt.getString("Dir", "NORTH"));
            } catch (IllegalArgumentException ignored) {
                direction = null;
            }
        }
        if (nbt.contains("StartX")) {
            startPos = new BlockPos(nbt.getInt("StartX", 0), nbt.getInt("StartY", 0), nbt.getInt("StartZ", 0));
        } else {
            startPos = null;
        }
        branchDepth = nbt.getInt("BranchDepth", 16);
        branchSpacing = nbt.getInt("BranchSpacing", 3);
        tunnelHeight = nbt.getInt("TunnelHeight", 2);
        primaryProgress = nbt.getInt("PrimaryProgress", 0);
        currentBranch = nbt.getInt("CurrentBranch", -1);
        branchLeft = nbt.getBoolean("BranchLeft", true);
        branchProgress = nbt.getInt("BranchProgress", 0);
        returningToChest = nbt.getBoolean("ReturningToChest", false);
        idleAtChest = nbt.getBoolean("IdleAtChest", false);
        oreMiningMode = OreMiningMode.fromOrdinal(nbt.getInt("OreMiningMode", 0));
        readBaseMiningNbt(nbt);
    }

    // ==================== Polymorphic Dispatch Methods ====================

    @Override
    public int getConfigInt(String key, int defaultValue) {
        return switch (key) {
            case "branchDepth" -> branchDepth;
            case "branchSpacing" -> branchSpacing;
            case "tunnelHeight" -> tunnelHeight;
            case "oreMiningMode" -> oreMiningMode.ordinal();
            default -> defaultValue;
        };
    }

    @Override
    public void writeLegacyNbt(WriteView view) {
        if (chestPos != null) {
            view.putInt("MiningChestX", chestPos.getX());
            view.putInt("MiningChestY", chestPos.getY());
            view.putInt("MiningChestZ", chestPos.getZ());
        }
        if (direction != null) {
            view.putString("MiningDir", direction.name());
        }
        if (startPos != null) {
            view.putInt("MiningStartX", startPos.getX());
            view.putInt("MiningStartY", startPos.getY());
            view.putInt("MiningStartZ", startPos.getZ());
        }
        view.putInt("MiningBranchDepth", branchDepth);
        view.putInt("MiningBranchSpacing", branchSpacing);
        view.putInt("MiningTunnelHeight", tunnelHeight);
        view.putInt("MiningPrimaryProgress", primaryProgress);
        view.putInt("MiningCurrentBranch", currentBranch);
        view.putBoolean("MiningBranchLeft", branchLeft);
        view.putInt("MiningBranchProgress", branchProgress);
        view.putBoolean("MiningReturningToChest", returningToChest);
        view.putBoolean("MiningIdleAtChest", idleAtChest);
        view.putInt("MiningOreMiningMode", oreMiningMode.ordinal());
        if (buildingBlockType != null) {
            view.putString("MiningBuildingBlock", buildingBlockType);
        }
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        if (view.contains("MiningChestX")) {
            chestPos = new BlockPos(
                view.getInt("MiningChestX", 0),
                view.getInt("MiningChestY", 0),
                view.getInt("MiningChestZ", 0)
            );
        } else {
            chestPos = null;
        }
        String miningDir = view.getString("MiningDir", null);
        if (miningDir != null) {
            try {
                direction = Direction.valueOf(miningDir);
            } catch (IllegalArgumentException ignored) {
                direction = null;
            }
        }
        if (view.contains("MiningStartX")) {
            startPos = new BlockPos(
                view.getInt("MiningStartX", 0),
                view.getInt("MiningStartY", 0),
                view.getInt("MiningStartZ", 0)
            );
        } else {
            startPos = null;
        }
        branchDepth = view.getInt("MiningBranchDepth", 16);
        branchSpacing = view.getInt("MiningBranchSpacing", 3);
        tunnelHeight = view.getInt("MiningTunnelHeight", 2);
        primaryProgress = view.getInt("MiningPrimaryProgress", 0);
        currentBranch = view.getInt("MiningCurrentBranch", -1);
        branchLeft = view.getBoolean("MiningBranchLeft", true);
        branchProgress = view.getInt("MiningBranchProgress", 0);
        returningToChest = view.getBoolean("MiningReturningToChest", false);
        idleAtChest = view.getBoolean("MiningIdleAtChest", false);
        oreMiningMode = OreMiningMode.fromOrdinal(view.getInt("MiningOreMiningMode", 0));
        String block = view.getString("MiningBuildingBlock", null);
        buildingBlockType = (block != null && !block.isEmpty()) ? block : null;
    }

    @Override
    public boolean canStartFromIdle() {
        return isIdleAtChest();
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        if (isIdleAtChest()) {
            startFromIdle();
            return FeedResult.STARTED;
        }
        return FeedResult.ALREADY_ACTIVE;
    }

    @Override
    public void handleOwnerDamage() {
        resetToIdle();
    }

    // ==================== Mining Logic ====================

    private void tickMiningMode() {
        if (chestPos == null || direction == null || startPos == null) {
            // Invalid state, stop mining
            entity.setBuildingPaths(false);
            return;
        }

        // State 1: Idle at chest (waiting for gold nugget)
        if (idleAtChest) {
            double dx = entity.getX() - (startPos.getX() + 0.5);
            double dz = entity.getZ() - (startPos.getZ() + 0.5);
            double distSq = dx * dx + dz * dz;
            if (distSq > 4.0) {
                entity.getNavigation().startMovingTo(startPos.getX() + 0.5,
                    startPos.getY(), startPos.getZ() + 0.5, 1.0);
            } else {
                entity.getNavigation().stop();
            }
            return;
        }

        // State 2: Returning to chest to deposit
        if (returningToChest) {
            tickMiningReturn();
            return;
        }

        // State 3: Check if inventory is full (need to return)
        if (isInventoryFull()) {
            returningToChest = true;
            leftTarget = null;
            rightTarget = null;
            leftBreakProgress = 0;
            rightBreakProgress = 0;
            return;
        }

        // State 4: Active mining
        tickMiningActive();
    }

    private void tickMiningReturn() {
        double dx = entity.getX() - (startPos.getX() + 0.5);
        double dz = entity.getZ() - (startPos.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq > 4.0) {
            entity.getNavigation().startMovingTo(startPos.getX() + 0.5,
                startPos.getY(), startPos.getZ() + 0.5, 1.1);

            if (entity.getNavigation().isIdle() && distSq > 16.0) {
                stuckTicks++;
                if (stuckTicks >= 60) {
                    teleportToStart();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
        } else {
            entity.getNavigation().stop();
            depositInventoryToChest(chestPos);
            returningToChest = false;
            if (isInventoryFull()) {
                idleAtChest = true;
                entity.setBuildingPaths(false);
            }
        }
    }

    private void tickMiningActive() {
        placeBlocksUnderFeet();
        scanForOres();
        tryPlaceTorchInDarkArea();

        // Get targets for each hand independently
        if (leftTarget == null || entity.getEntityWorld().getBlockState(leftTarget).isAir()) {
            leftTarget = getNextMiningTarget();
            leftBreakProgress = 0;
            leftSwingTick = 0;
        }
        if (rightTarget == null || entity.getEntityWorld().getBlockState(rightTarget).isAir()) {
            rightTarget = getNextMiningTargetExcluding(leftTarget);
            rightBreakProgress = 0;
            rightSwingTick = 0;
        }

        // If no targets, we're done
        if (leftTarget == null && rightTarget == null) {
            entity.setBuildingPaths(false);
            return;
        }

        // Navigate toward the closest target
        BlockPos navTarget = leftTarget != null ? leftTarget : rightTarget;
        if (leftTarget != null && rightTarget != null) {
            // Navigate to midpoint if both targets exist
            double midX = (leftTarget.getX() + rightTarget.getX()) / 2.0 + 0.5;
            double midY = Math.min(leftTarget.getY(), rightTarget.getY());
            double midZ = (leftTarget.getZ() + rightTarget.getZ()) / 2.0 + 0.5;
            entity.getNavigation().startMovingTo(midX, midY, midZ, 1.1);
        } else {
            entity.getNavigation().startMovingTo(navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5, 1.1);
        }

        // Mine with left hand if in range
        if (leftTarget != null) {
            double ldx = entity.getX() - (leftTarget.getX() + 0.5);
            double ldy = entity.getY() - leftTarget.getY();
            double ldz = entity.getZ() - (leftTarget.getZ() + 0.5);
            double lDistSq = ldx * ldx + ldy * ldy + ldz * ldz;
            if (lDistSq <= 25.0) {
                mineBlockWithHand(leftTarget, true);
            }
        }

        // Mine with right hand if in range
        if (rightTarget != null) {
            double rdx = entity.getX() - (rightTarget.getX() + 0.5);
            double rdy = entity.getY() - rightTarget.getY();
            double rdz = entity.getZ() - (rightTarget.getZ() + 0.5);
            double rDistSq = rdx * rdx + rdy * rdy + rdz * rdz;
            if (rDistSq <= 25.0) {
                mineBlockWithHand(rightTarget, false);
            }
        }

        // Stuck detection
        BlockPos primaryTarget = leftTarget != null ? leftTarget : rightTarget;
        if (primaryTarget != null) {
            double pdx = entity.getX() - (primaryTarget.getX() + 0.5);
            double pdy = entity.getY() - primaryTarget.getY();
            double pdz = entity.getZ() - (primaryTarget.getZ() + 0.5);
            double pDistSq = pdx * pdx + pdy * pdy + pdz * pdz;
            if (entity.getNavigation().isIdle() && pDistSq > 16.0) {
                stuckTicks++;
                if (stuckTicks >= 60) {
                    teleportToStart();
                    stuckTicks = 0;
                    leftTarget = null;
                    rightTarget = null;
                    leftBreakProgress = 0;
                    rightBreakProgress = 0;
                    primaryProgress = 0;
                    currentBranch = -1;
                    branchProgress = 0;
                    pendingOres.clear();
                }
            } else {
                stuckTicks = 0;
            }
        }
    }

    /**
     * Get next mining target, excluding a specific position.
     */
    private BlockPos getNextMiningTargetExcluding(BlockPos exclude) {
        BlockPos target = getNextMiningTarget();
        if (target != null && target.equals(exclude)) {
            // Get another target from pending ores or branch mining
            if (!pendingOres.isEmpty()) {
                for (BlockPos orePos : pendingOres) {
                    if (!orePos.equals(exclude)) {
                        BlockState state = entity.getEntityWorld().getBlockState(orePos);
                        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                        if (isOreBlock(blockId) && !state.isAir()) {
                            return orePos;
                        }
                    }
                }
            }
            return null; // No second target available
        }
        return target;
    }

    private void teleportToStart() {
        LOGGER.info("Mining Golem stuck detected! Teleporting to start pos: {}", startPos);
        entity.teleportWithParticles(startPos);
    }

    private boolean isInventoryFull() {
        Inventory inventory = entity.getInventory();
        int emptySlots = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
            }
        }
        return emptySlots < 2;
    }

    private void scanForOres() {
        if (entity.getEntityWorld().isClient()) return;

        // Skip ore scanning entirely if mode is NEVER
        if (oreMiningMode == OreMiningMode.NEVER) {
            pendingOres.clear();
            return;
        }

        BlockPos center = entity.getBlockPos();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = entity.getEntityWorld().getBlockState(pos);
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();

                    if (isOreBlock(blockId) && !pendingOres.contains(pos)) {
                        pendingOres.add(pos);
                    }
                }
            }
        }
    }

    private BlockPos getNextMiningTarget() {
        if (!pendingOres.isEmpty()) {
            // Check if we should skip ores based on mode
            if (oreMiningMode == OreMiningMode.NEVER) {
                pendingOres.clear();
                return getNextBranchMiningTarget();
            }

            // In SILK_TOUCH_FORTUNE mode, only mine if we have a valid tool
            if (oreMiningMode == OreMiningMode.SILK_TOUCH_FORTUNE && !hasValidEnchantedTool()) {
                pendingOres.clear();
                return getNextBranchMiningTarget();
            }

            BlockPos orePos = pendingOres.iterator().next();
            BlockState state = entity.getEntityWorld().getBlockState(orePos);
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            if (!isOreBlock(blockId) || state.isAir()) {
                pendingOres.remove(orePos);
                return getNextMiningTarget();
            }
            return orePos;
        }
        return getNextBranchMiningTarget();
    }

    /**
     * Check if the golem has a tool with Silk Touch or Fortune 3+.
     * Prefers Silk Touch over Fortune.
     */
    private boolean hasValidEnchantedTool() {
        Inventory inventory = entity.getInventory();
        var world = entity.getEntityWorld();
        if (world == null) return false;

        var registryManager = world.getRegistryManager();
        var enchantmentRegistry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);

        // Get entries using identifier from registry key
        var silkTouchEntry = enchantmentRegistry.getEntry(Enchantments.SILK_TOUCH.getValue());
        var fortuneEntry = enchantmentRegistry.getEntry(Enchantments.FORTUNE.getValue());

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // Check for Silk Touch
            if (silkTouchEntry.isPresent() && EnchantmentHelper.getLevel(silkTouchEntry.get(), stack) > 0) {
                return true;
            }

            // Check for Fortune 3+
            if (fortuneEntry.isPresent() && EnchantmentHelper.getLevel(fortuneEntry.get(), stack) >= 3) {
                return true;
            }
        }
        return false;
    }

    private BlockPos getNextBranchMiningTarget() {
        if (currentBranch == -1) {
            BlockPos primaryStart = startPos.offset(direction, 1);
            BlockPos target = primaryStart.offset(direction, primaryProgress);

            if (primaryProgress > 0 && primaryProgress % branchSpacing == 0) {
                currentBranch = primaryProgress / branchSpacing;
                branchLeft = true;
                branchProgress = 0;
                return getNextBranchMiningTarget();
            }

            for (int y = 1; y < tunnelHeight; y++) {
                BlockPos layerTarget = target.up(y - 1);
                if (shouldMineBlock(layerTarget)) {
                    return layerTarget;
                }
            }

            primaryProgress++;
            return getNextBranchMiningTarget();
        } else {
            Direction branchDir = getBranchDirection(branchLeft);
            BlockPos branchStart = startPos.offset(direction, 1 + currentBranch * branchSpacing);
            BlockPos target = branchStart.offset(branchDir, branchProgress + 1);

            for (int y = 1; y < tunnelHeight; y++) {
                BlockPos layerTarget = target.up(y - 1);
                if (shouldMineBlock(layerTarget)) {
                    return layerTarget;
                }
            }

            branchProgress++;

            if (branchProgress >= branchDepth) {
                if (branchLeft) {
                    branchLeft = false;
                    branchProgress = 0;
                } else {
                    currentBranch = -1;
                    branchProgress = 0;
                }
            }

            return getNextBranchMiningTarget();
        }
    }

    private Direction getBranchDirection(boolean left) {
        if (direction == Direction.NORTH) {
            return left ? Direction.WEST : Direction.EAST;
        } else if (direction == Direction.SOUTH) {
            return left ? Direction.EAST : Direction.WEST;
        } else if (direction == Direction.EAST) {
            return left ? Direction.NORTH : Direction.SOUTH;
        } else {
            return left ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private boolean shouldMineBlock(BlockPos pos) {
        BlockState state = entity.getEntityWorld().getBlockState(pos);
        if (state.isAir() || state.getHardness(entity.getEntityWorld(), pos) < 0) {
            return false;
        }

        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();

        // Never mine chests (used for storage)
        if (isChestBlock(blockId)) {
            return false;
        }

        // Never mine torches directly (lighting is important)
        if (isTorchBlock(blockId)) {
            return false;
        }

        // Don't mine non-solid blocks on the floor (flowers, saplings, grass, etc.)
        if (pos.getY() == startPos.getY() && state.getCollisionShape(entity.getEntityWorld(), pos).isEmpty()) {
            return false;
        }

        return true;
    }

    private void mineOreSurroundings(BlockPos center) {
        for (Direction dir : Direction.values()) {
            BlockPos adjacent = center.offset(dir);
            BlockState state = entity.getEntityWorld().getBlockState(adjacent);

            if (!state.isAir() && state.getHardness(entity.getEntityWorld(), adjacent) >= 0) {
                if (!pendingOres.contains(adjacent)) {
                    pendingOres.add(adjacent);
                }
            }
        }
    }
}
