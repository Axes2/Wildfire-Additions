package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Client-only wiring for the fire sprinkler: registers the head's block-entity renderer and registers
 * the standalone head model so it gets baked and can be fetched by {@link FireSprinklerRenderer}.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FireSprinklerClientEvents {

    private FireSprinklerClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.FIRE_SPRINKLER_BE.get(), FireSprinklerRenderer::new);
    }

    @SubscribeEvent
    public static void registerModels(ModelEvent.RegisterAdditional event) {
        event.register(FireSprinklerRenderer.HEAD_MODEL);
    }
}
