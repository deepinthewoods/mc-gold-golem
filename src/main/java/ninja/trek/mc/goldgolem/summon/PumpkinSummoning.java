package ninja.trek.mc.goldgolem.summon;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
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

        // Remove gold block and spawn golem; do not place the pumpkin
        world.breakBlock(below, false, player);
        ServerWorld sw = (ServerWorld) world;
        GoldGolemEntity golem = new GoldGolemEntity(GoldGolemEntities.GOLD_GOLEM, sw);
        golem.refreshPositionAndAngles(below.getX() + 0.5, below.getY(), below.getZ() + 0.5, player.getYaw(), 0);
        golem.setOwner(player);
        sw.spawnEntity(golem);

        if (!player.isCreative()) stack.decrement(1);
        return ActionResult.SUCCESS;
    }
}
