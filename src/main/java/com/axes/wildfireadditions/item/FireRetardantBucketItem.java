package com.axes.wildfireadditions.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A bucket of fire-retardant slurry (water bucket + 2 magma cream). It isn't a placeable fluid - it exists
 * to load an aircraft tank with retardant: right-click a tank-equipped aircraft with three of these and
 * the tank drops a coat of retardant instead of dousing water. Emptied back to a plain bucket on use, the
 * same as a vanilla water bucket.
 */
public class FireRetardantBucketItem extends Item {

    public FireRetardantBucketItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.wildfireadditions.fire_retardant_bucket.desc").withStyle(ChatFormatting.GRAY));
    }
}
