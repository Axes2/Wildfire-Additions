package com.axes.wildfireadditions.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A looping, positional spray sound that fades itself in when its source starts spraying and out when
 * it stops, then stops once silent. One instance follows one hose or one turret; the seamless
 * {@code spray_loop.ogg} underneath means it can hold indefinitely without a click or pulse.
 *
 * <p>The source is supplied as two callbacks so the same class serves both devices: {@code active}
 * reports whether the hose/turret is still spraying, and {@code position} gives the current world
 * position to place the sound at each tick (a moving nozzle for the hose, a fixed one for the turret).
 */
public class SpraySoundInstance extends AbstractTickableSoundInstance {

    // ~0.06/tick reaches full volume in ~0.7s - a smooth fade rather than an abrupt on/off.
    private static final float FADE_PER_TICK = 0.06f;

    private final BooleanSupplier active;
    private final Supplier<Vec3> position;
    private final float maxVolume;

    public SpraySoundInstance(SoundEvent sound, SoundSource source, float maxVolume, float pitch,
                              BooleanSupplier active, Supplier<Vec3> position) {
        super(sound, source, RandomSource.create());
        this.active = active;
        this.position = position;
        this.maxVolume = maxVolume;
        this.pitch = pitch;
        this.volume = 0.0f;   // start silent and fade up
        this.looping = true;
        this.delay = 0;
        moveTo(position.get());
    }

    // Without this a sound that starts at volume 0 is culled before it can ever fade in.
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        moveTo(position.get());

        float target = active.getAsBoolean() ? maxVolume : 0.0f;
        if (this.volume < target) {
            this.volume = Math.min(target, this.volume + FADE_PER_TICK);
        } else if (this.volume > target) {
            this.volume = Math.max(target, this.volume - FADE_PER_TICK);
        }

        // Once the source is off and the fade-out has run to silence, retire the instance.
        if (target == 0.0f && this.volume <= 0.0001f) {
            stop();
        }
    }

    private void moveTo(Vec3 pos) {
        if (pos == null) return;
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
    }
}
