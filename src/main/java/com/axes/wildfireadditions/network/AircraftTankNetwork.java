package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.event.AircraftAirDropHandler;
import com.axes.wildfireadditions.registry.ModAttachments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server-side plumbing for the deploy key. Registration is on the mod event bus; the handler runs on the
 * server thread (via {@link IPayloadContext#enqueueWork}) and is fully authoritative - it ignores the
 * packet unless the sender really is riding an aircraft that really has a loaded tank, then spends exactly
 * one charge and hands off to {@link AircraftAirDropHandler}.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AircraftTankNetwork {

    private AircraftTankNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(DeployTankPayload.TYPE, DeployTankPayload.STREAM_CODEC, AircraftTankNetwork::onDeploy);
    }

    private static void onDeploy(DeployTankPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity aircraft = ImmersiveAircraftCompat.ridingAircraft(player);
            if (aircraft == null) return;

            AircraftTankData tank = aircraft.getData(ModAttachments.AIRCRAFT_TANK.get());
            if (!tank.installed() || tank.isEmpty()) return;

            AircraftTankData.Fluid fluid = tank.fluid();
            aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), tank.withOneSpent());

            // Lay the stripe along the aircraft's travel direction, falling back to its facing when it's
            // hovering (e.g. a stationary helicopter) so the drop still has a sensible orientation.
            var move = aircraft.getDeltaMovement();
            double dirX = move.x;
            double dirZ = move.z;
            if (dirX * dirX + dirZ * dirZ < 1.0e-3) {
                double yaw = Math.toRadians(aircraft.getYRot());
                dirX = -Math.sin(yaw);
                dirZ = Math.cos(yaw);
            }

            AircraftAirDropHandler.startDrop((ServerLevel) player.level(),
                    aircraft.getX(), aircraft.getY(), aircraft.getZ(), dirX, dirZ, fluid);
        });
    }
}
