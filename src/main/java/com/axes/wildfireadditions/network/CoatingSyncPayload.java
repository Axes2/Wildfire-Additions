package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A single server -> client update about one chunk's coatings.
 *
 * <ul>
 *   <li>{@code replace = true}: the authoritative full set for this chunk (sent when a player first
 *       starts tracking the chunk). {@code posRemove} is unused.</li>
 *   <li>{@code replace = false}: an incremental delta - {@code posAdd}/{@code expiryAdd} are freshly
 *       sprayed coatings, {@code posRemove} are coatings that have just been snuffed or expired.</li>
 * </ul>
 *
 * Positions are packed {@link net.minecraft.core.BlockPos#asLong()} values; {@code expiryAdd[i]} is the
 * lapse game-time for {@code posAdd[i]}.
 */
public record CoatingSyncPayload(long chunkPos, boolean replace, long[] posAdd, long[] expiryAdd,
                                 long[] posRemove) implements CustomPacketPayload {

    public static final Type<CoatingSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WildfireAdditions.MODID, "coating_sync"));

    public static final StreamCodec<FriendlyByteBuf, CoatingSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeLong(payload.chunkPos);
                buf.writeBoolean(payload.replace);
                buf.writeLongArray(payload.posAdd);
                buf.writeLongArray(payload.expiryAdd);
                buf.writeLongArray(payload.posRemove);
            },
            buf -> new CoatingSyncPayload(
                    buf.readLong(),
                    buf.readBoolean(),
                    buf.readLongArray(),
                    buf.readLongArray(),
                    buf.readLongArray()
            )
    );

    @Override
    public Type<CoatingSyncPayload> type() {
        return TYPE;
    }
}
