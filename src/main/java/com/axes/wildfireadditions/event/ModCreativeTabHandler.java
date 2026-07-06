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

@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class ModCreativeTabHandler {

    private static final ResourceKey<CreativeModeTab> PMWEATHER_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("pmweather", "pmweather_tab"));

    @SubscribeEvent
    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == PMWEATHER_TAB) {
            event.accept(ModItems.FIRE_SPRINKLER_ITEM);
        }
    }
}
