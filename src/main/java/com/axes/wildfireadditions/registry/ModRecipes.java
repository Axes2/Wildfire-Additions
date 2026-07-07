package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.recipe.RetardantSprayerRefillRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, WildfireAdditions.MODID);

    // The crafting-grid "refill" for the retardant sprayer. A special (dynamic) recipe, so it carries no
    // ingredient list of its own beyond the crafting-book category - the JSON just names this serializer.
    public static final Supplier<SimpleCraftingRecipeSerializer<RetardantSprayerRefillRecipe>> RETARDANT_SPRAYER_REFILL =
            RECIPE_SERIALIZERS.register("retardant_sprayer_refill",
                    () -> new SimpleCraftingRecipeSerializer<>(RetardantSprayerRefillRecipe::new));
}
