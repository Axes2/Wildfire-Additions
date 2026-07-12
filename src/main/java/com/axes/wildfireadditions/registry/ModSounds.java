package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, WildfireAdditions.MODID);

    // Both spray loops share one seamless ogg (assets/.../sounds/spray_loop.ogg) but are separate
    // events so the hose and the turret get their own subtitle and sound category. Variable-range so
    // the audible radius scales with the fade volume the client sound instance drives.
    public static final DeferredHolder<SoundEvent, SoundEvent> HOSE_SPRAY = register("hose_spray");
    public static final DeferredHolder<SoundEvent, SoundEvent> SPRINKLER_SPRAY = register("sprinkler_spray");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, name)));
    }
}
