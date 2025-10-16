package ninja.trek.mc.goldgolem.summon;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.CarvedPumpkinBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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

        // Decide mode: Wall Mode if gold block is touching any non-air, non-snow layer block on sides (exclude below)
        boolean wallMode = false;
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

        if (wallMode) {
            // Scan combined module per spec
            var res = ninja.trek.mc.goldgolem.wall.WallScanner.scan(world, below, player);
            if (!res.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Wall mode summon failed: " + res.error()), true);
                }
                return ActionResult.FAIL;
            }
            var def = res.def();
            // Validate join slices across all gold markers per spec
            var validation = ninja.trek.mc.goldgolem.wall.WallModuleValidator.validate(world, def.origin, def.voxels, def.goldMarkers, below);
            if (!validation.ok()) {
                if (player instanceof net.minecraft.server.network.ServerPlayerEntity sp) {
                    sp.sendMessage(net.minecraft.text.Text.literal("[Gold Golem] Wall validation failed: " + validation.error()), true);
                }
                return ActionResult.FAIL;
            }

            // Spawn golem with wall mode set
            GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, (ServerWorld) world);
            golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
            golem.setOwner(player);
            golem.setBuildMode(GoldGolemEntity.BuildMode.WALL);

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
                System.err.println("[GoldGolem] Failed to write wall snapshot: " + ioe);
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
            golem.setBuildMode(GoldGolemEntity.BuildMode.PATH);
            world.breakBlock(below, false, player);
            ((ServerWorld) world).spawnEntity(golem);
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        }
    }
}
