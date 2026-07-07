package com.axes.wildfireadditions;

import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModBlockEntities;
import com.axes.wildfireadditions.registry.ModBlocks;
import com.axes.wildfireadditions.registry.ModItems;
import com.axes.wildfireadditions.registry.ModRecipes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(WildfireAdditions.MODID)
public class WildfireAdditions {
    public static final String MODID = "wildfireadditions";

    public WildfireAdditions(IEventBus modEventBus) {
        // Register the deferred registers to the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModRecipes.RECIPE_SERIALIZERS.register(modEventBus);
    }
}