package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server request to flip the tank on the aircraft the sender is riding between drawing water and
 * drawing retardant. Carries no data; the server re-derives the aircraft and current state from the sender.
 */
public record SwitchTankFluidPayload() implements CustomPacketPayload {

    public static final SwitchTankFluidPayload INSTANCE = new SwitchTankFluidPayload();

    public static final Type<SwitchTankFluidPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "switch_tank_fluid"));

    public static final StreamCodec<FriendlyByteBuf, SwitchTankFluidPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<SwitchTankFluidPayload> type() {
        return TYPE;
    }
}
