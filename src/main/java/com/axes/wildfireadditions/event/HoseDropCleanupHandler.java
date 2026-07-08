package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.registry.ModItems;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * The fire hose is a live extension of its pump box, not a normal inventory item, so it should never sit
 * on the ground. Any hose {@link ItemEntity} - a player pressing Q, a death drop, a hose that couldn't
 * fit back into an inventory - is discarded the moment it tries to enter the world, on both sides so the
 * client and server agree it never existed.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class HoseDropCleanupHandler {

    private HoseDropCleanupHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity itemEntity
                && itemEntity.getItem().is(ModItems.HOSE.get())) {
            event.setCanceled(true);
        }
    }
}
