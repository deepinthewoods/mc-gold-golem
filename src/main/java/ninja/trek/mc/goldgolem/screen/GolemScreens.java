package ninja.trek.mc.goldgolem.screen;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.net.*;

/** Utilities to open Gold Golem screens from the server side. */
public final class GolemScreens {
    private GolemScreens() {}

    public static void open(ServerPlayerEntity player, int entityId, Inventory golemInventory) {
        // Inspect entity to decide UI flags
        var world0 = player.getEntityWorld();
        var ent0 = world0.getEntityById(entityId);
        boolean sliderEnabled = true;
        boolean excavationMode = false;
        boolean miningMode = false;
        boolean terraformingMode = false;
        boolean treeMode = false;
        if (ent0 instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity g0) {
            BuildMode mode = g0.getBuildMode();
            if (mode == BuildMode.WALL || mode == BuildMode.TOWER) {
                sliderEnabled = false;
            } else if (mode == BuildMode.EXCAVATION) {
                excavationMode = true;
                sliderEnabled = false;
            } else if (mode == BuildMode.MINING) {
                miningMode = true;
                sliderEnabled = false;
            } else if (mode == BuildMode.TERRAFORMING) {
                terraformingMode = true;
                sliderEnabled = false;
            } else if (mode == BuildMode.TREE) {
                treeMode = true;
                sliderEnabled = false;
            }
        }

        // Build dynamic UI spec
        int gradientRows = terraformingMode ? 3 : 2; // 3 rows for terraforming (vertical, horizontal, sloped)
        int golemSlots = golemInventory.size();
        int slider = sliderEnabled ? 1 : (excavationMode ? 2 : (miningMode ? 3 : (terraformingMode ? 4 : (treeMode ? 5 : 0))));
        var openData = new GolemOpenData(entityId, gradientRows, golemSlots, slider);

        player.openHandledScreen(new ExtendedScreenHandlerFactory<GolemOpenData>() {
            @Override
            public GolemOpenData getScreenOpeningData(ServerPlayerEntity player) {
                return openData;
            }

            @Override
            public Text getDisplayName() {
                return Text.translatable("screen.gold_golem.inventory");
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity ignored) {
                return new GolemInventoryScreenHandler(syncId, playerInventory, golemInventory, openData);
            }
        });

        // Send initial sync to the opener
        var world = player.getEntityWorld();
        var e = world.getEntityById(entityId);
        if (e instanceof ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem) {
            BuildMode mode = golem.getBuildMode();

            // Send gradient sync for PATH mode
            if (mode == BuildMode.PATH || mode == BuildMode.GRADIENT) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new SyncGradientS2CPayload(
                                entityId,
                                golem.getPathWidth(),
                                golem.getGradientWindow(),
                                golem.getStepGradientWindow(),
                                java.util.Arrays.asList(golem.getGradientCopy()),
                                java.util.Arrays.asList(golem.getStepGradientCopy())
                        ));
            }

            // Send group mode sync for WALL, TOWER, TREE
            if (mode.isGroupMode()) {
                sendGroupModeSync(player, golem, mode);
            }

            // Send excavation sync
            if (mode == BuildMode.EXCAVATION) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new SyncExcavationS2CPayload(entityId, golem.getExcavationHeight(), golem.getExcavationDepth()));
            }

            // Send terraforming sync
            if (mode == BuildMode.TERRAFORMING) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                        new SyncTerraformingS2CPayload(
                                entityId,
                                golem.getTerraformingScanRadius(),
                                golem.getTerraformingGradientVerticalWindow(),
                                golem.getTerraformingGradientHorizontalWindow(),
                                golem.getTerraformingGradientSlopedWindow(),
                                java.util.Arrays.asList(golem.getTerraformingGradientVerticalCopy()),
                                java.util.Arrays.asList(golem.getTerraformingGradientHorizontalCopy()),
                                java.util.Arrays.asList(golem.getTerraformingGradientSlopedCopy())
                        ));
            }
        }
    }

    /**
     * Send group mode state using the generic payload.
     */
    private static void sendGroupModeSync(ServerPlayerEntity player, ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem, BuildMode mode) {
        int entityId = golem.getId();

        // Initialize groups on first open if empty
        switch (mode) {
            case WALL -> {
                if (golem.getWallUniqueBlockIds() != null && !golem.getWallUniqueBlockIds().isEmpty()) {
                    if (golem.getWallGroupWindows().isEmpty()) {
                        golem.initWallGroups(golem.getWallUniqueBlockIds());
                    }
                    var ids = golem.getWallUniqueBlockIds();
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new UniqueBlocksS2CPayload(entityId, ids));
                    var groups = golem.getWallBlockGroupMap(ids);
                    var extraData = GroupModeStateS2CPayload.createWallExtraData();
                    // Use generic payloads
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeBlockGroupsS2CPayload(entityId, mode, groups));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeStateS2CPayload(entityId, mode, golem.getWallGroupWindows(), golem.getWallGroupFlatSlots(), extraData));
                }
            }
            case TOWER -> {
                if (golem.getTowerUniqueBlockIds() != null && !golem.getTowerUniqueBlockIds().isEmpty()) {
                    if (golem.getTowerGroupWindows().isEmpty()) {
                        golem.initTowerGroups(golem.getTowerUniqueBlockIds());
                    }
                    var ids = golem.getTowerUniqueBlockIds();
                    var counts = golem.getTowerBlockCounts();
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new UniqueBlocksS2CPayload(entityId, ids));
                    var groups = golem.getTowerBlockGroupMap(ids);
                    var extraData = GroupModeStateS2CPayload.createTowerExtraData(counts, golem.getTowerHeight());
                    // Use generic payloads
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeBlockGroupsS2CPayload(entityId, mode, groups));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeStateS2CPayload(entityId, mode, golem.getTowerGroupWindows(), golem.getTowerGroupFlatSlots(), extraData));
                }
            }
            case TREE -> {
                if (golem.getTreeUniqueBlockIds() != null && !golem.getTreeUniqueBlockIds().isEmpty()) {
                    if (golem.getTreeGroupWindows().isEmpty()) {
                        golem.initTreeGroups(golem.getTreeUniqueBlockIds());
                    }
                    var ids = golem.getTreeUniqueBlockIds();
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new UniqueBlocksS2CPayload(entityId, ids));
                    var groups = golem.getTreeBlockGroupMap(ids);
                    var extraData = GroupModeStateS2CPayload.createTreeExtraData(golem.getTreeTilingPreset().ordinal());
                    // Use generic payloads
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeBlockGroupsS2CPayload(entityId, mode, groups));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new GroupModeStateS2CPayload(entityId, mode, golem.getTreeGroupWindows(), golem.getTreeGroupFlatSlots(), extraData));
                }
            }
            default -> { }
        }
    }
}
