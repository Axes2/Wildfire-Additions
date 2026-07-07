package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server request to fire the tank on the aircraft the sender is currently riding. It carries no
 * data: the server re-derives everything it needs (which aircraft, what fluid, whether there's a charge
 * left) from the player's own state, so a spoofed packet can't drop someone else's tank or conjure fluid.
 */
public record DeployTankPayload() implements CustomPacketPayload {

    public static final DeployTankPayload INSTANCE = new DeployTankPayload();

    public static final Type<DeployTankPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "deploy_tank"));

    public static final StreamCodec<FriendlyByteBuf, DeployTankPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<DeployTankPayload> type() {
        return TYPE;
    }
}
