package com.axes.wildfireadditions.aircraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The state of the water/retardant tank bolted onto an aircraft, stored as a serialised data attachment
 * on the aircraft entity itself (see {@link com.axes.wildfireadditions.registry.ModAttachments#AIRCRAFT_TANK}).
 * Keeping it on the entity - rather than reaching into Immersive Aircraft's own inventory - is what lets
 * this be a genuine <i>soft</i> dependency: we never touch an IA class, we just hang our own note on the
 * vehicle and detect that the vehicle happens to be an aircraft by its registry namespace.
 *
 * <p>The tank is deliberately single-fluid: it holds up to {@link #MAX_CHARGES} loads of exactly one of
 * water or retardant, and can't be topped up with the other until it's been emptied back to
 * {@link Fluid#NONE}. Each deploy spends one charge.
 */
public record AircraftTankData(boolean installed, Fluid fluid, int charges) {

    /** How many bucket-loads a full tank holds; also how many buckets it takes to fill from empty. */
    public static final int MAX_CHARGES = 3;

    /** The default, and the state of a vehicle that has never had a tank fitted. */
    public static final AircraftTankData EMPTY = new AircraftTankData(false, Fluid.NONE, 0);

    public static final Codec<AircraftTankData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("installed").forGetter(AircraftTankData::installed),
            Fluid.CODEC.fieldOf("fluid").forGetter(AircraftTankData::fluid),
            Codec.INT.fieldOf("charges").forGetter(AircraftTankData::charges)
    ).apply(instance, AircraftTankData::new));

    /** A freshly fitted, empty tank. */
    public static AircraftTankData installed() {
        return new AircraftTankData(true, Fluid.NONE, 0);
    }

    public boolean isEmpty() {
        return charges <= 0;
    }

    public boolean isFull() {
        return charges >= MAX_CHARGES;
    }

    /** Whether this tank will accept another bucket of {@code incoming}: room left, and same/blank fluid. */
    public boolean canAccept(Fluid incoming) {
        return installed && !isFull() && (fluid == Fluid.NONE || fluid == incoming);
    }

    /** This tank with one more charge of {@code incoming}. Caller must have checked {@link #canAccept}. */
    public AircraftTankData withAdded(Fluid incoming) {
        return new AircraftTankData(true, incoming, charges + 1);
    }

    /** This tank with one charge spent; resets to {@link Fluid#NONE} once it runs dry so it can be refilled with either fluid. */
    public AircraftTankData withOneSpent() {
        int left = Math.max(0, charges - 1);
        return new AircraftTankData(true, left == 0 ? Fluid.NONE : fluid, left);
    }

    /** What the tank drops. {@link #NONE} is the "no fluid loaded" placeholder, never actually deployed. */
    public enum Fluid {
        NONE,
        WATER,
        RETARDANT;

        public static final Codec<Fluid> CODEC = Codec.STRING.xmap(
                name -> {
                    try {
                        return Fluid.valueOf(name);
                    } catch (IllegalArgumentException e) {
                        return NONE;
                    }
                },
                Fluid::name);
    }
}
