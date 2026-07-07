package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

public class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES = DeferredRegister.create(Registries.PARTICLE_TYPE, WildfireAdditions.MODID);

    // A single droplet of the pressurised water stream. Spawned at the nozzle with a real initial
    // velocity and flies its own ballistic arc client-side (see WaterJetParticle), so the stream
    // physically travels from the nozzle to the fire instead of being painted along a precomputed arc.
    public static final Supplier<SimpleParticleType> WATER_JET = PARTICLE_TYPES.register("water_jet",
            () -> new SimpleParticleType(false));

    // Soft white spray/mist: the aerated high-pressure jacket at the nozzle, the breakup spray the
    // stream sheds in flight, and the plume thrown up where droplets land.
    public static final Supplier<SimpleParticleType> WATER_MIST = PARTICLE_TYPES.register("water_mist",
            () -> new SimpleParticleType(false));
}
