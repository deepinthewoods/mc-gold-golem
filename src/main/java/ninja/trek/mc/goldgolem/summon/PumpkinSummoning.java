package ninja.trek.mc.goldgolem.summon;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public class PumpkinSummoning {
    public static void register() {
        UseBlockCallback.EVENT.register(PumpkinSummoning::onUseBlock);
    }

    private static InteractionResult onUseBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem bi)) return InteractionResult.PASS;
        if (!(bi.getBlock() instanceof CarvedPumpkinBlock)) return InteractionResult.PASS;

        BlockPos placePos = hit.getBlockPos().relative(hit.getDirection());
        BlockPos below = placePos.below();
        if (!world.getBlockState(below).is(Blocks.GOLD_BLOCK)) return InteractionResult.PASS;

        if (world.isClientSide()) return InteractionResult.SUCCESS;

        String desiredName = null;
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            desiredName = customName.getString();
        }
        if (desiredName != null && !desiredName.isBlank()) {
            java.nio.file.Path snapshotPath = GoldGolemEntity.findSnapshotPath(desiredName);
            if (snapshotPath != null) {
                GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
                golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
                golem.setOwner(player);
                boolean applied = golem.applySnapshotFromPath((ServerLevel) world, snapshotPath, below, player, desiredName);
                if (applied) {
                    world.destroyBlock(below, false, player);
                    ((ServerLevel) world).addFreshEntity(golem);
                    if (!player.isCreative()) stack.shrink(1);
                    return InteractionResult.SUCCESS;
                }
            }
        }

        // Check for Mining/Excavation/Tunnel Mode: check chest placement
        net.minecraft.core.Direction chestDirection1 = null;
        net.minecraft.core.Direction chestDirection2 = null;
        net.minecraft.core.Direction chestDirection3 = null;
        int chestCount = 0;
        for (var dir : new net.minecraft.core.Direction[]{
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.EAST,
                net.minecraft.core.Direction.WEST
        }) {
            var np = below.relative(dir);
            var st = world.getBlockState(np);
            // Check for chest, trapped chest, or barrel
            boolean isStorageBlock = st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST) || st.is(Blocks.BARREL);
            if (isStorageBlock) {
                if (chestCount == 0) chestDirection1 = dir;
                else if (chestCount == 1) chestDirection2 = dir;
                else if (chestCount == 2) chestDirection3 = dir;
                chestCount++;
            }
        }

        // Debug: Log chest count and detected blocks
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (chestCount > 0) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Debug] Found " + chestCount + " storage block(s) at: " +
                    (chestDirection1 != null ? chestDirection1.getSerializedName() : "none") +
                    (chestDirection2 != null ? ", " + chestDirection2.getSerializedName() : "") +
                    (chestDirection3 != null ? ", " + chestDirection3.getSerializedName() : "")), false);
            } else {
                // Show what blocks are around the gold block
                StringBuilder blockInfo = new StringBuilder("[Debug] No chests detected. Adjacent blocks: ");
                for (var dir : new net.minecraft.core.Direction[]{
                        net.minecraft.core.Direction.NORTH,
                        net.minecraft.core.Direction.SOUTH,
                        net.minecraft.core.Direction.EAST,
                        net.minecraft.core.Direction.WEST
                }) {
                    var np = below.relative(dir);
                    var st = world.getBlockState(np);
                    blockInfo.append(dir.getSerializedName()).append("=").append(st.getBlock().getName().getString()).append(" ");
                }
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal(blockInfo.toString()), false);
            }
        }

        // Check if tunnel mode (3 chests on 3 sides, empty 4th side is tunnel direction)
        boolean tunnelMode = (chestCount == 3);

        // Check if excavation mode (2 chests on adjacent/non-opposite sides)
        boolean excavationMode = false;
        if (!tunnelMode && chestCount == 2) {
            // Check if directions are not opposite
            excavationMode = (chestDirection1 != chestDirection2.getOpposite());
        }

        boolean miningMode = (!tunnelMode && chestCount == 1);

        // Check for Tower Mode: gold block below the pumpkin's gold block
        BlockPos belowBelow = below.below();
        boolean towerMode = !tunnelMode && !miningMode && !excavationMode && world.getBlockState(belowBelow).is(Blocks.GOLD_BLOCK);

        // Check for Terraforming Mode: 3x3 layer of gold blocks
        boolean terraformingMode = false;
        if (!tunnelMode && !towerMode && !miningMode && !excavationMode) {
            // Check if this gold block is the center of a 3x3 horizontal gold platform
            boolean is3x3Gold = true;
            for (int dx = -1; dx <= 1 && is3x3Gold; dx++) {
                for (int dz = -1; dz <= 1 && is3x3Gold; dz++) {
                    BlockPos checkPos = below.offset(dx, 0, dz);
                    if (!world.getBlockState(checkPos).is(Blocks.GOLD_BLOCK)) {
                        is3x3Gold = false;
                    }
                }
            }
            terraformingMode = is3x3Gold;
        }

        // Check for Tree Mode: second gold block touching pumpkin's gold block
        boolean treeMode = false;
        BlockPos secondGoldPos = null;
        if (!tunnelMode && !towerMode && !miningMode && !excavationMode && !terraformingMode) {
            for (var dir : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH,
                    net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.EAST,
                    net.minecraft.core.Direction.WEST,
                    net.minecraft.core.Direction.UP,
                    net.minecraft.core.Direction.DOWN
            }) {
                var np = below.relative(dir);
                if (world.getBlockState(np).is(Blocks.GOLD_BLOCK)) {
                    treeMode = true;
                    secondGoldPos = np;
                    break;
                }
            }
        }

        // Decide mode: Wall Mode if gold block is touching any non-air, non-snow layer block on sides (exclude below)
        // Tower, mining, excavation, terraforming, and tree modes take precedence over wall mode
        boolean wallMode = false;
        if (!tunnelMode && !towerMode && !miningMode && !excavationMode && !terraformingMode && !treeMode) {
            for (var dir : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH,
                    net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.EAST,
                    net.minecraft.core.Direction.WEST,
                    net.minecraft.core.Direction.UP
            }) {
                var np = below.relative(dir);
                var st = world.getBlockState(np);
                if (!st.isAir() && !st.is(Blocks.SNOW)) { wallMode = true; break; }
            }
        }

        if (tunnelMode) {
            // Tunnel Mode: 3 chests on 3 sides, dig in the direction of the empty side
            // Find the empty direction (the one without a chest)
            net.minecraft.core.Direction emptyDir = null;
            for (var dir : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH,
                    net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.EAST,
                    net.minecraft.core.Direction.WEST
            }) {
                if (dir != chestDirection1 && dir != chestDirection2 && dir != chestDirection3) {
                    emptyDir = dir;
                    break;
                }
            }
            if (emptyDir == null) emptyDir = net.minecraft.core.Direction.NORTH; // fallback

            BlockPos chest1 = below.relative(chestDirection1);
            BlockPos chest2 = below.relative(chestDirection2);
            BlockPos chest3 = below.relative(chestDirection3);

            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TUNNEL);
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.TUNNEL)));
            golem.setTunnelConfig(chest1, chest2, chest3, emptyDir, below);

            world.destroyBlock(below, false, player);
            ((ServerLevel) world).addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (excavationMode) {
            // Excavation Mode: 2 chests on adjacent sides, excavate in opposite diagonal
            BlockPos chest1 = below.relative(chestDirection1);
            BlockPos chest2 = below.relative(chestDirection2);

            // Spawn golem with excavation mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.EXCAVATION);
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.EXCAVATION)));
            golem.setExcavationConfig(chest1, chest2, chestDirection1, chestDirection2, below);

            world.destroyBlock(below, false, player);
            ((ServerLevel) world).addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (miningMode) {
            // Mining Mode: chest on one side, mine in opposite direction
            BlockPos chestPos = below.relative(chestDirection1);
            net.minecraft.core.Direction miningDir = chestDirection1.getOpposite();

            // Spawn golem with mining mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.MINING);
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.MINING)));
            golem.setMiningConfig(chestPos, miningDir, below);

            world.destroyBlock(below, false, player);
            ((ServerLevel) world).addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (terraformingMode) {
            // Terraforming Mode: scan skeleton structure touching 3x3 gold platform
            var res = ninja.trek.mc.goldgolem.terraforming.TerraformingScanner.scan(world, below, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Terraforming mode summon failed: " + res.error()), true);
                }
                return InteractionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with terraforming mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TERRAFORMING);
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.TERRAFORMING)));
            golem.setTerraformingConfig(def, below);

            // Remove the 3x3 gold platform
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos removePos = below.offset(dx, 0, dz);
                    world.destroyBlock(removePos, false, player);
                }
            }

            ServerLevel sw = (ServerLevel) world;
            sw.addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (treeMode) {
            // Tree Mode: scan for input modules separated by gold blocks
            var res = ninja.trek.mc.goldgolem.tree.TreeScanner.scan(world, secondGoldPos, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Tree mode summon failed: " + res.error()), true);
                }
                return InteractionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with tree mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(secondGoldPos.getX() + 0.5, secondGoldPos.getY(), secondGoldPos.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TREE);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.tree.TreeScanner.writeJson(gameDir, golem.getUUID(), def);
                jsonRel = gameDir.relativize(out).toString();
            } catch (Exception ioe) {
                // Non-fatal; continue without external snapshot
                jsonRel = null;
            }

            // Capture module block states for resurrection snapshots
            java.util.List<java.util.Map<BlockPos, BlockState>> moduleStates = new java.util.ArrayList<>();
            for (var module : def.modules) {
                java.util.Map<BlockPos, BlockState> blocks = new java.util.HashMap<>();
                for (BlockPos rel : module.voxels) {
                    BlockPos abs = secondGoldPos.offset(rel);
                    blocks.put(rel, world.getBlockState(abs));
                }
                moduleStates.add(blocks);
            }
            golem.setTreeModuleBlockStates(moduleStates);

            // Set tree capture data on golem
            golem.setTreeCapture(def.modules, def.uniqueBlockIds, secondGoldPos, jsonRel);
            // Set name AFTER all data is set so the snapshot written by setCustomName is complete
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.TREE)));

            // Remove both gold blocks (pumpkin gold and second gold)
            world.destroyBlock(below, false, player);
            world.destroyBlock(secondGoldPos, false, player);

            ServerLevel sw = (ServerLevel) world;
            sw.addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (towerMode) {
            // Tower Mode: Find bottom gold block and count total height
            BlockPos bottomGold = belowBelow;
            // Find the actual bottom gold block by going down until we hit a non-gold block
            while (world.getBlockState(bottomGold.below()).is(Blocks.GOLD_BLOCK)) {
                bottomGold = bottomGold.below();
            }

            // Count gold blocks upward from below (pumpkin's gold block) to determine tower height
            int towerHeight = 0;
            BlockPos checkPos = below; // Start from the pumpkin's gold block
            while (world.getBlockState(checkPos).is(Blocks.GOLD_BLOCK)) {
                towerHeight++;
                checkPos = checkPos.above();
            }

            if (towerHeight == 0) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Tower mode: No gold blocks found for height"), true);
                }
                return InteractionResult.FAIL;
            }

            // Collect all gold block positions for flood fill starting points
            java.util.List<BlockPos> goldBlockPositions = new java.util.ArrayList<>();
            BlockPos collectPos = bottomGold;
            while (world.getBlockState(collectPos).is(Blocks.GOLD_BLOCK)) {
                goldBlockPositions.add(collectPos);
                collectPos = collectPos.above();
            }

            // Scan the module structure from all gold block positions
            var res = ninja.trek.mc.goldgolem.tower.TowerScanner.scan(world, goldBlockPositions, bottomGold, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Tower mode summon failed: " + res.error()), true);
                }
                return InteractionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with tower mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(bottomGold.getX() + 0.5, bottomGold.getY(), bottomGold.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TOWER);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.tower.TowerScanner.writeJson(gameDir, golem.getUUID(), def);
                jsonRel = gameDir.relativize(out).toString();
            } catch (Exception ioe) {
                // Non-fatal; continue without external snapshot
                jsonRel = null;
            }

            // Build module template with voxels relative to origin
            java.util.List<ninja.trek.mc.goldgolem.tower.TowerModuleTemplate.Voxel> vox = new java.util.ArrayList<>();
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (var r : def.voxels) {
                var abs = def.origin.offset(r);
                var st = world.getBlockState(abs);
                vox.add(new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate.Voxel(r, st));
                minY = Math.min(minY, r.getY());
                maxY = Math.max(maxY, r.getY());
            }
            ninja.trek.mc.goldgolem.tower.TowerModuleTemplate template =
                new ninja.trek.mc.goldgolem.tower.TowerModuleTemplate(vox,
                    minY == Integer.MAX_VALUE ? 0 : minY,
                    maxY == Integer.MIN_VALUE ? 0 : maxY);

            // Set tower data on golem
            golem.setTowerCapture(def.uniqueBlockIds, def.blockCounts, bottomGold, jsonRel, towerHeight, template);
            // Set name AFTER all data is set so the snapshot written by setCustomName is complete
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.TOWER)));

            // Remove all gold blocks in the column
            BlockPos removePos = bottomGold;
            while (world.getBlockState(removePos).is(Blocks.GOLD_BLOCK)) {
                world.destroyBlock(removePos, false, player);
                removePos = removePos.above();
            }

            ServerLevel sw = (ServerLevel) world;
            sw.addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        } else if (wallMode) {
            // Scan combined module per spec
            if (player instanceof net.minecraft.server.level.ServerPlayer sp0) {
                sp0.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall mode detected, scanning..."), false);
            }
            var res = ninja.trek.mc.goldgolem.wall.WallScanner.scan(world, below, player);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp0) {
                if (res.ok()) {
                    sp0.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Scan OK: " + res.def().voxels.size() + " voxels, " + res.def().goldMarkers.size() + " gold markers"), false);
                }
            }
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall mode summon failed: " + res.error()), false);
                }
                return InteractionResult.FAIL;
            }
            var def = res.def();
            // Debug output removed
            // Validate join slices across all gold markers per spec
            var validation = ninja.trek.mc.goldgolem.wall.WallModuleValidator.validate(world, def.origin, def.voxels, def.goldMarkers, below);
            if (!validation.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall validation failed: " + validation.error()), false);
                }
                return InteractionResult.FAIL;
            }
            // Validation summary logging removed

            // Spawn golem with wall mode set
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.WALL);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.wall.WallScanner.writeJson(gameDir, golem.getUUID(), def);
                // store path relative to game dir for portability
                jsonRel = gameDir.relativize(out).toString();
            } catch (Exception ioe) {
                // Non-fatal; continue without external snapshot
                jsonRel = null;
            }
            // Extract modules and enforce uniqueness + size/count limits
            var extraction = ninja.trek.mc.goldgolem.wall.WallModuleExtractor.extract(world, def.origin, def.voxels, def.goldMarkers, below);
            if (!extraction.ok()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall module extraction failed: " + extraction.error()), false);
                }
                return InteractionResult.FAIL;
            }

            try {
            // Build module templates with per-voxel block ids relative to each module's A marker
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Extraction OK: " + extraction.modules().size() + " modules"), false);
            }
            // Find the correct block state for the pumpkin position from a non-summon gold marker
            BlockPos pumpkinAbs = below.above();
            BlockPos pumpkinRel = pumpkinAbs.subtract(def.origin);
            net.minecraft.world.level.block.state.BlockState correctPumpkinState = null;
            for (BlockPos gRel : def.goldMarkers) {
                BlockPos gAbs = def.origin.offset(gRel);
                if (gAbs.equals(below)) continue;
                correctPumpkinState = world.getBlockState(gAbs.above());
                break;
            }

            java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> templates = new java.util.ArrayList<>();
            for (var mod : extraction.modules()) {
                java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel> vox = new java.util.ArrayList<>();
                int minY = Integer.MAX_VALUE;
                for (var r : mod.voxels()) {
                    var abs = def.origin.offset(r);
                    var st = world.getBlockState(abs);
                    // store position relative to module a-marker
                    var relToA = new net.minecraft.core.BlockPos(r.getX() - mod.aMarker().getX(), r.getY() - mod.aMarker().getY(), r.getZ() - mod.aMarker().getZ());
                    vox.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(relToA, st));
                    minY = Math.min(minY, relToA.getY());
                }

                // Patch pumpkin position and its neighbors in the module that contains it
                if (correctPumpkinState != null && mod.voxels().contains(pumpkinRel)) {
                    BlockPos pumpkinRelToA = new net.minecraft.core.BlockPos(
                            pumpkinRel.getX() - mod.aMarker().getX(),
                            pumpkinRel.getY() - mod.aMarker().getY(),
                            pumpkinRel.getZ() - mod.aMarker().getZ());
                    // Index voxel positions for fast lookup
                    java.util.Map<BlockPos, Integer> posToIdx = new java.util.HashMap<>();
                    for (int vi = 0; vi < vox.size(); vi++) posToIdx.put(vox.get(vi).rel, vi);

                    // Replace pumpkin block with the correct state
                    Integer pi = posToIdx.get(pumpkinRelToA);
                    if (pi != null) {
                        vox.set(pi, new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(pumpkinRelToA, correctPumpkinState));
                        // Recompute neighbor states as if the correct block were at the pumpkin position
                        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                            BlockPos neighborRelToA = pumpkinRelToA.relative(dir);
                            Integer ni = posToIdx.get(neighborRelToA);
                            if (ni == null) continue;
                            BlockPos neighborAbs = pumpkinAbs.relative(dir);
                            net.minecraft.world.level.block.state.BlockState neighborState = vox.get(ni).state;
                            net.minecraft.world.level.block.state.BlockState corrected = neighborState.updateShape(
                                    world, world, neighborAbs, dir.getOpposite(),
                                    pumpkinAbs, correctPumpkinState, world.getRandom());
                            if (corrected != neighborState) {
                                vox.set(ni, new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(neighborRelToA, corrected));
                            }
                        }
                    }
                }

                // Add voxels at gold marker positions (A and B) to fill 1-block gaps at module boundaries
                // Positions relative to module's A marker
                BlockPos aRelToA = BlockPos.ZERO; // A marker relative to itself
                BlockPos bRelToA = new BlockPos(
                        mod.bMarker().getX() - mod.aMarker().getX(),
                        mod.bMarker().getY() - mod.aMarker().getY(),
                        mod.bMarker().getZ() - mod.aMarker().getZ());
                // Index existing voxel positions for fast lookup
                java.util.Map<BlockPos, ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel> voxIndex = new java.util.HashMap<>();
                for (var vv : vox) voxIndex.put(vv.rel, vv);
                for (BlockPos goldRelToA : new BlockPos[]{aRelToA, bRelToA}) {
                    if (voxIndex.containsKey(goldRelToA)) continue; // already present
                    // Try voxel directly above
                    BlockPos above = goldRelToA.above();
                    net.minecraft.world.level.block.state.BlockState fillState = null;
                    if (voxIndex.containsKey(above)) {
                        fillState = voxIndex.get(above).state;
                    } else {
                        // Fallback: try adjacent voxels (N/S/E/W/below)
                        for (net.minecraft.core.Direction adjDir : new net.minecraft.core.Direction[]{
                                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST,
                                net.minecraft.core.Direction.DOWN}) {
                            BlockPos adj = goldRelToA.relative(adjDir);
                            if (voxIndex.containsKey(adj)) {
                                fillState = voxIndex.get(adj).state;
                                break;
                            }
                        }
                    }
                    if (fillState == null && correctPumpkinState != null) {
                        fillState = correctPumpkinState;
                    }
                    if (fillState != null) {
                        var newVoxel = new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(goldRelToA, fillState);
                        vox.add(newVoxel);
                        voxIndex.put(goldRelToA, newVoxel);
                        minY = Math.min(minY, goldRelToA.getY());
                    }
                }

                templates.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate(mod.aMarker(), mod.bMarker(), vox, minY == Integer.MAX_VALUE ? 0 : minY));
            }

            // Summon golem and persist capture metadata
            golem.setWallCapture(def.uniqueBlockIds, def.origin, jsonRel);
            golem.setWallJoinSignature(validation.signature());
            golem.setWallJoinMeta(validation.axis(), validation.uSize());
            golem.setWallSliceSymmetric(validation.symmetric());
            // Build join template from a non-summon slice using the validated axis
            ninja.trek.mc.goldgolem.wall.WallJoinSlice best = null;
            for (var g : def.goldMarkers) {
                var s = ninja.trek.mc.goldgolem.wall.WallJoinSlice.from(world, def.origin, def.voxels, g, validation.axis()).orElse(null);
                if (s != null) {
                    // Prefer the slice from a non-summon marker; among those, pick the one with most points
                    BlockPos markerAbs = def.origin.offset(g);
                    boolean isSummonMarker = markerAbs.equals(below);
                    if (best == null || (!isSummonMarker && s.points.size() >= best.points.size())) best = s;
                }
            }
            if (best != null) {
                // Pack entries as (dy,du,idIndex) with a small LUT using full state strings
                java.util.ArrayList<String> lut = new java.util.ArrayList<>();
                java.util.ArrayList<int[]> entries = new java.util.ArrayList<>();
                for (var p : best.points) {
                    net.minecraft.world.level.block.state.BlockState st = best.blockStates.get(p);
                    String stateStr = (st != null)
                            ? ninja.trek.mc.goldgolem.wall.WallJoinSlice.serializeState(st)
                            : best.blockIds.get(p);
                    int idx2 = lut.indexOf(stateStr);
                    if (idx2 < 0) { idx2 = lut.size(); lut.add(stateStr); }
                    entries.add(new int[]{p.dy(), p.du(), idx2});
                }
                golem.setWallJoinTemplate(entries, lut);
            }
            int count = extraction.modules().size();
            int longest = 0;
            for (var m : extraction.modules()) longest = Math.max(longest, m.voxels().size());
            golem.setWallModulesMeta(count, longest);
            golem.setWallTemplates(templates);
            // Set name AFTER all data is set so the snapshot written by setCustomName is complete
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.WALL)));
            // Remove gold block only after success
            world.destroyBlock(below, false, player);
            ServerLevel sw = (ServerLevel) world;
            sw.addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall golem spawned!"), false);
            }
            return InteractionResult.SUCCESS;
            } catch (Exception ex) {
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("[Gold Golem] Wall spawn exception: " + ex.getMessage()), false);
                }
                ex.printStackTrace();
                return InteractionResult.FAIL;
            }
        } else {
            // Pathing Mode: spawn as before
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerLevel) world);
            golem.snapTo(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYRot(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.PATH);
            golem.setCustomName(Component.literal(GoldGolemEntity.getNextGolemName(BuildMode.PATH)));
            world.destroyBlock(below, false, player);
            ((ServerLevel) world).addFreshEntity(golem);
            if (!player.isCreative()) stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
    }
}
