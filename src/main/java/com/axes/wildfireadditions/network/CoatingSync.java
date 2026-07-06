package com.axes.wildfireadditions.network;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ClientCoatingStore;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Server -> client plumbing for the coating tint.
 *
 * <p>Registration happens on the mod event bus; the actual clientbound handler is deliberately common
 * code that only writes into {@link ClientCoatingStore} (no {@code net.minecraft.client.*} references),
 * so nothing here forces client classes to load on a dedicated server.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CoatingSync {

    private static final long[] EMPTY = new long[0];

    private CoatingSync() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(CoatingSyncPayload.TYPE, CoatingSyncPayload.STREAM_CODEC, CoatingSync::handleOnClient);
    }

    // Common handler: only touches the client store, so it's safe to reference from registration on
    // both physical sides. enqueueWork bounces us onto the render thread before mutating the store.
    private static void handleOnClient(CoatingSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientCoatingStore.apply(payload));
    }

    /** Tells every player tracking this block's chunk about one freshly sprayed coating. */
    public static void sendAdd(ServerLevel level, long packedPos, long expiry) {
        ChunkPos chunk = new ChunkPos(net.minecraft.core.BlockPos.of(packedPos));
        CoatingSyncPayload payload = new CoatingSyncPayload(
                chunk.toLong(), false, new long[]{packedPos}, new long[]{expiry}, EMPTY);
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk, payload);
    }

    /** Tells every player tracking this block's chunk that a coating is gone (snuffed or expired). */
    public static void sendRemove(ServerLevel level, long packedPos) {
        ChunkPos chunk = new ChunkPos(net.minecraft.core.BlockPos.of(packedPos));
        CoatingSyncPayload payload = new CoatingSyncPayload(
                chunk.toLong(), false, EMPTY, EMPTY, new long[]{packedPos});
        PacketDistributor.sendToPlayersTrackingChunk(level, chunk, payload);
    }

    /**
     * Sends a player the full, authoritative coating set for one chunk - used when they start tracking
     * it, so late joiners and players walking into a sprayed area see the tint immediately.
     */
    public static void sendFullChunk(ServerPlayer player, ChunkPos chunk, Long2LongMap coatings) {
        long[] pos = new long[coatings.size()];
        long[] exp = new long[coatings.size()];
        int i = 0;
        for (Long2LongMap.Entry entry : coatings.long2LongEntrySet()) {
            pos[i] = entry.getLongKey();
            exp[i] = entry.getLongValue();
            i++;
        }
        CoatingSyncPayload payload = new CoatingSyncPayload(chunk.toLong(), true, pos, exp, EMPTY);
        PacketDistributor.sendToPlayer(player, payload);
    }
}
