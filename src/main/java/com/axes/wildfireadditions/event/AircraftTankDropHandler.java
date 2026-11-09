package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * Drops the fitted firefighting tank as an item when its aircraft is destroyed (crashed, broken apart by a
 * player, or killed), instead of the tank silently vanishing with the vehicle.
 *
 * <p>Immersive Aircraft removes a destroyed vehicle with {@code discard()}, so we watch for the aircraft
 * leaving the level with a {@code DISCARDED} or {@code KILLED} removal reason. Chunk unloads and dimension
 * changes use other reasons and are ignored, so the tank stays put on an aircraft that is merely unloaded.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class AircraftTankDropHandler {

    private AircraftTankDropHandler() {
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!ImmersiveAircraftCompat.isAircraft(entity)) return;

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != Entity.RemovalReason.DISCARDED && reason != Entity.RemovalReason.KILLED) return;

        if (!entity.hasData(ModAttachments.AIRCRAFT_TANK.get())) return;
        AircraftTankData tank = entity.getData(ModAttachments.AIRCRAFT_TANK.get());
        if (!tank.installed()) return;

        // Clear first so the tank can only ever be dropped once.
        entity.setData(ModAttachments.AIRCRAFT_TANK.get(), AircraftTankData.EMPTY);
        entity.spawnAtLocation(new ItemStack(ModItems.AIRCRAFT_TANK.get()));
    }
}
