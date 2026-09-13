package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldGolemSupplyChestTest {
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
    void transfersSupplyChestContentsIntoGolemInventory() {
        SimpleContainer supply = new SimpleContainer(2);
        SimpleContainer golemInventory = new SimpleContainer(2);
        supply.setItem(0, stackOfCobblestone(32));
        supply.setItem(1, stackOfCobblestone(16));

        assertTrue(GoldGolemEntity.transferSupplyItems(supply, golemInventory));

        assertTrue(supply.isEmpty());
        assertEquals(48, countItems(golemInventory));
    }

    @Test
    void leavesItemsInSupplyChestWhenGolemInventoryIsFull() {
        SimpleContainer supply = new SimpleContainer(1);
        SimpleContainer golemInventory = new SimpleContainer(1);
        supply.setItem(0, stackOfCobblestone(10));
        golemInventory.setItem(0, stackOfCobblestone(60));

        assertTrue(GoldGolemEntity.transferSupplyItems(supply, golemInventory));

        assertEquals(6, countItems(supply));
        assertEquals(64, countItems(golemInventory));
        assertFalse(GoldGolemEntity.transferSupplyItems(supply, golemInventory));
    }

    private static ItemStack stackOfCobblestone(int count) {
        ItemStack stack = new ItemStack(Blocks.COBBLESTONE);
        stack.setCount(count);
        return stack;
    }

    private static int countItems(Container inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            count += inventory.getItem(slot).getCount();
        }
        return count;
    }
}
