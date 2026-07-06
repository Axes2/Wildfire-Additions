package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ChunkCoatingData;
import com.axes.wildfireadditions.coating.RetardantCoating;
import com.axes.wildfireadditions.network.CoatingSync;
import dev.protomanly.pmweather.block.PMWFireBlock;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Coating lifecycle + responsive extinguishing. The actual fire-<i>spread</i> blocking is done up front
 * by {@link com.axes.wildfireadditions.mixin.PMWFireBlockMixin}, which vetoes PMWeather's
 * {@code canBurnOn} for coated blocks below {@link #REQUIRED_INTENSITY} - so ordinary fire can never get
 * established on a coated block in the first place, and a coated structure simply isn't fuel until an
 * inferno that fierce comes along.
 *
 * <p>That mixin makes this handler's job small and non-authoritative:
 * <ul>
 *   <li><b>Prune expired notes.</b> Nothing ticks coatings down; this pass forgets notes whose 10-minute
 *       timestamp has passed and tells clients to drop the tint. This is the only active upkeep the
 *       "sticky note" model needs.</li>
 *   <li><b>Snuff on contact.</b> When a player sprays a structure that's <i>already</i> alight, the mixin
 *       stops the fire re-establishing but the existing flames would otherwise take a few random ticks to
 *       gutter out. We remove any sub-threshold {@link PMWFireBlock} sitting on a coated block right away
 *       so spraying a burning wall visibly puts it out. A true inferno (intensity ≥ {@link #REQUIRED_INTENSITY})
 *       is left to burn, keeping the tool "beatable by the fiercest fires".</li>
 * </ul>
 *
 * <p>Only chunks that actually hold notes are visited ({@link RetardantCoating#activeSet}), every
 * {@link #CHECK_PERIOD} ticks.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class RetardantFireHandler {

    // The fire intensity (PMWFireBlock.INTENSITY runs 1-10) a fire must reach to burn a coated block.
    // Read by PMWFireBlockMixin as the pre-spread veto threshold, and reused here for on-contact snuffing.
    // A freshly spread fire starts low and only a sustained blaze climbs this high, so 9 means
    // "practically fireproof, but the most intense infernos still win". Lower it to weaken the coating.
    public static final int REQUIRED_INTENSITY = 9;

    private static final int CHECK_PERIOD = 5; // Ticks between coating passes (~4x/second).

    private RetardantFireHandler() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        if (level.getGameTime() % CHECK_PERIOD != 0) return;

        LongSet active = RetardantCoating.activeSet(level);
        if (active.isEmpty()) return;

        long now = level.getGameTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongList emptyChunks = new LongArrayList();

        LongIterator chunkIt = active.iterator();
        while (chunkIt.hasNext()) {
            long chunkKey = chunkIt.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunkKey);
            ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
            if (chunk == null) continue; // Not loaded right now - re-checked once it loads again.

            ChunkCoatingData data = RetardantCoating.dataFor(chunk);
            boolean changed = processChunk(level, data, now, cursor);
            if (changed) chunk.setUnsaved(true);
            if (data.isEmpty()) emptyChunks.add(chunkKey);
        }

        // Drop now-empty chunks from the active set outside the iterator to avoid concurrent mutation.
        for (int i = 0; i < emptyChunks.size(); i++) {
            active.remove(emptyChunks.getLong(i));
        }
    }

    // Prunes expired notes and snuffs sub-threshold flames on coated blocks. Returns true if the note
    // set changed (so the chunk gets marked dirty).
    private static boolean processChunk(ServerLevel level, ChunkCoatingData data, long now,
                                        BlockPos.MutableBlockPos cursor) {
        Long2LongMap notes = data.view();
        if (notes.isEmpty()) return false;

        boolean changed = false;
        var it = notes.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            Long2LongMap.Entry entry = it.next();
            long packedPos = entry.getLongKey();

            if (entry.getLongValue() <= now) {
                it.remove(); // Expired note - forget it and tell clients to stop tinting.
                CoatingSync.sendRemove(level, packedPos);
                changed = true;
                continue;
            }

            // Snuff any low-intensity fire sitting on the coated block itself or directly above it (fire
            // rests on top of the fuel it's consuming). Re-ignition is already prevented by the mixin.
            int px = BlockPos.getX(packedPos), py = BlockPos.getY(packedPos), pz = BlockPos.getZ(packedPos);
            cursor.set(px, py, pz);
            snuffIfSubThreshold(level, cursor);
            cursor.set(px, py + 1, pz);
            snuffIfSubThreshold(level, cursor);
        }
        return changed;
    }

    private static void snuffIfSubThreshold(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PMWFireBlock)) return;
        if (state.getValue(PMWFireBlock.INTENSITY) >= REQUIRED_INTENSITY) return; // Inferno: let it burn.
        level.removeBlock(pos, false);
        spawnSmotherPuff(level, pos);
    }

    private static void spawnSmotherPuff(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        level.sendParticles(DustParticleOptions.REDSTONE, x, y, z, 6, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 3, 0.2, 0.2, 0.2, 0.01);
    }
}
