package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import dev.protomanly.pmweather.block.ModBlocks;
import dev.protomanly.pmweather.block.PMWFireBlock;
import dev.protomanly.pmweather.data.DataAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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
 *   <li><b>Never climbs into trees, and never triggers native (wind-biased) spread.</b>
 *       {@code PMWFireBlock.canBurnOn} refuses leaves/logs below intensity 4, and native spread only
 *       triggers at {@code intensity > 2}. Critically, {@code randomTick} increments intensity and
 *       checks that spread condition in the <i>same call</i>, before we ever get a chance to intervene:
 *       {@code intensity += random.nextInt(1, clamp(intensity + 1, 2, 11))}. Capping at {@value #CAP}
 *       makes that same-tick bump land on exactly {@code clamp(1+1,2,11)=2} -> {@code nextInt(1,2)} has
 *       only one possible outcome, 1 -> intensity deterministically becomes 2, and {@code 2 > 2} is
 *       false. So the wind-biased spread can never fire from one of our embers, not even for a single
 *       tick - it isn't just clamped back down after the fact, it's mathematically unreachable. (A cap
 *       of 2 looked safe but wasn't: that same increment can reach 3 or 4 in one call, which *does*
 *       satisfy the spread check before our own clamp ever runs.)</li>
 *   <li><b>Burns toward the wildfire, not away - at real wildfire range.</b> Since native spread is
 *       disabled by the cap, the fire only advances via our own creep, in two tiers. Up close, it steps
 *       toward the nearest actual (untracked) fire block, for precise final approach and merging. At
 *       long range - a real wildfire can be well over 100 blocks off - checking individual blocks
 *       would mean millions of lookups, so instead we read PMWeather's own per-chunk
 *       STABLE_FIRE_INTENSITY (the same coarse, pre-aggregated "how much fire is around here" signal
 *       WindEngine itself samples to bias wind toward fires) across a wide grid of chunks - roughly
 *       200 cheap lookups instead of millions of block checks - and creep toward the nearest chunk
 *       that's actually carrying fire. Only chunks the server already has loaded are read, so this
 *       never forces distant chunks to load just to peek at them.</li>
 *   <li><b>Merges seamlessly.</b> Our embers are real {@code PMWFireBlock}s, so when one reaches the
 *       wildfire we simply stop tracking it - no override, no reset. From that moment it's an
 *       ordinary wildfire block that grows and behaves like any other.</li>
 * </ul>
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class DripTorchFireHandler {

    public static final int START_INTENSITY = 1;
    // MUST stay 1. See the class doc: randomTick's own same-call intensity bump can reach at most
    // clamp(CAP+1,2,11) afterwards, and that must not exceed 2 or native (wind-biased) spread can
    // fire before we ever get a chance to clamp it back down. CAP=1 is the only value where that's
    // guaranteed rather than merely likely.
    private static final int CAP = 1;
    private static final int MAX_EMBERS = 220; // Per-level safety bound against runaway tracking.

    // Cheap defense-in-depth: re-clamp every tick in case of any other growth path we haven't
    // accounted for. The actual wind-spread guarantee above doesn't depend on this running often -
    // it holds even if this handler never ran at all.
    private static final int CLAMP_PERIOD = 1; // Ticks between clamp/prune/merge passes.
    private static final int CREEP_PERIOD = 10; // Ticks between directed-spread passes.
    private static final int CREEP_BUDGET = 16; // Max embers that attempt to creep per pass.

    // Fine tier: precise steering once a real fire block is within reach. Kept small - it's a
    // per-block scan - since its only job now is short-range precision, not long-range sensing.
    private static final int FINE_SCAN_RADIUS = 10; // Horizontal reach, in blocks.
    private static final int FINE_SCAN_VERTICAL = 4; // Vertical reach, in blocks (fire on slopes).
    private static final int FINE_CREEP_STEP = 2; // Blocks advanced per creep at this tier.

    // Coarse tier: long-range sensing via per-chunk data instead of per-block, so a wildfire that's
    // realistically far away (100+ blocks) can still be detected and headed towards affordably.
    private static final int LONG_RANGE_CHUNK_RADIUS = 7; // ~112 blocks; chunks, not blocks.
    private static final float CHUNK_FIRE_THRESHOLD = 0.75f; // Min STABLE_FIRE_INTENSITY to bother creeping.
    private static final int LONG_RANGE_CREEP_STEP = 6; // Bigger leaps while just heading in-general.

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

    // Advances the burning front toward the wildfire, one modest step at a time. Tries precise
    // (fine, block-level) targeting first; if nothing's close enough for that, falls back to coarse
    // (chunk-level) sensing so a wildfire well over 100 blocks away can still pull the fire toward it.
    private static void creepTowardFire(ServerLevel level, Set<BlockPos> set) {
        if (set.size() >= MAX_EMBERS) return;

        List<BlockPos> frontier = new ArrayList<>(set);
        int attempts = 0;

        for (BlockPos ember : frontier) {
            if (attempts >= CREEP_BUDGET || set.size() >= MAX_EMBERS) break;

            BlockPos fineTarget = findNearestWildfire(level, set, ember);
            if (fineTarget != null) {
                attempts++;
                stepToward(level, ember, fineTarget.getX(), fineTarget.getZ(), FINE_CREEP_STEP);
                continue;
            }

            ChunkPos hotChunk = findNearestHotChunk(level, ember);
            if (hotChunk == null) continue; // No fire detectable at any range - stay put.
            attempts++;

            int targetX = hotChunk.x * 16 + 8;
            int targetZ = hotChunk.z * 16 + 8;
            stepToward(level, ember, targetX, targetZ, LONG_RANGE_CREEP_STEP);
        }
    }

    // Plants one new ember stepSize blocks closer to (targetX, targetZ), dropped onto whatever
    // ground is actually there (follows terrain instead of floating).
    private static void stepToward(ServerLevel level, BlockPos ember, int targetX, int targetZ, int stepSize) {
        double dx = targetX - ember.getX();
        double dz = targetZ - ember.getZ();
        if (dx * dx + dz * dz <= 2.5) return; // Already there - merge handles it.

        int tx = ember.getX() + (int) Math.signum(dx) * stepSize;
        int tz = ember.getZ() + (int) Math.signum(dz) * stepSize;

        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
        if (Math.abs(top.getY() - ember.getY()) > MAX_STEP_HEIGHT) return;

        plantEmber(level, top);
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

        for (int dx = -FINE_SCAN_RADIUS; dx <= FINE_SCAN_RADIUS; dx++) {
            for (int dz = -FINE_SCAN_RADIUS; dz <= FINE_SCAN_RADIUS; dz++) {
                for (int dy = -FINE_SCAN_VERTICAL; dy <= FINE_SCAN_VERTICAL; dy++) {
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

    // Finds the nearest currently-loaded chunk (within LONG_RANGE_CHUNK_RADIUS) whose
    // STABLE_FIRE_INTENSITY says a wildfire is actually there. This is what lets the fire sense a
    // blaze 100+ blocks off without scanning individual blocks: PMWeather already maintains this
    // per-chunk aggregate (WindEngine samples the very same field to bias wind toward fires), so we
    // just read it directly - about (2*7+1)^2 = 225 cheap lookups instead of millions of block checks.
    // Chunks the server hasn't loaded are skipped rather than force-loaded, since an unloaded chunk
    // isn't being simulated anyway - there's no wildfire to detect there even if one existed.
    private static ChunkPos findNearestHotChunk(ServerLevel level, BlockPos origin) {
        int originCx = origin.getX() >> 4;
        int originCz = origin.getZ() >> 4;
        ChunkPos best = null;
        long bestDist = Long.MAX_VALUE;

        for (int dcx = -LONG_RANGE_CHUNK_RADIUS; dcx <= LONG_RANGE_CHUNK_RADIUS; dcx++) {
            for (int dcz = -LONG_RANGE_CHUNK_RADIUS; dcz <= LONG_RANGE_CHUNK_RADIUS; dcz++) {
                int cx = originCx + dcx;
                int cz = originCz + dcz;
                if (!level.hasChunk(cx, cz)) continue;

                ChunkAccess chunk = level.getChunk(cx, cz);
                if (chunk.getData(DataAttachments.STABLE_FIRE_INTENSITY) < CHUNK_FIRE_THRESHOLD) continue;

                long d = (long) dcx * dcx + (long) dcz * dcz;
                if (d < bestDist) {
                    bestDist = d;
                    best = new ChunkPos(cx, cz);
                }
            }
        }
        return best;
    }
}
