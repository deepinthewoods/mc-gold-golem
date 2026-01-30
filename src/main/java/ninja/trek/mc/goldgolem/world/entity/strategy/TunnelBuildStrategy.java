package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.OreMiningMode;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for Tunnel mode.
 * Digs an infinite rectangular tunnel in one direction, depositing to 3 chests.
 */
public class TunnelBuildStrategy extends BaseMiningStrategy {

    // Tunnel configuration
    private BlockPos chestPos1 = null;
    private BlockPos chestPos2 = null;
    private BlockPos chestPos3 = null;
    private Direction tunnelDir = null;
    private BlockPos startPos = null;
    private int width = 3;   // 1-9
    private int height = 3;  // 2-6
    private OreMiningMode oreMiningMode = OreMiningMode.SILK_TOUCH_FORTUNE;

    // Tunnel progress state
    private int sliceProgress = 0; // how far along the tunnel we've dug
    private int currentChestIndex = 0; // 0-2 round-robin for depositing
    private boolean returningToChest = false;
    private boolean idleAtStart = false;

    // PlacementPlanner for smart movement
    private PlacementPlanner planner;
    private int ticksInAir = 0;
    private static final int TICKS_IN_AIR_THRESHOLD = 5;

    // Movement tracking for return-to-chest stuck detection
    private double lastX = 0, lastZ = 0;
    private int noMovementTicks = 0;
    private static final double MOVEMENT_THRESHOLD = 0.1;
    private static final int STUCK_TICKS_BEFORE_TELEPORT = 100;

    @Override
    public BuildMode getMode() {
        return BuildMode.TUNNEL;
    }

    @Override
    public String getNbtPrefix() {
        return "Tunnel";
    }

    @Override
    public void initialize(GoldGolemEntity golem) {
        super.initialize(golem);
        planner = new PlacementPlanner(golem);
        ticksInAir = 0;
    }

    @Override
    public void tick(GoldGolemEntity golem, PlayerEntity owner) {
        if (entity == null) return;
        tickTunnelMode();
    }

    @Override
    public void cleanup(GoldGolemEntity golem) {
        super.cleanup(golem);
        clearState();
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public boolean usesPlayerTracking() {
        return false;
    }

    // ==================== Deposit Filtering ====================

    @Override
    protected boolean shouldSkipDeposit(ItemStack stack) {
        return isToolOrTorch(stack);
    }

    private boolean isToolOrTorch(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        if (item == Items.TORCH || item == Items.SOUL_TORCH) return true;
        String itemId = Registries.ITEM.getId(item).toString();
        return itemId.contains("_pickaxe") || itemId.contains("_shovel") ||
               itemId.contains("_axe") || itemId.contains("_hoe") || itemId.contains("_sword");
    }

    // ==================== Configuration Methods ====================

    public void setConfig(BlockPos chest1, BlockPos chest2, BlockPos chest3, Direction dir, BlockPos startPos) {
        this.chestPos1 = chest1;
        this.chestPos2 = chest2;
        this.chestPos3 = chest3;
        this.tunnelDir = dir;
        this.startPos = startPos;
        this.idleAtStart = true;
    }

    public void setWidth(int width) {
        int newWidth = Math.max(1, Math.min(9, width));
        if (newWidth != this.width && !idleAtStart) {
            this.width = newWidth;
            restartTunnel();
        } else {
            this.width = newWidth;
        }
    }

    public void setHeight(int height) {
        int newHeight = Math.max(2, Math.min(6, height));
        if (newHeight != this.height && !idleAtStart) {
            this.height = newHeight;
            restartTunnel();
        } else {
            this.height = newHeight;
        }
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public OreMiningMode getOreMiningMode() { return oreMiningMode; }
    public void setOreMiningMode(OreMiningMode mode) { this.oreMiningMode = mode != null ? mode : OreMiningMode.ALWAYS; }

    public boolean isIdleAtStart() { return idleAtStart; }

    public void resetToIdle() {
        idleAtStart = true;
        returningToChest = false;
        resetMiningState();
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) planner.clear();
    }

    public void startFromIdle() {
        idleAtStart = false;
        skipCompletedSlices();
    }

    public void restartTunnel() {
        sliceProgress = 0;
        returningToChest = false;
        resetMiningState();
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) planner.clear();
        skipCompletedSlices();
        idleAtStart = false;
    }

    private void skipCompletedSlices() {
        if (entity == null || startPos == null || tunnelDir == null) return;
        int maxSlice = 255;
        while (sliceProgress <= maxSlice) {
            if (sliceHasBlocksToMine(sliceProgress)) break;
            sliceProgress++;
        }
    }

    private boolean sliceHasBlocksToMine(int slice) {
        List<BlockPos> blocks = getBlocksForSlice(slice);
        for (BlockPos pos : blocks) {
            if (shouldMineBlock(pos)) return true;
        }
        return false;
    }

    public void clearState() {
        sliceProgress = 0;
        currentChestIndex = 0;
        returningToChest = false;
        idleAtStart = false;
        resetMiningState();
        ticksInAir = 0;
        noMovementTicks = 0;
        if (planner != null) planner.clear();
    }

    // ==================== NBT Serialization ====================

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (chestPos1 != null) {
            nbt.putInt("Chest1X", chestPos1.getX());
            nbt.putInt("Chest1Y", chestPos1.getY());
            nbt.putInt("Chest1Z", chestPos1.getZ());
        }
        if (chestPos2 != null) {
            nbt.putInt("Chest2X", chestPos2.getX());
            nbt.putInt("Chest2Y", chestPos2.getY());
            nbt.putInt("Chest2Z", chestPos2.getZ());
        }
        if (chestPos3 != null) {
            nbt.putInt("Chest3X", chestPos3.getX());
            nbt.putInt("Chest3Y", chestPos3.getY());
            nbt.putInt("Chest3Z", chestPos3.getZ());
        }
        if (tunnelDir != null) nbt.putString("TunnelDir", tunnelDir.name());
        if (startPos != null) {
            nbt.putInt("StartX", startPos.getX());
            nbt.putInt("StartY", startPos.getY());
            nbt.putInt("StartZ", startPos.getZ());
        }
        nbt.putInt("Width", width);
        nbt.putInt("Height", height);
        nbt.putInt("OreMiningMode", oreMiningMode.ordinal());
        nbt.putInt("SliceProgress", sliceProgress);
        nbt.putInt("CurrentChestIndex", currentChestIndex);
        nbt.putBoolean("ReturningToChest", returningToChest);
        nbt.putBoolean("IdleAtStart", idleAtStart);
        writeBaseMiningNbt(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.contains("Chest1X")) {
            chestPos1 = new BlockPos(nbt.getInt("Chest1X", 0), nbt.getInt("Chest1Y", 0), nbt.getInt("Chest1Z", 0));
        } else { chestPos1 = null; }
        if (nbt.contains("Chest2X")) {
            chestPos2 = new BlockPos(nbt.getInt("Chest2X", 0), nbt.getInt("Chest2Y", 0), nbt.getInt("Chest2Z", 0));
        } else { chestPos2 = null; }
        if (nbt.contains("Chest3X")) {
            chestPos3 = new BlockPos(nbt.getInt("Chest3X", 0), nbt.getInt("Chest3Y", 0), nbt.getInt("Chest3Z", 0));
        } else { chestPos3 = null; }
        if (nbt.contains("TunnelDir")) {
            try { tunnelDir = Direction.valueOf(nbt.getString("TunnelDir", "NORTH")); }
            catch (IllegalArgumentException ignored) { tunnelDir = null; }
        }
        if (nbt.contains("StartX")) {
            startPos = new BlockPos(nbt.getInt("StartX", 0), nbt.getInt("StartY", 0), nbt.getInt("StartZ", 0));
        } else { startPos = null; }
        width = nbt.getInt("Width", 3);
        height = nbt.getInt("Height", 3);
        oreMiningMode = OreMiningMode.fromOrdinal(nbt.getInt("OreMiningMode", 0));
        sliceProgress = nbt.getInt("SliceProgress", 0);
        currentChestIndex = nbt.getInt("CurrentChestIndex", 0);
        returningToChest = nbt.getBoolean("ReturningToChest", false);
        idleAtStart = nbt.getBoolean("IdleAtStart", false);
        readBaseMiningNbt(nbt);
    }

    // ==================== Polymorphic Dispatch Methods ====================

    @Override
    public int getConfigInt(String key, int defaultValue) {
        return switch (key) {
            case "width" -> width;
            case "height" -> height;
            case "oreMiningMode" -> oreMiningMode.ordinal();
            default -> defaultValue;
        };
    }

    @Override
    public void setConfigInt(String key, int value) {
        switch (key) {
            case "width" -> setWidth(value);
            case "height" -> setHeight(value);
        }
    }

    @Override
    public void writeLegacyNbt(WriteView view) {
        if (chestPos1 != null) {
            view.putInt("TunnelChest1X", chestPos1.getX());
            view.putInt("TunnelChest1Y", chestPos1.getY());
            view.putInt("TunnelChest1Z", chestPos1.getZ());
        }
        if (chestPos2 != null) {
            view.putInt("TunnelChest2X", chestPos2.getX());
            view.putInt("TunnelChest2Y", chestPos2.getY());
            view.putInt("TunnelChest2Z", chestPos2.getZ());
        }
        if (chestPos3 != null) {
            view.putInt("TunnelChest3X", chestPos3.getX());
            view.putInt("TunnelChest3Y", chestPos3.getY());
            view.putInt("TunnelChest3Z", chestPos3.getZ());
        }
        if (tunnelDir != null) view.putString("TunnelDir", tunnelDir.name());
        if (startPos != null) {
            view.putInt("TunnelStartX", startPos.getX());
            view.putInt("TunnelStartY", startPos.getY());
            view.putInt("TunnelStartZ", startPos.getZ());
        }
        view.putInt("TunnelWidth", width);
        view.putInt("TunnelHeight", height);
        view.putInt("TunnelOreMiningMode", oreMiningMode.ordinal());
        view.putInt("TunnelSliceProgress", sliceProgress);
        view.putInt("TunnelCurrentChestIndex", currentChestIndex);
        view.putBoolean("TunnelReturningToChest", returningToChest);
        view.putBoolean("TunnelIdleAtStart", idleAtStart);
        if (buildingBlockType != null) {
            view.putString("TunnelBuildingBlock", buildingBlockType);
        }
    }

    @Override
    public void readLegacyNbt(ReadView view) {
        if (view.contains("TunnelChest1X")) {
            chestPos1 = new BlockPos(view.getInt("TunnelChest1X", 0), view.getInt("TunnelChest1Y", 0), view.getInt("TunnelChest1Z", 0));
        } else { chestPos1 = null; }
        if (view.contains("TunnelChest2X")) {
            chestPos2 = new BlockPos(view.getInt("TunnelChest2X", 0), view.getInt("TunnelChest2Y", 0), view.getInt("TunnelChest2Z", 0));
        } else { chestPos2 = null; }
        if (view.contains("TunnelChest3X")) {
            chestPos3 = new BlockPos(view.getInt("TunnelChest3X", 0), view.getInt("TunnelChest3Y", 0), view.getInt("TunnelChest3Z", 0));
        } else { chestPos3 = null; }
        String dir = view.getString("TunnelDir", null);
        if (dir != null) {
            try { tunnelDir = Direction.valueOf(dir); }
            catch (IllegalArgumentException ignored) { tunnelDir = null; }
        }
        if (view.contains("TunnelStartX")) {
            startPos = new BlockPos(view.getInt("TunnelStartX", 0), view.getInt("TunnelStartY", 0), view.getInt("TunnelStartZ", 0));
        } else { startPos = null; }
        width = view.getInt("TunnelWidth", 3);
        height = view.getInt("TunnelHeight", 3);
        oreMiningMode = OreMiningMode.fromOrdinal(view.getInt("TunnelOreMiningMode", 0));
        sliceProgress = view.getInt("TunnelSliceProgress", 0);
        currentChestIndex = view.getInt("TunnelCurrentChestIndex", 0);
        returningToChest = view.getBoolean("TunnelReturningToChest", false);
        idleAtStart = view.getBoolean("TunnelIdleAtStart", false);
        String block = view.getString("TunnelBuildingBlock", null);
        buildingBlockType = (block != null && !block.isEmpty()) ? block : null;
    }

    @Override
    public boolean canStartFromIdle() {
        return isIdleAtStart();
    }

    @Override
    public FeedResult handleFeedInteraction(PlayerEntity player) {
        if (isWaitingForResources()) {
            setWaitingForResources(false);
            return FeedResult.RESUMED;
        }
        if (isIdleAtStart()) {
            startFromIdle();
            return FeedResult.STARTED;
        }
        restartTunnel();
        return FeedResult.STARTED;
    }

    @Override
    public void handleOwnerDamage() {
        resetToIdle();
    }

    // ==================== Tunnel Logic ====================

    private void tickTunnelMode() {
        if (chestPos1 == null || chestPos2 == null || chestPos3 == null || startPos == null || tunnelDir == null) {
            entity.setBuildingPaths(false);
            return;
        }

        // State 1: Idle at start
        if (idleAtStart) {
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
            tickTunnelReturn();
            return;
        }

        // State 3: Check if inventory is full
        if (isInventoryFull()) {
            returningToChest = true;
            leftTarget = null;
            rightTarget = null;
            leftBreakProgress = 0;
            rightBreakProgress = 0;
            return;
        }

        // State 4: Active tunneling
        tickTunnelActive();
    }

    private void tickTunnelReturn() {
        BlockPos targetChest = getChestByIndex(currentChestIndex);
        double dx = entity.getX() - (targetChest.getX() + 0.5);
        double dz = entity.getZ() - (targetChest.getZ() + 0.5);
        double distSq = dx * dx + dz * dz;

        if (distSq > 4.0) {
            entity.getNavigation().startMovingTo(targetChest.getX() + 0.5,
                targetChest.getY(), targetChest.getZ() + 0.5, 1.1);

            double movedX = entity.getX() - lastX;
            double movedZ = entity.getZ() - lastZ;
            double movedDistSq = movedX * movedX + movedZ * movedZ;

            if (movedDistSq < MOVEMENT_THRESHOLD * MOVEMENT_THRESHOLD) {
                noMovementTicks++;
                if (noMovementTicks >= STUCK_TICKS_BEFORE_TELEPORT && distSq > 9.0) {
                    entity.teleportWithParticles(targetChest);
                    noMovementTicks = 0;
                }
            } else {
                noMovementTicks = 0;
            }

            lastX = entity.getX();
            lastZ = entity.getZ();
        } else {
            entity.getNavigation().stop();
            depositInventoryToChest(targetChest);
            // Cycle to next chest
            currentChestIndex = (currentChestIndex + 1) % 3;
            returningToChest = false;
            noMovementTicks = 0;
            if (isInventoryFull()) {
                // Still full after depositing -> try next chest or go idle
                if (isInventoryFullAfterDeposit()) {
                    idleAtStart = true;
                    entity.setBuildingPaths(false);
                } else {
                    returningToChest = true;
                }
            }
        }
    }

    private boolean isInventoryFullAfterDeposit() {
        Inventory inventory = entity.getInventory();
        int emptySlots = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 2;
    }

    private void tickTunnelActive() {
        // Reactive floor building
        if (needsFloorSupport()) {
            placeFloorBlocks();
        }

        // Torch placement
        tryPlaceTorchInDarkArea();

        // Get blocks for current slice
        List<BlockPos> sliceBlocks = getMinableBlocksForSlice(sliceProgress);

        // Check if slice is done
        if (sliceBlocks.isEmpty() && leftTarget == null && rightTarget == null) {
            sliceProgress++;

            // Return to deposit if inventory has items
            if (!isInventoryEmpty()) {
                returningToChest = true;
                leftTarget = null;
                rightTarget = null;
                leftBreakProgress = 0;
                rightBreakProgress = 0;
                return;
            }
            return;
        }

        // Assign targets to each hand
        if (leftTarget == null || entity.getEntityWorld().getBlockState(leftTarget).isAir()) {
            leftTarget = getNextBlock(sliceBlocks, null);
            leftBreakProgress = 0;
            leftSwingTick = 0;
            leftTool = ItemStack.EMPTY;
        }
        if (rightTarget == null || entity.getEntityWorld().getBlockState(rightTarget).isAir()) {
            rightTarget = getNextBlock(sliceBlocks, leftTarget);
            rightBreakProgress = 0;
            rightSwingTick = 0;
            rightTool = ItemStack.EMPTY;
        }

        // Navigate toward targets
        BlockPos navTarget = leftTarget != null ? leftTarget : rightTarget;
        if (navTarget == null) return;

        if (leftTarget != null && rightTarget != null) {
            double midX = (leftTarget.getX() + rightTarget.getX()) / 2.0 + 0.5;
            double midY = Math.min(leftTarget.getY(), rightTarget.getY());
            double midZ = (leftTarget.getZ() + rightTarget.getZ()) / 2.0 + 0.5;
            entity.getNavigation().startMovingTo(midX, midY, midZ, 1.1);
        } else {
            entity.getNavigation().startMovingTo(navTarget.getX() + 0.5, navTarget.getY(), navTarget.getZ() + 0.5, 1.1);
        }

        // Mine with left hand
        if (leftTarget != null) {
            double ldx = entity.getX() - (leftTarget.getX() + 0.5);
            double ldy = entity.getY() - leftTarget.getY();
            double ldz = entity.getZ() - (leftTarget.getZ() + 0.5);
            if (ldx * ldx + ldy * ldy + ldz * ldz <= 25.0) {
                mineBlockWithHand(leftTarget, true);
            }
        }

        // Mine with right hand
        if (rightTarget != null) {
            double rdx = entity.getX() - (rightTarget.getX() + 0.5);
            double rdy = entity.getY() - rightTarget.getY();
            double rdz = entity.getZ() - (rightTarget.getZ() + 0.5);
            if (rdx * rdx + rdy * rdy + rdz * rdz <= 25.0) {
                mineBlockWithHand(rightTarget, false);
            }
        }
    }

    private BlockPos getNextBlock(List<BlockPos> blocks, BlockPos exclude) {
        for (BlockPos pos : blocks) {
            if (pos.equals(exclude)) continue;
            BlockState state = entity.getEntityWorld().getBlockState(pos);
            if (!state.isAir() && shouldMineBlock(pos)) return pos;
        }
        return null;
    }

    /**
     * Get all blocks for a given slice (cross-section perpendicular to tunnel direction).
     * The cross-section is centered on the tunnel axis (width) and starts at floor level (height).
     */
    private List<BlockPos> getBlocksForSlice(int slice) {
        List<BlockPos> blocks = new ArrayList<>();
        if (tunnelDir == null || startPos == null) return blocks;

        // The perpendicular direction for width spread
        Direction perpDir = getPerpendicularDirection(tunnelDir);

        // Center offset for width (e.g., width=3 -> offsets -1,0,1; width=1 -> offset 0)
        int halfWidth = (width - 1) / 2;

        BlockPos sliceCenter = startPos.offset(tunnelDir, slice);

        for (int w = -halfWidth; w <= halfWidth; w++) {
            BlockPos columnBase = sliceCenter.offset(perpDir, w);
            for (int dy = 0; dy < height; dy++) {
                blocks.add(columnBase.up(dy));
            }
        }
        return blocks;
    }

    private List<BlockPos> getMinableBlocksForSlice(int slice) {
        List<BlockPos> all = getBlocksForSlice(slice);
        List<BlockPos> minable = new ArrayList<>();
        for (BlockPos pos : all) {
            if (shouldMineBlock(pos)) minable.add(pos);
        }
        return minable;
    }

    /**
     * Get a horizontal direction perpendicular to the given direction.
     */
    private Direction getPerpendicularDirection(Direction dir) {
        return switch (dir) {
            case NORTH, SOUTH -> Direction.EAST;
            case EAST, WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private BlockPos getChestByIndex(int index) {
        return switch (index) {
            case 1 -> chestPos2;
            case 2 -> chestPos3;
            default -> chestPos1;
        };
    }

    private boolean needsFloorSupport() {
        if (entity.isOnGround()) {
            ticksInAir = 0;
            return false;
        }
        ticksInAir++;
        if (ticksInAir < TICKS_IN_AIR_THRESHOLD) return false;
        BlockPos below = entity.getBlockPos().down();
        BlockPos twoBelow = below.down();
        return entity.getEntityWorld().getBlockState(below).isAir()
            && entity.getEntityWorld().getBlockState(twoBelow).isAir();
    }

    private boolean isInventoryFull() {
        Inventory inventory = entity.getInventory();
        int emptySlots = 0;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) emptySlots++;
        }
        return emptySlots < 2;
    }

    private boolean isInventoryEmpty() {
        Inventory inventory = entity.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && !isToolOrTorch(stack)) return false;
        }
        return true;
    }

    private void placeFloorBlocks() {
        if (entity.getEntityWorld().isClient()) return;
        BlockPos below = entity.getBlockPos().down();
        if (!entity.getEntityWorld().getBlockState(below).isAir()) return;

        Inventory inventory = entity.getInventory();
        if (buildingBlockType == null) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
                if (!isGravityBlock(blockItem.getBlock())) {
                    buildingBlockType = Registries.BLOCK.getId(blockItem.getBlock()).toString();
                    break;
                }
            }
            if (buildingBlockType == null) {
                entity.handleMissingBuildingBlock();
                return;
            }
        }

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;
            String blockId = Registries.BLOCK.getId(blockItem.getBlock()).toString();
            if (blockId.equals(buildingBlockType)) {
                BlockState state = blockItem.getBlock().getDefaultState();
                entity.getEntityWorld().setBlockState(below, state);
                entity.beginHandAnimation(isLeftHandActive(), below, null);
                alternateHand();
                stack.decrement(1);
                return;
            }
        }
        entity.handleMissingBuildingBlock();
    }

    private boolean shouldMineBlock(BlockPos pos) {
        BlockState state = entity.getEntityWorld().getBlockState(pos);
        if (state.isAir() || state.getHardness(entity.getEntityWorld(), pos) < 0) return false;

        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        if (isChestBlock(blockId)) return false;
        if (isTorchBlock(blockId)) return false;

        // Don't mine non-solid blocks on the floor
        if (pos.getY() == startPos.getY() && state.getCollisionShape(entity.getEntityWorld(), pos).isEmpty()) {
            return false;
        }

        // Check ore mining mode
        if (isOreBlock(blockId)) {
            switch (oreMiningMode) {
                case NEVER: return false;
                case SILK_TOUCH_FORTUNE:
                    if (!hasValidEnchantedTool()) return false;
                    break;
                case ALWAYS: default: break;
            }
        }

        return true;
    }

    private boolean hasValidEnchantedTool() {
        Inventory inventory = entity.getInventory();
        var world = entity.getEntityWorld();
        if (world == null) return false;

        var registryManager = world.getRegistryManager();
        var enchantmentRegistry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);
        var silkTouchEntry = enchantmentRegistry.getEntry(Enchantments.SILK_TOUCH.getValue());
        var fortuneEntry = enchantmentRegistry.getEntry(Enchantments.FORTUNE.getValue());

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            if (silkTouchEntry.isPresent() && EnchantmentHelper.getLevel(silkTouchEntry.get(), stack) > 0) return true;
            if (fortuneEntry.isPresent() && EnchantmentHelper.getLevel(fortuneEntry.get(), stack) >= 3) return true;
        }
        return false;
    }
}
