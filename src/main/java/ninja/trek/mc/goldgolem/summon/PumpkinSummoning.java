package ninja.trek.mc.goldgolem.summon;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.CarvedPumpkinBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.registry.GoldGolemEntities;
import ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity;

public class PumpkinSummoning {
    public static void register() {
        UseBlockCallback.EVENT.register(PumpkinSummoning::onUseBlock);
    }

    private static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hit) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem bi)) return ActionResult.PASS;
        if (!(bi.getBlock() instanceof CarvedPumpkinBlock)) return ActionResult.PASS;

        BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
        BlockPos below = placePos.down();
        if (!world.getBlockState(below).isOf(Blocks.GOLD_BLOCK)) return ActionResult.PASS;

        if (world.isClient()) return ActionResult.SUCCESS;

        String desiredName = null;
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) {
            desiredName = customName.getString();
        }
        if (desiredName != null && !desiredName.isBlank()) {
            java.nio.file.Path snapshotPath = GoldGolemEntity.findSnapshotPath(desiredName);
            if (snapshotPath != null) {
                GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
                golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
                golem.setOwner(player);
                boolean applied = golem.applySnapshotFromPath((ServerWorld) world, snapshotPath, below, player, desiredName);
                if (applied) {
                    world.breakBlock(below, false, player);
                    ((ServerWorld) world).spawnEntity(golem);
                    if (!player.isCreative()) stack.decrement(1);
                    return ActionResult.SUCCESS;
                }
            }
        }

        // Check for Mining/Excavation Mode: check chest placement
        net.minecraft.util.math.Direction chestDirection1 = null;
        net.minecraft.util.math.Direction chestDirection2 = null;
        int chestCount = 0;
        for (var dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH,
                net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST,
                net.minecraft.util.math.Direction.WEST
        }) {
            var np = below.offset(dir);
            var st = world.getBlockState(np);
            // Check for chest, trapped chest, or barrel
            boolean isStorageBlock = st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL);
            if (isStorageBlock) {
                if (chestCount == 0) chestDirection1 = dir;
                else if (chestCount == 1) chestDirection2 = dir;
                chestCount++;
            }
        }

        // Debug: Log chest count and detected blocks
        if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
            if (chestCount > 0) {
                sp.sendMessage(net.minecraft.text.Text.literal("[Debug] Found " + chestCount + " storage block(s) at: " +
                    (chestDirection1 != null ? chestDirection1.asString() : "none") +
                    (chestDirection2 != null ? ", " + chestDirection2.asString() : "")), false);
            } else {
                // Show what blocks are around the gold block
                StringBuilder blockInfo = new StringBuilder("[Debug] No chests detected. Adjacent blocks: ");
                for (var dir : new net.minecraft.util.math.Direction[]{
                        net.minecraft.util.math.Direction.NORTH,
                        net.minecraft.util.math.Direction.SOUTH,
                        net.minecraft.util.math.Direction.EAST,
                        net.minecraft.util.math.Direction.WEST
                }) {
                    var np = below.offset(dir);
                    var st = world.getBlockState(np);
                    blockInfo.append(dir.asString()).append("=").append(st.getBlock().getName().getString()).append(" ");
                }
                sp.sendMessage(net.minecraft.text.Text.literal(blockInfo.toString()), false);
            }
        }

        // Check if excavation mode (2 chests on adjacent/non-opposite sides)
        boolean excavationMode = false;
        if (chestCount == 2) {
            // Check if directions are not opposite
            excavationMode = (chestDirection1 != chestDirection2.getOpposite());
        }

        boolean miningMode = (chestCount == 1);

        // Check for Tower Mode: gold block below the pumpkin's gold block
        BlockPos belowBelow = below.down();
        boolean towerMode = !miningMode && !excavationMode && world.getBlockState(belowBelow).isOf(Blocks.GOLD_BLOCK);

        // Check for Terraforming Mode: 3x3 layer of gold blocks
        boolean terraformingMode = false;
        if (!towerMode && !miningMode && !excavationMode) {
            // Check if this gold block is the center of a 3x3 horizontal gold platform
            boolean is3x3Gold = true;
            for (int dx = -1; dx <= 1 && is3x3Gold; dx++) {
                for (int dz = -1; dz <= 1 && is3x3Gold; dz++) {
                    BlockPos checkPos = below.add(dx, 0, dz);
                    if (!world.getBlockState(checkPos).isOf(Blocks.GOLD_BLOCK)) {
                        is3x3Gold = false;
                    }
                }
            }
            terraformingMode = is3x3Gold;
        }

        // Check for Tree Mode: second gold block touching pumpkin's gold block
        boolean treeMode = false;
        BlockPos secondGoldPos = null;
        if (!towerMode && !miningMode && !excavationMode && !terraformingMode) {
            for (var dir : new net.minecraft.util.math.Direction[]{
                    net.minecraft.util.math.Direction.NORTH,
                    net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST,
                    net.minecraft.util.math.Direction.WEST,
                    net.minecraft.util.math.Direction.UP,
                    net.minecraft.util.math.Direction.DOWN
            }) {
                var np = below.offset(dir);
                if (world.getBlockState(np).isOf(Blocks.GOLD_BLOCK)) {
                    treeMode = true;
                    secondGoldPos = np;
                    break;
                }
            }
        }

        // Decide mode: Wall Mode if gold block is touching any non-air, non-snow layer block on sides (exclude below)
        // Tower, mining, excavation, terraforming, and tree modes take precedence over wall mode
        boolean wallMode = false;
        if (!towerMode && !miningMode && !excavationMode && !terraformingMode && !treeMode) {
            for (var dir : new net.minecraft.util.math.Direction[]{
                    net.minecraft.util.math.Direction.NORTH,
                    net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST,
                    net.minecraft.util.math.Direction.WEST,
                    net.minecraft.util.math.Direction.UP
            }) {
                var np = below.offset(dir);
                var st = world.getBlockState(np);
                if (!st.isAir() && !st.isOf(Blocks.SNOW)) { wallMode = true; break; }
            }
        }

        if (excavationMode) {
            // Excavation Mode: 2 chests on adjacent sides, excavate in opposite diagonal
            BlockPos chest1 = below.offset(chestDirection1);
            BlockPos chest2 = below.offset(chestDirection2);

            // Spawn golem with excavation mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.EXCAVATION);
            golem.setExcavationConfig(chest1, chest2, chestDirection1, chestDirection2, below);

            world.breakBlock(below, false, player);
            ((ServerWorld) world).spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else if (miningMode) {
            // Mining Mode: chest on one side, mine in opposite direction
            BlockPos chestPos = below.offset(chestDirection1);
            net.minecraft.util.math.Direction miningDir = chestDirection1.getOpposite();

            // Spawn golem with mining mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.MINING);
            golem.setMiningConfig(chestPos, miningDir, below);

            world.breakBlock(below, false, player);
            ((ServerWorld) world).spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else if (terraformingMode) {
            // Terraforming Mode: scan skeleton structure touching 3x3 gold platform
            var res = ninja.trek.mc.goldgolem.terraforming.TerraformingScanner.scan(world, below, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Terraforming mode summon failed: " + res.error()), true);
                }
                return ActionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with terraforming mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TERRAFORMING);
            golem.setTerraformingConfig(def, below);

            // Remove the 3x3 gold platform
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos removePos = below.add(dx, 0, dz);
                    world.breakBlock(removePos, false, player);
                }
            }

            ServerWorld sw = (ServerWorld) world;
            sw.spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else if (treeMode) {
            // Tree Mode: scan for input modules separated by gold blocks
            var res = ninja.trek.mc.goldgolem.tree.TreeScanner.scan(world, secondGoldPos, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Tree mode summon failed: " + res.error()), true);
                }
                return ActionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with tree mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(secondGoldPos.getX() + 0.5, secondGoldPos.getY(), secondGoldPos.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TREE);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.tree.TreeScanner.writeJson(gameDir, golem.getUuid(), def);
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
                    BlockPos abs = secondGoldPos.add(rel);
                    blocks.put(rel, world.getBlockState(abs));
                }
                moduleStates.add(blocks);
            }
            golem.setTreeModuleBlockStates(moduleStates);

            // Set tree capture data on golem
            golem.setTreeCapture(def.modules, def.uniqueBlockIds, secondGoldPos, jsonRel);

            // Remove both gold blocks (pumpkin gold and second gold)
            world.breakBlock(below, false, player);
            world.breakBlock(secondGoldPos, false, player);

            ServerWorld sw = (ServerWorld) world;
            sw.spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else if (towerMode) {
            // Tower Mode: Find bottom gold block and count total height
            BlockPos bottomGold = belowBelow;
            // Find the actual bottom gold block by going down until we hit a non-gold block
            while (world.getBlockState(bottomGold.down()).isOf(Blocks.GOLD_BLOCK)) {
                bottomGold = bottomGold.down();
            }

            // Count gold blocks upward from below (pumpkin's gold block) to determine tower height
            int towerHeight = 0;
            BlockPos checkPos = below; // Start from the pumpkin's gold block
            while (world.getBlockState(checkPos).isOf(Blocks.GOLD_BLOCK)) {
                towerHeight++;
                checkPos = checkPos.up();
            }

            if (towerHeight == 0) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Tower mode: No gold blocks found for height"), true);
                }
                return ActionResult.FAIL;
            }

            // Collect all gold block positions for flood fill starting points
            java.util.List<BlockPos> goldBlockPositions = new java.util.ArrayList<>();
            BlockPos collectPos = bottomGold;
            while (world.getBlockState(collectPos).isOf(Blocks.GOLD_BLOCK)) {
                goldBlockPositions.add(collectPos);
                collectPos = collectPos.up();
            }

            // Scan the module structure from all gold block positions
            var res = ninja.trek.mc.goldgolem.tower.TowerScanner.scan(world, goldBlockPositions, bottomGold, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Tower mode summon failed: " + res.error()), true);
                }
                return ActionResult.FAIL;
            }
            var def = res.def();

            // Spawn golem with tower mode
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(bottomGold.getX() + 0.5, bottomGold.getY(), bottomGold.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.TOWER);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.tower.TowerScanner.writeJson(gameDir, golem.getUuid(), def);
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
                var abs = def.origin.add(r);
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

            // Remove all gold blocks in the column
            BlockPos removePos = bottomGold;
            while (world.getBlockState(removePos).isOf(Blocks.GOLD_BLOCK)) {
                world.breakBlock(removePos, false, player);
                removePos = removePos.up();
            }

            ServerWorld sw = (ServerWorld) world;
            sw.spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else if (wallMode) {
            // Scan combined module per spec
            var res = ninja.trek.mc.goldgolem.wall.WallScanner.scan(world, below, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Wall mode summon failed: " + res.error()), true);
                }
                return ActionResult.FAIL;
            }
            var def = res.def();
            // Debug output removed
            // Validate join slices across all gold markers per spec
            var validation = ninja.trek.mc.goldgolem.wall.WallModuleValidator.validate(world, def.origin, def.voxels, def.goldMarkers, below);
            if (!validation.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Wall validation failed: " + validation.error()), true);
                }
                return ActionResult.FAIL;
            }
            // Validation summary logging removed

            // Spawn golem with wall mode set
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.WALL);

            // Persist JSON file under game dir
            String jsonRel = null;
            try {
                java.nio.file.Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
                java.nio.file.Path out = ninja.trek.mc.goldgolem.wall.WallScanner.writeJson(gameDir, golem.getUuid(), def);
                // store path relative to game dir for portability
                jsonRel = gameDir.relativize(out).toString();
            } catch (Exception ioe) {
                // Non-fatal; continue without external snapshot
                jsonRel = null;
            }
            // Extract modules and enforce uniqueness + size/count limits
            var extraction = ninja.trek.mc.goldgolem.wall.WallModuleExtractor.extract(world, def.origin, def.voxels, def.goldMarkers, below);
            if (!extraction.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Wall module extraction failed: " + extraction.error()), true);
                }
                return ActionResult.FAIL;
            }

            // Build module templates with per-voxel block ids relative to each module's A marker
            java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate> templates = new java.util.ArrayList<>();
            for (var mod : extraction.modules()) {
                java.util.List<ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel> vox = new java.util.ArrayList<>();
                int minY = Integer.MAX_VALUE;
                for (var r : mod.voxels()) {
                    var abs = def.origin.add(r);
                    var st = world.getBlockState(abs);
                    // store position relative to module a-marker
                    var relToA = new net.minecraft.util.math.BlockPos(r.getX() - mod.aMarker().getX(), r.getY() - mod.aMarker().getY(), r.getZ() - mod.aMarker().getZ());
                    vox.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate.Voxel(relToA, st));
                    minY = Math.min(minY, relToA.getY());
                }
                templates.add(new ninja.trek.mc.goldgolem.wall.WallModuleTemplate(mod.aMarker(), mod.bMarker(), vox, minY == Integer.MAX_VALUE ? 0 : minY));
            }

            // Summon golem and persist capture metadata
            golem.setWallCapture(def.uniqueBlockIds, def.origin, jsonRel);
            golem.setWallJoinSignature(validation.signature());
            golem.setWallJoinMeta(validation.axis(), validation.uSize());
            // Build join template from the largest slice (no ignoring), so we can fill the pumpkin slot when needed
            // Choose preferred axis as in validator
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (var r : def.voxels) { minX = Math.min(minX, r.getX()); maxX = Math.max(maxX, r.getX()); minZ = Math.min(minZ, r.getZ()); maxZ = Math.max(maxZ, r.getZ()); }
            var preferred = (maxX - minX) >= (maxZ - minZ)
                    ? ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.X_THICK
                    : ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.Z_THICK;
            ninja.trek.mc.goldgolem.wall.WallJoinSlice best = null;
            for (var g : def.goldMarkers) {
                var s = ninja.trek.mc.goldgolem.wall.WallJoinSlice.from(world, def.origin, def.voxels, g, preferred).orElse(null);
                if (s == null && preferred == ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.X_THICK) {
                    s = ninja.trek.mc.goldgolem.wall.WallJoinSlice.from(world, def.origin, def.voxels, g, ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.Z_THICK).orElse(null);
                } else if (s == null) {
                    s = ninja.trek.mc.goldgolem.wall.WallJoinSlice.from(world, def.origin, def.voxels, g, ninja.trek.mc.goldgolem.wall.WallJoinSlice.Axis.X_THICK).orElse(null);
                }
                if (s != null) {
                    if (best == null || s.points.size() > best.points.size()) best = s;
                }
            }
            if (best != null) {
                // Pack entries as (dy,du,idIndex) with a small LUT
                java.util.ArrayList<String> lut = new java.util.ArrayList<>();
                java.util.ArrayList<int[]> entries = new java.util.ArrayList<>();
                for (var p : best.points) {
                    String id = best.blockIds.get(p);
                    int idx = lut.indexOf(id);
                    if (idx < 0) { idx = lut.size(); lut.add(id); }
                    entries.add(new int[]{p.dy(), p.du(), idx});
                }
                golem.setWallJoinTemplate(entries, lut);
            }
            int count = extraction.modules().size();
            int longest = 0;
            for (var m : extraction.modules()) longest = Math.max(longest, m.voxels().size());
            golem.setWallModulesMeta(count, longest);
            golem.setWallTemplates(templates);
            // Remove gold block only after success
            world.breakBlock(below, false, player);
            ServerWorld sw = (ServerWorld) world;
            sw.spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        } else {
            // Pathing Mode: spawn as before
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(BuildMode.PATH);
            world.breakBlock(below, false, player);
            ((ServerWorld) world).spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        }
    }
}
