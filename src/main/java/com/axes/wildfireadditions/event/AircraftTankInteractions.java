package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.aircraft.AircraftTankData;
import com.axes.wildfireadditions.aircraft.AircraftTankData.Fluid;
import com.axes.wildfireadditions.compat.ImmersiveAircraftCompat;
import com.axes.wildfireadditions.registry.ModAttachments;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * All the ground-crew handling for the tank: fitting it to an aircraft, loading it with buckets, and
 * pulling it back off. It hangs off {@link PlayerInteractEvent.EntityInteract}, which fires before
 * Immersive Aircraft's own "mount me" interaction runs - so when the player is holding one of our items
 * we consume the interaction (cancelling it stops the mount), and otherwise we stay completely out of the
 * way and let IA behave normally.
 *
 * <p>Interactions, all by right-clicking the aircraft:
 * <ul>
 *   <li>holding an <b>aircraft tank</b> and none is fitted &rarr; fit it;</li>
 *   <li>holding a <b>water bucket</b> or <b>fire-retardant bucket</b> &rarr; load one charge (returns an
 *       empty bucket), provided the tank isn't full and isn't already holding the other fluid;</li>
 *   <li><b>sneaking, empty-handed</b>, on an empty fitted tank &rarr; retrieve the tank item.</li>
 * </ul>
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class AircraftTankInteractions {

    private enum Action { NONE, INSTALL, FILL_WATER, FILL_RETARDANT, REMOVE }

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
            // Mutate the live held stack, not the event's snapshot, so bucket/tank consumption is authoritative.
            perform((ServerLevel) level, player, target, hand, player.getItemInHand(hand), tank, action);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));
    }

    private static Action classify(Player player, InteractionHand hand, ItemStack stack, AircraftTankData tank) {
        if (stack.is(ModItems.AIRCRAFT_TANK.get())) {
            return tank.installed() ? Action.NONE : Action.INSTALL;
        }
        if (!tank.installed()) return Action.NONE;

        if (stack.is(Items.WATER_BUCKET) && tank.canAccept(Fluid.WATER)) return Action.FILL_WATER;
        if (stack.is(ModItems.FIRE_RETARDANT_BUCKET.get()) && tank.canAccept(Fluid.RETARDANT)) return Action.FILL_RETARDANT;
        if (hand == InteractionHand.MAIN_HAND && stack.isEmpty() && player.isShiftKeyDown() && tank.isEmpty()) {
            return Action.REMOVE;
        }
        return Action.NONE;
    }

    private static void perform(ServerLevel level, Player player, Entity aircraft, InteractionHand hand,
                                ItemStack stack, AircraftTankData tank, Action action) {
        switch (action) {
            case INSTALL -> {
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), AircraftTankData.installed());
                if (!player.getAbilities().instabuild) stack.shrink(1);
                playAt(level, aircraft, SoundEvents.ITEM_FRAME_ADD_ITEM, 0.9f);
            }
            case FILL_WATER -> {
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), tank.withAdded(Fluid.WATER));
                returnEmptyBucket(player, hand, stack);
                playAt(level, aircraft, SoundEvents.BUCKET_EMPTY, 1.0f);
            }
            case FILL_RETARDANT -> {
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), tank.withAdded(Fluid.RETARDANT));
                returnEmptyBucket(player, hand, stack);
                playAt(level, aircraft, SoundEvents.BUCKET_EMPTY_LAVA, 1.0f);
            }
            case REMOVE -> {
                aircraft.setData(ModAttachments.AIRCRAFT_TANK.get(), AircraftTankData.EMPTY);
                giveOrDrop(player, new ItemStack(ModItems.AIRCRAFT_TANK.get()));
                playAt(level, aircraft, SoundEvents.ITEM_FRAME_REMOVE_ITEM, 0.9f);
            }
            default -> {
            }
        }
    }

    // Consumes one bucket (unless creative) and hands back an empty bucket, exactly like vanilla dumping a
    // water bucket: since our buckets stack to one, shrinking empties the slot and we drop the empty in.
    private static void returnEmptyBucket(Player player, InteractionHand hand, ItemStack stack) {
        if (player.getAbilities().instabuild) return;
        stack.shrink(1);
        ItemStack empty = new ItemStack(Items.BUCKET);
        if (stack.isEmpty()) {
            player.setItemInHand(hand, empty);
        } else {
            giveOrDrop(player, empty);
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
