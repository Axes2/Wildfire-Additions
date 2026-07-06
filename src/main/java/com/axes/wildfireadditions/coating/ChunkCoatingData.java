package com.axes.wildfireadditions.coating;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * The "sticky notes" for one chunk: a compact map of packed block position -> the game time at which
 * that block's retardant coating expires.
 *
 * <p>This is the whole memory model behind the Retardant Sprayer. Rather than inventing thousands of
 * new "fireproof" block states, we attach one of these to each chunk (see
 * {@link com.axes.wildfireadditions.registry.ModAttachments}) and just remember which positions inside
 * it were sprayed and when the coating lapses. Nothing ticks these down actively - a note is simply
 * ignored (and lazily pruned) once its timestamp is in the past, so an untouched, fully-expired chunk
 * costs nothing until something next looks at it.
 *
 * <p>Positions are stored packed via {@link net.minecraft.core.BlockPos#asLong()}; only the block's
 * own coordinates matter, so the same structure serialises cheaply as a flat list of (pos, expiry)
 * pairs. Longs are used for expiry because coating lifetimes are measured against
 * {@link net.minecraft.world.level.Level#getGameTime()}, which is a long.
 */
public class ChunkCoatingData {

    private final Long2LongMap coatings;

    public ChunkCoatingData() {
        this.coatings = new Long2LongOpenHashMap();
        this.coatings.defaultReturnValue(Long.MIN_VALUE);
    }

    private ChunkCoatingData(Long2LongMap coatings) {
        this.coatings = coatings;
        this.coatings.defaultReturnValue(Long.MIN_VALUE);
    }

    /** Records (or refreshes) the coating on {@code packedPos}, set to lapse at {@code expiryGameTime}. */
    public void put(long packedPos, long expiryGameTime) {
        coatings.put(packedPos, expiryGameTime);
    }

    /** Drops the note for {@code packedPos} outright (e.g. when an inferno overwhelms the coating). */
    public void remove(long packedPos) {
        coatings.remove(packedPos);
    }

    public boolean isEmpty() {
        return coatings.isEmpty();
    }

    /** True if this block currently carries an active, unexpired coating. */
    public boolean isCoated(long packedPos, long now) {
        long expiry = coatings.get(packedPos);
        return expiry != Long.MIN_VALUE && expiry > now;
    }

    /** A snapshot of the raw pos -> expiry entries, used when syncing a whole chunk to a client. */
    public Long2LongMap view() {
        return coatings;
    }

    /**
     * Discards every note whose timestamp has already passed. Returns true if anything was removed, so
     * callers can mark the owning chunk dirty / re-broadcast only when the set actually changed.
     */
    public boolean pruneExpired(long now) {
        if (coatings.isEmpty()) return false;
        var it = coatings.long2LongEntrySet().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Long2LongMap.Entry entry = it.next();
            if (entry.getLongValue() <= now) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    // --- Serialisation -----------------------------------------------------------------------------

    private record Entry(long pos, long expiry) {
    }

    private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
            Codec.LONG.fieldOf("expiry").forGetter(Entry::expiry)
    ).apply(instance, Entry::new));

    /**
     * Stored as a flat list of (pos, expiry) pairs. A map with long keys can't round-trip through NBT
     * directly (NBT compound keys must be strings), so the list form is both simpler and portable.
     */
    public static final Codec<ChunkCoatingData> CODEC = ENTRY_CODEC.listOf().xmap(
            ChunkCoatingData::fromEntries,
            ChunkCoatingData::toEntries
    );

    private static ChunkCoatingData fromEntries(List<Entry> entries) {
        Long2LongMap map = new Long2LongOpenHashMap(entries.size());
        for (Entry e : entries) {
            map.put(e.pos(), e.expiry());
        }
        return new ChunkCoatingData(map);
    }

    private List<Entry> toEntries() {
        List<Entry> out = new ArrayList<>(coatings.size());
        for (Long2LongMap.Entry e : coatings.long2LongEntrySet()) {
            out.add(new Entry(e.getLongKey(), e.getLongValue()));
        }
        return out;
    }
}
