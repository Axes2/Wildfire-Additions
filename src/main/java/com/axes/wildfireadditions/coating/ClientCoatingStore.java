package com.axes.wildfireadditions.coating;

import com.axes.wildfireadditions.network.CoatingSyncPayload;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The client's mirror of the coatings the server has told it about, keyed by packed chunk position.
 *
 * <p>Deliberately free of any client-only ({@code net.minecraft.client.*}) imports so it can be
 * populated from the common payload handler without dragging client classes onto a dedicated server's
 * classpath. The actual rendering (which <i>is</i> client-only) reads from here via
 * {@link #forEachCoating}.
 *
 * <p>Only ever touched from the client (render/network) thread, so it needs no synchronisation.
 */
public final class ClientCoatingStore {

    private static final Long2ObjectMap<Long2LongMap> BY_CHUNK = new Long2ObjectOpenHashMap<>();

    private ClientCoatingStore() {
    }

    /** Applies one server update - either a full chunk replacement or an add/remove delta. */
    public static void apply(CoatingSyncPayload payload) {
        Long2LongMap chunk = BY_CHUNK.get(payload.chunkPos());

        if (payload.replace()) {
            if (payload.posAdd().length == 0) {
                BY_CHUNK.remove(payload.chunkPos());
                return;
            }
            chunk = new Long2LongOpenHashMap(payload.posAdd().length);
            chunk.defaultReturnValue(Long.MIN_VALUE);
            BY_CHUNK.put(payload.chunkPos(), chunk);
        } else if (chunk == null) {
            chunk = new Long2LongOpenHashMap();
            chunk.defaultReturnValue(Long.MIN_VALUE);
            BY_CHUNK.put(payload.chunkPos(), chunk);
        }

        long[] add = payload.posAdd();
        long[] exp = payload.expiryAdd();
        for (int i = 0; i < add.length && i < exp.length; i++) {
            chunk.put(add[i], exp[i]);
        }
        for (long remove : payload.posRemove()) {
            chunk.remove(remove);
        }
        if (chunk.isEmpty()) {
            BY_CHUNK.remove(payload.chunkPos());
        }
    }

    /** Wipes everything - used on dimension change / disconnect so stale notes don't bleed across worlds. */
    public static void clear() {
        BY_CHUNK.clear();
    }

    public interface CoatingConsumer {
        void accept(long packedPos);
    }

    /**
     * Visits every currently-coated, unexpired position the client knows about, pruning expired notes
     * as it goes so the store self-cleans just by being drawn.
     */
    public static void forEachCoating(long now, CoatingConsumer consumer) {
        if (BY_CHUNK.isEmpty()) return;
        var chunkIt = BY_CHUNK.long2ObjectEntrySet().iterator();
        while (chunkIt.hasNext()) {
            Long2LongMap chunk = chunkIt.next().getValue();
            var it = chunk.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                Long2LongMap.Entry entry = it.next();
                if (entry.getLongValue() <= now) {
                    it.remove();
                } else {
                    consumer.accept(entry.getLongKey());
                }
            }
            if (chunk.isEmpty()) {
                chunkIt.remove();
            }
        }
    }
}
