package com.axes.wildfireadditions;

import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModBlockEntities;
import com.axes.wildfireadditions.registry.ModBlocks;
import com.axes.wildfireadditions.registry.ModItems;
import com.axes.wildfireadditions.registry.ModParticles;
import com.axes.wildfireadditions.registry.ModRecipes;
import com.axes.wildfireadditions.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(WildfireAdditions.MODID)
public class WildfireAdditions {
    public static final String MODID = "wildfireadditions";

    public WildfireAdditions(IEventBus modEventBus, ModContainer container) {
        // Register the deferred registers to the mod event bus
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        ModRecipes.RECIPE_SERIALIZERS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);

        // Register configs. STARTUP is registered first because the sprayer reads its charge pool from it
        // when items are built (during the registry events dispatched right after this constructor); a
        // STARTUP config is loaded early enough for that, unlike a per-world SERVER config.
        container.registerConfig(ModConfig.Type.STARTUP, WildfireConfig.STARTUP_SPEC);
        container.registerConfig(ModConfig.Type.SERVER, WildfireConfig.SERVER_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, WildfireConfig.CLIENT_SPEC);
    }
}