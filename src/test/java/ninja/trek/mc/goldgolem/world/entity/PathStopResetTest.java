package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ninja.trek.mc.goldgolem.BuildMode;
import ninja.trek.mc.goldgolem.client.state.ClientState;
import ninja.trek.mc.goldgolem.net.ServerNet;
import ninja.trek.mc.goldgolem.world.entity.strategy.GradientMiningHelper;
import ninja.trek.mc.goldgolem.world.entity.strategy.PathBuildStrategy;
import ninja.trek.mc.goldgolem.world.entity.strategy.path.LineSeg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PathStopResetTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Holder<?> nugget = BuiltInRegistries.ITEM.wrapAsHolder(Items.GOLD_NUGGET);
        if (nugget instanceof Holder.Reference<?> reference && !reference.areComponentsBound()) {
            reference.bindComponents(DataComponentMap.EMPTY);
        }
    }

    @Test
    void ownerHitWhileHoldingNuggetDiscardsWorkAndRestartHasNoOldLines() throws ReflectiveOperationException {
        GoldGolemEntity golem = mock(GoldGolemEntity.class);
        ServerLevel world = mock(ServerLevel.class);
        ServerPlayer owner = mock(ServerPlayer.class);
        PathNavigation navigation = mock(PathNavigation.class);
        DamageSource hit = mock(DamageSource.class);
        GradientMiningHelper miner = new GradientMiningHelper();
        PathBuildStrategy strategy = new PathBuildStrategy();
        strategy.setEntity(golem);
        strategy.setWaitingForResources(true);
        ArrayDeque<LineSeg> lines = new ArrayDeque<>();
        ArrayDeque<BlockPos> mines = new ArrayDeque<>();
        Vec3 oldStart = new Vec3(10, 65, 10);
        LineSeg oldLine = new LineSeg(oldStart, oldStart.add(3, 0, 0));
        lines.add(oldLine);
        mines.add(new BlockPos(12, 64, 10));
        miner.startMining(new BlockPos(11, 64, 10));

        setField(GoldGolemEntity.class, golem, "activeStrategy", strategy);
        setField(GoldGolemEntity.class, golem, "pendingLines", lines);
        setField(GoldGolemEntity.class, golem, "trackStart", oldStart);
        setField(GoldGolemEntity.class, golem, "currentLine", oldLine);
        setField(GoldGolemEntity.class, golem, "buildingPaths", true);
        setField(Entity.class, golem, "entityData", mock(SynchedEntityData.class));
        when(golem.level()).thenReturn(world);
        when(golem.getOwnerPlayer()).thenReturn(owner);
        when(golem.isOwner(owner)).thenReturn(true);
        when(golem.getNavigation()).thenReturn(navigation);
        when(golem.getPendingLines()).thenReturn(lines);
        when(golem.getPathPendingMines()).thenReturn(mines);
        when(golem.getPathGradientMiner()).thenReturn(miner);
        when(golem.getBuildMode()).thenReturn(BuildMode.PATH);
        when(golem.getId()).thenReturn(321);
        when(golem.getX()).thenReturn(30.5);
        when(golem.getY()).thenReturn(65.0);
        when(golem.getZ()).thenReturn(30.5);
        when(golem.getTrackStart()).thenCallRealMethod();
        when(golem.getCurrentLine()).thenCallRealMethod();
        doCallRealMethod().when(golem).setTrackStart(any());
        doCallRealMethod().when(golem).setCurrentLine(any());
        when(hit.getEntity()).thenReturn(owner);
        when(hit.is(DamageTypes.PLAYER_ATTACK)).thenReturn(true);
        when(owner.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(new ItemStack(Items.GOLD_NUGGET));
        when(owner.isCreative()).thenReturn(true);
        doCallRealMethod().when(golem).hurtServer(world, hit, 1.0F);
        doCallRealMethod().when(golem).mobInteract(owner, InteractionHand.MAIN_HAND);

        ClientState.setLines(321, List.of(oldLine.a, oldLine.b), Optional.of(oldStart), false);
        try (var network = mockStatic(ServerNet.class)) {
            // Deliver the real stop/start line messages to the real client cache.
            network.when(() -> ServerNet.sendLines(eq(owner), eq(321), anyList(), any(), anyBoolean()))
                    .thenAnswer(call -> {
                        ClientState.setLines(321, call.getArgument(2), call.getArgument(3), call.getArgument(4));
                        return null;
                    });

            assertFalse(golem.hurtServer(world, hit, 1.0F));

            assertTrue(lines.isEmpty());
            assertTrue(mines.isEmpty());
            assertNull(golem.getTrackStart());
            assertNull(golem.getCurrentLine());
            assertFalse(miner.isMining());
            assertFalse(strategy.isWaitingForResources());
            assertNull(ClientState.getLineData(321));
            verify(navigation, atLeastOnce()).stop();
            verify(golem, atLeastOnce()).clearPlacementTracking();

            golem.mobInteract(owner, InteractionHand.MAIN_HAND);

            assertTrue(lines.isEmpty());
            assertTrue(mines.isEmpty());
            assertNull(golem.getCurrentLine());
            assertFalse(miner.isMining());
            assertTrue(ClientState.getLineData(321).points.isEmpty());
            assertEquals(Optional.of(new Vec3(30.5, 65.05, 30.5)), ClientState.getLineData(321).anchor);
        } finally {
            ClientState.setLines(321, null, Optional.empty(), false);
        }
    }

    @Test
    void clearMessageRemovesOnlyStoppedGolemAndPreservesActivePreviews() {
        Vec3 anchor = new Vec3(3, 65, 3);
        try {
            ClientState.setLines(400, List.of(anchor, anchor.add(3, 0, 0)), Optional.of(anchor), false);
            ClientState.setLines(401, List.of(), Optional.of(anchor), false);
            ClientState.setLines(402, List.of(), Optional.empty(), true);

            ClientState.setLines(400, List.of(), Optional.empty(), false);

            assertNull(ClientState.getLineData(400));
            assertEquals(Optional.of(anchor), ClientState.getLineData(401).anchor);
            assertTrue(ClientState.getLineData(402).noValid);
        } finally {
            for (int id = 400; id <= 402; id++) ClientState.setLines(id, null, Optional.empty(), false);
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value)
            throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
