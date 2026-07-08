package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.client.particle.WaterJetParticle;
import com.axes.wildfireadditions.client.particle.WaterMistParticle;
import com.axes.wildfireadditions.registry.ModParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * Client-only wiring for the water stream particles: binds the registered particle types to their
 * sprite-backed factories (sprites come from assets/wildfireadditions/particles/*.json).
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
}
