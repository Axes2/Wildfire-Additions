package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ChunkCoatingData;
import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.network.CoatingSync;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Keeps coating state consistent with the world lifecycle:
 *
 * <ul>
 *   <li><b>Chunk sent to a player</b> - push that chunk's full note set so the tint appears the moment
 *       the chunk becomes visible (covers late joiners and walking into a sprayed area).</li>
 *   <li><b>Chunk loaded from disk</b> - re-register it as "active" if it still carries persisted notes,
 *       so {@link RetardantFireHandler} keeps guarding it across a save/reload without scanning the
 *       whole world.</li>
 *   <li><b>Dimension unloaded</b> - drop the in-memory active-chunk tracking for it.</li>
 * </ul>
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class RetardantCoatingEvents {

    private RetardantCoatingEvents() {
    }

    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Sent event) {
        ServerLevel level = event.getLevel();
        ChunkAccess chunk = level.getChunk(event.getPos().x, event.getPos().z, ChunkStatus.FULL, false);
        if (chunk == null || !chunk.hasData(com.axes.wildfireadditions.registry.ModAttachments.COATING.get())) return;

        ChunkCoatingData data = RetardantCoating.dataFor(chunk);
        if (data.isEmpty()) return;
        CoatingSync.sendFullChunk(event.getPlayer(), event.getPos(), data.view());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RetardantCoating.onChunkLoaded(level, event.getChunk());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            RetardantCoating.clearDimension(level.dimension());
        }
    }
}
