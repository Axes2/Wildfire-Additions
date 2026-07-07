package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.network.DeployTankPayload;
import com.axes.wildfireadditions.network.SwitchTankFluidPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-side driver for the deploy keybind: each tick it drains any queued presses of
 * {@link ModKeyMappings#DEPLOY_TANK} and, if the player is currently riding an Immersive Aircraft vehicle,
 * fires a {@link DeployTankPayload} to the server. The riding check here is only to avoid pointless
 * packets - the server re-validates everything before it drops anything.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT)
public final class AircraftTankClientEvents {

    private AircraftTankClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean riding = ImmersiveAircraftCompat.isAircraft(mc.player.getVehicle());

        while (ModKeyMappings.DEPLOY_TANK.consumeClick()) {
            if (riding) {
                PacketDistributor.sendToServer(DeployTankPayload.INSTANCE);
            }
        }
        while (ModKeyMappings.SWITCH_TANK_FLUID.consumeClick()) {
            if (riding) {
                PacketDistributor.sendToServer(SwitchTankFluidPayload.INSTANCE);
            }
        }
    }
}
