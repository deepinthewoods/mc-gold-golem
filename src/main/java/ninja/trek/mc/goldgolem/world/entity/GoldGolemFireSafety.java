package ninja.trek.mc.goldgolem.world.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Fire-hazard and potion helpers for {@link GoldGolemEntity}. */
public final class GoldGolemFireSafety {
    private GoldGolemFireSafety() {}

    public static int findFireResistancePotionSlot(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isFireResistancePotion(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    public static boolean isFireResistancePotion(ItemStack stack) {
        if (stack.isEmpty() || !isSupportedPotionItem(stack)) return false;

        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) return false;
        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (effect.is(MobEffects.FIRE_RESISTANCE)) return true;
        }
        return false;
    }

    public static boolean isSplashPotion(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SplashPotionItem;
    }

    private static boolean isSupportedPotionItem(ItemStack stack) {
        return stack.getItem() instanceof SplashPotionItem
                || stack.getItem() instanceof PotionItem && !(stack.getItem() instanceof ThrowablePotionItem);
    }

    public static boolean isFireOrLava(BlockState state) {
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(BlockTags.FIRE)) return true;

        FluidState fluid = state.getFluidState();
        return fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA) || fluid.is(FluidTags.LAVA);
    }
}
