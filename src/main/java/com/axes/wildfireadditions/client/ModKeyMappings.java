package com.axes.wildfireadditions.client;

import com.axes.wildfireadditions.WildfireAdditions;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * The "Deploy Aircraft Tank" keybind. It's registered <b>unbound by default</b> (as the feature request
 * asked), so it never clashes with an existing key - the player picks one in Options &rarr; Controls. When
 * pressed while riding an aircraft, {@link AircraftTankClientEvents} sends the deploy request to the server.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ModKeyMappings {

    public static final String CATEGORY = "key.categories.wildfireadditions";

    public static final KeyMapping DEPLOY_TANK = new KeyMapping(
            "key.wildfireadditions.deploy_tank",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), // unbound until the player assigns one
            CATEGORY);

    private ModKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(DEPLOY_TANK);
    }
}
