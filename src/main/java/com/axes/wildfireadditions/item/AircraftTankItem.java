package com.axes.wildfireadditions.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The aircraft tank: an item you fit to an Immersive Aircraft vehicle by right-clicking the aircraft with
 * it in hand. Once fitted, the tank lives on the aircraft (see
 * {@link com.axes.wildfireadditions.aircraft.AircraftTankData}); this item is just the physical thing you
 * install and, on sneak-right-clicking an empty tank, retrieve. All the fitting/filling/removing logic
 * lives in {@link com.axes.wildfireadditions.event.AircraftTankInteractions}.
 */
public class AircraftTankItem extends Item {

    public AircraftTankItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wildfireadditions.aircraft_tank.desc").withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.wildfireadditions.aircraft_tank.desc2").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }
}
