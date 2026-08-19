package ninja.trek.mc.goldgolem.api.structure;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import ninja.trek.mc.goldgolem.tower.TowerModuleTemplate;
import ninja.trek.mc.goldgolem.tree.TilingPreset;
import ninja.trek.mc.goldgolem.wall.WallJoinSlice;
import ninja.trek.mc.goldgolem.wall.WallModuleTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoldGolemTemplateCodecTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsTowerStateProperties() throws Exception {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.WEST);
        TowerModuleTemplate tower = new TowerModuleTemplate(
                List.of(new TowerModuleTemplate.Voxel(BlockPos.ZERO, stairs)),
                0,
                0
        );
        GoldGolemTemplate original = new GoldGolemTemplate(
                1,
                TemplateMode.TOWER,
                List.of(),
                tower,
                List.of(),
                TilingPreset.SMALL_3x3,
                null,
                List.of("minecraft:oak_stairs"),
                new GradientConfig(
                        Map.of("minecraft:oak_stairs", 0),
                        List.of(List.of("minecraft:stone", "gold-golem:mine/minecraft/iron_pickaxe")),
                        List.of(1.5f),
                        List.of(4)
                ),
                12,
                0
        );

        GoldGolemTemplate decoded = GoldGolemTemplateCodec.fromJson(GoldGolemTemplateCodec.toJson(original));

        assertEquals(TemplateMode.TOWER, decoded.mode());
        assertEquals(12, decoded.defaultHeight());
        assertEquals(Direction.WEST, decoded.towerModule().voxels.getFirst().state.getValue(StairBlock.FACING));
        assertEquals(List.of("minecraft:stone", "gold-golem:mine/minecraft/iron_pickaxe"),
                decoded.gradient().groupSlots().getFirst());
        assertEquals(1.5f, decoded.gradient().groupWindows().getFirst());
    }

    @Test
    void roundTripsWallAndTreeModules() throws Exception {
        WallModuleTemplate wall = new WallModuleTemplate(
                BlockPos.ZERO,
                new BlockPos(2, 0, 0),
                List.of(new WallModuleTemplate.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
                0,
                WallJoinSlice.Axis.X_THICK,
                WallJoinSlice.Axis.X_THICK
        );
        GoldGolemTemplate wallTemplate = new GoldGolemTemplate(
                1, TemplateMode.WALL, List.of(wall), null, List.of(),
                TilingPreset.SMALL_3x3, null, List.of(), 1, 0);
        GoldGolemTemplate decodedWall = GoldGolemTemplateCodec.fromJson(
                GoldGolemTemplateCodec.toJson(wallTemplate));
        assertEquals(new BlockPos(2, 0, 0), decodedWall.wallModules().getFirst().bMarker);

        Map<BlockPos, BlockState> treeModule = new LinkedHashMap<>();
        treeModule.put(new BlockPos(1, 2, 3), Blocks.OAK_LOG.defaultBlockState());
        GoldGolemTemplate treeTemplate = new GoldGolemTemplate(
                1, TemplateMode.TREE, List.of(), null, List.of(treeModule),
                TilingPreset.LARGE_5x5, "minecraft:dirt", List.of(), 1, 0);
        GoldGolemTemplate decodedTree = GoldGolemTemplateCodec.fromJson(
                GoldGolemTemplateCodec.toJson(treeTemplate));
        assertEquals(TilingPreset.LARGE_5x5, decodedTree.treeTilingPreset());
        assertNotNull(decodedTree.treeModules().getFirst().get(new BlockPos(1, 2, 3)));
    }

    @Test
    void acceptsPublicPayloadEmbeddedInSnapshot() throws Exception {
        GoldGolemTemplate template = towerTemplate();
        JsonObject snapshot = new JsonObject();
        snapshot.add(GoldGolemTemplateCodec.SNAPSHOT_TEMPLATE_KEY, GoldGolemTemplateCodec.toJson(template));

        assertEquals(TemplateMode.TOWER, GoldGolemTemplateCodec.fromJson(snapshot).mode());
    }

    @Test
    void rejectsLegacySnapshotWithoutPublicPayload() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("version", 1);
        legacy.addProperty("mode", "TOWER");

        assertThrows(TemplateLoadException.class, () -> GoldGolemTemplateCodec.fromJson(legacy));
    }

    @Test
    void rejectsUnknownBlockAndProperty() {
        JsonObject unknownBlockTemplate = GoldGolemTemplateCodec.toJson(towerTemplate());
        JsonObject state = unknownBlockTemplate.getAsJsonObject("towerModule")
                .getAsJsonArray("voxels").get(0).getAsJsonObject().getAsJsonObject("state");
        state.addProperty("id", "missing:test_block");
        assertThrows(TemplateLoadException.class,
                () -> GoldGolemTemplateCodec.fromJson(unknownBlockTemplate));

        JsonObject unknownPropertyTemplate = GoldGolemTemplateCodec.toJson(towerTemplate());
        state = unknownPropertyTemplate.getAsJsonObject("towerModule")
                .getAsJsonArray("voxels").get(0).getAsJsonObject().getAsJsonObject("state");
        state.getAsJsonObject("properties").addProperty("missing_property", "value");
        assertThrows(TemplateLoadException.class,
                () -> GoldGolemTemplateCodec.fromJson(unknownPropertyTemplate));
    }

    private static GoldGolemTemplate towerTemplate() {
        TowerModuleTemplate tower = new TowerModuleTemplate(
                List.of(new TowerModuleTemplate.Voxel(BlockPos.ZERO, Blocks.STONE.defaultBlockState())),
                0,
                0
        );
        return new GoldGolemTemplate(
                1, TemplateMode.TOWER, List.of(), tower, List.of(),
                TilingPreset.SMALL_3x3, null, List.of("minecraft:stone"), 4, 0);
    }
}
