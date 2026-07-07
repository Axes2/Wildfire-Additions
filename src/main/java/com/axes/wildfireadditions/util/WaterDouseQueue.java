package com.axes.wildfireadditions.util;

import com.axes.wildfireadditions.WildfireAdditions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Makes the water take real time to arrive, gameplay-wise. Instead of dousing the moment a stream is
 * aimed, the hose and the sprinkler schedule each douse pass here with the flight time their ballistic
 * trace computed; the douse then executes that many ticks later, when the (equally ballistic) visible
 * droplets are physically arriving at the target. Water already in the air when the nozzle moves away
 * still lands where it was headed - exactly like the particles do.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class WaterDouseQueue {

    private record PendingDouse(long arrivalTick, BlockPos pos) {
    }

    // Per-dimension queues ordered by arrival tick (aim changes mean later-scheduled water can arrive
    // sooner than earlier-scheduled water, so insertion order alone isn't enough).
    private static final Map<ResourceKey<Level>, PriorityQueue<PendingDouse>> QUEUES = new HashMap<>();

    // Safety valve; a hose keeps ~10 passes in flight and even a farm of sprinklers stays well below this.
    private static final int MAX_PENDING_PER_LEVEL = 1024;

    private WaterDouseQueue() {
    }

    /** Schedules a douse pass at {@code pos}, arriving {@code flightSeconds} of simulated flight from now. */
    public static void schedule(ServerLevel level, BlockPos pos, double flightSeconds) {
        long arrival = level.getGameTime() + Math.max(0L, Math.round(flightSeconds * 20.0));
        PriorityQueue<PendingDouse> queue = QUEUES.computeIfAbsent(level.dimension(),
                k -> new PriorityQueue<>(Comparator.comparingLong(PendingDouse::arrivalTick)));
        if (queue.size() < MAX_PENDING_PER_LEVEL) {
            queue.add(new PendingDouse(arrival, pos.immutable()));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            PriorityQueue<PendingDouse> queue = QUEUES.get(level.dimension());
            if (queue == null) continue;

            long now = level.getGameTime();
            PendingDouse head;
            while ((head = queue.peek()) != null && head.arrivalTick() <= now) {
                queue.poll();
                // Skip water arriving in a chunk that unloaded mid-flight rather than force-loading it.
                // Otherwise: the target fire may already be out (or never was fire); extinguishAt
                // degrades to a harmless wetting pass in that case, which is exactly what arriving
                // water should do.
                if (level.hasChunkAt(head.pos())) {
                    WaterStream.extinguishAt(level, head.pos(), now);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUES.clear();
    }
}
