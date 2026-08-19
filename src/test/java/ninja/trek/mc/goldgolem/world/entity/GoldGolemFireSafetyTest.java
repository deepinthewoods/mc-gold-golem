package ninja.trek.mc.goldgolem.world.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GoldGolemFireSafetyTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindTestItemComponents(Items.POTION);
        bindTestItemComponents(Items.SPLASH_POTION);
        bindTestItemComponents(Items.LINGERING_POTION);
    }

    private static void bindTestItemComponents(Item item) {
        Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
        if (holder instanceof Holder.Reference<?> reference && !reference.areComponentsBound()) {
            reference.bindComponents(DataComponentMap.EMPTY);
        }
    }

    @Test
    void findsDrinkableFireResistancePotion() {
        SimpleContainer inventory = new SimpleContainer(3);
        inventory.setItem(0, PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        inventory.setItem(1, PotionContents.createItemStack(Items.POTION, Potions.LONG_FIRE_RESISTANCE));

        assertEquals(1, GoldGolemFireSafety.findFireResistancePotionSlot(inventory));
    }

    @Test
    void recognizesSplashFireResistancePotion() {
        ItemStack splashPotion = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.FIRE_RESISTANCE);

        assertTrue(GoldGolemFireSafety.isFireResistancePotion(splashPotion));
        assertTrue(GoldGolemFireSafety.isSplashPotion(splashPotion));
    }

    @Test
    void ignoresLingeringFireResistancePotion() {
        ItemStack lingeringPotion = PotionContents.createItemStack(Items.LINGERING_POTION, Potions.FIRE_RESISTANCE);

        assertFalse(GoldGolemFireSafety.isFireResistancePotion(lingeringPotion));
        assertFalse(GoldGolemFireSafety.isSplashPotion(lingeringPotion));
    }

    @Test
    void recognizesFireSoulFireAndLava() {
        assertTrue(GoldGolemFireSafety.isFireOrLava(Blocks.FIRE.defaultBlockState()));
        assertTrue(GoldGolemFireSafety.isFireOrLava(Blocks.SOUL_FIRE.defaultBlockState()));
        assertTrue(GoldGolemFireSafety.isFireOrLava(Blocks.LAVA.defaultBlockState()));
        assertFalse(GoldGolemFireSafety.isFireOrLava(Blocks.STONE.defaultBlockState()));
    }
}
