package com.axes.wildfireadditions.recipe;

import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.item.RetardantSprayerItem;
import com.axes.wildfireadditions.registry.ModRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * A shapeless crafting-grid "refill" for the {@link RetardantSprayerItem}: drop a spent (or partly spent)
 * sprayer plus one or more Magma Cream into a crafting grid to top the retardant charge back up. Each
 * Magma Cream restores a configurable percentage of full charge (default 25%, see
 * {@link WildfireConfig#sprayerRefillPercentPerMagmaCream()}).
 *
 * <p>Implemented as a {@link CustomRecipe} rather than a data-driven shapeless recipe because the result's
 * durability depends on how much Magma Cream is supplied and how damaged the incoming sprayer is - values
 * that can't be baked into a static JSON result.
 */
public class RetardantSprayerRefillRecipe extends CustomRecipe {

    public RetardantSprayerRefillRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        ItemStack sprayer = ItemStack.EMPTY;
        int magmaCount = 0;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof RetardantSprayerItem) {
                if (!sprayer.isEmpty() || stack.getCount() != 1) return false; // Only one sprayer, unstacked.
                sprayer = stack;
            } else if (stack.is(Items.MAGMA_CREAM)) {
                magmaCount++;
            } else {
                return false; // Anything else in the grid disqualifies the refill.
            }
        }

        // Need exactly one damaged sprayer and at least one Magma Cream; refilling a full sprayer is a no-op.
        return !sprayer.isEmpty() && magmaCount > 0 && sprayer.isDamaged();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack sprayer = ItemStack.EMPTY;
        int magmaCount = 0;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.getItem() instanceof RetardantSprayerItem) {
                sprayer = stack;
            } else if (stack.is(Items.MAGMA_CREAM)) {
                magmaCount++;
            }
        }

        if (sprayer.isEmpty()) return ItemStack.EMPTY;

        ItemStack result = sprayer.copy();
        result.setCount(1);
        // Each Magma Cream restores a configurable percentage of the sprayer's full charge (default 25%),
        // scaled to whatever the durability pool is currently set to. At least 1 point, so a tiny pool or
        // percentage still does something.
        int perCream = Math.max(1,
                (int) Math.round(result.getMaxDamage() * WildfireConfig.sprayerRefillPercentPerMagmaCream() / 100.0));
        int restored = magmaCount * perCream;
        result.setDamageValue(Math.max(0, result.getDamageValue() - restored));
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2; // Room for the sprayer plus at least one Magma Cream.
    }

    // A special recipe with no fixed output - the real result is computed in assemble() from the inputs.
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.RETARDANT_SPRAYER_REFILL.get();
    }
}
