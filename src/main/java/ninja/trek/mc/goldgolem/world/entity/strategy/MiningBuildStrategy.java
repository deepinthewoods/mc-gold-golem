package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
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

import java.util.HashSet;
import java.util.Set;

/**
 * Strategy for Mining mode.
 * Digs tunnels in a branch mining pattern and deposits ore in a chest.
 */
public class MiningBuildStrategy extends AbstractBuildStrategy {

    // Mining configuration
    private BlockPos chestPos = null;
    private Direction direction = null;
    private BlockPos startPos = null;
    private int branchDepth = 16;
    private int branchSpacing = 3;
    private int tunnelHeight = 2;
    private OreMiningMode oreMiningMode = OreMiningMode.ALWAYS;

    // Mining progress state
    private int primaryProgress = 0;
    private int currentBranch = -1;
    private boolean branchLeft = true;
    private int branchProgress = 0;
    private boolean returningToChest = false;
    private boolean idleAtChest = false;

    // Mining runtime state (not persisted)
    private final Set<BlockPos> pendingOres = new HashSet<>();
    private String buildingBlockType = null;
    private int breakProgress = 0;
    private BlockPos currentTarget = null;

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
        currentTarget = null;
        breakProgress = 0;
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
        currentTarget = null;
        breakProgress = 0;
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
        currentTarget = null;
        breakProgress = 0;
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
        if (buildingBlockType != null) {
            nbt.putString("BuildingBlock", buildingBlockType);
        }
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
        buildingBlockType = nbt.contains("BuildingBlock") ? nbt.getString("BuildingBlock", null) : null;
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
            currentTarget = null;
            breakProgress = 0;
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
            depositInventoryToChest();
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

        BlockPos targetPos = getNextMiningTarget();
        if (targetPos == null) {
            entity.setBuildingPaths(false);
            return;
        }

        double ty = targetPos.getY();
        entity.getNavigation().startMovingTo(targetPos.getX() + 0.5, ty, targetPos.getZ() + 0.5, 1.1);

        double dx = entity.getX() - (targetPos.getX() + 0.5);
        double dy = entity.getY() - targetPos.getY();
        double dz = entity.getZ() - (targetPos.getZ() + 0.5);
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= 25.0) {
            mineBlock(targetPos);
        } else {
            if (currentTarget != null && !currentTarget.equals(targetPos)) {
                currentTarget = null;
                breakProgress = 0;
            }
        }

        if (entity.getNavigation().isIdle() && distSq > 16.0) {
            stuckTicks++;
            if (stuckTicks >= 60) {
                teleportToStart();
                stuckTicks = 0;
                currentTarget = null;
                breakProgress = 0;
                primaryProgress = 0;
                currentBranch = -1;
                branchProgress = 0;
                pendingOres.clear();
            }
        } else {
            stuckTicks = 0;
        }
    }

    private void teleportToStart() {
        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 0.5, entity.getZ(),
                40, 0.5, 0.5, 0.5, 0.2);
            sw.spawnParticles(ParticleTypes.PORTAL, startPos.getX() + 0.5,
                startPos.getY() + 0.5, startPos.getZ() + 0.5, 40, 0.5, 0.5, 0.5, 0.2);
        }
        entity.refreshPositionAndAngles(startPos.getX() + 0.5, startPos.getY(),
            startPos.getZ() + 0.5, entity.getYaw(), entity.getPitch());
        entity.getNavigation().stop();
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

    private void depositInventoryToChest() {
        if (chestPos == null || entity.getEntityWorld().isClient()) return;

        var chestEntity = entity.getEntityWorld().getBlockEntity(chestPos);
        if (!(chestEntity instanceof Inventory chestInv)) return;

        Inventory inventory = entity.getInventory();
        int buildingBlocksKept = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            String blockId = getBlockIdFromStack(stack);
            boolean isBuildingBlock = buildingBlockType != null && blockId != null &&
                blockId.equals(buildingBlockType);

            if (isBuildingBlock && buildingBlocksKept < 64) {
                int toKeep = Math.min(64 - buildingBlocksKept, stack.getCount());
                buildingBlocksKept += toKeep;
                if (stack.getCount() > toKeep) {
                    ItemStack toDeposit = stack.copy();
                    toDeposit.setCount(stack.getCount() - toKeep);
                    ItemStack remainder = transferToInventory(toDeposit, chestInv);
                    stack.setCount(toKeep + (remainder.isEmpty() ? 0 : remainder.getCount()));
                    inventory.setStack(i, stack);
                }
            } else {
                ItemStack remainder = transferToInventory(stack, chestInv);
                inventory.setStack(i, remainder);
            }
        }
    }

    private ItemStack transferToInventory(ItemStack stack, Inventory targetInv) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        for (int i = 0; i < targetInv.size(); i++) {
            ItemStack targetStack = targetInv.getStack(i);
            if (targetStack.isEmpty()) continue;
            if (ItemStack.areItemsAndComponentsEqual(stack, targetStack)) {
                int space = targetStack.getMaxCount() - targetStack.getCount();
                if (space > 0) {
                    int toTransfer = Math.min(space, stack.getCount());
                    targetStack.setCount(targetStack.getCount() + toTransfer);
                    targetInv.setStack(i, targetStack);
                    stack.decrement(toTransfer);
                    if (stack.isEmpty()) return ItemStack.EMPTY;
                }
            }
        }

        for (int i = 0; i < targetInv.size(); i++) {
            if (targetInv.getStack(i).isEmpty()) {
                targetInv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    private void placeBlocksUnderFeet() {
        if (entity.getEntityWorld().isClient()) return;

        BlockPos below = entity.getBlockPos().down();
        if (!entity.getEntityWorld().getBlockState(below).isAir()) return;

        Inventory inventory = entity.getInventory();
        if (buildingBlockType == null) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) continue;

                var block = blockItem.getBlock();
                String blockId = Registries.BLOCK.getId(block).toString();

                if (!isOreBlock(blockId) && !isGravityBlock(block)) {
                    buildingBlockType = blockId;
                    break;
                }
            }
            if (buildingBlockType == null) {
                entity.handleMissingBuildingBlock();
                return;
            }
        }

        if (buildingBlockType != null) {
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
                    inventory.setStack(i, stack);
                    return;
                }
            }
            entity.handleMissingBuildingBlock();
        }
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

    /**
     * Find the best enchanted tool for mining ores.
     * Prefers Silk Touch over Fortune 3+.
     */
    private ItemStack findBestEnchantedTool(BlockState state) {
        Inventory inventory = entity.getInventory();
        var world = entity.getEntityWorld();
        if (world == null) return ItemStack.EMPTY;

        var registryManager = world.getRegistryManager();
        var enchantmentRegistry = registryManager.getOrThrow(RegistryKeys.ENCHANTMENT);

        // Get entries using identifier from registry key
        var silkTouchEntry = enchantmentRegistry.getEntry(Enchantments.SILK_TOUCH.getValue());
        var fortuneEntry = enchantmentRegistry.getEntry(Enchantments.FORTUNE.getValue());

        ItemStack silkTouchTool = ItemStack.EMPTY;
        ItemStack fortuneTool = ItemStack.EMPTY;
        int bestFortuneLevel = 0;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;

            // Check for Silk Touch (preferred)
            if (silkTouchEntry.isPresent() && EnchantmentHelper.getLevel(silkTouchEntry.get(), stack) > 0) {
                silkTouchTool = stack;
            }

            // Check for Fortune
            if (fortuneEntry.isPresent()) {
                int fortuneLevel = EnchantmentHelper.getLevel(fortuneEntry.get(), stack);
                if (fortuneLevel >= 3 && fortuneLevel > bestFortuneLevel) {
                    bestFortuneLevel = fortuneLevel;
                    fortuneTool = stack;
                }
            }
        }

        // Prefer Silk Touch over Fortune
        if (!silkTouchTool.isEmpty()) return silkTouchTool;
        if (!fortuneTool.isEmpty()) return fortuneTool;
        return ItemStack.EMPTY;
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
        return !state.isAir() && state.getHardness(entity.getEntityWorld(), pos) >= 0;
    }

    private boolean isOreBlock(String blockId) {
        return blockId.contains("_ore") || blockId.contains("ancient_debris") ||
               blockId.equals("minecraft:gilded_blackstone");
    }

    private boolean isGravityBlock(net.minecraft.block.Block block) {
        return block instanceof FallingBlock;
    }

    private String getBlockIdFromStack(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        return Registries.BLOCK.getId(blockItem.getBlock()).toString();
    }

    private void mineBlock(BlockPos pos) {
        if (entity.getEntityWorld().isClient()) return;

        BlockState state = entity.getEntityWorld().getBlockState(pos);
        if (state.isAir()) {
            pendingOres.remove(pos);
            currentTarget = null;
            breakProgress = 0;
            return;
        }

        if (currentTarget == null || !currentTarget.equals(pos)) {
            currentTarget = pos;
            breakProgress = 0;
        }

        ItemStack bestTool = findBestTool(state);
        float breakSpeed = bestTool.isEmpty() ? 1.0f : bestTool.getMiningSpeedMultiplier(state);
        breakSpeed *= 0.5f;

        float hardness = state.getHardness(entity.getEntityWorld(), pos);
        if (hardness < 0) {
            currentTarget = null;
            breakProgress = 0;
            return;
        }

        int requiredTicks = (int) Math.ceil((hardness * 30.0f) / breakSpeed);
        requiredTicks = Math.max(1, requiredTicks);

        breakProgress++;

        if (breakProgress >= requiredTicks) {
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            boolean isOre = isOreBlock(blockId);

            if (entity.getEntityWorld() instanceof ServerWorld sw) {
                var drops = net.minecraft.block.Block.getDroppedStacks(state, sw, pos,
                    entity.getEntityWorld().getBlockEntity(pos), entity, bestTool);

                for (ItemStack drop : drops) {
                    addToInventory(drop);
                }
            }

            entity.getEntityWorld().breakBlock(pos, false);

            if (!bestTool.isEmpty() && bestTool.isDamageable()) {
                bestTool.damage(1, entity, EquipmentSlot.MAINHAND);
                Inventory inventory = entity.getInventory();
                for (int i = 0; i < inventory.size(); i++) {
                    if (inventory.getStack(i) == bestTool) {
                        inventory.setStack(i, bestTool);
                        break;
                    }
                }
            }

            pendingOres.remove(pos);
            currentTarget = null;
            breakProgress = 0;

            if (isOre) {
                mineOreSurroundings(pos);
            }
        }
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

    private ItemStack findBestTool(BlockState state) {
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        Inventory inventory = entity.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(state)) continue;

            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestTool = stack;
            }
        }

        return bestTool;
    }

    private void addToInventory(ItemStack stack) {
        if (stack.isEmpty()) return;

        Inventory inventory = entity.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty()) continue;

            if (ItemStack.areItemsAndComponentsEqual(stack, slot)) {
                int space = slot.getMaxCount() - slot.getCount();
                if (space > 0) {
                    int toAdd = Math.min(space, stack.getCount());
                    slot.setCount(slot.getCount() + toAdd);
                    inventory.setStack(i, slot);
                    stack.decrement(toAdd);
                    if (stack.isEmpty()) return;
                }
            }
        }

        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.getStack(i).isEmpty()) {
                inventory.setStack(i, stack.copy());
                return;
            }
        }

        if (entity.getEntityWorld() instanceof ServerWorld sw) {
            entity.dropStack(sw, stack);
        }
    }
}
