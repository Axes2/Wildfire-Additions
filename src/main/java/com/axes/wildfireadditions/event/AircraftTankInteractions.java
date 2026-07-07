package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Fitting and removing the firefighting tank, by right-clicking the aircraft. It hangs off
 * {@link PlayerInteractEvent.EntityInteract}, which fires before Immersive Aircraft's own "mount me"
 * interaction - so when the player is holding a tank (or sneak-empty-handed to remove one) we consume the
 * interaction, and otherwise we stay out of the way and let IA behave normally.
 *
 * <p>Loading fluid is no longer done here: the tank draws buckets from the aircraft's cargo when it fires
 * (see {@link com.axes.wildfireadditions.network.AircraftTankNetwork}). Interactions here are just:
 * <ul>
 *   <li>holding an <b>aircraft tank</b> and none is fitted &rarr; fit it;</li>
 *   <li><b>sneaking, empty-handed</b> on a fitted aircraft &rarr; retrieve the tank item.</li>
 * </ul>
 * Both give the player an action-bar status message so it's always clear whether an aircraft is rigged for
 * firefighting. (Retrieval is best done standing next to the aircraft, since sneaking while riding
 * dismounts you before the interaction can land.)
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class AircraftTankInteractions {

    private enum Action { NONE, INSTALL, REMOVE }

    private AircraftTankInteractions() {
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget();
        if (!ImmersiveAircraftCompat.isAircraft(target)) return;

        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack stack = event.getItemStack();
        Level level = event.getLevel();

        AircraftTankData tank = target.getData(ModAttachments.AIRCRAFT_TANK.get());
        Action action = classify(player, hand, stack, tank);
        if (action == Action.NONE) return;

        // Mutate authoritatively on the server; the client just needs the interaction swallowed so the
        // aircraft doesn't also try to seat the player.
        if (!level.isClientSide()) {
            perform((ServerLevel) level, player, target, hand, stack, action);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
    }

    private static Action classify(Player player, InteractionHand hand, ItemStack stack, AircraftTankData tank) {
        if (stack.is(ModItems.AIRCRAFT_TANK.get())) {
            return tank.installed() ? Action.NONE : Action.INSTALL;
        }
        if (tank.installed() && hand == InteractionHand.MAIN_HAND && stack.isEmpty() && player.isShiftKeyDown()) {
            return Action.REMOVE;
        }
        return Action.NONE;
    }

    private static void perform(ServerLevel level, Player player, Entity aircraft, InteractionHand hand,
                                ItemStack stack, Action action) {
        switch (action) {
            case INSTALL -> {
                AircraftTankData fitted = AircraftTankData.fitted();
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), fitted);
                if (!player.getAbilities().instabuild) stack.shrink(1);
                playAt(level, aircraft, SoundEvents.ITEM_FRAME_ADD_ITEM, 0.9f);
                player.displayClientMessage(Component.translatable("message.wildfireadditions.tank_installed",
                        Component.translatable(fitted.selectedFluid().translationKey())), true);
            }
            case REMOVE -> {
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), AircraftTankData.EMPTY);
                giveOrDrop(player, new ItemStack(ModItems.AIRCRAFT_TANK.get()));
                playAt(level, aircraft, SoundEvents.ITEM_FRAME_REMOVE_ITEM, 0.9f);
                player.displayClientMessage(Component.translatable("message.wildfireadditions.tank_removed"), true);
            }
            default -> {
            }
        }
    }

    private static void giveOrDrop(Player player, ItemStack item) {
        if (!player.getInventory().add(item)) {
            player.drop(item, false);
        }
    }

    private static void playAt(ServerLevel level, Entity aircraft, net.minecraft.sounds.SoundEvent sound, float pitch) {
        level.playSound(null, aircraft.getX(), aircraft.getY(), aircraft.getZ(), sound, SoundSource.PLAYERS,
                0.8f, pitch + level.random.nextFloat() * 0.1f);
    }
}
