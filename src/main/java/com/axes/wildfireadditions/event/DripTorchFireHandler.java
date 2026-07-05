package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import dev.protomanly.pmweather.block.ModBlocks;
import dev.protomanly.pmweather.block.PMWFireBlock;
import dev.protomanly.pmweather.data.DataAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the low-intensity "backfire" the drip torch lights.
 *
 * <p>The whole point of this tool is to be a controlled burn, not another way to grow the wildfire.
 * Three guarantees make that safe, all built on facts from PMWeather's own {@code PMWFireBlock}:
 *
 * <ul>
 *   <li><b>Never climbs into trees.</b> {@code PMWFireBlock.canBurnOn} refuses leaves/logs below
 *       intensity 4, and native spread only triggers at {@code intensity > 2}. We cap every ember
 *       we plant at intensity {@value #CAP}, which is below both thresholds - so our fire can neither
 *       ignite a tree directly nor use PMWeather's own spread to seed uncapped children that later
 *       could. Every burning block that exists because of the drip torch is one we planted and clamp.</li>
 *   <li><b>Burns toward the wildfire, not away.</b> Since native spread is disabled by the cap, the
 *       fire only advances via our own creep, which steps embers toward the nearest genuine wildfire
 *       block (gated on the chunk actually carrying fire, via PMWeather's STABLE_FIRE_INTENSITY).</li>
 *   <li><b>Merges seamlessly.</b> Our embers are real {@code PMWFireBlock}s, so when one reaches the
 *       wildfire we simply stop tracking it - no override, no reset. From that moment it's an
 *       ordinary wildfire block that grows and behaves like any other.</li>
 * </ul>
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class DripTorchFireHandler {

    public static final int START_INTENSITY = 2;
    private static final int CAP = 2; // Must stay <= 2: disables native spread and can't ignite trees.
    private static final int MAX_EMBERS = 220; // Per-level safety bound against runaway tracking.

    // Clamp every tick: PMWeather's random tick can briefly grow an ember above the cap, and a
    // second random tick in that window would re-enable native spread. Capping every tick shrinks
    // that window to nothing, keeping the "can't seed uncapped fire" guarantee airtight.
    private static final int CLAMP_PERIOD = 1; // Ticks between clamp/prune/merge passes.
    private static final int CREEP_PERIOD = 10; // Ticks between directed-spread passes.
    private static final int CREEP_BUDGET = 16; // Max embers that attempt to creep per pass.
    private static final int CREEP_STEP = 2; // Blocks advanced toward the fire per creep.

    private static final int SCAN_RADIUS = 10; // Horizontal reach when looking for the wildfire.
    private static final int SCAN_VERTICAL = 4; // Vertical reach, to find fire on slopes.
    private static final float CHUNK_FIRE_THRESHOLD = 0.75f; // Min STABLE_FIRE_INTENSITY to bother creeping.
    private static final int MAX_STEP_HEIGHT = 3; // Don't let the fire creep up/down cliffs.

    private static final Map<ResourceKey<Level>, Set<BlockPos>> EMBERS = new HashMap<>();

    /**
     * Places a capped ember at {@code pos} (if the spot is valid) and starts tracking it. Called both
     * by the drip torch when the player lights the ground, and by the creep logic below.
     *
     * @return true if a new ember was actually planted
     */
    public static boolean plantEmber(ServerLevel level, BlockPos pos) {
        pos = pos.immutable();
        Set<BlockPos> set = EMBERS.computeIfAbsent(level.dimension(), k -> new LinkedHashSet<>());
        if (set.size() >= MAX_EMBERS) return false;

        if (!level.getBlockState(pos).isAir()) return false;
        BlockState ground = level.getBlockState(pos.below());
        // Only ever light on soil-type ground - never leaves, logs, or structures.
        if (!PMWFireBlock.isGroundSuitable(level, ground, pos.below())) return false;

        BlockState fire = ModBlocks.FIRE.get().defaultBlockState().setValue(PMWFireBlock.INTENSITY, START_INTENSITY);
        level.setBlockAndUpdate(pos, fire);
        set.add(pos);
        return true;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        Set<BlockPos> set = EMBERS.get(level.dimension());
        if (set == null || set.isEmpty()) return;

        long time = level.getGameTime();
        if (time % CLAMP_PERIOD == 0) {
            clampAndMerge(level, set);
        }
        if (time % CREEP_PERIOD == 0) {
            creepTowardFire(level, set);
        }
    }

    // Keeps tracked embers at or below the cap, drops any that have burned out, and releases any that
    // have reached the real wildfire (so they merge in and behave normally from then on).
    private static void clampAndMerge(ServerLevel level, Set<BlockPos> set) {
        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos pos : set) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PMWFireBlock)) {
                toRemove.add(pos); // Burned out or consumed - nothing left to manage.
                continue;
            }

            if (isAdjacentToWildfire(level, set, pos)) {
                toRemove.add(pos); // Reached the wildfire: stop managing, let it merge seamlessly.
                continue;
            }

            int intensity = state.getValue(PMWFireBlock.INTENSITY);
            if (intensity > CAP) {
                level.setBlockAndUpdate(pos, state.setValue(PMWFireBlock.INTENSITY, CAP));
            }
        }

        set.removeAll(toRemove);
    }

    // Advances the burning front toward the wildfire, one modest step at a time, but only from embers
    // whose chunk (or a neighbour) actually carries wildfire - so with no fire around, nothing spreads.
    private static void creepTowardFire(ServerLevel level, Set<BlockPos> set) {
        if (set.size() >= MAX_EMBERS) return;

        List<BlockPos> frontier = new ArrayList<>(set);
        int attempts = 0;

        for (BlockPos ember : frontier) {
            if (attempts >= CREEP_BUDGET || set.size() >= MAX_EMBERS) break;
            if (maxNearbyChunkFire(level, ember) < CHUNK_FIRE_THRESHOLD) continue;

            BlockPos fire = findNearestWildfire(level, set, ember);
            if (fire == null) continue;
            attempts++;

            double dx = fire.getX() - ember.getX();
            double dz = fire.getZ() - ember.getZ();
            if (dx * dx + dz * dz <= 2.5) continue; // Already touching - merge handles it.

            int tx = ember.getX() + (int) Math.signum(dx) * CREEP_STEP;
            int tz = ember.getZ() + (int) Math.signum(dz) * CREEP_STEP;

            // Drop onto whatever ground is actually there (follows terrain instead of floating).
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
            if (Math.abs(top.getY() - ember.getY()) > MAX_STEP_HEIGHT) continue;

            plantEmber(level, top);
        }
    }

    private static boolean isAdjacentToWildfire(ServerLevel level, Set<BlockPos> set, BlockPos pos) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockState(m).getBlock() instanceof PMWFireBlock && !set.contains(m)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static BlockPos findNearestWildfire(ServerLevel level, Set<BlockPos> set, BlockPos origin) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!(level.getBlockState(m).getBlock() instanceof PMWFireBlock)) continue;
                    if (set.contains(m)) continue; // One of ours, not the wildfire.

                    long d = (long) dx * dx + (long) dz * dz + (long) dy * dy;
                    if (d < bestDist) {
                        bestDist = d;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }

    // The strongest STABLE_FIRE_INTENSITY across this ember's chunk and its 8 neighbours - a cheap,
    // PMWeather-native gate for "is there actually a wildfire near enough to be worth creeping toward".
    private static float maxNearbyChunkFire(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        float max = 0.0f;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ChunkAccess chunk = level.getChunk(cx + ox, cz + oz);
                max = Math.max(max, chunk.getData(DataAttachments.STABLE_FIRE_INTENSITY));
            }
        }
        return max;
    }
}
