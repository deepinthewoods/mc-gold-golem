package ninja.trek.mc.goldgolem.world.entity.strategy;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import ninja.trek.mc.goldgolem.BuildMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseMiningStrategyDepositTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindTestItemComponents(Blocks.COBBLESTONE.asItem());
    }

    private static void bindTestItemComponents(net.minecraft.world.item.Item item) {
        Holder<net.minecraft.world.item.Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        if (holder instanceof Holder.Reference<?> reference && !reference.areComponentsBound()) {
            reference.bindComponents(DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64)
                .build());
        }
    }

    @Test
    void fillsBothHalvesOfCombinedChestInventory() {
        TestMiningStrategy strategy = new TestMiningStrategy();
        SimpleContainer golemInventory = filledContainer(30, Blocks.COBBLESTONE);
        SimpleContainer firstHalf = new SimpleContainer(27);
        SimpleContainer secondHalf = new SimpleContainer(27);

        strategy.setBuildingBlockType(Blocks.COBBLESTONE);
        strategy.deposit(golemInventory, new CompoundContainer(firstHalf, secondHalf));

        assertEquals(64, countItems(golemInventory));
        assertEquals(27 * 64, countItems(firstHalf));
        assertEquals(2 * 64, countItems(secondHalf));
    }

    @Test
    void depositsBuildingBlockStacksBeyondReservedStack() {
        TestMiningStrategy strategy = new TestMiningStrategy();
        SimpleContainer golemInventory = filledContainer(3, Blocks.COBBLESTONE);
        SimpleContainer chest = new SimpleContainer(27);

        strategy.setBuildingBlockType(Blocks.COBBLESTONE);
        strategy.deposit(golemInventory, chest);

        assertEquals(64, countItems(golemInventory));
        assertEquals(128, countItems(chest));
    }

    private static SimpleContainer filledContainer(int slots, Block block) {
        SimpleContainer inventory = new SimpleContainer(slots);
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = new ItemStack(block);
            stack.setCount(64);
            inventory.setItem(slot, stack);
        }
        return inventory;
    }

    private static int countItems(Container inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            count += inventory.getItem(slot).getCount();
        }
        return count;
    }

    private static final class TestMiningStrategy extends BaseMiningStrategy {
        @Override
        public BuildMode getMode() {
            return BuildMode.EXCAVATION;
        }

        @Override
        public void tick(ninja.trek.mc.goldgolem.world.entity.GoldGolemEntity golem, Player owner) {
        }

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public void writeNbt(CompoundTag nbt) {
        }

        @Override
        public void readNbt(CompoundTag nbt) {
        }

        void setBuildingBlockType(Block block) {
            buildingBlockType = BuiltInRegistries.BLOCK.getKey(block).toString();
        }

        void deposit(Container inventory, Container chest) {
            depositInventoryToContainer(inventory, chest);
        }
    }
}
