package com.axes.wildfireadditions.aircraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The state of the firefighting tank fitted to an aircraft, stored as a serialised data attachment on the
 * aircraft entity itself (see {@link com.axes.wildfireadditions.registry.ModAttachments#AIRCRAFT_TANK}).
 * Keeping it on the entity - rather than reaching into Immersive Aircraft's own code - is what lets this be
 * a genuine <i>soft</i> dependency: we never touch an IA class, we just hang our own note on the vehicle.
 *
 * <p>The tank no longer stores fluid itself. It draws buckets straight from the aircraft's cargo
 * ({@code net.minecraft.world.Container}, which every IA aircraft implements) when a drop is fired, so a
 * plane's payload capacity is simply however many water / retardant buckets the player has loaded into it.
 * All this attachment records is whether a tank is fitted at all and which of the two fluids it's currently
 * set to draw.
 */
public record AircraftTankData(boolean installed, Fluid selectedFluid) {

    /** The default, and the state of a vehicle that has never had a tank fitted. */
    public static final AircraftTankData EMPTY = new AircraftTankData(false, Fluid.WATER);

    public static final Codec<AircraftTankData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("installed").forGetter(AircraftTankData::installed),
            Fluid.CODEC.fieldOf("selectedFluid").forGetter(AircraftTankData::selectedFluid)
    ).apply(instance, AircraftTankData::new));

    /** A freshly fitted tank, defaulting to drawing water. */
    public static AircraftTankData fitted() {
        return new AircraftTankData(true, Fluid.WATER);
    }

    public AircraftTankData withFluid(Fluid fluid) {
        return new AircraftTankData(installed, fluid);
    }

    /** This tank flipped to the other fluid. */
    public AircraftTankData toggledFluid() {
        return withFluid(selectedFluid == Fluid.WATER ? Fluid.RETARDANT : Fluid.WATER);
    }

    /** What the tank drops. */
    public enum Fluid {
        WATER("message.wildfireadditions.fluid.water"),
        RETARDANT("message.wildfireadditions.fluid.retardant");

        public static final Codec<Fluid> CODEC = Codec.STRING.xmap(
                name -> {
                    try {
                        return Fluid.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        return WATER;
                    }
                },
                Fluid::name);

        private final String translationKey;

        Fluid(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }
}
