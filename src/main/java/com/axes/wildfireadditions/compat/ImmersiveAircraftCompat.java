package com.axes.wildfireadditions.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * The whole of our Immersive Aircraft "integration" - and deliberately so. Because the aircraft-tank
 * feature is a soft dependency, we never compile against or import a single Immersive Aircraft class.
 * Instead we recognise one of its aircraft purely by the namespace of its registered entity type: every
 * IA vehicle (biplane, airship, gyrodyne, quadrocopter, warship, ...) is registered under the
 * {@code immersive_aircraft} namespace, so a namespace match is a reliable, version-proof "is this one of
 * their aircraft?" test that costs nothing and still works if the mod is absent.
 */
public final class ImmersiveAircraftCompat {

    public static final String MOD_ID = "immersive_aircraft";

    // Cached once: whether Immersive Aircraft is even present. Nothing here depends on it beyond this flag,
    // but it lets callers early-out without a registry lookup when the mod isn't installed.
    private static final boolean LOADED = ModList.get() != null && ModList.get().isLoaded(MOD_ID);

    private ImmersiveAircraftCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    /** True if {@code entity} is an Immersive Aircraft vehicle, identified by its entity-type namespace. */
    public static boolean isAircraft(Entity entity) {
        if (!LOADED || entity == null) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null && MOD_ID.equals(id.getNamespace());
    }

    /** The IA aircraft the player is currently riding, or {@code null} if they aren't riding one. */
    public static Entity ridingAircraft(Entity passenger) {
        Entity vehicle = passenger.getVehicle();
        return isAircraft(vehicle) ? vehicle : null;
    }
}
