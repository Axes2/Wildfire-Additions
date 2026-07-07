package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.aircraft.AircraftTankData.Fluid;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.event.AircraftAirDropHandler;
import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server-side plumbing for the two tank keybinds. Registration is on the mod event bus; handlers run on the
 * server thread and are fully authoritative - they ignore a packet unless the sender really is riding a
 * tank-fitted aircraft.
 *
 * <p>Firing a drop draws a bucket from the aircraft's own cargo. Every IA aircraft implements the vanilla
 * {@link Container} interface, so we read and write that cargo without ever referencing an IA class: find a
 * bucket of the selected fluid, swap it for an empty bucket in place (both bucket types stack to one, so a
 * slot always frees exactly), and only then start the drop. If there's no matching bucket aboard, the drop
 * is refused with an action-bar message.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AircraftTankNetwork {

    private AircraftTankNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(DeployTankPayload.TYPE, DeployTankPayload.STREAM_CODEC, AircraftTankNetwork::onDeploy);
        registrar.playToServer(SwitchTankFluidPayload.TYPE, SwitchTankFluidPayload.STREAM_CODEC, AircraftTankNetwork::onSwitchFluid);
    }

    private static void onDeploy(DeployTankPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity aircraft = ImmersiveAircraftCompat.ridingAircraft(player);
            if (aircraft == null) return;

            AircraftTankData tank = aircraft.getData(ModAttachments.AIRCRAFT_TANK.get());
            if (!tank.installed()) {
                status(player, "message.wildfireadditions.no_tank");
                return;
            }
            if (!(aircraft instanceof Container cargo)) {
                status(player, "message.wildfireadditions.no_cargo");
                return;
            }

            Fluid fluid = tank.selectedFluid();
            Item bucket = fluid == Fluid.WATER ? Items.WATER_BUCKET : ModItems.FIRE_RETARDANT_BUCKET.get();
            int slot = findBucketSlot(cargo, bucket);
            if (slot < 0) {
                player.displayClientMessage(Component.translatable("message.wildfireadditions.out_of_fluid",
                        Component.translatable(fluid.translationKey())), true);
                return;
            }

            // Spend the bucket and leave an empty one in its place (both bucket types stack to one, so the
            // slot is empty after removing the single bucket).
            cargo.removeItem(slot, 1);
            if (cargo.getItem(slot).isEmpty()) {
                cargo.setItem(slot, new ItemStack(Items.BUCKET));
            } else {
                giveOrDrop(player, new ItemStack(Items.BUCKET));
            }
            cargo.setChanged();

            AircraftAirDropHandler.startDrop((ServerLevel) player.level(), aircraft, fluid);
        });
    }

    private static void onSwitchFluid(SwitchTankFluidPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Entity aircraft = ImmersiveAircraftCompat.ridingAircraft(player);
            if (aircraft == null) return;

            AircraftTankData tank = aircraft.getData(ModAttachments.AIRCRAFT_TANK.get());
            if (!tank.installed()) {
                status(player, "message.wildfireadditions.no_tank");
                return;
            }

            AircraftTankData switched = tank.toggledFluid();
            aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), switched);
            player.displayClientMessage(Component.translatable("message.wildfireadditions.tank_switched",
                    Component.translatable(switched.selectedFluid().translationKey())), true);
        });
    }

    // First cargo slot holding the given bucket item, or -1 if the aircraft isn't carrying one.
    private static int findBucketSlot(Container cargo, Item bucket) {
        for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
            if (cargo.getItem(slot).is(bucket)) return slot;
        }
        return -1;
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack item) {
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }

    private static void status(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
