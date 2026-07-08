package com.axes.wildfireadditions.event;

import com.axes.wildfireadditions.WildfireAdditions;
import dev.protomanly.pmweather.block.ModBlocks;
import dev.protomanly.pmweather.block.PMWFireBlock;
import dev.protomanly.pmweather.data.DataAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the low-intensity "backfire" the drip torch lights.
 *
 * <p>The whole point of this tool is to be a controlled burn, not another way to grow the wildfire.
 * Three guarantees make that safe, all built on facts from PMWeather's own {@code PMWFireBlock}:
 *
 * <ul>
 *   <li><b>Never climbs into trees, and never triggers native (wind-biased) spread.</b>
 *       {@code PMWFireBlock.canBurnOn} refuses leaves/logs below intensity 4, and native spread only
 *       triggers at {@code intensity > 2}. {@code randomTick} increments intensity and checks that
 *       spread condition in the <i>same call</i>: {@code intensity += random.nextInt(1, clamp(intensity
 *       + 1, 2, 11))}. Capping at {@value #CAP} makes that same-call bump land on exactly
 *       {@code clamp(1+1,2,11)=2} -> {@code nextInt(1,2)} has only one possible outcome, 1 -> intensity
 *       becomes 2, and {@code 2 > 2} is false. So a <i>single</i> random tick of one of our embers can
 *       never satisfy the spread check.
 *       <p><b>But a single tick was never the whole story.</b> That "lands on exactly 2" argument only
 *       holds if the block is sitting at the cap <i>when the tick starts</i>. {@code randomTick} writes
 *       the bumped value (2) straight back to the world, and our {@link #clampIntensity} pass only runs
 *       later, at {@code LevelTickEvent.Post}. If the very same fire block is random-ticked <i>twice in
 *       one game tick</i> - rare, but possible, since each section random-ticks several positions per
 *       tick and can land on the same block more than once - the second tick reads the un-clamped 2,
 *       computes {@code clamp(2+1,2,11)=3 -> nextInt(1,3)} and reaches 3 or 4, and now {@code 3 > 2}
 *       fires PMWeather's own {@code trySpreadFireBlock}. That spawns a brand-new, <i>untracked</i>,
 *       uncapped fire block: a genuine wildfire seed, the exact thing this tool must never create. The
 *       Post clamp cannot close that window because both ticks happen before Post ever runs.
 *       <p>So the airtight guarantee is not the cap alone - it's the cap plus
 *       {@link com.axes.wildfireadditions.mixin.PMWFireBlockMixin}, which redirects the intensity read
 *       at the top of {@code randomTick} and clamps it to {@value #CAP} for our tracked embers <i>every
 *       time the tick runs</i>. Because every tick now computes from a base of at most {@value #CAP} no
 *       matter what value is currently stored, the bump can reach at most 2 in any single call, and
 *       intensity can never accumulate across ticks. {@code intensity > 2} (spread), {@code intensity >
 *       6} (scorch) and {@code intensity >= 8} (fire whirl) are all rendered unreachable at the source,
 *       for any number of ticks in any single game tick.</li>
 *   <li><b>Burns toward the wildfire, not away - at real wildfire range.</b> Since native spread is
 *       disabled by the cap, the fire only advances via our own creep, in two tiers. Up close, it steps
 *       toward the nearest actual (untracked) fire block, for precise final approach and merging. At
 *       long range - a real wildfire can be well over 100 blocks off - checking individual blocks
 *       would mean millions of lookups, so instead we read PMWeather's own per-chunk
 *       STABLE_FIRE_INTENSITY (the same coarse, pre-aggregated "how much fire is around here" signal
 *       WindEngine itself samples to bias wind toward fires) across a wide grid of chunks - roughly
 *       200 cheap lookups instead of millions of block checks - and creep toward the nearest chunk
 *       that's actually carrying fire. Only chunks the server already has loaded are read, so this
 *       never forces distant chunks to load just to peek at them.
 *       <p><b>When no fire is detectable at any range,</b> the tool doesn't just gutter out where it was
 *       lit - that would make it useless for the routine job of clearing a patch of undergrowth. Each
 *       ember instead carries a small outward-spread budget (see {@link #NO_FIRE_SPREAD_MIN}..{@link
 *       #NO_FIRE_SPREAD_MAX}): with no fire to head for, it pushes out one ring, hands its children a
 *       budget one smaller, and is spent. That grows a bounded diamond of cleared ground - at most 3-5
 *       blocks out from each ignition - and then burns itself out. It is strictly decrementing and
 *       entirely our own capped embers, so it can never run away into a wildfire the way native spread
 *       could.</li>
 *   <li><b>Merges into a firebreak, never a fuse.</b> Our embers are real {@code PMWFireBlock}s at a
 *       separate set of positions from the wildfire's own blocks, which we never touch - so we neither
 *       override nor reset the wildfire. Crucially, we deliberately do NOT hand our embers off to the
 *       wildfire when they meet it: every ember stays capped until it burns its own patch out into
 *       (non-flammable) charred ground and is then pruned. So where a backburn meets the main fire, the
 *       fuel between has already been consumed and the boundary is burned-out ground the wildfire can't
 *       cross - a firebreak. (An earlier version released embers from the cap on contact so they would
 *       "merge" into the wildfire. That was a mistake: a released ember becomes an uncapped, growing
 *       fire, which is then adjacent to the next tracked ember and releases it in turn - so the whole
 *       connected backburn would "infect" into full wildfire one ember at a time, roughly a block every
 *       few ticks. Keeping embers capped until they burn out removes that failure mode entirely.)</li>
 * </ul>
 *
 * <p>The tracked set needs to be large: a slow, wide backfire that's actually starving the wildfire of
 * fuel (rather than a thin single-file trail) can easily need thousands of embers alive at once. That
 * stays affordable because the only per-ember work every tick is a single block-state read (the
 * intensity clamp - see {@link #clampIntensity}); embers leave the set only by burning out. Each creep
 * pass also lets only a bounded, <i>randomly shuffled</i> subset of embers attempt to advance, so the
 * front stays roughly even instead of a few lucky embers (always the same ones, in a fixed iteration
 * order) racing ahead while the rest of the line never gets a turn.
 */
@EventBusSubscriber(modid = WildfireAdditions.MODID)
public class DripTorchFireHandler {

    public static final int START_INTENSITY = 1;
    // MUST stay 1. See the class doc: randomTick's own same-call intensity bump can reach at most
    // clamp(CAP+1,2,11) afterwards, and that must not exceed 2 or native (wind-biased) spread can
    // fire. CAP=1 is the only value where a single tick is guaranteed safe; the mixin (which reads
    // this constant) is what extends that guarantee to multiple ticks within one game tick.
    // Public so PMWFireBlockMixin can clamp our embers' randomTick reads to exactly this value.
    public static final int CAP = 1;
    // NB: a low cap does NOT make the tool weak. The two things a bigger intensity would buy -
    // visible flames and clearing undergrowth - are delivered independently of it: our own flame
    // particles (see emitEmberParticles) and burning straight through vegetation (see plantEmber).
    // Raising the block's actual intensity to 2+ would re-enable the same-tick wind-biased native
    // spread this whole class exists to prevent, so it stays at 1 and we add the punch elsewhere.

    // Raised substantially: a wide, slow-advancing backfire that's actually starving a fire line of
    // fuel needs far more simultaneously-tracked embers than a thin trail ever did. Affordable because
    // the only per-ember work each tick is one cheap block-state read (the intensity clamp).
    private static final int MAX_EMBERS = 4000;

    // The intensity clamp is one block-state read (plus a write only when it fires) per tracked ember.
    // The airtight wind-spread guarantee now lives in PMWFireBlockMixin (which caps the value each
    // randomTick reads); this pass is the belt-and-braces reset of the stored value between game ticks
    // and, more importantly, the ONLY path by which an ember leaves the set: when it's burned out into
    // non-fire ground, the clamp pass prunes it.
    private static final int CLAMP_PERIOD = 1; // Ticks between intensity-clamp passes.

    // ~1 block/second: one step every CREEP_PERIOD ticks (20 ticks = 1 second).
    private static final int CREEP_PERIOD = 20;
    private static final int CREEP_STEP = 1;

    // The idle (no-fire) outward foliage clear advances far slower than the toward-fire creep: one ring
    // roughly every NO_FIRE_SPREAD_PERIOD ticks (~4 seconds), i.e. about a quarter of the creep rate.
    // At full creep speed the cleared diamond grew almost faster than a player could step out of it;
    // this keeps the toward-a-wildfire creep brisk while making routine clearing a gentle crawl. Must be
    // a whole multiple of CREEP_PERIOD, since the spread is only ever attempted on a creep pass.
    private static final int NO_FIRE_SPREAD_PERIOD = CREEP_PERIOD * 4;

    // Max embers that attempt to creep per pass. The frontier is shuffled first (see creepTowardFire)
    // so this bound doesn't silently turn into "only the first N in iteration order ever advance" -
    // over several passes, every ember gets a fair shot, keeping the whole line advancing together
    // instead of a few racing ahead while the rest of the coverage never fills in.
    private static final int CREEP_BUDGET = 128;

    // Fine tier: precise steering once a real fire block is within reach. Kept small - it's a
    // per-block scan - since its only job now is short-range precision, not long-range sensing.
    private static final int FINE_SCAN_RADIUS = 10; // Horizontal reach, in blocks.
    private static final int FINE_SCAN_VERTICAL = 4; // Vertical reach, in blocks (fire on slopes).

    // Coarse tier: long-range sensing via per-chunk data instead of per-block, so a wildfire that's
    // realistically far away (100+ blocks) can still be detected and headed towards affordably.
    private static final int LONG_RANGE_CHUNK_RADIUS = 7; // ~112 blocks; chunks, not blocks.
    private static final float CHUNK_FIRE_THRESHOLD = 0.75f; // Min STABLE_FIRE_INTENSITY to bother creeping.

    private static final int MAX_STEP_HEIGHT = 3; // Don't let the fire creep up/down cliffs.

    // With no fire to head for, an ember still clears a useful patch of undergrowth: it spreads
    // outward, handing each child a budget one smaller, until the budget is spent. The starting budget
    // is randomised in this (inclusive) range, so a freshly lit ember pushes its cleared diamond 3-5
    // blocks out and then burns off. Strictly decrementing = strictly bounded; it can never grow without
    // limit. Keep the range small: this is routine foliage clearing, not another way to torch the map.
    private static final int NO_FIRE_SPREAD_MIN = 3;
    private static final int NO_FIRE_SPREAD_MAX = 5;
    private static final int SPENT = 0; // Budget value once an ember has done its one outward spread.

    private static final int PARTICLE_PERIOD = 3; // Ticks between cosmetic flame bursts.
    private static final float PARTICLE_CHANCE = 0.02f; // Per-ember chance each burst - keeps totals bounded.

    // ember position -> remaining no-fire outward-spread budget. A map (not a set) so each ember can
    // carry how many more times its lineage may push outward when there's no fire to creep toward.
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> EMBERS = new HashMap<>();

    /**
     * True if {@code pos} is a drip-torch ember we're actively managing in {@code level}. Read by
     * {@link com.axes.wildfireadditions.mixin.PMWFireBlockMixin} so it only ever clamps <i>our</i>
     * fire's random-tick intensity, never the wildfire's.
     */
    public static boolean isTrackedEmber(Level level, BlockPos pos) {
        Map<BlockPos, Integer> set = EMBERS.get(level.dimension());
        return set != null && set.containsKey(pos);
    }

    // Whether pos's chunk is currently loaded, without forcing it to load. Level.getBlockState
    // resolves through getChunk(..., create=true), so every unguarded read here would drag an
    // unloaded chunk back in; callers use this to leave dormant embers untouched instead.
    private static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // Drop a dimension's tracked embers when it unloads (e.g. quitting a single-player world), so stale
    // positions can't bleed into the next world loaded in the same JVM and be mistaken for live embers.
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            EMBERS.remove(level.dimension());
        }
    }

    // Belt-and-braces: clear everything on shutdown, matching WaterDouseQueue, in case a dimension
    // wasn't individually unloaded first.
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        EMBERS.clear();
    }

    /**
     * Places a capped ember at {@code pos} (if the spot is valid) and starts tracking it, giving it a
     * fresh randomised no-fire spread budget. Called both by the drip torch when the player lights the
     * ground, and by the creep logic when it steps toward a fire.
     *
     * @return true if a new ember was actually planted
     */
    public static boolean plantEmber(ServerLevel level, BlockPos pos) {
        return plantEmber(level, pos, randomSpreadBudget(level));
    }

    private static boolean plantEmber(ServerLevel level, BlockPos pos, int spreadBudget) {
        pos = pos.immutable();
        Map<BlockPos, Integer> set = EMBERS.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>());
        if (set.size() >= MAX_EMBERS) return false;

        // Never overwrite existing fire - that includes our own embers, but critically also the
        // wildfire itself, which we must never override. (Fire may be in the REPLACEABLE tag below, so
        // this explicit guard is what actually protects the wildfire's blocks.)
        BlockState here = level.getBlockState(pos);
        if (here.getBlock() instanceof PMWFireBlock) return false;

        // The fire may occupy air OR burn straight through low undergrowth (tall grass, ferns, dead
        // bush, flowers) - that's how the line clears vegetation from its path. It must never be placed
        // into a fluid, though.
        boolean canReplace = here.isAir() || here.is(BlockTags.REPLACEABLE) || here.is(BlockTags.FLOWERS);
        if (!canReplace || !here.getFluidState().isEmpty()) return false;

        BlockState ground = level.getBlockState(pos.below());
        // Only ever light on soil-type ground - never leaves, logs, or structures.
        if (!PMWFireBlock.isGroundSuitable(level, ground, pos.below())) return false;

        // If a plant is actually standing here, break it so the foliage visibly shatters into its
        // breaking particles instead of just blinking out of existence under the fire. dropBlock=false:
        // the fire consumes it, so no grass/seeds/flowers pop out - it's a burn, not a harvest.
        if (!here.isAir()) {
            level.destroyBlock(pos, false);
        }

        BlockState fire = ModBlocks.FIRE.get().defaultBlockState().setValue(PMWFireBlock.INTENSITY, START_INTENSITY);
        level.setBlockAndUpdate(pos, fire);
        set.put(pos, spreadBudget);
        return true;
    }

    private static int randomSpreadBudget(ServerLevel level) {
        return NO_FIRE_SPREAD_MIN + level.random.nextInt(NO_FIRE_SPREAD_MAX - NO_FIRE_SPREAD_MIN + 1);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        Map<BlockPos, Integer> set = EMBERS.get(level.dimension());
        if (set == null || set.isEmpty()) return;

        long time = level.getGameTime();
        if (time % CLAMP_PERIOD == 0) {
            clampIntensity(level, set);
        }
        if (time % PARTICLE_PERIOD == 0) {
            emitEmberParticles(level, set);
        }
        if (time % CREEP_PERIOD == 0) {
            creepTowardFire(level, set);
        }
    }

    // Keeps tracked embers at or below the cap, and drops any that have burned out. The mixin already
    // guarantees an ember's randomTick never computes above the cap, so this pass is defensive - it
    // resets the stored value between game ticks - but its load-bearing job is pruning: an ember whose
    // block is no longer fire has burned out and leaves the set here.
    private static void clampIntensity(ServerLevel level, Map<BlockPos, Integer> set) {
        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos pos : set.keySet()) {
            // Never force-load a chunk just to check an ember. An ember in an unloaded chunk is dormant:
            // leave it tracked and re-check it once its chunk loads again (matching RetardantFireHandler),
            // rather than pulling the chunk back in every tick - which would pin abandoned backburns loaded
            // and, because such chunks don't random-tick, keep those embers alive forever.
            if (!isLoaded(level, pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PMWFireBlock)) {
                toRemove.add(pos); // Burned out or consumed - nothing left to manage.
                continue;
            }

            int intensity = state.getValue(PMWFireBlock.INTENSITY);
            if (intensity > CAP) {
                level.setBlockAndUpdate(pos, state.setValue(PMWFireBlock.INTENSITY, CAP));
            }
        }

        for (BlockPos pos : toRemove) {
            set.remove(pos);
        }
    }

    // Cosmetic only. The fire block is capped at a low intensity for safety (see class doc), so
    // PMWeather's own animate never gives it the big flames it reserves for intensity 3+. We add our
    // own modest flame/ember particles so the backburn still reads as a real, lively fire. Bounded by a
    // low per-ember chance, so even a full 4000-ember set only puts out a few dozen particles per burst;
    // sendParticles itself only forwards them to players close enough to see, so distant embers cost
    // just the RNG roll.
    private static void emitEmberParticles(ServerLevel level, Map<BlockPos, Integer> set) {
        for (BlockPos pos : set.keySet()) {
            if (!isLoaded(level, pos)) continue; // No viewers near a dormant ember; nothing to show.
            if (level.random.nextFloat() >= PARTICLE_CHANCE) continue;
            double x = pos.getX() + 0.5, y = pos.getY() + 0.15, z = pos.getZ() + 0.5;
            level.sendParticles(ParticleTypes.FLAME, x, y, z, 2, 0.22, 0.08, 0.22, 0.01);
            if (level.random.nextInt(3) == 0) {
                level.sendParticles(ParticleTypes.SMOKE, x, y + 0.35, z, 1, 0.12, 0.1, 0.12, 0.01);
            }
        }
    }

    // Advances the burning front toward the wildfire, one block at a time (~1 block/second overall,
    // via CREEP_PERIOD). Tries precise (fine, block-level) targeting first; if nothing's close enough
    // for that, falls back to coarse (chunk-level) sensing so a wildfire well over 100 blocks away can
    // still pull the fire toward it. If there's no fire to sense at any range, the ember instead does a
    // bounded outward foliage-clearing spread (see spreadOutward). The frontier is shuffled before the
    // budget is applied so a large tracked set advances as one even front rather than a few embers
    // monopolising every pass.
    private static void creepTowardFire(ServerLevel level, Map<BlockPos, Integer> set) {
        if (set.size() >= MAX_EMBERS) return;

        // The idle outward clear runs on a slower cadence than the toward-fire creep (see
        // NO_FIRE_SPREAD_PERIOD). Toward-fire steps still happen every pass; the no-fire ring is only
        // pushed on the passes that line up with that slower period.
        boolean idleSpreadDue = level.getGameTime() % NO_FIRE_SPREAD_PERIOD == 0;

        List<BlockPos> frontier = new ArrayList<>(set.keySet());
        Collections.shuffle(frontier);
        int attempts = 0;

        for (BlockPos ember : frontier) {
            if (attempts >= CREEP_BUDGET || set.size() >= MAX_EMBERS) break;
            // Skip embers whose chunk is unloaded - creeping from one would force-load it (and the
            // chunks its fire/ground scan reaches into) purely to advance a fire nobody is watching.
            if (!isLoaded(level, ember)) continue;

            BlockPos fineTarget = findNearestWildfire(level, set, ember);
            if (fineTarget != null) {
                attempts++;
                stepToward(level, ember, fineTarget.getX(), fineTarget.getZ(), CREEP_STEP);
                continue;
            }

            ChunkPos hotChunk = findNearestHotChunk(level, ember);
            if (hotChunk != null) {
                attempts++;
                int targetX = hotChunk.x * 16 + 8;
                int targetZ = hotChunk.z * 16 + 8;
                stepToward(level, ember, targetX, targetZ, CREEP_STEP);
                continue;
            }

            // No fire detectable at any range. Rather than sit and gutter out, clear a little ground:
            // push one ring outward if this ember still has budget, then mark it spent so it doesn't
            // keep trying. Once the budget reaches zero the ember just burns out where it is. Only done
            // on the slow idle cadence; off-cadence passes leave the budget untouched for the next one.
            if (!idleSpreadDue) continue;
            Integer budget = set.get(ember);
            if (budget == null || budget <= 0) continue;
            attempts++;
            spreadOutward(level, set, ember, budget);
            set.put(ember, SPENT);
        }
    }

    // The no-fire fallback: plant a ring of embers one block out in each horizontal direction, each
    // with a budget one smaller than this ember's. Repeated ring-by-ring, this grows a bounded diamond
    // of cleared ground (radius = the starting budget) and then stops, because the budget strictly
    // decreases and children at budget 0 spread no further.
    private static void spreadOutward(ServerLevel level, Map<BlockPos, Integer> set, BlockPos ember, int budget) {
        int childBudget = budget - 1;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (set.size() >= MAX_EMBERS) return;
            int tx = ember.getX() + dir.getStepX();
            int tz = ember.getZ() + dir.getStepZ();
            BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
            if (Math.abs(top.getY() - ember.getY()) > MAX_STEP_HEIGHT) continue; // Don't climb cliffs.
            plantEmber(level, top, childBudget);
        }
    }

    // Plants one new ember stepSize blocks closer to (targetX, targetZ), dropped onto whatever
    // ground is actually there (follows terrain instead of floating). Fire-creep children get a fresh
    // full spread budget, so if the wildfire they were heading for ever vanishes, the front still does
    // its normal bounded outward clear from wherever it ended up.
    private static void stepToward(ServerLevel level, BlockPos ember, int targetX, int targetZ, int stepSize) {
        double dx = targetX - ember.getX();
        double dz = targetZ - ember.getZ();
        // Already at the fire: don't plant into or past it. This ember just holds the line here and
        // burns out, leaving charred ground the wildfire can't cross.
        if (dx * dx + dz * dz <= 2.5) return;

        int tx = ember.getX() + (int) Math.signum(dx) * stepSize;
        int tz = ember.getZ() + (int) Math.signum(dz) * stepSize;

        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(tx, 0, tz));
        if (Math.abs(top.getY() - ember.getY()) > MAX_STEP_HEIGHT) return;

        plantEmber(level, top);
    }

    private static BlockPos findNearestWildfire(ServerLevel level, Map<BlockPos, Integer> set, BlockPos origin) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;

        for (int dx = -FINE_SCAN_RADIUS; dx <= FINE_SCAN_RADIUS; dx++) {
            for (int dz = -FINE_SCAN_RADIUS; dz <= FINE_SCAN_RADIUS; dz++) {
                for (int dy = -FINE_SCAN_VERTICAL; dy <= FINE_SCAN_VERTICAL; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isLoaded(level, m)) continue; // Don't force-load a neighbour chunk to scan it.
                    if (!(level.getBlockState(m).getBlock() instanceof PMWFireBlock)) continue;
                    if (set.containsKey(m)) continue; // One of ours, not the wildfire.

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
