package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import com.axes.wildfireadditions.coating.ChunkCoatingData;
import com.axes.wildfireadditions.coating.RetardantCoating;
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
 * Where the coating actually stops PMWeather's wildfire.
 *
 * <h2>The rule</h2>
 * PMWeather grows a fire by placing a {@link PMWFireBlock} at a target position and letting its
 * {@code INTENSITY} (1-10) climb over time. When such a block lands on a coated position, this handler
 * consults the coating and decides its fate by the fire's own intensity:
 *
 * <ul>
 *   <li><b>Below {@link #REQUIRED_INTENSITY}</b> - the ordinary case for a fire that has just spread
 *       onto the block - the retardant smothers it and the fire block is removed. This is <i>not</i>
 *       blanket immunity: an ordinary encroaching fire simply can't get established on a coated block.</li>
 *   <li><b>At or above {@link #REQUIRED_INTENSITY}</b> - only reached by a sustained, raging inferno -
 *       the coating is overwhelmed. The note is consumed and the fire is left to burn, so the tool is
 *       "difficult to beat, except for the most intense infernos" rather than a hard firewall.</li>
 * </ul>
 *
 * <p>This is a deliberately non-invasive, reactive integration: it reads only the public
 * {@code PMWFireBlock.INTENSITY} the rest of this mod already uses, and needs no changes to (or mixins
 * into) PMWeather. The trade-off is that it acts a beat <i>after</i> a fire block appears rather than
 * vetoing the spread pre-emptively. See the file request notes accompanying this change for the
 * PMWeather source needed to (optionally) upgrade this into a true pre-spread intensity check.
 *
 * <h2>Cost</h2>
 * Only chunks that actually hold notes are visited ({@link RetardantCoating#activeSet}), and only every
 * {@link #CHECK_PERIOD} ticks - far more often than a fire block's own random tick, so encroaching fire
 * is caught long before it can climb or spread. The same pass prunes expired notes.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public final class RetardantFireHandler {

    // INTENSITY runs 1-10 (see PMWFireBlock / the hose + drip-torch code). A freshly spread fire starts
    // low and only a sustained blaze climbs this high, so 9 means "practically fireproof, but a true
    // inferno still wins". Tune here to make the coating tougher (10) or more fragile (lower).
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
            boolean changed = processChunk(level, chunk, data, now, cursor);
            if (changed) chunk.setUnsaved(true);
            if (data.isEmpty()) emptyChunks.add(chunkKey);
        }

        // Drop now-empty chunks from the active set outside the iterator to avoid concurrent mutation.
        for (int i = 0; i < emptyChunks.size(); i++) {
            active.remove(emptyChunks.getLong(i));
        }
    }

    // Walks one chunk's notes: prunes expired ones, and applies the intensity rule to any coated block
    // currently on fire. Returns true if the note set changed (so the chunk gets marked dirty).
    private static boolean processChunk(ServerLevel level, ChunkAccess chunk, ChunkCoatingData data,
                                        long now, BlockPos.MutableBlockPos cursor) {
        Long2LongMap notes = data.view();
        if (notes.isEmpty()) return false;

        boolean changed = false;
        var it = notes.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            Long2LongMap.Entry entry = it.next();
            long packedPos = entry.getLongKey();

            if (entry.getLongValue() <= now) {
                it.remove(); // Expired note - just forget it and tell clients to stop tinting.
                com.axes.wildfireadditions.network.CoatingSync.sendRemove(level, packedPos);
                changed = true;
                continue;
            }

            cursor.set(packedPos);
            BlockState state = level.getBlockState(cursor);
            if (!(state.getBlock() instanceof PMWFireBlock)) continue;

            // Judge the fire pressing on this block by the fiercest flame at or adjacent to it - the
            // "inferno" trying to take it - not just the (initially low) intensity of the block that
            // was placed here. That's what makes the tool graded rather than a hard wall: ordinary
            // fire is snuffed each pass and never climbs, but its source keeps burning on its own
            // (uncoated) blocks, and once that source has raged up to REQUIRED_INTENSITY it finally
            // breaks through here.
            int fireIntensity = surroundingFireIntensity(level, cursor, state.getValue(PMWFireBlock.INTENSITY));
            if (fireIntensity < REQUIRED_INTENSITY) {
                // Retardant smothers the encroaching fire. The note stays, so the block keeps resisting.
                level.removeBlock(cursor, false);
                spawnSmotherPuff(level, cursor);
            } else {
                // An inferno this fierce overwhelms the coating: consume the note and let it burn.
                it.remove();
                com.axes.wildfireadditions.network.CoatingSync.sendRemove(level, packedPos);
                changed = true;
            }
        }
        return changed;
    }

    // The strongest PMWeather fire intensity at this block or its six face-neighbours - i.e. how fierce
    // the fire bearing down on this coated block is. Reads only the public INTENSITY property.
    private static int surroundingFireIntensity(ServerLevel level, BlockPos pos, int selfIntensity) {
        int max = selfIntensity;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            probe.setWithOffset(pos, dir);
            BlockState neighbour = level.getBlockState(probe);
            if (neighbour.getBlock() instanceof PMWFireBlock) {
                max = Math.max(max, neighbour.getValue(PMWFireBlock.INTENSITY));
            }
        }
        return max;
    }

    private static void spawnSmotherPuff(ServerLevel level, BlockPos pos) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        level.sendParticles(DustParticleOptions.REDSTONE, x, y, z, 6, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.SMOKE, x, y, z, 3, 0.2, 0.2, 0.2, 0.01);
    }
}
