package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Adds this mod's items to ProtoManly's Weather main creative tab ({@code pmweather:pmweather_tab}),
 * so all the wildfire tools live alongside PMWeather's own gear instead of in a separate tab.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CreativeTabHandler {

    private static final ResourceKey<CreativeModeTab> PMWEATHER_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("pmweather", "pmweather_tab"));

    private CreativeTabHandler() {
    }

    @SubscribeEvent
    public static void addToPmweatherTab(BuildCreativeModeTabContentsEvent event) {
        if (!event.getTabKey().equals(PMWEATHER_TAB)) return;

        event.accept(ModItems.PUMP_BOX_ITEM);
        event.accept(ModItems.HOSE);
        event.accept(ModItems.DRIP_TORCH);
        event.accept(ModItems.RETARDANT_SPRAYER);
    }
}
