package com.axes.wildfireadditions.coating;

import com.axes.wildfireadditions.network.CoatingSync;
import com.axes.wildfireadditions.registry.ModAttachments;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * The server-side entry point for reading and writing retardant "sticky notes" (see
 * {@link ChunkCoatingData}). Everything that coats a block, checks whether a block is coated, or wants
 * to walk the set of coated chunks goes through here.
 *
 * <p>To avoid scanning every loaded chunk each tick, we keep a lightweight per-dimension set of
 * <i>which</i> chunks currently hold at least one note ({@link #ACTIVE_CHUNKS}). It's rebuilt lazily:
 * coating a block adds its chunk, and {@link #onChunkLoaded} re-registers any chunk that comes back
 * from disk still carrying persisted notes, so the set survives a save/reload.
 */
public final class RetardantCoating {

    /** How long a coating lasts, in game ticks: 10 minutes * 60s * 20 ticks. */
    public static final int EXPIRY_TICKS = 10 * 60 * 20;

    private static final Map<ResourceKey<Level>, LongSet> ACTIVE_CHUNKS = new HashMap<>();

    private RetardantCoating() {
    }

    /**
     * Coats {@code pos} until {@code now + EXPIRY_TICKS}, marks the chunk dirty, registers the chunk as
     * active, and pushes the note to nearby clients. The block's chunk must already be loaded (it always
     * is - this is only ever called for a block the player just clicked).
     */
    public static void coat(ServerLevel level, BlockPos pos) {
        long expiry = level.getGameTime() + EXPIRY_TICKS;
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkCoatingData data = chunk.getData(ModAttachments.COATING.get());
        data.put(pos.asLong(), expiry);
        chunk.setUnsaved(true);
        activeSet(level).add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        CoatingSync.sendAdd(level, pos.asLong(), expiry);
    }

    /** Whether {@code pos} currently carries an active, unexpired coating. Never forces a chunk to load. */
    public static boolean isCoated(Level level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null || !chunk.hasData(ModAttachments.COATING.get())) return false;
        return chunk.getData(ModAttachments.COATING.get()).isCoated(pos.asLong(), level.getGameTime());
    }

    /** Removes the note at {@code pos} (e.g. when an inferno overwhelms it) and syncs the removal. */
    public static void removeCoating(ServerLevel level, ChunkAccess chunk, long packedPos) {
        chunk.getData(ModAttachments.COATING.get()).remove(packedPos);
        chunk.setUnsaved(true);
        CoatingSync.sendRemove(level, packedPos);
    }

    // --- Active-chunk bookkeeping ------------------------------------------------------------------

    /** The mutable set of packed chunk positions that currently hold at least one note, for a dimension. */
    public static LongSet activeSet(ServerLevel level) {
        return ACTIVE_CHUNKS.computeIfAbsent(level.dimension(), k -> new LongOpenHashSet());
    }

    /** Re-registers a freshly loaded chunk if it came back from disk still holding persisted notes. */
    public static void onChunkLoaded(ServerLevel level, ChunkAccess chunk) {
        if (!chunk.hasData(ModAttachments.COATING.get())) return;
        if (chunk.getData(ModAttachments.COATING.get()).isEmpty()) return;
        activeSet(level).add(chunk.getPos().toLong());
    }

    /** Drops all in-memory active-chunk tracking for a dimension when it unloads. */
    public static void clearDimension(ResourceKey<Level> dimension) {
        ACTIVE_CHUNKS.remove(dimension);
    }

    public static ChunkCoatingData dataFor(ChunkAccess chunk) {
        return chunk.getData(ModAttachments.COATING.get());
    }
}
