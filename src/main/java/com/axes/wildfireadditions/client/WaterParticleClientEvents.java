package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.client.particle.WaterJetParticle;
import com.axes.wildfireadditions.client.particle.WaterMistParticle;
import com.axes.wildfireadditions.registry.ModItems;
import com.axes.wildfireadditions.registry.ModParticles;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * Client-only wiring for the water stream particles: binds the registered particle types to their
 * sprite-backed factories (sprites come from assets/wildfireadditions/particles/*.json), plus the
 * "spraying" model predicate the hose uses to swap to its forward-pointing pose while in use.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WaterParticleClientEvents {

    private WaterParticleClientEvents() {
    }

    @SubscribeEvent
    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.WATER_JET.get(), WaterJetParticle.Provider::new);
        event.registerSpriteSet(ModParticles.WATER_MIST.get(), WaterMistParticle.Provider::new);
    }

    // Drives the hose model override in hose.json: reads 1.0 exactly while the holder is actively using
    // (spraying) this hose stack, which swaps the flat held sprite for hose_spraying.json's nozzle-forward
    // pose. Registered on the render thread via enqueueWork, as ItemProperties requires.
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(
                ModItems.HOSE.get(),
                ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "spraying"),
                (stack, level, entity, seed) ->
                        entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0F : 0.0F));
    }
}
