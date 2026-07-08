package com.axes.wildfireadditions.registry;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.config.WildfireConfig;
import com.axes.wildfireadditions.item.AircraftTankItem;
import com.axes.wildfireadditions.item.DripTorchItem;
import com.axes.wildfireadditions.item.FireRetardantBucketItem;
import com.axes.wildfireadditions.item.HoseItem;
import com.axes.wildfireadditions.item.RetardantSprayerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WildfireAdditions.MODID);

    public static final DeferredItem<Item> PUMP_BOX_ITEM = ITEMS.register("pump_box",
            () -> new BlockItem(ModBlocks.PUMP_BOX.get(), new Item.Properties()));

    public static final DeferredItem<Item> FIRE_SPRINKLER_ITEM = ITEMS.register("fire_sprinkler",
            () -> new BlockItem(ModBlocks.FIRE_SPRINKLER.get(), new Item.Properties()));

    public static final DeferredItem<Item> HOSE = ITEMS.register("hose",
            () -> new HoseItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DRIP_TORCH = ITEMS.register("drip_torch",
            () -> new DripTorchItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> RETARDANT_SPRAYER = ITEMS.register("retardant_sprayer",
            () -> new RetardantSprayerItem(new Item.Properties().durability(WildfireConfig.sprayerDurability())));

    // A bucket of fire-retardant slurry (water bucket + 2 magma cream); loads a tank with retardant. Stacks
    // to one like a filled bucket, so filling a tank takes three separate buckets.
    public static final DeferredItem<Item> FIRE_RETARDANT_BUCKET = ITEMS.register("fire_retardant_bucket",
            () -> new FireRetardantBucketItem(new Item.Properties().stacksTo(1)));

    // The tank the player fits to an Immersive Aircraft vehicle to drop water or retardant from the air.
    public static final DeferredItem<Item> AIRCRAFT_TANK = ITEMS.register("aircraft_tank",
            () -> new AircraftTankItem(new Item.Properties().stacksTo(1)));
}